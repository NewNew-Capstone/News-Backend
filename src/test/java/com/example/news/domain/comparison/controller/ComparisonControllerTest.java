package com.example.news.domain.comparison.controller;

import com.example.news.domain.comparison.dto.ComparisonVideoTargetResponse;
import com.example.news.domain.comparison.dto.click.ClickVideoCompareResponse;
import com.example.news.domain.comparison.dto.collect.CollectMultilingualRequest;
import com.example.news.domain.comparison.dto.collect.CollectMultilingualResponse;
import com.example.news.domain.comparison.exception.ComparisonException;
import com.example.news.domain.comparison.exception.code.ComparisonErrorCode;
import com.example.news.domain.comparison.service.ComparisonCollectService;
import com.example.news.domain.comparison.service.ComparisonProxyService;
import com.example.news.global.config.SecurityConfig;
import com.example.news.global.jwt.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {ComparisonController.class, ClickedVideoComparisonController.class},
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class, JwtAuthenticationFilter.class}
        )
)
class ComparisonControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @MockBean
    ComparisonProxyService comparisonProxyService;

    @MockBean
    ComparisonCollectService comparisonCollectService;

    @Test
    void getComparisonHome_returnsPythonPayload() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {"issue_keywords":["election"],"sections":[]}
                """);
        when(comparisonProxyService.getComparisonHome(5)).thenReturn(payload);

        mockMvc.perform(get("/api/v1/comparison/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.issue_keywords[0]").value("election"));

        verify(comparisonProxyService).getComparisonHome(5);
    }

    @Test
    void searchVideos_delegatesKeywordAndLimit() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {"sections":[{"country_code":"KR","videos":[]}]}
                """);
        when(comparisonProxyService.searchVideos("반도체", 3)).thenReturn(payload);

        mockMvc.perform(get("/api/v1/comparison/search")
                        .param("keyword", "반도체")
                        .param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.sections[0].country_code").value("KR"));

        verify(comparisonProxyService).searchVideos("반도체", 3);
    }

    @Test
    void getComparisonGraph_returnsNodesAndEdges() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {"nodes":[{"id":"abc"}],"edges":[]}
                """);
        when(comparisonProxyService.getComparisonGraph("abc")).thenReturn(payload);

        mockMvc.perform(get("/api/v1/comparison/videos/abc/graph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.nodes[0].id").value("abc"));

        verify(comparisonProxyService).getComparisonGraph("abc");
    }

    @Test
    void getAnalysisTarget_returnsTargetBridge() throws Exception {
        when(comparisonProxyService.getAnalysisTarget("abc"))
                .thenReturn(new ComparisonVideoTargetResponse("abc", 10L, "/api/v1/analysis/10", true));

        mockMvc.perform(get("/api/v1/comparison/videos/abc/target"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.youtubeVideoId").value("abc"))
                .andExpect(jsonPath("$.body.targetId").value(10))
                .andExpect(jsonPath("$.body.analysisPath").value("/api/v1/analysis/10"))
                .andExpect(jsonPath("$.body.analysisAvailable").value(true));
    }

    @Test
    void searchVideos_returns400_whenKeywordBlank() throws Exception {
        when(comparisonProxyService.searchVideos("   ", 5))
                .thenThrow(new ComparisonException(
                        ComparisonErrorCode.INVALID_COMPARISON_REQUEST,
                        "keyword는 비어 있을 수 없습니다."
                ));

        mockMvc.perform(get("/api/v1/comparison/search")
                        .param("keyword", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status.statusCode").value("CP001"));
    }

    @Test
    void getAnalysisTarget_returns404_whenVideoMissing() throws Exception {
        when(comparisonProxyService.getAnalysisTarget("missing"))
                .thenThrow(new ComparisonException(
                        ComparisonErrorCode.COMPARISON_VIDEO_NOT_FOUND,
                        "youtubeVideoId에 해당하는 영상을 찾을 수 없습니다: missing"
                ));

        mockMvc.perform(get("/api/v1/comparison/videos/missing/target"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status.statusCode").value("CP003"));
    }

    @Test
    void collectMultilingual_returnsCollectResult() throws Exception {
        CollectMultilingualResponse response = CollectMultilingualResponse.builder()
                .status("partial")
                .requestedKeyword("트럼프 대만")
                .expandedKeywords(CollectMultilingualResponse.ExpandedKeywords.builder()
                        .ko(java.util.List.of("트럼프 대만"))
                        .en(java.util.List.of("trump taiwan"))
                        .zh(java.util.List.of("特朗普 台湾"))
                        .build())
                .collectedCounts(java.util.Map.of("KR", 1, "US", 1, "CN", 0))
                .ingestedVideoIds(java.util.List.of("vid1"))
                .errors(java.util.List.of())
                .build();
        when(comparisonCollectService.collectMultilingual(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/comparison/collect-multilingual")
                        .contentType("application/json")
                        .content("""
                                {"keyword_ko":"트럼프 대만","max_per_language":10}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.status").value("partial"))
                .andExpect(jsonPath("$.body.requested_keyword").value("트럼프 대만"))
                .andExpect(jsonPath("$.body.collected_counts.KR").value(1))
                .andExpect(jsonPath("$.body.ingested_video_ids[0]").value("vid1"));
    }

    @Test
    void collectMultilingual_returns400_whenKeywordBlank() throws Exception {
        mockMvc.perform(post("/api/v1/comparison/collect-multilingual")
                        .contentType("application/json")
                        .content("""
                                {"keyword_ko":" ","max_per_language":10}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status.statusCode").value("C007"));
    }

    @Test
    void compareOnClick_returnsReadyGraph() throws Exception {
        ClickVideoCompareResponse response = new ClickVideoCompareResponse(
                "rt-1",
                "q0Jo5F8pHbs",
                ClickVideoCompareResponse.Status.READY,
                new ClickVideoCompareResponse.Graph(
                        objectMapper.readTree("[{\"id\":\"us1\"}]"),
                        objectMapper.readTree("[]"),
                        objectMapper.readTree("[{\"country_code\":\"US\"}]")
                )
        );
        when(comparisonProxyService.compareOnClick(any())).thenReturn(response);

        mockMvc.perform(post("/api/videos/compare-on-click")
                        .contentType("application/json")
                        .content("""
                                {
                                  "keyword":"트럼프 대만",
                                  "video":{
                                    "videoId":"q0Jo5F8pHbs",
                                    "title":"[지식뉴스] 시진핑의 대만 야욕",
                                    "description":"트럼프 대만 중국 관련 뉴스",
                                    "countryCode":"KR",
                                    "language":"ko",
                                    "channelId":"sbs-channel",
                                    "channelName":"교양이를 부탁해",
                                    "publishedAt":"2026-05-21T13:39:03Z",
                                    "thumbnailUrl":"https://i.ytimg.com/vi/q0Jo5F8pHbs/hqdefault.jpg",
                                    "viewCount":45121
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.requestId").value("rt-1"))
                .andExpect(jsonPath("$.body.selectedVideoId").value("q0Jo5F8pHbs"))
                .andExpect(jsonPath("$.body.status").value("READY"))
                .andExpect(jsonPath("$.body.graph.nodes[0].id").value("us1"))
                .andExpect(jsonPath("$.body.graph.countryPerspectives[0].country_code").value("US"));

        verify(comparisonProxyService).compareOnClick(any());
    }

    @Test
    void compareOnClick_returns400_whenRequestInvalid() throws Exception {
        mockMvc.perform(post("/api/videos/compare-on-click")
                        .contentType("application/json")
                        .content("""
                                {"keyword":" ","video":{"videoId":"q0Jo5F8pHbs"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status.statusCode").value("C007"));
    }

    @Test
    void compareOnClick_returns502_whenPythonCallFails() throws Exception {
        when(comparisonProxyService.compareOnClick(any()))
                .thenThrow(new ComparisonException(
                        ComparisonErrorCode.COMPARISON_API_FAILED,
                        "Python clicked-video API 호출 실패: 503 SERVICE_UNAVAILABLE"
                ));

        mockMvc.perform(post("/api/videos/compare-on-click")
                        .contentType("application/json")
                        .content("""
                                {
                                  "keyword":"트럼프 대만",
                                  "video":{"videoId":"q0Jo5F8pHbs"}
                                }
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status.statusCode").value("CP002"))
                .andExpect(jsonPath("$.status.description").value("Python clicked-video API 호출 실패: 503 SERVICE_UNAVAILABLE"));
    }

    @Test
    void getCompareJob_returnsPythonPayload() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {"request_id":"rt-1","status":"running"}
                """);
        when(comparisonProxyService.getCompareJob("rt-1")).thenReturn(payload);

        mockMvc.perform(get("/api/videos/compare-jobs/rt-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.request_id").value("rt-1"))
                .andExpect(jsonPath("$.body.status").value("running"));

        verify(comparisonProxyService).getCompareJob("rt-1");
    }
}
