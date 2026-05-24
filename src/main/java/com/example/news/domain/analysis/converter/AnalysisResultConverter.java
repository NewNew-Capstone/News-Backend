package com.example.news.domain.analysis.converter;

import com.example.news.domain.analysis.dto.AnalysisResultResponse;
import com.example.news.domain.analysis.dto.FocusKeywordDto;
import com.example.news.domain.analysis.entity.BiasAnalysisFocusKeyword;
import com.example.news.domain.analysis.entity.BiasAnalysisKeyword;
import com.example.news.domain.analysis.entity.BiasAnalysisResult;
import com.example.news.domain.analysis.entity.BiasEvidence;
import com.example.news.domain.analysis.entity.HighlightSpan;
import com.example.news.domain.analysis.entity.SentenceBiasLabel;
import com.example.news.domain.analysis.enums.BiasKeywordType;

import java.util.List;

public class AnalysisResultConverter {

    private static Double clampScore(Double score) {
        if (score == null) return null;
        return Math.max(0.0, Math.min(1.0, score));
    }

    public static AnalysisResultResponse.KeywordItem toKeywordItem(BiasAnalysisKeyword keyword) {
        return new AnalysisResultResponse.KeywordItem(
                keyword.getKeywordText(),
                keyword.getKeywordType(),
                clampScore(keyword.getScore())
        );
    }

    public static FocusKeywordDto toFocusKeywordItem(BiasAnalysisFocusKeyword keyword) {
        return new FocusKeywordDto(
                keyword.getKeywordText(),
                keyword.getScore() == null ? 0.0 : clampScore(keyword.getScore()),
                keyword.getOccurrenceCount() == null ? 0 : keyword.getOccurrenceCount(),
                keyword.getSentenceCount() == null ? 0 : keyword.getSentenceCount()
        );
    }

    public static AnalysisResultResponse.EvidenceItem toEvidenceItem(BiasEvidence evidence) {
        Long contentSentenceId = evidence.getContentSentence() != null
                ? evidence.getContentSentence().getId()
                : null;
        return new AnalysisResultResponse.EvidenceItem(
                contentSentenceId,
                evidence.getEvidenceType(),
                evidence.getTitle(),
                evidence.getDescription(),
                evidence.getSourceText(),
                clampScore(evidence.getConfidenceScore())
        );
    }

    public static AnalysisResultResponse.SentenceLabelItem toSentenceLabelItem(SentenceBiasLabel label) {
        return new AnalysisResultResponse.SentenceLabelItem(
                label.getContentSentence().getId(),
                label.getLabelType(),
                clampScore(label.getScore())
        );
    }

    public static AnalysisResultResponse.HighlightSpanItem toHighlightSpanItem(HighlightSpan span) {
        return new AnalysisResultResponse.HighlightSpanItem(
                span.getContentSentence().getId(),
                span.getStartOffset(),
                span.getEndOffset(),
                span.getLabelType(),
                clampScore(span.getScore()),
                span.getMatchedWord()
        );
    }

    public static AnalysisResultResponse toResponse(
            BiasAnalysisResult result,
            List<AnalysisResultResponse.KeywordItem> keywords,
            List<FocusKeywordDto> focusKeywords,
            List<AnalysisResultResponse.SentenceLabelItem> sentenceLabels,
            List<AnalysisResultResponse.EvidenceItem> evidences,
            List<AnalysisResultResponse.HighlightSpanItem> highlightSpans
    ) {
        List<AnalysisResultResponse.KeywordItem> emotionKeywords = keywords.stream()
                .filter(k -> k.keywordType() == BiasKeywordType.EMOTION)
                .toList();

        return new AnalysisResultResponse(
                result.getTargetId(),
                result.getOverallBiasScore(),
                result.getOpinionScore(),
                result.getEmotionScore(),
                result.getHeadlineBodyGapScore(),
                result.getHeadlineBodyGapStd(),
                result.getHeadlineBodyGapLead(),
                result.getHeadlineBodyGapTail(),
                result.getHeadlineBodyGapLabel(),
                result.getScoreReasonSummary(),
                result.getSummaryText(),
                result.getFactRatio(),
                result.getScoreEvidence(),
                keywords,
                emotionKeywords,
                sentenceLabels,
                evidences,
                focusKeywords,
                highlightSpans
        );
    }
}
