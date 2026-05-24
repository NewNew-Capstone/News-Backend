package com.example.news.domain.comparison.controller;

import com.example.news.domain.comparison.dto.CompareOnClickRequest;
import com.example.news.domain.comparison.dto.CompareOnClickResponse;
import com.example.news.domain.comparison.service.CompareOnClickService;
import com.example.news.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CompareOnClickController {

    private final CompareOnClickService compareOnClickService;

    @PostMapping({"/api/videos/compare-on-click", "/api/v1/comparison/compare-on-click"})
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
}
