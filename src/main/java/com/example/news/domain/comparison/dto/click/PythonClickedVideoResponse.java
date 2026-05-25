package com.example.news.domain.comparison.dto.click;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record PythonClickedVideoResponse(
        @JsonProperty("request_id")
        String requestId,

        @JsonProperty("selected_video_id")
        String selectedVideoId,

        @JsonProperty("queued_count")
        Integer queuedCount,

        @JsonProperty("skipped_existing_count")
        Integer skippedExistingCount,

        @JsonProperty("current_graph")
        JsonNode currentGraph
) {
}
