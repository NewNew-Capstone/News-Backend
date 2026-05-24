package com.example.news.domain.comparison.controller;

import com.example.news.domain.comparison.dto.click.ClickVideoCompareRequest;
import com.example.news.domain.comparison.dto.click.ClickVideoCompareResponse;
import com.example.news.domain.comparison.service.ComparisonProxyService;
import com.example.news.global.response.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
public class ClickedVideoComparisonController {

    private final ComparisonProxyService comparisonProxyService;

    @PostMapping("/compare-on-click")
    public ApiResponse<ClickVideoCompareResponse> compareOnClick(
            @Valid @RequestBody ClickVideoCompareRequest request
    ) {
        return ApiResponse.ok(comparisonProxyService.compareOnClick(request));
    }

    @GetMapping("/compare-jobs/{requestId}")
    public ApiResponse<JsonNode> getCompareJob(@PathVariable String requestId) {
        return ApiResponse.ok(comparisonProxyService.getCompareJob(requestId));
    }
}
