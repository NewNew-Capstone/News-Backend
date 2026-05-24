package com.example.news.domain.content.repository;

import com.example.news.domain.content.entity.YoutubeTranscript;
import com.example.news.domain.content.entity.YoutubeVideo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface YoutubeTranscriptRepository extends JpaRepository<YoutubeTranscript, Long> {
    // 영상에 연결된 자막 조회
    Optional<YoutubeTranscript> findTopByYoutubeVideoOrderByCreatedAtDesc(YoutubeVideo youtubeVideo);

    boolean existsByYoutubeVideoId(Long youtubeVideoId);
}
