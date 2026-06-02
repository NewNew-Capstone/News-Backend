package com.example.news.domain.issue.service;

import com.example.news.domain.analysis.entity.BiasAnalysisResult;
import com.example.news.domain.analysis.enums.TargetType;
import com.example.news.domain.analysis.repository.BiasAnalysisKeywordRepository;
import com.example.news.domain.analysis.repository.BiasAnalysisResultRepository;
import com.example.news.domain.analysis.service.AnalysisService;
import com.example.news.domain.content.entity.YoutubeVideo;
import com.example.news.domain.content.repository.YoutubeVideoKeywordRepository;
import com.example.news.domain.content.repository.YoutubeVideoRepository;
import com.example.news.domain.content.service.YoutubeSearchService;
import com.example.news.domain.content.service.YoutubeTranscriptService;
import com.example.news.domain.issue.converter.IssueConverter;
import com.example.news.domain.issue.dto.*;
import com.example.news.domain.issue.entity.ComparisonCountryItem;
import com.example.news.domain.issue.entity.ComparisonResult;
import com.example.news.domain.issue.entity.IssueCluster;
import com.example.news.domain.issue.entity.IssueClusterItem;
import com.example.news.domain.issue.enums.ClusterStatus;
import com.example.news.domain.issue.enums.IssueClusterItemSourceType;
import com.example.news.domain.issue.enums.IssueClusterType;
import com.example.news.domain.issue.exception.IssueErrorCode;
import com.example.news.domain.issue.exception.IssueException;
import com.example.news.domain.issue.repository.ComparisonCountryItemRepository;
import com.example.news.domain.issue.repository.ComparisonResultRepository;
import com.example.news.domain.issue.repository.IssueClusterItemRepository;
import com.example.news.domain.issue.repository.IssueClusterRepository;
import com.example.news.domain.graph.service.IssueGraphSyncService;
import com.example.news.domain.graph.service.VideoGraphSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class IssueService {
    private static final List<String> CURATION_COUNTRIES = List.of("KR", "US", "CN");
    private static final int MIN_ANALYZED_PER_COUNTRY = 10;

    private final KeywordTranslationService keywordTranslationService;
    private final YoutubeSearchService youtubeSearchService;
    private final YoutubeVideoRepository youtubeVideoRepository;
    private final YoutubeVideoKeywordRepository youtubeVideoKeywordRepository;
    private final IssueClusterRepository issueClusterRepository;
    private final IssueClusterItemRepository issueClusterItemRepository;
    private final ComparisonResultRepository comparisonResultRepository;
    private final ComparisonCountryItemRepository comparisonCountryItemRepository;
    private final BiasAnalysisResultRepository biasAnalysisResultRepository;
    private final BiasAnalysisKeywordRepository biasAnalysisKeywordRepository;
    private final IssueGraphSyncService issueGraphSyncService;
    private final VideoGraphSyncService videoGraphSyncService;
    private final AnalysisService analysisService;
    private final YoutubeTranscriptService youtubeTranscriptService;

    // 국가별 이슈 영상 검색
    @Transactional
    public IssueSearchResponseDto search(String searchKeyword, String countries, int days) {
        return search(searchKeyword, countries, days, true);
    }

    // autoCluster=false 인 경우: IssueCluster/IssueClusterItem 저장 없이 검색 결과만 반환
    @Transactional
    public IssueSearchResponseDto search(String searchKeyword, String countries, int days, boolean autoCluster) {
        List<String> countryList = IssueConverter.parseCountries(countries);
        LocalDate[] dates = IssueConverter.parsePeriod(days);
        LocalDate startDate = dates[0];
        LocalDate endDate = dates[1];

        // 국가별 키워드 번역
        Map<String, String> translatedKeywords = keywordTranslationService.translate(searchKeyword, countryList);

        if (!autoCluster) {
            List<IssueSearchResponseDto.VideoResult> searchOnlyResults = new java.util.ArrayList<>();
            for (String countryCode : countryList) {
                String translatedKeyword = translatedKeywords.get(countryCode);
                String langCode = IssueConverter.getLanguageCode(countryCode);
                var videos = youtubeSearchService.searchByRegion(
                        translatedKeyword, countryCode, langCode, startDate, endDate);
                for (var card : videos) {
                    Long dbVideoId = youtubeVideoRepository.findByYoutubeVideoId(card.getYoutubeVideoId())
                            .map(YoutubeVideo::getId)
                            .orElse(null);
                    searchOnlyResults.add(IssueSearchResponseDto.VideoResult.builder()
                            .videoId(dbVideoId)
                            .youtubeVideoId(card.getYoutubeVideoId())
                            .countryCode(countryCode)
                            .title(card.getTitle())
                            .channelName(card.getChannelName())
                            .thumbnailUrl(card.getThumbnailUrl())
                            .publishedAt(card.getPublishedAt())
                            .similarityScore(null)
                            .isRepresentative(false)
                            .build());
                }
            }
            return IssueSearchResponseDto.builder()
                    .issueClusterId(null)
                    .searchKeyword(searchKeyword)
                    .periodStartDate(startDate)
                    .periodEndDate(endDate)
                    .results(searchOnlyResults)
                    .build();
        }

        // issue_cluster 생성 (검색 세션 한개를 의미함)
        IssueCluster cluster = issueClusterRepository.save(
                IssueCluster.builder()
                        .searchKeyword(searchKeyword)
                        .periodStartDate(startDate)
                        .periodEndDate(endDate)
                        .status(ClusterStatus.PENDING)
                        .clusterType(IssueClusterType.SEARCH_AUTO)
                        .build()
        );

        // 국가별 YouTube 검색 + issue_cluster_item 저장
        for (String countryCode : countryList) {
            String translatedKeyword = translatedKeywords.get(countryCode);
            String langCode = IssueConverter.getLanguageCode(countryCode);

            var videos = youtubeSearchService.searchByRegion(
                    translatedKeyword, countryCode, langCode, startDate, endDate);

            saveClusterItems(cluster, countryCode, videos);
        }

        // 저장된 아이템 조회 후 영상 정보 batch fetch
        List<IssueClusterItem> clusterItems = issueClusterItemRepository.findByIssueClusterId(cluster.getId());
        List<Long> videoIds = clusterItems.stream().map(IssueClusterItem::getYoutubeVideoId).toList();
        Map<Long, YoutubeVideo> videoMap = youtubeVideoRepository.findAllById(videoIds).stream()
                .collect(Collectors.toMap(YoutubeVideo::getId, v -> v));

        // 영상-영상 유사도 기반 서브클러스터링 (같은 서브토픽끼리 묶기)
        List<YoutubeVideo> allVideos = new java.util.ArrayList<>(videoMap.values());
        Map<String, Integer> subClusterMap = youtubeSearchService.clusterVideos(allVideos);
        if (!subClusterMap.isEmpty()) {
            clusterItems.forEach(item -> {
                YoutubeVideo video = videoMap.get(item.getYoutubeVideoId());
                if (video != null) {
                    Integer subClusterId = subClusterMap.get(video.getYoutubeVideoId());
                    if (subClusterId != null) {
                        issueClusterItemRepository.updateSubClusterId(item.getId(), subClusterId);
                    }
                }
            });
        }

        // 클러스터 내 모든 영상 백그라운드 분석 트리거 (커밋 이후 실행 보장)
        List<Long> triggerIds = videoIds;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                triggerIds.forEach(analysisService::triggerAnalysisAsync);
            }
        });

        IssueSearchResponseDto response = IssueConverter.toSearchResponse(cluster, clusterItems, videoMap);
        issueGraphSyncService.syncIssue(cluster, clusterItems, videoMap);
        return response;
    }

    // 국가별 대표 영상 비교 결과 조회 (저장된 비교 결과 조회)
    @Transactional(readOnly = true)
    public IssueComparisonResponseDto comparison(Long issueClusterId) {
        issueClusterRepository.findById(issueClusterId)
                .orElseThrow(() -> new IssueException(IssueErrorCode.ISSUE_CLUSTER_NOT_FOUND));
        assertCurationCluster(issueClusterId);

        ComparisonResult comparisonResult = comparisonResultRepository
                .findTopByIssueClusterIdOrderByCreatedAtDesc(issueClusterId)
                .orElseThrow(() -> new IssueException(IssueErrorCode.COMPARISON_RESULT_NOT_FOUND));

        List<ComparisonCountryItem> countryItems = comparisonCountryItemRepository
                .findByComparisonResultId(comparisonResult.getId());

        if (countryItems.isEmpty()) {
            return IssueComparisonResponseDto.builder()
                    .countries(List.of())
                    .build();
        }

        List<Long> videoIds = countryItems.stream()
                .map(ComparisonCountryItem::getRepresentativeVideoId)
                .filter(java.util.Objects::nonNull)
                .toList();

        Map<Long, YoutubeVideo> videoMap = youtubeVideoRepository.findAllById(videoIds).stream()
                .collect(Collectors.toMap(YoutubeVideo::getId, v -> v));

        Map<Long, BiasAnalysisResult> analysisResultMap = biasAnalysisResultRepository
                .findByTargetTypeAndTargetIdIn(TargetType.YOUTUBE_VIDEO, videoIds)
                .stream()
                .collect(Collectors.toMap(
                        BiasAnalysisResult::getTargetId,
                        r -> r,
                        (a, b) -> {
                            if (a.getCreatedAt() == null) return b;
                            if (b.getCreatedAt() == null) return a;
                            return a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b;
                        }
                ));

        List<IssueComparisonResponseDto.CountryResult> results = countryItems.stream()
                .map(item -> IssueConverter.toComparisonCountryResult(
                        item,
                        videoMap.get(item.getRepresentativeVideoId()),
                        analysisResultMap.get(item.getRepresentativeVideoId())
                ))
                .toList();

        return IssueComparisonResponseDto.builder()
                .countries(results)
                .build();
    }

    // 반대 관점 영상 도출: 같은 IssueCluster 내에서 opinionScore 차이가 가장 큰 영상 반환
    @Transactional
    public OpposingVideoResponseDto findOpposingVideo(Long videoId) {
        BiasAnalysisResult myResult = biasAnalysisResultRepository
                .findTopByTargetIdAndTargetTypeOrderByCreatedAtDesc(videoId, TargetType.YOUTUBE_VIDEO)
                .orElseThrow(() -> new IssueException(IssueErrorCode.ANALYSIS_NOT_COMPLETED));

        if (myResult.getOverallBiasScore() == null) {
            throw new IssueException(IssueErrorCode.ANALYSIS_NOT_COMPLETED);
        }
        double myBiasScore = myResult.getOverallBiasScore();

        // 같은 클러스터 내 후보 수집 (클러스터 미포함 시 키워드 공유 영상으로 fallback)
        Set<Long> clusterIds = issueClusterItemRepository.findByYoutubeVideoId(videoId)
                .stream()
                .map(IssueClusterItem::getIssueCluster)
                .filter(cluster -> cluster.getClusterType() == IssueClusterType.SEARCH_AUTO)
                .map(IssueCluster::getId)
                .collect(Collectors.toSet());

        List<Long> candidateVideoIds;
        if (!clusterIds.isEmpty()) {
            List<IssueClusterItem> allItems = issueClusterItemRepository.findByIssueClusterIdIn(clusterIds);

            // 내 영상의 subClusterId 조회
            Integer mySubClusterId = allItems.stream()
                    .filter(item -> item.getYoutubeVideoId().equals(videoId))
                    .map(IssueClusterItem::getSubClusterId)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);

            // 1차: 같은 서브클러스터 내 후보
            if (mySubClusterId != null) {
                candidateVideoIds = allItems.stream()
                        .filter(item -> !item.getYoutubeVideoId().equals(videoId))
                        .filter(item -> mySubClusterId.equals(item.getSubClusterId()))
                        .map(IssueClusterItem::getYoutubeVideoId)
                        .distinct()
                        .toList();
            } else {
                candidateVideoIds = List.of();
            }

            // 2차 fallback: 서브클러스터 후보 없으면 전체 클러스터에서 탐색
            if (candidateVideoIds.isEmpty()) {
                candidateVideoIds = allItems.stream()
                        .filter(item -> !item.getYoutubeVideoId().equals(videoId))
                        .map(IssueClusterItem::getYoutubeVideoId)
                        .distinct()
                        .toList();
            }
        } else {
            // 클러스터 미포함 영상: 같은 검색 키워드로 수집된 영상에서 후보 탐색
            candidateVideoIds = youtubeVideoKeywordRepository.findVideoIdsSharingKeywordWith(videoId);
        }

        if (candidateVideoIds.isEmpty()) {
            throw new IssueException(IssueErrorCode.OPPOSING_VIDEO_NOT_FOUND);
        }

        // overallBiasScore 차이 최대인 영상 선정
        BiasAnalysisResult opposing = biasAnalysisResultRepository
                .findByTargetTypeAndTargetIdIn(TargetType.YOUTUBE_VIDEO, candidateVideoIds)
                .stream()
                .filter(r -> r.getOverallBiasScore() != null && r.getOverallBiasScore() > 0.0)
                .max(Comparator
                        .comparingDouble((BiasAnalysisResult r) ->
                                Math.abs(myBiasScore - r.getOverallBiasScore()))
                        .thenComparingDouble(r -> r.getOpinionScore() != null ? r.getOpinionScore() : 0.0))
                .orElseThrow(() -> new IssueException(IssueErrorCode.OPPOSING_VIDEO_NOT_FOUND));

        YoutubeVideo opposingVideo = youtubeVideoRepository.findById(opposing.getTargetId())
                .orElseThrow(() -> new IssueException(IssueErrorCode.VIDEO_NOT_FOUND));

        var opposingTranscript = youtubeTranscriptService.getOrFetchTranscriptEntity(
                opposingVideo.getYoutubeVideoId(),
                true
        );
        analysisService.enrichDisplayTextIfNeeded(opposing, opposingTranscript);

        List<String> keywords = biasAnalysisKeywordRepository
                .findAllByBiasAnalysisResultId(opposing.getId())
                .stream()
                .map(k -> k.getKeywordText())
                .toList();

        double opinionGap = Math.abs(myBiasScore - opposing.getOverallBiasScore());

        return OpposingVideoResponseDto.builder()
                .youtubeVideoId(opposingVideo.getYoutubeVideoId())
                .title(opposingVideo.getTitle())
                .channelName(opposingVideo.getChannelName())
                .summaryText(opposing.getSummaryText())
                .opinionScore(opposing.getOpinionScore())
                .overallBiasScore(opposing.getOverallBiasScore())
                .opinionGap(opinionGap)
                .scoreEvidence(opposing.getScoreEvidence())
                .scoreReasonSummary(opposing.getScoreReasonSummary())
                .analysisKeywords(keywords)
                .build();
    }

    private void saveClusterItems(IssueCluster cluster, String countryCode,
                                  List<com.example.news.domain.content.dto.YoutubeVideoDto.VideoCard> videoCards) {
        for (int i = 0; i < videoCards.size(); i++) {
            var card = videoCards.get(i);
            final int rankNo = i + 1;
            youtubeVideoRepository.findByYoutubeVideoId(card.getYoutubeVideoId())
                    .ifPresent(video -> issueClusterItemRepository.save(
                            IssueClusterItem.builder()
                                    .issueCluster(cluster)
                                    .youtubeVideoId(video.getId())
                                    .countryCode(countryCode)
                                    .isRepresentative(false)
                                    .similarityScore(card.getSimilarityScore())
                                    .rankNo(rankNo)
                                    .sourceType(IssueClusterItemSourceType.AUTO)
                                    .build()
                    ));
        }
    }

    @Transactional
    public CurationDto.CurationSetResponse createCurationSet(CurationDto.CreateSetRequest request) {
        IssueCluster cluster = issueClusterRepository.save(IssueCluster.builder()
                .searchKeyword(request.searchKeyword().trim())
                .normalizedKeyword(request.searchKeyword().trim().toLowerCase())
                .periodStartDate(request.periodStartDate())
                .periodEndDate(request.periodEndDate())
                .status(ClusterStatus.DRAFT)
                .clusterType(IssueClusterType.CURATION_MANUAL)
                .build());
        return toCurationSetResponse(cluster);
    }

    @Transactional
    public CurationDto.CurationItemResponse addCurationItem(Long issueClusterId, CurationDto.AddItemRequest request) {
        IssueCluster cluster = issueClusterRepository.findById(issueClusterId)
                .orElseThrow(() -> new IssueException(IssueErrorCode.ISSUE_CLUSTER_NOT_FOUND));
        assertCurationCluster(cluster);
        assertDraft(cluster);
        String countryCode = normalizeCountryCode(request.countryCode());
        if (!CURATION_COUNTRIES.contains(countryCode)) {
            throw new IssueException(IssueErrorCode.INVALID_COUNTRY_SCOPE, "countryCode: " + countryCode);
        }
        youtubeVideoRepository.findById(request.videoId())
                .orElseThrow(() -> new IssueException(IssueErrorCode.VIDEO_NOT_FOUND, "videoId: " + request.videoId()));
        if (issueClusterItemRepository.findByIssueClusterIdAndYoutubeVideoId(issueClusterId, request.videoId()).isPresent()) {
            throw new IssueException(IssueErrorCode.CURATION_ITEM_DUPLICATE);
        }
        IssueClusterItem item = issueClusterItemRepository.save(IssueClusterItem.builder()
                .issueCluster(cluster)
                .youtubeVideoId(request.videoId())
                .countryCode(countryCode)
                .isRepresentative(false)
                .sourceType(IssueClusterItemSourceType.MANUAL)
                .build());
        return CurationDto.CurationItemResponse.builder()
                .issueClusterItemId(item.getId())
                .videoId(item.getYoutubeVideoId())
                .countryCode(item.getCountryCode())
                .sourceType(item.getSourceType().name())
                .build();
    }

    @Transactional
    public void removeCurationItem(Long issueClusterId, Long issueClusterItemId) {
        IssueCluster cluster = issueClusterRepository.findById(issueClusterId)
                .orElseThrow(() -> new IssueException(IssueErrorCode.ISSUE_CLUSTER_NOT_FOUND));
        assertCurationCluster(cluster);
        assertDraft(cluster);
        IssueClusterItem item = issueClusterItemRepository.findById(issueClusterItemId)
                .orElseThrow(() -> new IssueException(IssueErrorCode.VIDEO_NOT_FOUND, "issueClusterItemId: " + issueClusterItemId));
        if (!item.getIssueCluster().getId().equals(issueClusterId)) {
            throw new IssueException(IssueErrorCode.ISSUE_CLUSTER_NOT_FOUND, "cluster/item mismatch");
        }
        issueClusterItemRepository.delete(item);
    }

    @Transactional
    public CurationDto.CurationStatusResponse lockCurationSet(Long issueClusterId) {
        IssueCluster cluster = issueClusterRepository.findById(issueClusterId)
                .orElseThrow(() -> new IssueException(IssueErrorCode.ISSUE_CLUSTER_NOT_FOUND));
        assertCurationCluster(cluster);
        assertDraft(cluster);
        List<IssueClusterItem> items = issueClusterItemRepository.findByIssueClusterId(issueClusterId);
        if (items.isEmpty()) {
            throw new IssueException(IssueErrorCode.INVALID_CLUSTER_STATUS, "cannot lock empty curation set");
        }
        validateCurationItemsForLock(items);

        List<Long> videoIds = items.stream()
                .map(IssueClusterItem::getYoutubeVideoId)
                .distinct()
                .toList();
        Map<Long, YoutubeVideo> videoMap = youtubeVideoRepository.findAllById(videoIds).stream()
                .collect(Collectors.toMap(YoutubeVideo::getId, v -> v));
        List<Long> missingVideoIds = videoIds.stream()
                .filter(id -> !videoMap.containsKey(id))
                .toList();
        if (!missingVideoIds.isEmpty()) {
            throw new IssueException(IssueErrorCode.VIDEO_NOT_FOUND, "videoIds: " + missingVideoIds);
        }

        issueClusterRepository.updateStatus(issueClusterId, ClusterStatus.LOCKED);
        issueClusterRepository.updateStatus(issueClusterId, ClusterStatus.ANALYZING);

        // 기능2는 item.countryCode를 소스 오브 트루스로 사용해 Video 노드를 먼저 보장한다.
        Map<Long, String> countryHintByVideoId = items.stream()
                .collect(Collectors.toMap(
                        IssueClusterItem::getYoutubeVideoId,
                        item -> normalizeCountryCode(item.getCountryCode()),
                        (a, b) -> a
                ));
        for (Long videoId : videoIds) {
            YoutubeVideo video = videoMap.get(videoId);
            if (video != null) {
                videoGraphSyncService.syncVideoNow(video, countryHintByVideoId.get(videoId));
            }
        }

        // 기능2 큐레이션 세트는 LOCK 시점에 Neo4j Issue/PART_OF를 확정 반영한다.
        issueGraphSyncService.syncIssue(cluster, items, videoMap);

        videoIds.forEach(analysisService::triggerAnalysisAsync);
        return getCurationStatus(issueClusterId);
    }

    @Transactional(readOnly = true)
    public CurationDto.CurationStatusResponse getCurationStatus(Long issueClusterId) {
        IssueCluster cluster = issueClusterRepository.findById(issueClusterId)
                .orElseThrow(() -> new IssueException(IssueErrorCode.ISSUE_CLUSTER_NOT_FOUND));
        assertCurationCluster(cluster);
        List<IssueClusterItem> items = issueClusterItemRepository.findByIssueClusterId(issueClusterId);
        List<Long> videoIds = items.stream().map(IssueClusterItem::getYoutubeVideoId).distinct().toList();
        Map<Long, BiasAnalysisResult> analysisByTarget = biasAnalysisResultRepository
                .findByTargetTypeAndTargetIdIn(TargetType.YOUTUBE_VIDEO, videoIds)
                .stream()
                .collect(Collectors.toMap(
                        BiasAnalysisResult::getTargetId,
                        r -> r,
                        (a, b) -> a.getCreatedAt() != null && b.getCreatedAt() != null && b.getCreatedAt().isAfter(a.getCreatedAt()) ? b : a
                ));

        Map<String, Integer> analyzedByCountry = initializeCountryCounter();
        Map<String, Integer> totalByCountry = initializeCountryCounter();
        for (IssueClusterItem item : items) {
            String code = normalizeCountryCode(item.getCountryCode());
            if (!CURATION_COUNTRIES.contains(code)) continue;
            totalByCountry.put(code, totalByCountry.get(code) + 1);
            if (analysisByTarget.containsKey(item.getYoutubeVideoId())) {
                analyzedByCountry.put(code, analyzedByCountry.get(code) + 1);
            }
        }
        Map<String, Integer> remainingByCountry = initializeCountryCounter();
        List<String> missingCountries = new java.util.ArrayList<>();
        for (String country : CURATION_COUNTRIES) {
            int remaining = Math.max(0, MIN_ANALYZED_PER_COUNTRY - analyzedByCountry.get(country));
            remainingByCountry.put(country, remaining);
            if (remaining > 0) {
                missingCountries.add(country);
            }
        }
        return CurationDto.CurationStatusResponse.builder()
                .issueClusterId(issueClusterId)
                .status(cluster.getStatus())
                .totalItems(items.size())
                .analyzedByCountry(analyzedByCountry)
                .remainingByCountry(remainingByCountry)
                .readyForReport(missingCountries.isEmpty())
                .missingCountries(missingCountries)
                .build();
    }

    @Transactional(readOnly = true)
    public IssueComparisonReportResponseDto comparisonReport(Long issueClusterId) {
        IssueCluster cluster = issueClusterRepository.findById(issueClusterId)
                .orElseThrow(() -> new IssueException(IssueErrorCode.ISSUE_CLUSTER_NOT_FOUND));
        assertCurationCluster(cluster);
        List<IssueClusterItem> items = issueClusterItemRepository.findByIssueClusterId(issueClusterId);
        List<Long> videoIds = items.stream().map(IssueClusterItem::getYoutubeVideoId).distinct().toList();
        Map<Long, YoutubeVideo> videoById = youtubeVideoRepository.findAllById(videoIds).stream()
                .collect(Collectors.toMap(YoutubeVideo::getId, v -> v));
        Map<Long, BiasAnalysisResult> analysisByTarget = biasAnalysisResultRepository
                .findByTargetTypeAndTargetIdIn(TargetType.YOUTUBE_VIDEO, videoIds)
                .stream()
                .collect(Collectors.toMap(
                        BiasAnalysisResult::getTargetId,
                        r -> r,
                        (a, b) -> a.getCreatedAt() != null && b.getCreatedAt() != null && b.getCreatedAt().isAfter(a.getCreatedAt()) ? b : a
                ));

        CurationDto.CurationStatusResponse status = getCurationStatus(issueClusterId);

        List<IssueComparisonReportResponseDto.CountryMetrics> countries = CURATION_COUNTRIES.stream()
                .map(country -> buildCountryMetrics(country, items, videoById, analysisByTarget))
                .toList();

        return IssueComparisonReportResponseDto.builder()
                .issueClusterId(issueClusterId)
                .status(cluster.getStatus())
                .ready(status.readyForReport())
                .missingCountries(status.missingCountries())
                .countries(countries)
                .build();
    }

    private IssueComparisonReportResponseDto.CountryMetrics buildCountryMetrics(
            String countryCode,
            List<IssueClusterItem> items,
            Map<Long, YoutubeVideo> videoById,
            Map<Long, BiasAnalysisResult> analysisByTarget) {
        List<IssueClusterItem> countryItems = items.stream()
                .filter(i -> countryCode.equals(normalizeCountryCode(i.getCountryCode())))
                .toList();
        List<Long> analyzedIds = countryItems.stream()
                .map(IssueClusterItem::getYoutubeVideoId)
                .filter(analysisByTarget::containsKey)
                .toList();
        long totalViews = analyzedIds.stream()
                .map(videoById::get)
                .filter(java.util.Objects::nonNull)
                .map(v -> v.getViewCount() == null ? 0L : v.getViewCount())
                .reduce(0L, Long::sum);

        double avgViews = analyzedIds.isEmpty() ? 0.0 : (double) totalViews / analyzedIds.size();
        double avgBias = analyzedIds.stream()
                .map(analysisByTarget::get)
                .filter(java.util.Objects::nonNull)
                .map(BiasAnalysisResult::getOverallBiasScore)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
        double avgOpinion = analyzedIds.stream()
                .map(analysisByTarget::get)
                .filter(java.util.Objects::nonNull)
                .map(BiasAnalysisResult::getOpinionScore)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
        double avgFactRatio = analyzedIds.stream()
                .map(analysisByTarget::get)
                .filter(java.util.Objects::nonNull)
                .map(BiasAnalysisResult::getFactRatio)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

        Map<String, Integer> toneDistribution = new java.util.LinkedHashMap<>();
        toneDistribution.put("LOW_BIAS", 0);
        toneDistribution.put("MID_BIAS", 0);
        toneDistribution.put("HIGH_BIAS", 0);

        Map<String, Long> channelCounts = new java.util.HashMap<>();
        for (Long videoId : analyzedIds) {
            BiasAnalysisResult result = analysisByTarget.get(videoId);
            if (result != null && result.getOverallBiasScore() != null) {
                String tone = toneLabel(result.getOverallBiasScore());
                toneDistribution.put(tone, toneDistribution.get(tone) + 1);
            }
            YoutubeVideo video = videoById.get(videoId);
            if (video != null && video.getChannelId() != null) {
                channelCounts.merge(video.getChannelId(), 1L, Long::sum);
            }
        }
        long top1 = channelCounts.values().stream().max(Long::compareTo).orElse(0L);
        double top1Share = analyzedIds.isEmpty() ? 0.0 : (double) top1 / analyzedIds.size();
        List<Long> analysisResultIds = analyzedIds.stream()
                .map(analysisByTarget::get)
                .filter(java.util.Objects::nonNull)
                .map(BiasAnalysisResult::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        List<String> topKeywords = analysisResultIds.stream()
                .flatMap(id -> biasAnalysisKeywordRepository.findAllByBiasAnalysisResultId(id).stream())
                .map(k -> k.getKeywordText())
                .filter(k -> k != null && !k.isBlank())
                .collect(Collectors.groupingBy(
                        Function.identity(),
                        LinkedHashMap::new,
                        Collectors.counting()
                ))
                .entrySet()
                .stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();

        return IssueComparisonReportResponseDto.CountryMetrics.builder()
                .countryCode(countryCode)
                .videoCount(analyzedIds.size())
                .totalViewCount(totalViews)
                .avgViewCount(avgViews)
                .avgOverallBiasScore(avgBias)
                .avgOpinionScore(avgOpinion)
                .avgFactRatio(avgFactRatio)
                .toneDistribution(toneDistribution)
                .channelTop1Share(top1Share)
                .topKeywords(topKeywords)
                .build();
    }

    private String toneLabel(double overallBiasScore) {
        if (overallBiasScore < 0.33) return "LOW_BIAS";
        if (overallBiasScore < 0.66) return "MID_BIAS";
        return "HIGH_BIAS";
    }

    private Map<String, Integer> initializeCountryCounter() {
        Map<String, Integer> counter = new java.util.LinkedHashMap<>();
        CURATION_COUNTRIES.forEach(c -> counter.put(c, 0));
        return counter;
    }

    private void validateCurationItemsForLock(List<IssueClusterItem> items) {
        List<String> invalidCountryCodes = items.stream()
                .map(IssueClusterItem::getCountryCode)
                .map(this::normalizeCountryCode)
                .filter(code -> !CURATION_COUNTRIES.contains(code))
                .distinct()
                .toList();
        if (!invalidCountryCodes.isEmpty()) {
            throw new IssueException(
                    IssueErrorCode.INVALID_COUNTRY_SCOPE,
                    "invalid country codes in curation set: " + invalidCountryCodes
            );
        }
    }

    private void assertDraft(IssueCluster cluster) {
        if (cluster.getStatus() != ClusterStatus.DRAFT) {
            throw new IssueException(IssueErrorCode.INVALID_CLUSTER_STATUS, "status=" + cluster.getStatus());
        }
    }

    private void assertCurationCluster(Long issueClusterId) {
        IssueCluster cluster = issueClusterRepository.findById(issueClusterId)
                .orElseThrow(() -> new IssueException(IssueErrorCode.ISSUE_CLUSTER_NOT_FOUND));
        assertCurationCluster(cluster);
    }

    private void assertCurationCluster(IssueCluster cluster) {
        if (cluster.getClusterType() != IssueClusterType.CURATION_MANUAL) {
            throw new IssueException(IssueErrorCode.INVALID_CLUSTER_STATUS, "clusterType=" + cluster.getClusterType());
        }
    }

    private String normalizeCountryCode(String code) {
        return code == null ? "" : code.trim().toUpperCase();
    }

    private CurationDto.CurationSetResponse toCurationSetResponse(IssueCluster cluster) {
        return CurationDto.CurationSetResponse.builder()
                .issueClusterId(cluster.getId())
                .searchKeyword(cluster.getSearchKeyword())
                .periodStartDate(cluster.getPeriodStartDate())
                .periodEndDate(cluster.getPeriodEndDate())
                .status(cluster.getStatus())
                .build();
    }
}
