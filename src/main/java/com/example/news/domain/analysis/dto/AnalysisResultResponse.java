package com.example.news.domain.analysis.dto;

import com.example.news.domain.analysis.enums.BiasKeywordType;
import com.example.news.domain.analysis.enums.EvidenceType;
import com.example.news.domain.analysis.enums.SentenceLabelType;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AnalysisResultResponse(
        Long targetId,
        Double overallBiasScore,
        Double opinionScore,
        Double emotionScore,
        Double headlineBodyGapScore,
        Double headlineBodyGapStd,
        Double headlineBodyGapLead,
        Double headlineBodyGapTail,
        String headlineBodyGapLabel,
        String scoreReasonSummary,
        String summaryText,
        Double factRatio,
        String scoreEvidence,
        List<KeywordItem> keywords,
        List<KeywordItem> emotionKeywords,
        List<SentenceLabelItem> sentenceLabels,
        List<EvidenceItem> evidences,
        List<FocusKeywordDto> focusKeywords,
        List<HighlightSpanItem> highlightSpans
) {
    public AnalysisResultResponse {
        focusKeywords = focusKeywords == null ? List.of() : focusKeywords;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record KeywordItem(
            String keywordText,
            BiasKeywordType keywordType,
            Double score
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SentenceLabelItem(
            Long contentSentenceId,
            SentenceLabelType labelType,
            Double score
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EvidenceItem(
            Long contentSentenceId,
            EvidenceType evidenceType,
            String title,
            String description,
            String sourceText,
            Double confidenceScore
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record HighlightSpanItem(
            Long contentSentenceId,
            Integer startOffset,
            Integer endOffset,
            SentenceLabelType labelType,
            Double score,
            String matchedWord
    ) {}
}
