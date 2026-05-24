package com.example.news.domain.graph.service;

import com.example.news.domain.content.entity.YoutubeVideo;
import com.example.news.domain.graph.node.IssueNode;
import com.example.news.domain.graph.node.VideoNode;
import com.example.news.domain.graph.repository.IssueNodeRepository;
import com.example.news.domain.graph.repository.VideoNodeRepository;
import com.example.news.domain.issue.entity.IssueCluster;
import com.example.news.domain.issue.entity.IssueClusterItem;
import com.example.news.domain.issue.enums.IssueClusterType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IssueGraphSyncServiceTest {

    @Mock
    IssueNodeRepository issueNodeRepository;

    @Mock
    VideoNodeRepository videoNodeRepository;

    @InjectMocks
    IssueGraphSyncService issueGraphSyncService;

    @Test
    void syncIssue_savesClusterTypeForCurationIssue() {
        IssueCluster cluster = IssueCluster.builder()
                .id(4L)
                .searchKeyword("트럼프 대만")
                .clusterType(IssueClusterType.CURATION_MANUAL)
                .build();
        IssueClusterItem item = IssueClusterItem.builder()
                .youtubeVideoId(10L)
                .countryCode("KR")
                .build();
        YoutubeVideo video = YoutubeVideo.builder()
                .id(10L)
                .youtubeVideoId("video-10")
                .build();
        VideoNode videoNode = VideoNode.builder()
                .youtubeVideoId("video-10")
                .build();

        when(issueNodeRepository.findNodeOnlyByIssueId(4L)).thenReturn(Optional.empty());
        when(videoNodeRepository.findNodeOnlyByVideoId("video-10")).thenReturn(Optional.of(videoNode));

        issueGraphSyncService.syncIssueNow(cluster, List.of(item), Map.of(10L, video));

        ArgumentCaptor<IssueNode> issueCaptor = ArgumentCaptor.forClass(IssueNode.class);
        verify(issueNodeRepository).save(issueCaptor.capture());

        IssueNode savedIssue = issueCaptor.getValue();
        assertThat(savedIssue.getClusterType()).isEqualTo("CURATION_MANUAL");
        assertThat(videoNode.getIssues()).extracting(IssueNode::getClusterType).containsExactly("CURATION_MANUAL");
    }

    @Test
    void syncIssue_updatesClusterTypeOnExistingIssue() {
        IssueCluster cluster = IssueCluster.builder()
                .id(5L)
                .searchKeyword("반도체")
                .clusterType(IssueClusterType.CURATION_MANUAL)
                .build();
        IssueNode existingIssue = IssueNode.builder()
                .clusterId(5L)
                .searchKeyword("old")
                .name("old")
                .clusterType("SEARCH_AUTO")
                .build();

        when(issueNodeRepository.findNodeOnlyByIssueId(5L)).thenReturn(Optional.of(existingIssue));

        issueGraphSyncService.syncIssueNow(cluster, List.of(), Map.of());

        ArgumentCaptor<IssueNode> issueCaptor = ArgumentCaptor.forClass(IssueNode.class);
        verify(issueNodeRepository).save(issueCaptor.capture());

        assertThat(issueCaptor.getValue().getClusterType()).isEqualTo("CURATION_MANUAL");
    }
}
