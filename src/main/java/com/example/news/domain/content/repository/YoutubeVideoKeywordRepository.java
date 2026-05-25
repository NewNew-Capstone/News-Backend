package com.example.news.domain.content.repository;

import com.example.news.domain.content.entity.Keyword;
import com.example.news.domain.content.entity.YoutubeVideo;
import com.example.news.domain.content.entity.YoutubeVideoKeyword;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface YoutubeVideoKeywordRepository extends JpaRepository<YoutubeVideoKeyword, Long> {
    // 영상 키워드 연결 중복 방지
    boolean existsByYoutubeVideoAndKeyword(YoutubeVideo youtubeVideo, Keyword keyword);

    // 키워드로 연결된 영상 목록 조회 (캐시 히트 시 사용)
    List<YoutubeVideoKeyword> findByKeyword(Keyword keyword);

    // 같은 키워드를 공유하는 다른 영상 ID 목록 조회 (반대 관점 fallback용)
    @Query("SELECT DISTINCT vk.youtubeVideo.id FROM YoutubeVideoKeyword vk " +
           "WHERE vk.keyword IN (SELECT vk2.keyword FROM YoutubeVideoKeyword vk2 WHERE vk2.youtubeVideo.id = :videoId) " +
           "AND vk.youtubeVideo.id <> :videoId")
    List<Long> findVideoIdsSharingKeywordWith(@Param("videoId") Long videoId);
}
