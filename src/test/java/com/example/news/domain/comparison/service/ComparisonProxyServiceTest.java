package com.example.news.domain.comparison.service;

import com.example.news.domain.analysis.entity.BiasAnalysisResult;
import com.example.news.domain.analysis.enums.TargetType;
import com.example.news.domain.analysis.repository.BiasAnalysisResultRepository;
import com.example.news.domain.comparison.dto.ComparisonVideoTargetResponse;
import com.example.news.domain.comparison.dto.click.ClickVideoCompareRequest;
import com.example.news.domain.comparison.dto.click.ClickVideoCompareResponse;
import com.example.news.domain.comparison.dto.click.PythonClickedVideoRequest;
import com.example.news.domain.comparison.dto.collect.MultilingualKeywordExpandResponse;
import com.example.news.domain.comparison.exception.ComparisonException;
import com.example.news.domain.comparison.exception.code.ComparisonErrorCode;
import com.example.news.domain.content.entity.YoutubeVideo;
import com.example.news.domain.content.repository.YoutubeVideoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComparisonProxyServiceTest {

    @Mock
    YoutubeVideoRepository youtubeVideoRepository;

    @Mock
    BiasAnalysisResultRepository biasAnalysisResultRepository;

    ComparisonProxyService comparisonProxyService;

    AtomicReference<ClientRequest> capturedRequest;

    @BeforeEach
    void setUp() {
        capturedRequest = new AtomicReference<>();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(okJsonExchange("""
                        {"issue_keywords":["economy"],"sections":[]}
                        """))
                .build();
        comparisonProxyService = new ComparisonProxyService(
                webClient,
                youtubeVideoRepository,
                biasAnalysisResultRepository
        );
        ReflectionTestUtils.setField(comparisonProxyService, "pythonBaseUrl", "http://localhost:8000");
    }

    @Test
    void getComparisonHome_callsPythonHomeEndpoint() {
        JsonNode result = comparisonProxyService.getComparisonHome(5);

        assertThat(result.get("issue_keywords").get(0).asText()).isEqualTo("economy");
        assertThat(capturedRequest.get().url().toString())
                .isEqualTo("http://localhost:8000/kg/comparison-home?limit=5");
    }

    @Test
    void searchVideos_trimsKeywordAndCallsPythonSearchEndpoint() {
        comparisonProxyService.searchVideos("  경제  ", 5);

        assertThat(capturedRequest.get().url().toString())
                .isEqualTo("http://localhost:8000/kg/search-videos?keyword=%EA%B2%BD%EC%A0%9C&limit=5");
    }

    @Test
    void getComparisonGraph_callsPythonGraphEndpoint() {
        comparisonProxyService.getComparisonGraph("abc123");

        assertThat(capturedRequest.get().url().toString())
                .isEqualTo("http://localhost:8000/kg/videos/abc123/comparison-graph");
    }

    @Test
    void pythonClickedVideoRequest_serializesExpectedWireShape() throws Exception {
        PythonClickedVideoRequest pythonRequest = PythonClickedVideoRequest.from(clickRequest(), "트럼프 대만");

        JsonNode json = new ObjectMapper().valueToTree(pythonRequest);

        assertThat(json.get("keyword").asText()).isEqualTo("트럼프 대만");
        assertThat(json.get("max_per_country").asInt()).isEqualTo(3);
        assertThat(json.get("selected_video").get("video_id").asText()).isEqualTo("q0Jo5F8pHbs");
        assertThat(json.get("selected_video").get("country_code").asText()).isEqualTo("KR");
        assertThat(json.get("selected_video").get("published_at").asText()).isEqualTo("2026-05-21T13:39:03Z");
        assertThat(json.get("related_candidates").isArray()).isTrue();
        assertThat(json.get("related_candidates").size()).isZero();
    }

    @Test
    void compareOnClick_postsClickedVideoAndReturnsReadyGraph() {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(okJsonExchange("""
                        {
                          "request_id":"rt-1",
                          "selected_video_id":"q0Jo5F8pHbs",
                          "queued_count":0,
                          "skipped_existing_count":1,
                          "current_graph":{
                            "nodes":[{"id":"us1"}],
                            "edges":[],
                            "country_perspectives":[{"country_code":"US"}]
                          }
                        }
                        """))
                .build();
        ComparisonProxyService service = serviceWith(webClient);

        ClickVideoCompareResponse result = service.compareOnClick(clickRequest());

        assertThat(capturedRequest.get().method().name()).isEqualTo("POST");
        assertThat(capturedRequest.get().url().toString())
                .isEqualTo("http://localhost:8000/kg/realtime-ingest/clicked-video");
        assertThat(result.requestId()).isEqualTo("rt-1");
        assertThat(result.selectedVideoId()).isEqualTo("q0Jo5F8pHbs");
        assertThat(result.status()).isEqualTo(ClickVideoCompareResponse.Status.READY);
        assertThat(result.graph().nodes().get(0).get("id").asText()).isEqualTo("us1");
        assertThat(result.graph().countryPerspectives().get(0).get("country_code").asText()).isEqualTo("US");
    }

    @Test
    void compareOnClick_returnsProcessing_whenCurrentGraphIsNull() {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(okJsonExchange("""
                        {
                          "request_id":"rt-2",
                          "selected_video_id":"q0Jo5F8pHbs",
                          "current_graph":null
                        }
                        """))
                .build();
        ComparisonProxyService service = serviceWith(webClient);

        ClickVideoCompareResponse result = service.compareOnClick(clickRequest());

        assertThat(result.requestId()).isEqualTo("rt-2");
        assertThat(result.selectedVideoId()).isEqualTo("q0Jo5F8pHbs");
        assertThat(result.status()).isEqualTo(ClickVideoCompareResponse.Status.PROCESSING);
        assertThat(result.graph()).isNull();
    }

    @Test
    void compareOnClick_throwsApiFailure_whenPythonReturnsUnavailable() {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                        .header("Content-Type", "application/json")
                        .body("{\"detail\":\"Neo4j unavailable\"}")
                        .build()))
                .build();
        ComparisonProxyService service = serviceWith(webClient);

        assertThatThrownBy(() -> service.compareOnClick(clickRequest()))
                .isInstanceOfSatisfying(ComparisonException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ComparisonErrorCode.COMPARISON_API_FAILED));
    }

    @Test
    void compareOnClick_throwsApiFailure_whenPythonReturnsNotFound() {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND)
                        .header("Content-Type", "application/json")
                        .body("{\"detail\":\"endpoint not found\"}")
                        .build()))
                .build();
        ComparisonProxyService service = serviceWith(webClient);

        assertThatThrownBy(() -> service.compareOnClick(clickRequest()))
                .isInstanceOfSatisfying(ComparisonException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ComparisonErrorCode.COMPARISON_API_FAILED));
    }

    @Test
    void compareOnClick_throwsApiFailure_whenPythonConnectionFails() {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new WebClientException("connection refused") {
                }))
                .build();
        ComparisonProxyService service = serviceWith(webClient);

        assertThatThrownBy(() -> service.compareOnClick(clickRequest()))
                .isInstanceOfSatisfying(ComparisonException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ComparisonErrorCode.COMPARISON_API_FAILED));
    }

    @Test
    void getCompareJob_callsPythonJobEndpointAndPreservesPayload() {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(okJsonExchange("""
                        {"request_id":"rt-1","status":"running"}
                        """))
                .build();
        ComparisonProxyService service = serviceWith(webClient);

        JsonNode result = service.getCompareJob("rt-1");

        assertThat(capturedRequest.get().url().toString())
                .isEqualTo("http://localhost:8000/kg/realtime-ingest/jobs/rt-1");
        assertThat(result.get("request_id").asText()).isEqualTo("rt-1");
        assertThat(result.get("status").asText()).isEqualTo("running");
    }

    @Test
    void searchVideos_throws400ErrorCode_whenKeywordBlank() {
        assertThatThrownBy(() -> comparisonProxyService.searchVideos(" ", 5))
                .isInstanceOfSatisfying(ComparisonException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ComparisonErrorCode.INVALID_COMPARISON_REQUEST));
    }

    @Test
    void getComparisonHome_throws400ErrorCode_whenLimitOutOfRange() {
        assertThatThrownBy(() -> comparisonProxyService.getComparisonHome(51))
                .isInstanceOfSatisfying(ComparisonException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ComparisonErrorCode.INVALID_COMPARISON_REQUEST));
    }

    @Test
    void getComparisonHome_throwsApiFailure_whenPythonReturnsError() {
        WebClient failingWebClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build()))
                .build();
        ComparisonProxyService service = new ComparisonProxyService(
                failingWebClient,
                youtubeVideoRepository,
                biasAnalysisResultRepository
        );
        ReflectionTestUtils.setField(service, "pythonBaseUrl", "http://localhost:8000");

        assertThatThrownBy(() -> service.getComparisonHome(5))
                .isInstanceOfSatisfying(ComparisonException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ComparisonErrorCode.COMPARISON_API_FAILED));
    }

    @Test
    void getAnalysisTarget_returnsInternalTargetIdAndAvailability() {
        YoutubeVideo video = YoutubeVideo.builder()
                .id(10L)
                .youtubeVideoId("abc123")
                .title("title")
                .build();
        when(youtubeVideoRepository.findByYoutubeVideoId("abc123")).thenReturn(Optional.of(video));
        when(biasAnalysisResultRepository.findTopByTargetIdAndTargetTypeOrderByCreatedAtDesc(10L, TargetType.YOUTUBE_VIDEO))
                .thenReturn(Optional.of(BiasAnalysisResult.builder().build()));

        ComparisonVideoTargetResponse result = comparisonProxyService.getAnalysisTarget("abc123");

        assertThat(result.youtubeVideoId()).isEqualTo("abc123");
        assertThat(result.targetId()).isEqualTo(10L);
        assertThat(result.analysisPath()).isEqualTo("/api/v1/analysis/10");
        assertThat(result.analysisAvailable()).isTrue();
        verify(youtubeVideoRepository).findByYoutubeVideoId("abc123");
    }

    @Test
    void getAnalysisTarget_throws404ErrorCode_whenVideoMissing() {
        when(youtubeVideoRepository.findByYoutubeVideoId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> comparisonProxyService.getAnalysisTarget("missing"))
                .isInstanceOfSatisfying(ComparisonException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ComparisonErrorCode.COMPARISON_VIDEO_NOT_FOUND));
    }

    @Test
    void expandMultilingualKeywords_parsesNestedExpandedKeywords() {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(okJsonExchange("""
                        {
                          "requested_keyword":"트럼프 대만",
                          "expanded_keywords":{
                            "ko":["트럼프 대만"],
                            "en":["trump taiwan"],
                            "zh":["特朗普台湾"]
                          }
                        }
                        """))
                .build();
        ComparisonProxyService service = new ComparisonProxyService(
                webClient,
                youtubeVideoRepository,
                biasAnalysisResultRepository
        );
        ReflectionTestUtils.setField(service, "pythonBaseUrl", "http://localhost:8000");

        MultilingualKeywordExpandResponse result = service.expandMultilingualKeywords("트럼프 대만");

        assertThat(result.expandedKeywords().ko()).containsExactly("트럼프 대만");
        assertThat(result.expandedKeywords().en()).containsExactly("trump taiwan");
        assertThat(result.expandedKeywords().zh()).containsExactly("特朗普台湾");
    }

    private ExchangeFunction okJsonExchange(String body) {
        return request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .build());
        };
    }

    private ComparisonProxyService serviceWith(WebClient webClient) {
        ComparisonProxyService service = new ComparisonProxyService(
                webClient,
                youtubeVideoRepository,
                biasAnalysisResultRepository
        );
        ReflectionTestUtils.setField(service, "pythonBaseUrl", "http://localhost:8000");
        return service;
    }

    private ClickVideoCompareRequest clickRequest() {
        return new ClickVideoCompareRequest(
                " 트럼프 대만 ",
                new ClickVideoCompareRequest.Video(
                        "q0Jo5F8pHbs",
                        "[지식뉴스] 시진핑의 대만 야욕",
                        "트럼프 대만 중국 관련 뉴스",
                        "KR",
                        "ko",
                        "sbs-channel",
                        "교양이를 부탁해",
                        "2026-05-21T13:39:03Z",
                        "https://i.ytimg.com/vi/q0Jo5F8pHbs/hqdefault.jpg",
                        45121L
                )
        );
    }
}
