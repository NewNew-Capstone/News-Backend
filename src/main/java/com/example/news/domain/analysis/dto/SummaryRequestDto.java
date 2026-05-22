package com.example.news.domain.analysis.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.lang.Nullable;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SummaryRequestDto(
        @Nullable Long targetId,
        String title,
        String language,
        String rawText
) {}
