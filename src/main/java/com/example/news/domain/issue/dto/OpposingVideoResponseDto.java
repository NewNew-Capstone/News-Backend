package com.example.news.domain.issue.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class OpposingVideoResponseDto {
    private String       youtubeVideoId;
    private String       title;
    private String       channelName;
    private String       summaryText;
    private Double       opinionScore;
    private Double       overallBiasScore;
    private Double       opinionGap;
    private String       scoreEvidence;
    private String       scoreReasonSummary;
    private List<String> analysisKeywords;
}
