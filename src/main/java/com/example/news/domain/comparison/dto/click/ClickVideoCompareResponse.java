package com.example.news.domain.comparison.dto.click;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClickVideoCompareResponse(
        String requestId,
        String selectedVideoId,
        Status status,
        Graph graph
) {
    public enum Status {
        READY,
        PROCESSING
    }

    public record Graph(
            JsonNode nodes,
            JsonNode edges,
            JsonNode countryPerspectives
    ) {
    }
}
