package com.example.news.domain.content.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

public class YoutubeVideoDto {
    // 두가지 내부 클래스로 구성

    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL) // NULL 필드는 응답 json에서 제외
    public static class VideoCard {
        // 목록 조회용
        private String youtubeVideoId;
        private String title;
        private String thumbnailUrl;
        private String channelName;
        private LocalDateTime publishedAt;
        private Long viewCount;
        private Integer durationSeconds;
        private String countryCode;
        private Double similarityScore;
    }

    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VideoDetail {
        // 단건 상세 조회용
        private String youtubeVideoId;
        private String originalUrl;
        private String title;
        private String description;
        private String thumbnailUrl;
        private String channelId;
        private String channelName;
        private LocalDateTime publishedAt;
        private String countryCode;
        private String defaultLanguageCode;
        private Integer durationSeconds;
        private Boolean isEmbeddable;
        private Long viewCount;
        private Long likeCount;
        private Long commentCount;
    }
}
