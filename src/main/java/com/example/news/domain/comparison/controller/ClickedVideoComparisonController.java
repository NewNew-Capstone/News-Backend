package com.example.news.domain.comparison.controller;

import com.example.news.domain.comparison.dto.click.ClickVideoCompareRequest;
import com.example.news.domain.comparison.dto.click.ClickVideoCompareResponse;
import com.example.news.domain.comparison.exception.ComparisonException;
import com.example.news.domain.comparison.exception.code.ComparisonErrorCode;
import com.example.news.domain.comparison.service.ComparisonProxyService;
import com.example.news.global.response.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
public class ClickedVideoComparisonController {

    private final ComparisonProxyService comparisonProxyService;
    private final ObjectMapper objectMapper;

    @PostMapping("/compare-on-click")
    public ApiResponse<ClickVideoCompareResponse> compareOnClick(
            @Valid @RequestBody JsonNode request
    ) {
        ClickVideoCompareRequest normalizedRequest = toClickVideoCompareRequest(request);
        validate(normalizedRequest);
        return ApiResponse.ok(comparisonProxyService.compareOnClick(normalizedRequest));
    }

    @GetMapping("/compare-jobs/{requestId}")
    public ApiResponse<JsonNode> getCompareJob(@PathVariable String requestId) {
        return ApiResponse.ok(comparisonProxyService.getCompareJob(requestId));
    }

    private ClickVideoCompareRequest toClickVideoCompareRequest(JsonNode request) {
        if (request != null && request.hasNonNull("keyword") && request.hasNonNull("video")) {
            return objectMapper.convertValue(request, ClickVideoCompareRequest.class);
        }

        String keyword = firstText(request, "keyword", "searchKeyword", "search_keyword", "title");
        ClickVideoCompareRequest.Video video = new ClickVideoCompareRequest.Video(
                firstText(request, "videoId", "video_id", "youtubeVideoId", "youtube_video_id", "id"),
                firstText(request, "title"),
                firstText(request, "description"),
                firstText(request, "countryCode", "country_code"),
                firstText(request, "language", "defaultLanguageCode", "default_language_code"),
                firstText(request, "channelId", "channel_id"),
                firstText(request, "channelName", "channel_name"),
                firstText(request, "publishedAt", "published_at"),
                firstText(request, "thumbnailUrl", "thumbnail_url"),
                firstLong(request, "viewCount", "view_count")
        );
        return new ClickVideoCompareRequest(keyword, video);
    }

    private String firstText(JsonNode node, String... fieldNames) {
        if (node == null || node.isNull()) {
            return "";
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value == null || value.isNull()) {
                continue;
            }
            String text = value.asText("");
            if (!text.isBlank()) {
                return text.trim();
            }
        }
        return "";
    }

    private Long firstLong(JsonNode node, String... fieldNames) {
        if (node == null || node.isNull()) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                return value.asLong();
            }
            String text = value.asText("");
            if (text.isBlank()) {
                continue;
            }
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void validate(ClickVideoCompareRequest request) {
        if (request == null || request.keyword() == null || request.keyword().isBlank()) {
            throw new ComparisonException(
                    ComparisonErrorCode.INVALID_COMPARISON_REQUEST,
                    "keyword는 비어 있을 수 없습니다."
            );
        }
        if (request.video() == null || request.video().videoId() == null || request.video().videoId().isBlank()) {
            throw new ComparisonException(
                    ComparisonErrorCode.INVALID_COMPARISON_REQUEST,
                    "video.videoId는 비어 있을 수 없습니다."
            );
        }
    }
}
