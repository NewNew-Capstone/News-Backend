package com.example.news.domain.content.repository;

import com.example.news.domain.content.entity.Keyword;
import com.example.news.domain.content.entity.YoutubeVideo;
import com.example.news.domain.content.entity.YoutubeVideoKeyword;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface YoutubeVideoKeywordRepository extends JpaRepository<YoutubeVideoKeyword, Long> {
    // 영상 키워드 연결 중복 방지
    boolean existsByYoutubeVideoAndKeyword(YoutubeVideo youtubeVideo, Keyword keyword);

    // 키워드로 연결된 영상 목록 조회 (캐시 히트 시 사용)
    List<YoutubeVideoKeyword> findByKeyword(Keyword keyword);
}
