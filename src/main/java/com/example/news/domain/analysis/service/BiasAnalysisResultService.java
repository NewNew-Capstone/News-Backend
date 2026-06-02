package com.example.news.domain.analysis.service;

import com.example.news.domain.analysis.converter.AnalysisResultConverter;
import com.example.news.domain.analysis.dto.AnalysisResultResponse;
import com.example.news.domain.analysis.entity.BiasAnalysisResult;
import com.example.news.domain.analysis.entity.HighlightResult;
import com.example.news.domain.analysis.enums.TargetType;
import com.example.news.domain.analysis.exception.AnalysisException;
import com.example.news.domain.analysis.exception.code.AnalysisErrorCode;
import com.example.news.domain.analysis.repository.BiasAnalysisFocusKeywordRepository;
import com.example.news.domain.analysis.repository.BiasAnalysisKeywordRepository;
import com.example.news.domain.analysis.repository.BiasAnalysisResultRepository;
import com.example.news.domain.analysis.repository.BiasEvidenceRepository;
import com.example.news.domain.analysis.repository.HighlightResultRepository;
import com.example.news.domain.analysis.repository.HighlightSpanRepository;
import com.example.news.domain.analysis.repository.SentenceBiasLabelRepository;
import com.example.news.domain.content.repository.YoutubeTranscriptRepository;
import com.example.news.domain.content.repository.YoutubeVideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BiasAnalysisResultService {

    private final BiasAnalysisResultRepository biasAnalysisResultRepository;
    private final BiasAnalysisKeywordRepository biasAnalysisKeywordRepository;
    private final BiasAnalysisFocusKeywordRepository biasAnalysisFocusKeywordRepository;
    private final BiasEvidenceRepository biasEvidenceRepository;
    private final SentenceBiasLabelRepository sentenceBiasLabelRepository;
    private final HighlightResultRepository highlightResultRepository;
    private final HighlightSpanRepository highlightSpanRepository;
    private final YoutubeVideoRepository youtubeVideoRepository;
    private final YoutubeTranscriptRepository youtubeTranscriptRepository;
    private final AnalysisService analysisService;

    @Transactional(noRollbackFor = AnalysisException.class)
    public AnalysisResultResponse getAnalysisResult(Long targetId) {
        BiasAnalysisResult result = biasAnalysisResultRepository
                .findTopByTargetIdAndTargetTypeOrderByCreatedAtDesc(targetId, TargetType.YOUTUBE_VIDEO)
                .orElseThrow(() -> new AnalysisException(AnalysisErrorCode.ANALYSIS_RESULT_NOT_FOUND));

        enrichDisplayTextIfNeeded(result, targetId);

        var keywords = biasAnalysisKeywordRepository.findAllByBiasAnalysisResultId(result.getId())
                .stream()
                .map(AnalysisResultConverter::toKeywordItem)
                .toList();

        var focusKeywords = biasAnalysisFocusKeywordRepository.findAllByBiasAnalysisResultId(result.getId())
                .stream()
                .map(AnalysisResultConverter::toFocusKeywordItem)
                .toList();

        var evidences = biasEvidenceRepository.findAllByBiasAnalysisResultId(result.getId())
                .stream()
                .map(AnalysisResultConverter::toEvidenceItem)
                .toList();

        // SentenceBiasLabel은 AnalysisJob을 통해 조회
        // analysisJob이 null인 경우 빈 리스트 반환
        var sentenceLabels = Optional.ofNullable(result.getAnalysisJob())
                .map(job -> sentenceBiasLabelRepository.findAllByAnalysisJobId(job.getId())
                        .stream()
                        .map(AnalysisResultConverter::toSentenceLabelItem)
                        .toList())
                .orElse(List.of());

        // HighlightResult가 아직 저장되지 않은 경우 빈 리스트 반환
        var highlightSpans = highlightResultRepository.findByBiasAnalysisResultId(result.getId())
                .map(HighlightResult::getId)
                .map(highlightSpanRepository::findByHighlightResultId)
                .orElse(List.of())
                .stream()
                .map(AnalysisResultConverter::toHighlightSpanItem)
                .toList();

        return AnalysisResultConverter.toResponse(result, keywords, focusKeywords, sentenceLabels, evidences, highlightSpans);
    }

    private void enrichDisplayTextIfNeeded(BiasAnalysisResult result, Long targetId) {
        youtubeVideoRepository.findById(targetId)
                .flatMap(youtubeTranscriptRepository::findTopByYoutubeVideoOrderByCreatedAtDesc)
                .ifPresentOrElse(
                        transcript -> analysisService.enrichDisplayTextIfNeeded(result, transcript),
                        () -> analysisService.enrichScoreReasonSummaryIfNeeded(result, "ko")
                );
    }
}
