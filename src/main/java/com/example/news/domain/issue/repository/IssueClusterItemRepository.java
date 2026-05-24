package com.example.news.domain.issue.repository;

import com.example.news.domain.issue.entity.IssueClusterItem;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IssueClusterItemRepository extends JpaRepository<IssueClusterItem, Long> {

    List<IssueClusterItem> findByIssueClusterId(Long issueClusterId);
    long countByIssueClusterId(Long issueClusterId);

    List<IssueClusterItem> findByIssueClusterIdAndCountryCode(Long issueClusterId, String countryCode);
    Optional<IssueClusterItem> findByIssueClusterIdAndYoutubeVideoId(Long issueClusterId, Long youtubeVideoId);

    Optional<IssueClusterItem> findByIssueClusterIdAndIsRepresentativeTrue(Long issueClusterId);

    List<IssueClusterItem> findByYoutubeVideoId(Long youtubeVideoId);

    List<IssueClusterItem> findByIssueClusterIdIn(Collection<Long> clusterIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from IssueClusterItem i where i.issueCluster.id = :issueClusterId")
    void deleteByIssueClusterId(Long issueClusterId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from IssueClusterItem i where i.issueCluster.id = :issueClusterId and i.youtubeVideoId in :videoIds")
    void deleteByIssueClusterIdAndYoutubeVideoIdIn(Long issueClusterId, Collection<Long> videoIds);
}
