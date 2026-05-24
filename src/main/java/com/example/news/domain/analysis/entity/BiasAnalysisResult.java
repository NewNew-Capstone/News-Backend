package com.example.news.domain.analysis.entity;

import com.example.news.domain.analysis.enums.TargetType;
import com.example.news.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "bias_analysis_result")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiasAnalysisResult extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "BIAS_ANALYSIS_RESULT_ID")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ANALYSIS_JOB_ID")
    private AnalysisJob analysisJob;

    private Long targetId;

    @Enumerated(EnumType.STRING)
    private TargetType targetType;

    private Double overallBiasScore;

    private Double opinionScore;

    private Double emotionScore;

    private Double headlineBodyGapScore;

    private Double headlineBodyGapStd;

    private Double headlineBodyGapLead;

    private Double headlineBodyGapTail;

    private String headlineBodyGapLabel;

    @Column(columnDefinition = "TEXT")
    private String scoreReasonSummary;

    @Column(columnDefinition = "TEXT")
    private String summaryText;

    private Double factRatio;

    @Column(columnDefinition = "TEXT")
    private String scoreEvidence;

    public void updateScoreReasonSummary(String scoreReasonSummary) {
        this.scoreReasonSummary = scoreReasonSummary;
    }

    public void updateSummaryText(String summaryText) {
        this.summaryText = summaryText;
    }

}
