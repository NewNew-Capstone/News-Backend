package com.example.news.domain.analysis.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BiasAnalysisResultResponse(
        Long targetId,
        String targetType,
        Long transcriptId,

        Double overallBiasScore,
        Double opinionScore,
        Double emotionScore,
        @Nullable Double headlineBodyGapScore,
        @Nullable Double headlineBodyGapStd,
        @Nullable Double headlineBodyGapLead,
        @Nullable Double headlineBodyGapTail,
        @Nullable String headlineBodyGapLabel,
        @Nullable String scoreReasonSummary,

        String summaryText,

        Double factRatio,
        String scoreEvidence,
        Map<String, Double> biasTypeScores,

        @Nullable List<KeywordResultDto> keywords,
        @Nullable List<KeywordResultDto> emotionKeywords,
        @Nullable List<SentenceLabelResultDto> sentenceLabels,
        @Nullable List<EvidenceResultDto> evidences,
        @Nullable List<SentenceResultResponse> sentences,
        List<FocusKeywordDto> focusKeywords
) {
    public BiasAnalysisResultResponse {
        keywords = keywords == null ? List.of() : keywords;
        emotionKeywords = emotionKeywords == null
                ? keywords.stream()
                        .filter(k -> k.keywordType() != null && k.keywordType().equalsIgnoreCase("EMOTION"))
                        .toList()
                : emotionKeywords;
        focusKeywords = focusKeywords == null ? List.of() : focusKeywords;
    }
}
