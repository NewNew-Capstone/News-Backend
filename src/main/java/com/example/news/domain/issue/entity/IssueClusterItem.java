package com.example.news.domain.issue.entity;

import com.example.news.domain.issue.enums.IssueClusterItemSourceType;
import com.example.news.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 이슈 클러스터 구성 영상 — 특정 IssueCluster에 속하는 개별 YoutubeVideo 매핑 테이블.
 * 국가별 유사도 점수(similarityScore), 순위(rankNo), 대표 영상 여부(isRepresentative)를 저장한다.
 * YoutubeVideo는 BC 간 직접 참조 금지 원칙에 따라 ID(youtubeVideoId)만 보관한다.
 */
@Entity
@Table(
        name = "issue_cluster_item",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_issue_cluster_item_cluster_video",
                        columnNames = {"issue_cluster_id", "youtube_video_id"}
                )
        },
        indexes = {
                @Index(name = "idx_issue_cluster_item_cluster_country", columnList = "issue_cluster_id,country_code")
        }
)

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IssueClusterItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ISSUE_CLUSTER_ITEM_ID")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issue_cluster_id", nullable = false)
    private IssueCluster issueCluster;

    @Column(name = "youtube_video_id", nullable = false)
    private Long youtubeVideoId;

    @Column(length = 10)
    private String countryCode;

    private Double similarityScore;

    private Boolean isRepresentative;

    private Integer rankNo;

    private Integer subClusterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IssueClusterItemSourceType sourceType;

    public void updateSubClusterId(Integer subClusterId) {
        this.subClusterId = subClusterId;
    }
}
