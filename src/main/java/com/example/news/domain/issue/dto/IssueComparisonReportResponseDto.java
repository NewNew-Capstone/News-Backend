package com.example.news.domain.issue.dto;

import com.example.news.domain.issue.enums.ClusterStatus;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@Builder
public class IssueComparisonReportResponseDto {
    private Long issueClusterId;
    private ClusterStatus status;
    private boolean ready;
    private List<String> missingCountries;
    private List<CountryMetrics> countries;

    @Getter
    @Builder
    public static class CountryMetrics {
        private String countryCode;
        private int videoCount;
        private long totalViewCount;
        private double avgViewCount;
        private double avgOverallBiasScore;
        private double avgOpinionScore;
        private double avgFactRatio;
        private Map<String, Integer> toneDistribution;
        private double channelTop1Share;
        private List<String> topKeywords;
    }
}
