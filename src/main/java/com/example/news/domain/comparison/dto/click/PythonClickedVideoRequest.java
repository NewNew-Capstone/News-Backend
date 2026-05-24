package com.example.news.domain.comparison.dto.click;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record PythonClickedVideoRequest(
        String keyword,

        @JsonProperty("max_per_country")
        int maxPerCountry,

        @JsonProperty("selected_video")
        SelectedVideo selectedVideo,

        @JsonProperty("related_candidates")
        List<Object> relatedCandidates
) {
    public static PythonClickedVideoRequest from(ClickVideoCompareRequest request, String keyword) {
        ClickVideoCompareRequest.Video video = request.video();
        return new PythonClickedVideoRequest(
                keyword,
                3,
                new SelectedVideo(
                        video.videoId(),
                        video.title(),
                        video.description(),
                        video.countryCode(),
                        video.language(),
                        video.channelId(),
                        video.channelName(),
                        video.publishedAt(),
                        video.thumbnailUrl(),
                        video.viewCount()
                ),
                List.of()
        );
    }

    public record SelectedVideo(
            @JsonProperty("video_id")
            String videoId,
            String title,
            String description,
            @JsonProperty("country_code")
            String countryCode,
            String language,
            @JsonProperty("channel_id")
            String channelId,
            @JsonProperty("channel_name")
            String channelName,
            @JsonProperty("published_at")
            String publishedAt,
            @JsonProperty("thumbnail_url")
            String thumbnailUrl,
            @JsonProperty("view_count")
            Long viewCount
    ) {
    }
}
