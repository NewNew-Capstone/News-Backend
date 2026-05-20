package com.example.news.domain.analysis.controller;

import com.example.news.domain.analysis.dto.AnalysisJobResponse;
import com.example.news.domain.analysis.dto.AnalysisResultResponse;
import com.example.news.domain.analysis.entity.AnalysisJob;
import com.example.news.domain.analysis.service.AnalysisService;
import com.example.news.domain.analysis.service.BiasAnalysisResultService;
import com.example.news.domain.content.entity.YoutubeTranscript;
import com.example.news.domain.content.enums.YoutubeErrorCode;
import com.example.news.domain.content.service.YoutubeTranscriptService;
import com.example.news.global.exception.CustomException;
import com.example.news.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final BiasAnalysisResultService biasAnalysisResultService;
    private final AnalysisService analysisService;
    private final YoutubeTranscriptService youtubeTranscriptService;

    @PostMapping("/analyze/{youtubeVideoId}")
    public ApiResponse<AnalysisJobResponse> analyze(
            @PathVariable String youtubeVideoId) {
        YoutubeTranscript transcript = youtubeTranscriptService.getOrFetchTranscriptEntity(youtubeVideoId, true);
        if (transcript == null || transcript.getTranscriptText() == null) {
            throw new CustomException(YoutubeErrorCode.TRANSCRIPT_NOT_AVAILABLE);
        }
        AnalysisJob job = analysisService.getOrCreateAnalysisJob(transcript);
        return ApiResponse.ok(new AnalysisJobResponse(
                job.getId(),
                job.getTargetId(),
                job.getTargetType().name(),
                transcript.getId(),
                job.getStatus()
        ));
    }

    @GetMapping("/{targetId}")
    public ApiResponse<AnalysisResultResponse> getAnalysisResult(
            @PathVariable Long targetId) {
        AnalysisResultResponse result = biasAnalysisResultService.getAnalysisResult(targetId);
        return ApiResponse.ok(result);
    }
}
