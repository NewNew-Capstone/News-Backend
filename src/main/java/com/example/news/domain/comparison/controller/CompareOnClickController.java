package com.example.news.domain.comparison.controller;

import com.example.news.domain.comparison.dto.CompareOnClickRequest;
import com.example.news.domain.comparison.dto.CompareOnClickResponse;
import com.example.news.domain.comparison.service.CompareOnClickService;
import com.example.news.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class CompareOnClickController {

    private final CompareOnClickService compareOnClickService;

    @Value("${python.kg.base-url:${python.base-url:http://127.0.0.1:8000}}")
    private String pythonBaseUrl;

    @PostMapping("/api/v1/comparison/compare-on-click")
    public ApiResponse<CompareOnClickResponse> compareOnClick(
            @Valid @RequestBody CompareOnClickRequest request,
            @RequestParam(required = false, defaultValue = "3") Integer limitPerCountry
    ) {
        return ApiResponse.ok(compareOnClickService.compareOnClick(request, limitPerCountry));
    }

    @GetMapping("/api/v1/comparison/country-recommendations")
    public ApiResponse<CompareOnClickResponse> countryRecommendations(
            @RequestParam String keyword,
            @RequestParam(required = false, defaultValue = "3") Integer limitPerCountry
    ) {
        return ApiResponse.ok(compareOnClickService.recommendByKeyword(keyword, limitPerCountry));
    }

    @PostMapping("/api/v1/comparison/llm-difference")
    public ApiResponse<Map<String, Object>> llmDifference(@RequestBody Map<String, Object> request) {
        Map<String, Object> pythonResponse = requestPythonLlmDifference(request);
        if (pythonResponse != null && !pythonResponse.isEmpty()) {
            return ApiResponse.ok(pythonResponse);
        }

        Map<String, Object> selectedVideo = readMap(request.get("selectedVideo"));
        Map<String, Object> comparedVideo = readMap(request.get("comparedVideo"));
        Map<String, Object> relationEdge = readMap(request.get("relationEdge"));
        String selectedTitle = readText(selectedVideo.get("title"), "원본 영상");
        String comparedTitle = readText(comparedVideo.get("title"), "비교 영상");
        String selectedCountry = readText(selectedVideo.get("countryLocalLabel"), readText(selectedVideo.get("countryCode"), "원본 국가"));
        String comparedCountry = readText(comparedVideo.get("countryLocalLabel"), readText(comparedVideo.get("countryCode"), "비교 국가"));
        List<?> keywords = readList(relationEdge.get("keywords"));
        List<?> reasons = readList(relationEdge.get("reasons"));
        String keywordText = keywords.isEmpty()
                ? "공유 이슈"
                : String.join(", ", keywords.stream().map(String::valueOf).limit(4).toList());
        String reasonText = reasons.isEmpty()
                ? "두 영상은 같은 이슈를 서로 다른 국가 관점에서 설명합니다."
                : String.valueOf(reasons.get(0));
        String summary = selectedCountry + " 영상은 \"" + selectedTitle + "\"의 이슈 맥락을 중심으로 다루고, "
                + comparedCountry + " 영상은 \"" + comparedTitle + "\"에서 같은 주제를 다른 국가 관점으로 설명합니다. "
                + "핵심 차이는 강조점과 책임/위험을 바라보는 프레임입니다.";

        return ApiResponse.ok(Map.of(
                "summary", summary,
                "points", List.of(
                        selectedCountry + " 영상의 초점: " + selectedTitle,
                        comparedCountry + " 영상의 초점: " + comparedTitle,
                        "공유 키워드: " + keywordText
                ),
                "recommendationReason", reasonText,
                "llmUsed", false
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requestPythonLlmDifference(Map<String, Object> request) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            Object response = restTemplate.postForObject(
                    pythonBaseUrl + "/kg/llm-difference",
                    request,
                    Map.class
            );
            return response instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (RestClientException e) {
            log.warn("[ComparisonLLM] Python LLM difference call failed. pythonBaseUrl={}, reason={}",
                    pythonBaseUrl, e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private List<?> readList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private String readText(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }

        return String.valueOf(value);
    }
}
