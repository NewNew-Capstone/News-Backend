package com.example.news.domain.content.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class VideoRankDto {

    public record VideoItem(
            @JsonProperty("video_id") String videoId,
            String title,
            String description
    ) {}

    public record Request(
            String keyword,
            List<VideoItem> videos,
            @JsonProperty("top_n") int topN
    ) {}

    public record Response(
            @JsonProperty("ranked_video_ids") List<String> rankedVideoIds
    ) {}
}
