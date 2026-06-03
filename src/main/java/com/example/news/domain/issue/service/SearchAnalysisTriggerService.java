package com.example.news.domain.issue.service;

import com.example.news.domain.analysis.service.AnalysisService;
import com.example.news.domain.content.entity.YoutubeVideo;
import com.example.news.domain.content.repository.YoutubeVideoRepository;
import com.example.news.domain.content.service.YoutubeSearchService;
import com.example.news.domain.issue.entity.IssueCluster;
import com.example.news.domain.issue.entity.IssueClusterItem;
import com.example.news.domain.issue.enums.ClusterStatus;
import com.example.news.domain.issue.enums.IssueClusterItemSourceType;
import com.example.news.domain.issue.enums.IssueClusterType;
import com.example.news.domain.issue.repository.IssueClusterItemRepository;
import com.example.news.domain.issue.repository.IssueClusterRepository;
import com.example.news.global.event.VideoSearchedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchAnalysisTriggerService {

    private final IssueClusterRepository issueClusterRepository;
    private final IssueClusterItemRepository issueClusterItemRepository;
    private final YoutubeVideoRepository youtubeVideoRepository;
    private final AnalysisService analysisService;
    private final YoutubeSearchService youtubeSearchService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleVideoSearched(VideoSearchedEvent event) {
        LocalDate today = LocalDate.now();

        IssueCluster cluster = issueClusterRepository.save(
                IssueCluster.builder()
                        .searchKeyword(event.keyword())
                        .normalizedKeyword(event.keyword().trim().toLowerCase())
                        .periodStartDate(today)
                        .periodEndDate(today)
                        .status(ClusterStatus.PENDING)
                        .clusterType(IssueClusterType.SEARCH_AUTO)
                        .build()
        );

        List<YoutubeVideo> videos = youtubeVideoRepository.findAllById(event.videoDbIds());

        // IssueClusterItem 저장
        List<IssueClusterItem> savedItems = new ArrayList<>();
        for (YoutubeVideo video : videos) {
            String countryCode = video.getCountryCode() != null ? video.getCountryCode() : "KR";
            IssueClusterItem item = issueClusterItemRepository.save(
                    IssueClusterItem.builder()
                            .issueCluster(cluster)
                            .youtubeVideoId(video.getId())
                            .countryCode(countryCode)
                            .isRepresentative(false)
                            .sourceType(IssueClusterItemSourceType.AUTO)
                            .build()
            );
            savedItems.add(item);
            analysisService.triggerAnalysisAsync(video.getId());
        }

        // K-means 클러스터링 → subClusterId 저장
        Map<String, Integer> clusterMap = youtubeSearchService.clusterVideos(videos);
        if (!clusterMap.isEmpty()) {
            Map<Long, String> dbIdToYoutubeId = videos.stream()
                    .collect(Collectors.toMap(YoutubeVideo::getId, YoutubeVideo::getYoutubeVideoId));

            // 혼자 남는 서브클러스터 영상 → 가장 영상 수 적은 클러스터로 재배치
            Map<Integer, Long> clusterSizeMap = clusterMap.values().stream()
                    .collect(Collectors.groupingBy(c -> c, Collectors.counting()));
            Map<Integer, Long> multiVideoClusterSizeMap = clusterSizeMap.entrySet().stream()
                    .filter(e -> e.getValue() > 1)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            Map<String, Integer> mergedClusterMap = new java.util.HashMap<>(clusterMap);
            if (!multiVideoClusterSizeMap.isEmpty()) {
                Integer smallestClusterId = multiVideoClusterSizeMap.entrySet().stream()
                        .min(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse(null);
                if (smallestClusterId != null) {
                    clusterMap.forEach((videoId, clusterId) -> {
                        if (clusterSizeMap.getOrDefault(clusterId, 0L) == 1) {
                            mergedClusterMap.put(videoId, smallestClusterId);
                            log.info("singleton 서브클러스터 재배치: videoId={}, {} → {}", videoId, clusterId, smallestClusterId);
                        }
                    });
                }
            }

            for (IssueClusterItem item : savedItems) {
                String youtubeVideoId = dbIdToYoutubeId.get(item.getYoutubeVideoId());
                Integer subClusterId = mergedClusterMap.get(youtubeVideoId);
                if (subClusterId != null) {
                    item.updateSubClusterId(subClusterId);
                }
            }
        }

        log.info("일반 검색 백그라운드 처리 완료 (keyword={}, videos={}, clusters={})",
                event.keyword(), videos.size(), clusterMap.size());
    }
}
