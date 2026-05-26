package com.example.news.domain.comparison.service;

import com.example.news.domain.comparison.dto.CompareOnClickRequest;
import com.example.news.domain.comparison.dto.CompareOnClickResponse;
import com.example.news.domain.comparison.dto.collect.MultilingualKeywordExpandResponse;
import com.example.news.domain.comparison.exception.ComparisonException;
import com.example.news.domain.comparison.exception.code.ComparisonErrorCode;
import com.example.news.domain.content.converter.YoutubeConverter;
import com.example.news.domain.content.dto.YoutubeVideoDto;
import com.example.news.domain.content.entity.YoutubeVideo;
import com.example.news.domain.content.service.TitleTranslationService;
import com.example.news.domain.content.service.YoutubeSearchService;
import com.example.news.domain.content.service.YoutubeVideoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompareOnClickService {

    private static final String DEMO_LOG_PREFIX = "[DEMO-COMPARE]";
    private static final int DEFAULT_LIMIT_PER_COUNTRY = 3;
    private static final int SEARCH_BUFFER_MULTIPLIER = 2;
    private static final List<String> COUNTRIES = List.of("KR", "US", "CN");
    private static final Map<String, String> LANGUAGE_BY_COUNTRY = Map.of(
            "KR", "ko",
            "US", "en",
            "CN", "zh"
    );
    private static final Map<String, String> COUNTRY_NAME_BY_CODE = Map.of(
            "KR", "한국",
            "US", "미국",
            "CN", "중국"
    );
    private static final Pattern HASHTAG_PATTERN = Pattern.compile("#\\S+");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private final YoutubeVideoService youtubeVideoService;
    private final YoutubeSearchService youtubeSearchService;
    private final TitleTranslationService titleTranslationService;
    private final ComparisonProxyService comparisonProxyService;

    @Transactional
    public CompareOnClickResponse compareOnClick(CompareOnClickRequest request, Integer limitPerCountry) {
        int limit = resolveLimit(limitPerCountry);
        YoutubeVideo selectedVideo = youtubeVideoService.getOrFetchVideoEntity(request.youtubeVideoId());
        String sourceCountryCode = resolveCountryCode(request, selectedVideo);
        String searchKeyword = resolveSearchKeyword(request, selectedVideo);
        log.info("{} step=2 action=clicked_video_selected_legacy video_id={} title=\"{}\" country_code={} language={}",
                DEMO_LOG_PREFIX,
                request.youtubeVideoId(),
                compactLogText(firstNonBlank(request.title(), selectedVideo.getTitle())),
                sourceCountryCode,
                firstNonBlank(request.defaultLanguageCode(), selectedVideo.getDefaultLanguageCode()));
        MultilingualKeywordExpandResponse expandedKeywords = expandKeywords(searchKeyword);

        List<String> candidateCountries = COUNTRIES.stream()
                .filter(countryCode -> !countryCode.equals(sourceCountryCode))
                .toList();
        log.info("{} step=5 action=candidate_scope_legacy source_country={} candidate_countries={} excluded={}",
                DEMO_LOG_PREFIX, sourceCountryCode, candidateCountries, List.of(sourceCountryCode));

        List<CompareOnClickResponse.CountryVideoSection> sections = COUNTRIES.stream()
                .filter(countryCode -> !countryCode.equals(sourceCountryCode))
                .map(countryCode -> buildCountrySection(countryCode, expandedKeywords, request.youtubeVideoId(), limit))
                .toList();

        return CompareOnClickResponse.builder()
                .selectedVideo(YoutubeConverter.toVideoDetail(selectedVideo))
                .sourceCountryCode(sourceCountryCode)
                .searchKeyword(searchKeyword)
                .sections(sections)
                .build();
    }

    @Transactional
    public CompareOnClickResponse recommendByKeyword(String keyword, Integer limitPerCountry) {
        int limit = resolveLimit(limitPerCountry);
        String searchKeyword = normalizeSearchKeyword(keyword);
        log.info("{} step=1 action=search_keyword_received keyword=\"{}\" limit={} endpoint=/api/v1/comparison/country-recommendations",
                DEMO_LOG_PREFIX, compactLogText(searchKeyword), limit);
        MultilingualKeywordExpandResponse expandedKeywords = expandKeywords(searchKeyword);
        List<CompareOnClickResponse.CountryVideoSection> sections = COUNTRIES.stream()
                .map(countryCode -> buildCountrySection(countryCode, expandedKeywords, null, limit))
                .toList();

        return CompareOnClickResponse.builder()
                .sourceCountryCode(null)
                .searchKeyword(searchKeyword)
                .sections(sections)
                .build();
    }

    private CompareOnClickResponse.CountryVideoSection buildCountrySection(
            String countryCode,
            MultilingualKeywordExpandResponse expandedKeywords,
            String selectedYoutubeVideoId,
            int limit
    ) {
        String languageCode = LANGUAGE_BY_COUNTRY.get(countryCode);
        List<String> terms = keywordsForCountry(expandedKeywords, countryCode);
        Map<String, YoutubeVideoDto.VideoCard> videosById = new LinkedHashMap<>();

        for (String term : terms) {
            if (videosById.size() >= limit) {
                break;
            }

            try {
                List<YoutubeVideoDto.VideoCard> cards = youtubeSearchService.searchByRegion(
                        term,
                        countryCode,
                        languageCode,
                        (LocalDateTime) null,
                        Math.max(limit * SEARCH_BUFFER_MULTIPLIER, limit)
                );

                for (YoutubeVideoDto.VideoCard card : cards) {
                    if (card.getYoutubeVideoId() == null || card.getYoutubeVideoId().equals(selectedYoutubeVideoId)) {
                        continue;
                    }

                    videosById.putIfAbsent(card.getYoutubeVideoId(), withDisplayCountryAndKoreanTitle(card, countryCode));

                    if (videosById.size() >= limit) {
                        break;
                    }
                }
            } catch (RuntimeException e) {
                log.warn("compare-on-click country search failed. country={}, term={}, reason={}",
                        countryCode, term, e.getMessage());
            }
        }

        List<YoutubeVideoDto.VideoCard> countryVideos = new ArrayList<>(videosById.values());
        log.info("{} step=7 action=country_top_results_legacy country={} top_video_ids={}",
                DEMO_LOG_PREFIX,
                countryCode,
                countryVideos.stream().map(YoutubeVideoDto.VideoCard::getYoutubeVideoId).toList());

        return CompareOnClickResponse.CountryVideoSection.builder()
                .countryCode(countryCode)
                .countryName(COUNTRY_NAME_BY_CODE.get(countryCode))
                .languageCode(languageCode)
                .videos(countryVideos)
                .build();
    }

    private YoutubeVideoDto.VideoCard withDisplayCountryAndKoreanTitle(YoutubeVideoDto.VideoCard card, String countryCode) {
        String title = card.getTitle();

        if (!"KR".equals(countryCode)) {
            title = titleTranslationService.translateToKorean(title);
        }

        return YoutubeVideoDto.VideoCard.builder()
                .youtubeVideoId(card.getYoutubeVideoId())
                .title(title)
                .thumbnailUrl(card.getThumbnailUrl())
                .channelName(card.getChannelName())
                .publishedAt(card.getPublishedAt())
                .viewCount(card.getViewCount())
                .durationSeconds(card.getDurationSeconds())
                .countryCode(countryCode)
                .build();
    }

    private MultilingualKeywordExpandResponse expandKeywords(String searchKeyword) {
        try {
            MultilingualKeywordExpandResponse response = comparisonProxyService.expandMultilingualKeywords(searchKeyword);
            log.info("{} step=4 action=multilingual_keyword_expansion_legacy source_keyword=\"{}\" ko={} en={} zh={}",
                    DEMO_LOG_PREFIX,
                    compactLogText(searchKeyword),
                    response.expandedKeywords().ko(),
                    response.expandedKeywords().en(),
                    response.expandedKeywords().zh());
            return response;
        } catch (ComparisonException e) {
            log.warn("compare-on-click keyword expansion failed. keyword={}, reason={}",
                    searchKeyword, e.getMessage());
            return new MultilingualKeywordExpandResponse(
                    searchKeyword,
                    new MultilingualKeywordExpandResponse.ExpandedKeywords(
                            List.of(searchKeyword),
                            List.of(searchKeyword),
                            List.of(searchKeyword)
                    )
            );
        }
    }

    private List<String> keywordsForCountry(MultilingualKeywordExpandResponse expandedKeywords, String countryCode) {
        List<String> keywords = switch (countryCode) {
            case "KR" -> expandedKeywords.expandedKeywords().ko();
            case "US" -> expandedKeywords.expandedKeywords().en();
            case "CN" -> expandedKeywords.expandedKeywords().zh();
            default -> List.of();
        };

        List<String> normalized = keywords == null
                ? List.of()
                : keywords.stream()
                .filter(keyword -> keyword != null && !keyword.isBlank())
                .map(String::trim)
                .distinct()
                .toList();

        if (!normalized.isEmpty()) {
            return normalized;
        }

        String fallbackKeyword = expandedKeywords.requestedKeyword();
        return fallbackKeyword == null || fallbackKeyword.isBlank() ? List.of() : List.of(fallbackKeyword);
    }

    private String resolveSearchKeyword(CompareOnClickRequest request, YoutubeVideo selectedVideo) {
        String rawTitle = request.title() != null && !request.title().isBlank()
                ? request.title()
                : selectedVideo.getTitle();
        String normalized = normalizeSearchKeyword(rawTitle);

        if (normalized.isBlank()) {
            throw new ComparisonException(
                    ComparisonErrorCode.INVALID_COMPARISON_REQUEST,
                    "비교 검색에 사용할 영상 제목이 비어 있습니다."
            );
        }

        return normalized.length() > 80 ? normalized.substring(0, 80).trim() : normalized;
    }

    private String normalizeSearchKeyword(String keyword) {
        String withoutHashtags = HASHTAG_PATTERN.matcher(keyword == null ? "" : keyword).replaceAll(" ");
        String normalized = WHITESPACE_PATTERN.matcher(withoutHashtags).replaceAll(" ").trim();

        if (normalized.isBlank()) {
            throw new ComparisonException(
                    ComparisonErrorCode.INVALID_COMPARISON_REQUEST,
                    "keyword는 비어 있을 수 없습니다."
            );
        }

        return normalized.length() > 80 ? normalized.substring(0, 80).trim() : normalized;
    }

    private String resolveCountryCode(CompareOnClickRequest request, YoutubeVideo selectedVideo) {
        String countryCode = normalizeCountryCode(firstNonBlank(request.countryCode(), selectedVideo.getCountryCode()));

        if (COUNTRIES.contains(countryCode)) {
            return countryCode;
        }

        String languageCode = firstNonBlank(request.defaultLanguageCode(), selectedVideo.getDefaultLanguageCode());
        String normalizedLanguageCode = languageCode == null ? "" : languageCode.toLowerCase();

        if (normalizedLanguageCode.startsWith("ko")) {
            return "KR";
        }
        if (normalizedLanguageCode.startsWith("en")) {
            return "US";
        }
        if (normalizedLanguageCode.startsWith("zh") || normalizedLanguageCode.startsWith("cn")) {
            return "CN";
        }

        return "KR";
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return "";
    }

    private String normalizeCountryCode(String countryCode) {
        if (countryCode == null) {
            return "";
        }

        String normalized = countryCode.trim().toUpperCase();

        if ("KO".equals(normalized) || "KOR".equals(normalized)) {
            return "KR";
        }
        if ("USA".equals(normalized) || "EN".equals(normalized)) {
            return "US";
        }
        if ("CHN".equals(normalized) || "ZH".equals(normalized) || "ZH-CN".equals(normalized)) {
            return "CN";
        }

        return normalized;
    }

    private int resolveLimit(Integer limitPerCountry) {
        if (limitPerCountry == null) {
            return DEFAULT_LIMIT_PER_COUNTRY;
        }

        return Math.max(1, Math.min(limitPerCountry, 10));
    }

    private String compactLogText(String value) {
        if (value == null) {
            return "";
        }
        String compacted = value.replaceAll("\\s+", " ").trim();
        if (compacted.length() <= 120) {
            return compacted;
        }
        return compacted.substring(0, 117).trim() + "...";
    }
}
