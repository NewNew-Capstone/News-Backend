package com.example.news.domain.analysis.service;

import com.example.news.domain.analysis.dto.AnalysisResultResponse;
import com.example.news.domain.analysis.entity.AnalysisJob;
import com.example.news.domain.analysis.entity.BiasAnalysisFocusKeyword;
import com.example.news.domain.analysis.entity.BiasAnalysisResult;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BiasAnalysisResultServiceTest {

    @Mock
    BiasAnalysisResultRepository biasAnalysisResultRepository;

    @Mock
    BiasAnalysisKeywordRepository biasAnalysisKeywordRepository;

    @Mock
    BiasAnalysisFocusKeywordRepository biasAnalysisFocusKeywordRepository;

    @Mock
    BiasEvidenceRepository biasEvidenceRepository;

    @Mock
    SentenceBiasLabelRepository sentenceBiasLabelRepository;

    @Mock
    HighlightResultRepository highlightResultRepository;

    @Mock
    HighlightSpanRepository highlightSpanRepository;

    @InjectMocks
    BiasAnalysisResultService service;

    @Test
    void getAnalysisResult_returnsResponse_whenFound() {
        // given
        AnalysisJob mockJob = mock(AnalysisJob.class);
        when(mockJob.getId()).thenReturn(10L);

        BiasAnalysisResult mockResult = mock(BiasAnalysisResult.class);
        when(mockResult.getId()).thenReturn(1L);
        when(mockResult.getTargetId()).thenReturn(1L);
        when(mockResult.getAnalysisJob()).thenReturn(mockJob);

        when(biasAnalysisResultRepository.findTopByTargetIdAndTargetTypeOrderByCreatedAtDesc(1L, TargetType.YOUTUBE_VIDEO))
                .thenReturn(Optional.of(mockResult));
        when(biasAnalysisKeywordRepository.findAllByBiasAnalysisResultId(1L)).thenReturn(List.of());
        when(biasAnalysisFocusKeywordRepository.findAllByBiasAnalysisResultId(1L)).thenReturn(List.of(
                BiasAnalysisFocusKeyword.builder()
                        .keywordText("장경태")
                        .score(0.82)
                        .occurrenceCount(14)
                        .sentenceCount(11)
                        .build()
        ));
        when(biasEvidenceRepository.findAllByBiasAnalysisResultId(1L)).thenReturn(List.of());
        when(sentenceBiasLabelRepository.findAllByAnalysisJobId(10L)).thenReturn(List.of());
        when(highlightResultRepository.findByBiasAnalysisResultId(1L)).thenReturn(Optional.empty());

        // when
        AnalysisResultResponse response = service.getAnalysisResult(1L);

        // then
        assertThat(response).isNotNull();
        assertThat(response.targetId()).isEqualTo(1L);
        assertThat(response.keywords()).isEmpty();
        assertThat(response.focusKeywords()).hasSize(1);
        assertThat(response.focusKeywords().get(0).keywordText()).isEqualTo("장경태");
        assertThat(response.focusKeywords().get(0).occurrenceCount()).isEqualTo(14);
        assertThat(response.focusKeywords().get(0).sentenceCount()).isEqualTo(11);
        assertThat(response.sentenceLabels()).isEmpty();
        assertThat(response.evidences()).isEmpty();
        assertThat(response.highlightSpans()).isEmpty();
    }

    @Test
    void getAnalysisResult_throwsAnalysisException_whenNotFound() {
        // given
        when(biasAnalysisResultRepository.findTopByTargetIdAndTargetTypeOrderByCreatedAtDesc(99L, TargetType.YOUTUBE_VIDEO))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.getAnalysisResult(99L))
                .isInstanceOf(AnalysisException.class)
                .satisfies(ex -> assertThat(((AnalysisException) ex).getErrorCode())
                        .isEqualTo(AnalysisErrorCode.ANALYSIS_RESULT_NOT_FOUND));
    }
}
