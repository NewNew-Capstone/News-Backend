package com.example.news.domain.comparison.dto.click;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ClickVideoCompareRequest(
        @NotBlank(message = "keyword는 필수입니다.")
        String keyword,

        @Valid
        @NotNull(message = "video는 필수입니다.")
        Video video
) {
    public record Video(
            @NotBlank(message = "videoId는 필수입니다.")
            String videoId,
            String title,
            String description,
            String countryCode,
            String language,
            String channelId,
            String channelName,
            String publishedAt,
            String thumbnailUrl,
            Long viewCount
    ) {
    }
}
