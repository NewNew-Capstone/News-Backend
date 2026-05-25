package com.example.news.domain.comparison.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

public record CompareOnClickRequest(
        @NotBlank(message = "youtubeVideoId는 필수입니다.")
        String youtubeVideoId,
        String originalUrl,
        String title,
        String description,
        String thumbnailUrl,
        String channelId,
        String channelName,
        LocalDateTime publishedAt,
        String countryCode,
        String defaultLanguageCode,
        Integer durationSeconds,
        Boolean isEmbeddable,
        Long viewCount,
        Long likeCount,
        Long commentCount
) {
}
