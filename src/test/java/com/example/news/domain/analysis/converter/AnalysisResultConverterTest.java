package com.example.news.domain.analysis.converter;

import com.example.news.domain.analysis.dto.AnalysisResultResponse;
import com.example.news.domain.analysis.entity.BiasAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisResultConverterTest {

    @Test
    void toResponse_hidesSummaryFallbackText() {
        BiasAnalysisResult result = BiasAnalysisResult.builder()
                .targetId(10L)
                .summaryText("[LLM 안 됨] 영상 요약 생성에 실패했습니다.")
                .build();

        AnalysisResultResponse response = AnalysisResultConverter.toResponse(
                result,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        assertThat(response.summaryText()).isEmpty();
    }

    @Test
    void toResponse_keepsRealSummaryText() {
        BiasAnalysisResult result = BiasAnalysisResult.builder()
                .targetId(10L)
                .summaryText("트럼프 대통령의 관세 인상 발언과 한국 정부의 대응을 요약한다.")
                .build();

        AnalysisResultResponse response = AnalysisResultConverter.toResponse(
                result,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        assertThat(response.summaryText()).isEqualTo("트럼프 대통령의 관세 인상 발언과 한국 정부의 대응을 요약한다.");
    }
}
