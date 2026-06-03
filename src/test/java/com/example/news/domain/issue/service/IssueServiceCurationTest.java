package com.example.news.domain.issue.service;

import com.example.news.domain.analysis.entity.BiasAnalysisResult;
import com.example.news.domain.analysis.enums.TargetType;
import com.example.news.domain.analysis.repository.BiasAnalysisKeywordRepository;
import com.example.news.domain.analysis.repository.BiasAnalysisResultRepository;
import com.example.news.domain.analysis.service.AnalysisService;
import com.example.news.domain.content.entity.YoutubeVideo;
import com.example.news.domain.content.repository.YoutubeVideoKeywordRepository;
import com.example.news.domain.content.repository.YoutubeVideoRepository;
import com.example.news.domain.content.service.YoutubeSearchService;
import com.example.news.domain.content.service.YoutubeTranscriptService;
import com.example.news.domain.graph.service.IssueGraphSyncService;
import com.example.news.domain.graph.service.VideoGraphSyncService;
import com.example.news.domain.issue.dto.CurationDto;
import com.example.news.domain.issue.dto.OpposingVideoResponseDto;
import com.example.news.domain.issue.entity.IssueCluster;
import com.example.news.domain.issue.entity.IssueClusterItem;
import com.example.news.domain.issue.enums.ClusterStatus;
import com.example.news.domain.issue.enums.IssueClusterItemSourceType;
import com.example.news.domain.issue.enums.IssueClusterType;
import com.example.news.domain.issue.repository.ComparisonCountryItemRepository;
import com.example.news.domain.issue.repository.ComparisonResultRepository;
import com.example.news.domain.issue.repository.IssueClusterItemRepository;
import com.example.news.domain.issue.repository.IssueClusterRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IssueServiceCurationTest {

    @Mock KeywordTranslationService keywordTranslationService;
    @Mock YoutubeSearchService youtubeSearchService;
    @Mock YoutubeVideoRepository youtubeVideoRepository;
    @Mock YoutubeVideoKeywordRepository youtubeVideoKeywordRepository;
    @Mock IssueClusterRepository issueClusterRepository;
    @Mock IssueClusterItemRepository issueClusterItemRepository;
    @Mock ComparisonResultRepository comparisonResultRepository;
    @Mock ComparisonCountryItemRepository comparisonCountryItemRepository;
    @Mock BiasAnalysisResultRepository biasAnalysisResultRepository;
    @Mock BiasAnalysisKeywordRepository biasAnalysisKeywordRepository;
    @Mock IssueGraphSyncService issueGraphSyncService;
    @Mock VideoGraphSyncService videoGraphSyncService;
    @Mock AnalysisService analysisService;
    @Mock YoutubeTranscriptService youtubeTranscriptService;

    @InjectMocks IssueService issueService;

    @Test
    void createCurationSet_createsDraftCluster() {
        IssueCluster saved = IssueCluster.builder()
                .id(1L)
                .searchKeyword("테스트")
                .periodStartDate(LocalDate.of(2026, 5, 1))
                .periodEndDate(LocalDate.of(2026, 5, 10))
                .status(ClusterStatus.DRAFT)
                .clusterType(IssueClusterType.CURATION_MANUAL)
                .build();
        when(issueClusterRepository.save(any())).thenReturn(saved);

        CurationDto.CurationSetResponse result = issueService.createCurationSet(
                new CurationDto.CreateSetRequest("테스트", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10))
        );

        assertThat(result.issueClusterId()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo(ClusterStatus.DRAFT);
    }

    @Test
    void getCurationStatus_returnsRemainingByCountry() {
        Long clusterId = 1L;
        IssueCluster cluster = IssueCluster.builder()
                .id(clusterId)
                .status(ClusterStatus.ANALYZING)
                .clusterType(IssueClusterType.CURATION_MANUAL)
                .build();
        when(issueClusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));

        List<IssueClusterItem> items = List.of(
                IssueClusterItem.builder().id(1L).issueCluster(cluster).youtubeVideoId(11L).countryCode("KR").sourceType(IssueClusterItemSourceType.MANUAL).build(),
                IssueClusterItem.builder().id(2L).issueCluster(cluster).youtubeVideoId(12L).countryCode("US").sourceType(IssueClusterItemSourceType.MANUAL).build(),
                IssueClusterItem.builder().id(3L).issueCluster(cluster).youtubeVideoId(13L).countryCode("CN").sourceType(IssueClusterItemSourceType.MANUAL).build()
        );
        when(issueClusterItemRepository.findByIssueClusterId(clusterId)).thenReturn(items);

        BiasAnalysisResult kr = BiasAnalysisResult.builder().targetId(11L).targetType(TargetType.YOUTUBE_VIDEO).build();
        when(biasAnalysisResultRepository.findByTargetTypeAndTargetIdIn(TargetType.YOUTUBE_VIDEO, List.of(11L, 12L, 13L)))
                .thenReturn(List.of(kr));

        CurationDto.CurationStatusResponse result = issueService.getCurationStatus(clusterId);

        assertThat(result.readyForReport()).isFalse();
        assertThat(result.analyzedByCountry()).isEqualTo(Map.of("KR", 1, "US", 0, "CN", 0));
        assertThat(result.remainingByCountry()).isEqualTo(Map.of("KR", 9, "US", 10, "CN", 10));
    }

    @Test
    void lockCurationSet_triggersAnalysisForAllItems() {
        Long clusterId = 1L;
        IssueCluster cluster = IssueCluster.builder()
                .id(clusterId)
                .status(ClusterStatus.DRAFT)
                .clusterType(IssueClusterType.CURATION_MANUAL)
                .build();
        when(issueClusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));

        List<IssueClusterItem> items = List.of(
                IssueClusterItem.builder().issueCluster(cluster).youtubeVideoId(21L).countryCode("KR").sourceType(IssueClusterItemSourceType.MANUAL).build(),
                IssueClusterItem.builder().issueCluster(cluster).youtubeVideoId(22L).countryCode("US").sourceType(IssueClusterItemSourceType.MANUAL).build()
        );
        when(issueClusterItemRepository.findByIssueClusterId(clusterId)).thenReturn(items);
        when(youtubeVideoRepository.findAllById(List.of(21L, 22L))).thenReturn(List.of(
                YoutubeVideo.builder().id(21L).youtubeVideoId("video-21").build(),
                YoutubeVideo.builder().id(22L).youtubeVideoId("video-22").build()
        ));
        when(biasAnalysisResultRepository.findByTargetTypeAndTargetIdIn(TargetType.YOUTUBE_VIDEO, List.of(21L, 22L)))
                .thenReturn(List.of());

        issueService.lockCurationSet(clusterId);

        verify(issueClusterRepository).updateStatus(clusterId, ClusterStatus.LOCKED);
        verify(issueClusterRepository).updateStatus(clusterId, ClusterStatus.ANALYZING);
        verify(videoGraphSyncService).syncVideoNow(
                org.mockito.ArgumentMatchers.argThat(v -> Long.valueOf(21L).equals(v.getId())), eq("KR"));
        verify(videoGraphSyncService).syncVideoNow(
                org.mockito.ArgumentMatchers.argThat(v -> Long.valueOf(22L).equals(v.getId())), eq("US"));
        verify(issueGraphSyncService).syncIssue(eq(cluster), eq(items), any());
        verify(analysisService).triggerAnalysisAsync(21L);
        verify(analysisService).triggerAnalysisAsync(22L);
    }

    @Test
    void findOpposingVideoByRequestVideoId_acceptsYoutubeVideoIdString() {
        BiasAnalysisResult source = BiasAnalysisResult.builder()
                .targetId(28L)
                .targetType(TargetType.YOUTUBE_VIDEO)
                .overallBiasScore(0.2)
                .build();
        BiasAnalysisResult opposing = BiasAnalysisResult.builder()
                .id(2L)
                .targetId(29L)
                .targetType(TargetType.YOUTUBE_VIDEO)
                .overallBiasScore(0.8)
                .opinionScore(0.7)
                .build();
        YoutubeVideo sourceVideo = YoutubeVideo.builder()
                .id(28L)
                .youtubeVideoId("bG27BhaO3S4")
                .build();
        YoutubeVideo opposingVideo = YoutubeVideo.builder()
                .id(29L)
                .youtubeVideoId("opposing-video")
                .title("반대 관점")
                .build();

        when(youtubeVideoRepository.findByYoutubeVideoId("bG27BhaO3S4")).thenReturn(Optional.of(sourceVideo));
        when(biasAnalysisResultRepository.findTopByTargetIdAndTargetTypeOrderByCreatedAtDesc(28L, TargetType.YOUTUBE_VIDEO))
                .thenReturn(Optional.of(source));
        when(issueClusterItemRepository.findByYoutubeVideoId(28L)).thenReturn(List.of());
        when(youtubeVideoKeywordRepository.findVideoIdsSharingKeywordWith(28L)).thenReturn(List.of(29L));
        when(biasAnalysisResultRepository.findByTargetTypeAndTargetIdIn(TargetType.YOUTUBE_VIDEO, List.of(29L)))
                .thenReturn(List.of(opposing));
        when(youtubeVideoRepository.findById(29L)).thenReturn(Optional.of(opposingVideo));
        when(biasAnalysisKeywordRepository.findAllByBiasAnalysisResultId(2L)).thenReturn(List.of());

        OpposingVideoResponseDto result = issueService.findOpposingVideoByRequestVideoId("bG27BhaO3S4");

        assertThat(result.getYoutubeVideoId()).isEqualTo("opposing-video");
        verify(youtubeVideoRepository).findByYoutubeVideoId("bG27BhaO3S4");
        verify(biasAnalysisResultRepository).findTopByTargetIdAndTargetTypeOrderByCreatedAtDesc(28L, TargetType.YOUTUBE_VIDEO);
    }
}
