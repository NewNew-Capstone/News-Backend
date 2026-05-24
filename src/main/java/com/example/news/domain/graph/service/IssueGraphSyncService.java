package com.example.news.domain.graph.service;

import com.example.news.domain.content.entity.YoutubeVideo;
import com.example.news.domain.graph.node.IssueNode;
import com.example.news.domain.graph.repository.IssueNodeRepository;
import com.example.news.domain.graph.repository.VideoNodeRepository;
import com.example.news.domain.issue.entity.IssueCluster;
import com.example.news.domain.issue.entity.IssueClusterItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IssueGraphSyncService {

    private final IssueNodeRepository issueNodeRepository;
    private final VideoNodeRepository videoNodeRepository;

    // videoMap: IssueService.search()에서 이미 조회된 Map<PostgreSQL PK, YoutubeVideo> — 중복 쿼리 없음
    @Async
    public void syncIssue(IssueCluster cluster,
                          List<IssueClusterItem> clusterItems,
                          Map<Long, YoutubeVideo> videoMap) {
        syncIssueNow(cluster, clusterItems, videoMap);
    }

    public void syncIssueNow(IssueCluster cluster,
                             List<IssueClusterItem> clusterItems,
                             Map<Long, YoutubeVideo> videoMap) {
        try {
            // Issue 노드 upsert — keyword, name 둘 다 저장 (Python이 둘 다 참조)
            IssueNode issueNode = issueNodeRepository.findNodeOnlyByIssueId(cluster.getId())
                    .orElseGet(() -> IssueNode.builder()
                            .clusterId(cluster.getId())
                            .build());
            issueNode.setSearchKeyword(cluster.getSearchKeyword());
            issueNode.setName(cluster.getSearchKeyword());
            if (cluster.getClusterType() != null) {
                issueNode.setClusterType(cluster.getClusterType().name());
            }
            issueNode.setPeriodStartDate(cluster.getPeriodStartDate());
            issueNode.setPeriodEndDate(cluster.getPeriodEndDate());
            issueNodeRepository.save(issueNode);

            // 각 영상 VideoNode에 PART_OF 관계 연결 (addIssue 내부에서 중복 방지)
            for (IssueClusterItem item : clusterItems) {
                YoutubeVideo video = videoMap.get(item.getYoutubeVideoId());
                if (video == null) continue;

                videoNodeRepository.findNodeOnlyByVideoId(video.getYoutubeVideoId()).ifPresent(videoNode -> {
                    videoNode.addIssue(issueNode);
                    videoNodeRepository.save(videoNode);
                });
            }

            Set<String> countryDistribution = clusterItems.stream()
                    .map(IssueClusterItem::getCountryCode)
                    .filter(code -> code != null && !code.isBlank())
                    .collect(Collectors.toSet());
            if (countryDistribution.size() < 2) {
                logPipelineFailure(
                        "issue",
                        null,
                        null,
                        cluster.getId(),
                        "single-country issue distribution: " + countryDistribution,
                        false
                );
            }

        } catch (Exception e) {
            logPipelineFailure("issue", null, null, cluster.getId(), e.getMessage(), true);
            log.warn("[GraphSync] Issue 동기화 예외", e);
        }
    }

    private void logPipelineFailure(String pipeline,
                                    String videoId,
                                    Long targetId,
                                    Long issueId,
                                    String reason,
                                    boolean hasStackTrace) {
        log.warn(
                "pipeline={} video_id={} target_id={} issue_id={} reason=\"{}\" stacktrace={} event_time={}",
                pipeline,
                videoId,
                targetId,
                issueId,
                reason,
                hasStackTrace,
                OffsetDateTime.now()
        );
    }
}
