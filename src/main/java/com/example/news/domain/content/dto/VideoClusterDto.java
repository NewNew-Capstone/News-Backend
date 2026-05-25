package com.example.news.domain.content.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class VideoClusterDto {

    public record VideoItem(
            @JsonProperty("video_id") String videoId,
            String title,
            String description
    ) {}

    public record Request(
            List<VideoItem> videos,
            @JsonProperty("n_clusters") Integer nClusters
    ) {}

    public record ClusterResult(
            @JsonProperty("cluster_id") int clusterId,
            @JsonProperty("video_ids") List<String> videoIds
    ) {}

    public record Response(
            List<ClusterResult> clusters
    ) {}
}
