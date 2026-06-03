package com.example.news.domain.analysis.dto;

import com.example.news.domain.analysis.entity.AnalysisJob;
import com.example.news.domain.analysis.enums.JobStatus;
import com.example.news.domain.analysis.util.SummaryTextSanitizer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisJobResponse(
        Long jobId,
        Long targetId,
        String targetType,
        Long transcriptId,
        JobStatus status,
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
        Map<String, Double> biasTypeScores,
        List<KeywordResultDto> keywords,
        List<KeywordResultDto> emotionKeywords,
        List<SentenceLabelResultDto> sentenceLabels,
        List<EvidenceResultDto> evidences,
        List<FocusKeywordDto> focusKeywords
) {
    public AnalysisJobResponse(
            Long jobId,
            Long targetId,
            String targetType,
            Long transcriptId,
            JobStatus status
    ) {
        this(
                jobId,
                targetId,
                targetType,
                transcriptId,
                status,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public static AnalysisJobResponse from(
            AnalysisJob job,
            Long transcriptId,
            BiasAnalysisResultResponse analysisResult
    ) {
        if (analysisResult == null) {
            return new AnalysisJobResponse(
                    job.getId(),
                    job.getTargetId(),
                    job.getTargetType().name(),
                    transcriptId,
                    job.getStatus()
            );
        }

        return new AnalysisJobResponse(
                job.getId(),
                job.getTargetId(),
                job.getTargetType().name(),
                transcriptId,
                job.getStatus(),
                analysisResult.overallBiasScore(),
                analysisResult.opinionScore(),
                analysisResult.emotionScore(),
                analysisResult.headlineBodyGapScore(),
                analysisResult.headlineBodyGapStd(),
                analysisResult.headlineBodyGapLead(),
                analysisResult.headlineBodyGapTail(),
                analysisResult.headlineBodyGapLabel(),
                analysisResult.scoreReasonSummary(),
                SummaryTextSanitizer.clean(analysisResult.summaryText()),
                analysisResult.factRatio(),
                analysisResult.scoreEvidence(),
                analysisResult.biasTypeScores(),
                analysisResult.keywords(),
                analysisResult.emotionKeywords(),
                analysisResult.sentenceLabels(),
                analysisResult.evidences(),
                analysisResult.focusKeywords()
        );
    }
}
