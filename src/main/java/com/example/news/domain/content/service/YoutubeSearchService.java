package com.example.news.domain.content.service;

import com.example.news.domain.analysis.service.AnalysisService;
import com.example.news.domain.content.converter.YoutubeConverter;
import com.example.news.domain.content.dto.VideoClusterDto;
import com.example.news.domain.content.dto.VideoRankDto;
import com.example.news.domain.content.dto.YoutubeVideoDto;
import com.example.news.domain.content.entity.Keyword;
import com.example.news.domain.content.entity.YoutubeVideo;
import com.example.news.domain.content.entity.YoutubeVideoKeyword;
import com.example.news.domain.content.exception.YoutubeApiException;
import com.example.news.domain.content.repository.KeywordRepository;
import com.example.news.domain.content.repository.YoutubeVideoKeywordRepository;
import com.example.news.domain.content.repository.YoutubeVideoRepository;
import com.example.news.domain.graph.service.VideoGraphSyncService;
import com.example.news.global.event.VideoSearchedEvent;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class YoutubeSearchService {

    // 키워드 검색 핵심 서비스
    private final YouTube youtubeClient;
    private final YoutubeVideoRepository youtubeVideoRepository;
    private final KeywordRepository keywordRepository;
    private final YoutubeVideoKeywordRepository youtubeVideoKeywordRepository;
    private final TitleTranslationService titleTranslationService;
    private final VideoGraphSyncService videoGraphSyncService;
    private final ApplicationEventPublisher eventPublisher;
    private final RestTemplate restTemplate;
    private final ObjectProvider<AnalysisService> analysisServiceProvider;

    @Value("${youtube.api.key}")
    private String apiKey;
    @Value("${issue.search.auto-cluster-enabled:false}")
    private boolean issueSearchAutoClusterEnabled;
    @Value("${ai-pipeline.base-url}")
    private String aiPipelineBaseUrl;

    private static final int SHORTS_MAX_DURATION_SECONDS = 180;  // YouTube Shorts 최대 3분
    private static final int MIN_NEWS_DURATION_SECONDS = 60;     // 뉴스 영상 최소 1분
    private static final int MAX_NEWS_DURATION_SECONDS = 1200;   // 분석 시간 제한: 최대 20분
    private static final int RANK_BUFFER_SIZE = 50;             // 필터 후 20개 확보를 위한 랭킹 버퍼
    private static final int FINAL_RESULT_SIZE = 20;
    private static final int CACHE_MIN_SIZE = 20; // DB 캐시 사용 최소 영상 수

    // 폴백 단계별 조건 (단계가 높을수록 조건 완화)
    private static final int[] FALLBACK_MONTHS   = {1, 3, 6};       // 기간: 1개월 → 3개월 → 6개월
    private static final long[] FALLBACK_VIEWS   = {5000, 1000, 0}; // 조회수: 5000 → 1000 → 제한없음

    @Transactional
    public List<YoutubeVideoDto.VideoCard> search(String keyword, String sort) {
        // 1. DB 캐시 확인 — 같은 키워드로 이미 수집된 영상이 충분하면 재사용
        List<YoutubeVideoDto.VideoCard> cached = searchFromCache(keyword);
        if (cached != null) {
            log.info("keyword cache hit: keyword={}, size={}", keyword, cached.size());
            return cached;
        }

        // 2. 캐시 미스 — 폴백 단계별로 조건을 완화하며 20개 확보
        //    1단계: 1개월 + 조회수 5000 이상
        //    2단계: 3개월 + 조회수 1000 이상
        //    3단계: 6개월 + 조회수 제한 없음
        List<YoutubeVideo> filtered = List.of();
        String usKeyword = null;
        try {
            usKeyword = titleTranslationService.translateFromKorean(keyword, "en");
        } catch (Exception e) {
            log.warn("키워드 번역 실패, KR만 사용: {}", e.getMessage());
        }

        for (int step = 0; step < FALLBACK_MONTHS.length; step++) {
            int months = FALLBACK_MONTHS[step];
            long minViews = FALLBACK_VIEWS[step];
            LocalDateTime publishedAfter = LocalDateTime.now().minusMonths(months);

            List<VideoRankDto.VideoItem> allSnippets = new ArrayList<>(searchSnippets(keyword, publishedAfter));
            if (usKeyword != null) {
                try {
                    List<VideoRankDto.VideoItem> usSnippets = searchSnippetsByRegion(usKeyword, "US", "en", null, null);
                    Set<String> existingIds = allSnippets.stream()
                            .map(VideoRankDto.VideoItem::videoId)
                            .collect(Collectors.toSet());
                    usSnippets.stream()
                            .filter(s -> !existingIds.contains(s.videoId()))
                            .forEach(allSnippets::add);
                } catch (Exception e) {
                    log.warn("US 영상 수집 실패 (step={}): {}", step, e.getMessage());
                }
            }
            if (allSnippets.isEmpty()) continue;

            List<String> topIds = rankVideoIds(keyword, allSnippets).stream()
                    .map(VideoRankDto.RankedVideo::videoId)
                    .toList();
            if (topIds.isEmpty()) continue;

            List<YoutubeVideo> videos = fetchAndSaveVideos(topIds);
            filtered = topIds.stream()
                    .map(id -> videos.stream()
                            .filter(v -> v.getYoutubeVideoId().equals(id))
                            .findFirst().orElse(null))
                    .filter(Objects::nonNull)
                    .filter(v -> !isShorts(v))
                    .filter(v -> !isTooLong(v))
                    .filter(v -> minViews == 0 || (v.getViewCount() != null && v.getViewCount() >= minViews))
                    .limit(FINAL_RESULT_SIZE)
                    .collect(Collectors.toList());

            log.info("search fallback step={}, months={}, minViews={}, result={}", step, months, minViews, filtered.size());

            if (filtered.size() >= FINAL_RESULT_SIZE) break;
        }

        translateTitlesIfNeeded(filtered);
        linkKeywordToVideos(keyword, filtered);

        if (issueSearchAutoClusterEnabled) {
            List<Long> videoDbIds = filtered.stream().map(YoutubeVideo::getId).toList();
            eventPublisher.publishEvent(new VideoSearchedEvent(keyword, videoDbIds));
        }

        return filtered.stream()
                .map(YoutubeConverter::toVideoCard)
                .collect(Collectors.toList());
    }

    // DB 캐시 조회 — 폴백 단계별로 조건 완화하며 20개 확보, 부족하면 null 반환해 신규 검색으로 위임
    private List<YoutubeVideoDto.VideoCard> searchFromCache(String keyword) {
        String normalized = keyword.trim().toLowerCase();
        return keywordRepository.findByNormalizedKeyword(normalized)
                .map(keywordEntity -> {
                    List<YoutubeVideo> allCached = youtubeVideoKeywordRepository
                            .findByKeyword(keywordEntity)
                            .stream()
                            .map(YoutubeVideoKeyword::getYoutubeVideo)
                            .filter(v -> !isTooLong(v))
                            .collect(Collectors.toList());

                    List<YoutubeVideo> finalVideos = List.of();
                    for (int step = 0; step < FALLBACK_MONTHS.length; step++) {
                        int months = FALLBACK_MONTHS[step];
                        long minViews = FALLBACK_VIEWS[step];
                        LocalDateTime cutoff = LocalDateTime.now().minusMonths(months);

                        List<YoutubeVideo> candidates = allCached.stream()
                                .filter(v -> v.getPublishedAt() != null && v.getPublishedAt().isAfter(cutoff))
                                .filter(v -> minViews == 0 || (v.getViewCount() != null && v.getViewCount() >= minViews))
                                .collect(Collectors.toList());

                        if (candidates.size() < CACHE_MIN_SIZE) continue;

                        List<VideoRankDto.VideoItem> snippets = candidates.stream()
                                .map(v -> new VideoRankDto.VideoItem(
                                        v.getYoutubeVideoId(),
                                        v.getTitle() != null ? v.getTitle() : "",
                                        v.getDescription() != null ? v.getDescription() : ""
                                ))
                                .collect(Collectors.toList());

                        List<VideoRankDto.RankedVideo> ranked = rankVideoIds(keyword, snippets);
                        finalVideos = ranked.stream()
                                .map(r -> candidates.stream()
                                        .filter(v -> v.getYoutubeVideoId().equals(r.videoId()))
                                        .findFirst().orElse(null))
                                .filter(Objects::nonNull)
                                .filter(v -> !isShorts(v))
                                .limit(FINAL_RESULT_SIZE)
                                .collect(Collectors.toList());

                        log.info("cache fallback step={}, months={}, minViews={}, result={}", step, months, minViews, finalVideos.size());
                        if (finalVideos.size() >= FINAL_RESULT_SIZE) break;
                    }

                    if (finalVideos.isEmpty()) return null; // 캐시로 20개 불가 → 신규 검색

                    AnalysisService analysisService = analysisServiceProvider.getIfAvailable();
                    if (analysisService != null) {
                        finalVideos.forEach(v -> analysisService.triggerAnalysisAsync(v.getId()));
                    }

                    return finalVideos.stream()
                            .map(YoutubeConverter::toVideoCard)
                            .collect(Collectors.toList());
                })
                .orElse(null);
    }

    // YouTube search.list 호출 — snippet(title+description) 포함하여 50개 반환
    private List<VideoRankDto.VideoItem> searchSnippets(String keyword, LocalDateTime publishedAfter) {
        try {
            YouTube.Search.List searchRequest = youtubeClient.search().list(List.of("snippet"));
            searchRequest.setKey(apiKey);
            searchRequest.setQ(keyword);
            searchRequest.setType(List.of("video"));
            searchRequest.setMaxResults(50L);
            searchRequest.setOrder("date"); // YouTube 알고리즘 개입 제거, 중립적 후보 수집
            searchRequest.setPublishedAfter(publishedAfter.toInstant(ZoneOffset.UTC).toString());

            String trimmedKeyword = keyword == null ? "" : keyword.trim();
            if (trimmedKeyword.length() > 2) {
                searchRequest.setRelevanceLanguage("ko");
            }

            SearchListResponse response = searchRequest.execute();
            return response.getItems().stream()
                    .filter(item -> item.getId().getVideoId() != null)
                    .map(item -> new VideoRankDto.VideoItem(
                            item.getId().getVideoId(),
                            item.getSnippet().getTitle() != null ? item.getSnippet().getTitle() : "",
                            item.getSnippet().getDescription() != null ? item.getSnippet().getDescription() : ""
                    ))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new YoutubeApiException(e.getMessage(), e);
        }
    }

    // Python 파이프라인 호출 — 코사인 유사도 기반 상위 30개 (video_id + score) 반환
    private List<VideoRankDto.RankedVideo> rankVideoIds(String keyword, List<VideoRankDto.VideoItem> snippets) {
        try {
            VideoRankDto.Request request = new VideoRankDto.Request(keyword, snippets, RANK_BUFFER_SIZE);
            VideoRankDto.Response response = restTemplate.postForObject(
                    aiPipelineBaseUrl + "/content/rank-videos",
                    request,
                    VideoRankDto.Response.class
            );
            if (response == null || response.rankedVideos() == null) {
                log.warn("video ranking returned empty response for keyword={}", keyword);
                return snippets.stream()
                        .limit(30)
                        .map(v -> new VideoRankDto.RankedVideo(v.videoId(), 1.0))
                        .collect(Collectors.toList());
            }
            return response.rankedVideos();
        } catch (Exception e) {
            log.warn("video ranking failed for keyword={}, fallback to first 30. reason={}", keyword, e.getMessage());
            return snippets.stream()
                    .limit(30)
                    .map(v -> new VideoRankDto.RankedVideo(v.videoId(), 1.0))
                    .collect(Collectors.toList());
        }
    }

    // 국가별 비교용 검색 (번역된 키워드 + 지역/언어 + 날짜 범위) — 유사도 점수 포함
    @Transactional
    public List<YoutubeVideoDto.VideoCard> searchByRegion(
            String keyword,
            String regionCode,
            String relevanceLanguage,
            LocalDate startDate,
            LocalDate endDate) {

        List<VideoRankDto.VideoItem> snippets = searchSnippetsByRegion(keyword, regionCode, relevanceLanguage, startDate, endDate);
        if (snippets.isEmpty()) {
            return List.of();
        }

        List<VideoRankDto.RankedVideo> ranked = rankVideoIds(keyword, snippets);
        Map<String, Double> scoreMap = ranked.stream()
                .collect(Collectors.toMap(VideoRankDto.RankedVideo::videoId, VideoRankDto.RankedVideo::score));
        List<String> videoIds = ranked.stream().map(VideoRankDto.RankedVideo::videoId).collect(Collectors.toList());

        List<YoutubeVideo> videos = fetchAndSaveVideos(videoIds);
        translateTitlesIfNeeded(videos);
        linkKeywordToVideos(keyword, videos);

        return videos.stream()
                .filter(v -> !isShorts(v))
                .map(v -> YoutubeConverter.toVideoCard(v, scoreMap.getOrDefault(v.getYoutubeVideoId(), null)))
                .collect(Collectors.toList());
    }

    @Transactional
    public List<YoutubeVideoDto.VideoCard> searchByRegion(
            String keyword,
            String regionCode,
            String relevanceLanguage,
            LocalDateTime publishedAfter,
            int maxResults) {

        int boundedMaxResults = Math.max(1, Math.min(maxResults, 20));
        List<String> videoIds = searchVideoIdsByRegion(keyword, regionCode, relevanceLanguage, publishedAfter, boundedMaxResults);
        if (videoIds.isEmpty()) {
            return List.of();
        }

        List<YoutubeVideo> videos = fetchAndSaveVideos(videoIds);
        translateTitlesIfNeeded(videos);
        linkKeywordToVideos(keyword, videos);

        return videos.stream()
                .filter(v -> !isShorts(v))
                .map(YoutubeConverter::toVideoCard)
                .collect(Collectors.toList());
    }

    // 국가별 검색 — snippet(title+description) 포함하여 반환 (rankVideoIds 입력용)
    private List<VideoRankDto.VideoItem> searchSnippetsByRegion(
            String keyword,
            String regionCode,
            String relevanceLanguage,
            LocalDate startDate,
            LocalDate endDate) {
        try {
            YouTube.Search.List searchRequest = youtubeClient.search().list(List.of("snippet"));
            searchRequest.setKey(apiKey);
            searchRequest.setQ(keyword);
            searchRequest.setType(List.of("video"));
            searchRequest.setMaxResults(20L);
            searchRequest.setRegionCode(regionCode);
            searchRequest.setRelevanceLanguage(relevanceLanguage);

            if (startDate != null) {
                searchRequest.setPublishedAfter(
                        startDate.atStartOfDay().toInstant(ZoneOffset.UTC).toString());
            }
            if (endDate != null) {
                searchRequest.setPublishedBefore(
                        endDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString());
            }

            SearchListResponse response = searchRequest.execute();
            return response.getItems().stream()
                    .filter(item -> item.getId().getVideoId() != null)
                    .map(item -> new VideoRankDto.VideoItem(
                            item.getId().getVideoId(),
                            item.getSnippet().getTitle() != null ? item.getSnippet().getTitle() : "",
                            item.getSnippet().getDescription() != null ? item.getSnippet().getDescription() : ""
                    ))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new YoutubeApiException(e.getMessage(), e);
        }
    }

    private List<String> searchVideoIdsByRegion(
            String keyword,
            String regionCode,
            String relevanceLanguage,
            LocalDateTime publishedAfter,
            int maxResults) {
        try {
            YouTube.Search.List searchRequest = youtubeClient.search().list(List.of("snippet"));
            searchRequest.setKey(apiKey);
            searchRequest.setQ(keyword);
            searchRequest.setType(List.of("video"));
            searchRequest.setMaxResults((long) maxResults);
            searchRequest.setRegionCode(regionCode);
            searchRequest.setRelevanceLanguage(relevanceLanguage);

            if (publishedAfter != null) {
                searchRequest.setPublishedAfter(publishedAfter.toInstant(ZoneOffset.UTC).toString());
            }

            SearchListResponse response = searchRequest.execute();
            return response.getItems().stream()
                    .map(item -> item.getId().getVideoId())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new YoutubeApiException(e.getMessage(), e);
        }
    }

    private List<YoutubeVideo> fetchAndSaveVideos(List<String> videoIds) {
        List<YoutubeVideo> result = new ArrayList<>();

        // DB에 이미 있는 영상은 재호출 없이 반환 (자막 유무와 무관하게 포함 — 분석 파이프라인이 자막 수집 담당)
        List<String> missingIds = new ArrayList<>();
        for (String videoId : videoIds) {
            youtubeVideoRepository.findByYoutubeVideoId(videoId).ifPresentOrElse(
                    result::add,
                    () -> missingIds.add(videoId)
            );
        }

        if (missingIds.isEmpty()) return result;

        // 없는 것만 YouTube API로 가져오기
        try {
            YouTube.Videos.List videosRequest = youtubeClient.videos()
                    .list(List.of("snippet", "contentDetails", "statistics"));
            videosRequest.setKey(apiKey);
            videosRequest.setId(missingIds);

            VideoListResponse response = videosRequest.execute();
            for (Video video : response.getItems()) {
                YoutubeVideo saved = saveVideo(video);
                result.add(saved);
            }
        } catch (IOException e) {
            throw new YoutubeApiException(e.getMessage(), e);
        }

        return result;
    }

    // defaultLanguageCode가 한국어가 아닌 영상 제목만 번역해서 DB 업데이트
    private void translateTitlesIfNeeded(List<YoutubeVideo> videos) {
        for (YoutubeVideo video : videos) {
            String lang = video.getDefaultLanguageCode();
            if (lang != null && !lang.startsWith("ko")) {
                String translated = titleTranslationService.translateToKorean(video.getTitle());
                video.updateTitle(translated);
            }
        }
    }

    // 영상 단건 저장
    YoutubeVideo saveVideo(Video video) {
        String videoId = video.getId();
        YoutubeVideo saved = youtubeVideoRepository.findByYoutubeVideoId(videoId)
                .orElseGet(() -> youtubeVideoRepository.save(YoutubeConverter.toYoutubeVideoEntity(video)));
        videoGraphSyncService.syncVideoNow(saved);
        return saved;
    }

    // 20분 초과 영상 제외 — 분석 파이프라인 처리 시간 제한
    private boolean isTooLong(YoutubeVideo video) {
        return video.getDurationSeconds() != null
                && video.getDurationSeconds() > MAX_NEWS_DURATION_SECONDS;
    }

    // 뉴스 영상 부적합 판별 — 1분 미만이거나, 3분 이하이면서 #Shorts 태그 포함된 경우
    private boolean isShorts(YoutubeVideo video) {
        if (video.getDurationSeconds() == null) {
            return false;
        }
        if (video.getDurationSeconds() < MIN_NEWS_DURATION_SECONDS) {
            return true;
        }
        if (video.getDurationSeconds() > SHORTS_MAX_DURATION_SECONDS) {
            return false;
        }
        String title = video.getTitle() != null ? video.getTitle().toLowerCase() : "";
        String description = video.getDescription() != null ? video.getDescription().toLowerCase() : "";
        return title.contains("#shorts") || description.contains("#shorts");
    }

    // 영상 목록을 파이프라인에 전송하여 서브클러스터 할당 결과 반환
    // 반환: {youtubeVideoId → subClusterId}
    public Map<String, Integer> clusterVideos(List<YoutubeVideo> videos) {
        List<VideoClusterDto.VideoItem> items = videos.stream()
                .map(v -> new VideoClusterDto.VideoItem(
                        v.getYoutubeVideoId(),
                        v.getTitle() != null ? v.getTitle() : "",
                        v.getDescription() != null ? v.getDescription() : ""
                ))
                .toList();

        try {
            VideoClusterDto.Request request = new VideoClusterDto.Request(items, null);
            VideoClusterDto.Response response = restTemplate.postForObject(
                    aiPipelineBaseUrl + "/content/cluster-videos",
                    request,
                    VideoClusterDto.Response.class
            );
            if (response == null || response.clusters() == null) {
                log.warn("cluster-videos returned empty response, skipping sub-clustering");
                return Map.of();
            }
            Map<String, Integer> videoIdToCluster = new java.util.HashMap<>();
            for (VideoClusterDto.ClusterResult cluster : response.clusters()) {
                for (String videoId : cluster.videoIds()) {
                    videoIdToCluster.put(videoId, cluster.clusterId());
                }
            }
            return videoIdToCluster;
        } catch (Exception e) {
            log.warn("cluster-videos failed, skipping sub-clustering. reason={}", e.getMessage());
            return Map.of();
        }
    }

    // 영상-키워드 연결 중복 없이 저장
    private void linkKeywordToVideos(String keyword, List<YoutubeVideo> videos) {
        String normalized = keyword.trim().toLowerCase();
        Keyword keywordEntity = keywordRepository.findByNormalizedKeyword(normalized)
                .orElseGet(() -> keywordRepository.save(
                        Keyword.builder()
                                .keywordName(keyword)
                                .normalizedKeyword(normalized)
                                .build()
                ));

        for (YoutubeVideo video : videos) {
            if (!youtubeVideoKeywordRepository.existsByYoutubeVideoAndKeyword(video, keywordEntity)) {
                youtubeVideoKeywordRepository.save(
                        YoutubeVideoKeyword.builder()
                                .youtubeVideo(video)
                                .keyword(keywordEntity)
                                .build()
                );
            }
        }
    }

}
