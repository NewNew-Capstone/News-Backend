package com.example.news.domain.issue.controller;

import com.example.news.domain.issue.dto.*;
import com.example.news.domain.issue.service.IssueService;
import com.example.news.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/issues")
@RequiredArgsConstructor
public class IssueController {

    private final IssueService issueService;

    // 국가별 이슈 영상 검색
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<IssueSearchResponseDto>> search(
            @RequestParam String searchKeyword, // 파라미터로 한국어 키워드
            @RequestParam String countries, // 파라미터로 나라 (쉼표로 구분함) (ex, KR, JP, US)
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "false") boolean autoCluster) { // 기본은 검색 전용 모드
        return ResponseEntity.ok(ApiResponse.ok(issueService.search(searchKeyword, countries, days, autoCluster)));
    }

    // 국가별 대표 영상 비교 결과 조회 (저장된 비교 결과 조회)
    // 예: /api/v1/issues/comparison?issueClusterId=1
    @GetMapping("/comparison")
    public ResponseEntity<ApiResponse<IssueComparisonResponseDto>> comparison(
            @RequestParam Long issueClusterId) {
        return ResponseEntity.ok(ApiResponse.ok(issueService.comparison(issueClusterId)));
    }

    // 반대 관점 영상 조회
    // 예: /api/v1/issues/opposing?videoId=1 또는 /api/v1/issues/opposing?videoId={youtubeVideoId}
    @GetMapping("/opposing")
    public ResponseEntity<ApiResponse<OpposingVideoResponseDto>> opposing(
            @RequestParam String videoId) {
        return ResponseEntity.ok(ApiResponse.ok(issueService.findOpposingVideoByRequestVideoId(videoId)));
    }

    @PostMapping("/curation/sets")
    public ResponseEntity<ApiResponse<CurationDto.CurationSetResponse>> createCurationSet(
            @Valid @RequestBody CurationDto.CreateSetRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(issueService.createCurationSet(request)));
    }

    @PostMapping("/curation/sets/{issueClusterId}/items")
    public ResponseEntity<ApiResponse<CurationDto.CurationItemResponse>> addCurationItem(
            @PathVariable Long issueClusterId,
            @Valid @RequestBody CurationDto.AddItemRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(issueService.addCurationItem(issueClusterId, request)));
    }

    @DeleteMapping("/curation/sets/{issueClusterId}/items/{issueClusterItemId}")
    public ResponseEntity<ApiResponse<String>> removeCurationItem(
            @PathVariable Long issueClusterId,
            @PathVariable Long issueClusterItemId) {
        issueService.removeCurationItem(issueClusterId, issueClusterItemId);
        return ResponseEntity.ok(ApiResponse.ok("deleted"));
    }

    @PostMapping("/curation/sets/{issueClusterId}/lock")
    public ResponseEntity<ApiResponse<CurationDto.CurationStatusResponse>> lockCurationSet(
            @PathVariable Long issueClusterId) {
        return ResponseEntity.ok(ApiResponse.ok(issueService.lockCurationSet(issueClusterId)));
    }

    @GetMapping("/curation/sets/{issueClusterId}/status")
    public ResponseEntity<ApiResponse<CurationDto.CurationStatusResponse>> curationStatus(
            @PathVariable Long issueClusterId) {
        return ResponseEntity.ok(ApiResponse.ok(issueService.getCurationStatus(issueClusterId)));
    }

    @GetMapping("/comparison/report")
    public ResponseEntity<ApiResponse<IssueComparisonReportResponseDto>> comparisonReport(
            @RequestParam Long issueClusterId) {
        return ResponseEntity.ok(ApiResponse.ok(issueService.comparisonReport(issueClusterId)));
    }

}
