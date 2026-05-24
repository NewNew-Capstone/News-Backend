package com.example.news.domain.analysis.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.lang.Nullable;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScoreReasonRequestDto(
        @Nullable Long targetId,
        String language,
        Double overallBiasScore,
        Double opinionScore,
        Double emotionScore,
        Double factRatio,
        @Nullable Double headlineBodyGapScore,
        String scoreEvidence
) {}
