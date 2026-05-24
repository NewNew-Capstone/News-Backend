package com.example.news.domain.analysis.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisDtoJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void biasAnalysisResultResponse_deserializesFocusKeywords() throws Exception {
        String json = """
                {
                  "target_id": 10,
                  "focus_keywords": [
                    {
                      "keyword_text": "트럼프",
                      "score": 0.75,
                      "occurrence_count": 6,
                      "sentence_count": 4
                    }
                  ]
                }
                """;

        BiasAnalysisResultResponse response = objectMapper.readValue(json, BiasAnalysisResultResponse.class);

        assertThat(response.focusKeywords()).hasSize(1);
        FocusKeywordDto focusKeyword = response.focusKeywords().get(0);
        assertThat(focusKeyword.keywordText()).isEqualTo("트럼프");
        assertThat(focusKeyword.score()).isEqualTo(0.75);
        assertThat(focusKeyword.occurrenceCount()).isEqualTo(6);
        assertThat(focusKeyword.sentenceCount()).isEqualTo(4);
    }

    @Test
    void biasAnalysisResultResponse_defaultsFocusKeywordsToEmptyList() throws Exception {
        BiasAnalysisResultResponse missingFieldResponse = objectMapper.readValue(
                """
                        {
                          "target_id": 10
                        }
                        """,
                BiasAnalysisResultResponse.class);
        BiasAnalysisResultResponse emptyArrayResponse = objectMapper.readValue(
                """
                        {
                          "target_id": 10,
                          "focus_keywords": []
                        }
                        """,
                BiasAnalysisResultResponse.class);

        assertThat(missingFieldResponse.focusKeywords()).isEmpty();
        assertThat(emptyArrayResponse.focusKeywords()).isEmpty();
    }

    @Test
    void analysisResultResponse_serializesFocusKeywordsAsEmptyArray() throws Exception {
        AnalysisResultResponse response = new AnalysisResultResponse(
                10L,
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
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertThat(json.has("focus_keywords")).isTrue();
        assertThat(json.get("focus_keywords").isArray()).isTrue();
        assertThat(json.get("focus_keywords").size()).isZero();
    }
}
