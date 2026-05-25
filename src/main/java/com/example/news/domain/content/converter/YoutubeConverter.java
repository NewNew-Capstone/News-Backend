package com.example.news.domain.content.converter;

import com.example.news.domain.content.dto.YoutubeCommentDto;
import com.example.news.domain.content.dto.YoutubeTranscriptDto;
import com.example.news.domain.content.dto.YoutubeVideoDto;
import com.example.news.domain.content.entity.ContentSource;
import com.example.news.domain.content.entity.YoutubeComment;
import com.example.news.domain.content.entity.YoutubeTranscript;
import com.example.news.domain.content.entity.YoutubeVideo;
import com.example.news.domain.content.enums.TranscriptSource;
import com.google.api.services.youtube.model.*;
import com.google.api.client.util.DateTime;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;

public class YoutubeConverter {
    // 엔티티에서 DTO 변환 정적 메서드 모음.
    // 커멘트 반영

    public static YoutubeVideoDto.VideoCard toVideoCard(YoutubeVideo video) {
        return toVideoCard(video, null);
    }

    public static YoutubeVideoDto.VideoCard toVideoCard(YoutubeVideo video, Double similarityScore) {
        return YoutubeVideoDto.VideoCard.builder()
                .youtubeVideoId(video.getYoutubeVideoId())
                .title(video.getTitle())
                .thumbnailUrl(video.getThumbnailUrl())
                .channelName(video.getChannelName())
                .publishedAt(video.getPublishedAt())
                .viewCount(video.getViewCount())
                .durationSeconds(video.getDurationSeconds())
                .countryCode(video.getCountryCode())
                .similarityScore(similarityScore)
                .build();
    }

    public static YoutubeVideoDto.VideoDetail toVideoDetail(YoutubeVideo video) {
        return YoutubeVideoDto.VideoDetail.builder()
                .youtubeVideoId(video.getYoutubeVideoId())
                .originalUrl(video.getOriginalUrl())
                .title(video.getTitle())
                .description(video.getDescription())
                .thumbnailUrl(video.getThumbnailUrl())
                .channelId(video.getChannelId())
                .channelName(video.getChannelName())
                .publishedAt(video.getPublishedAt())
                .countryCode(video.getCountryCode())
                .defaultLanguageCode(video.getDefaultLanguageCode())
                .durationSeconds(video.getDurationSeconds())
                .isEmbeddable(video.getIsEmbeddable())
                .viewCount(video.getViewCount())
                .likeCount(video.getLikeCount())
                .commentCount(video.getCommentCount())
                .build();
    }

    public static YoutubeCommentDto toCommentDto(YoutubeComment comment) {
        return YoutubeCommentDto.builder()
                .commentId(comment.getExternalCommentId())
                .authorName(comment.getAuthorName())
                .content(comment.getContent())
                .likeCount(comment.getLikeCount())
                .publishedAt(comment.getPublishedAt())
                .isTopComment(comment.getIsTopComment())
                .build();
    }

    public static YoutubeTranscriptDto toTranscriptDto(YoutubeTranscript transcript) {
        return YoutubeTranscriptDto.builder()
                .youtubeVideoId(transcript.getYoutubeVideo().getYoutubeVideoId())
                .transcriptText(transcript.getTranscriptText())
                .transcriptSource(transcript.getTranscriptSource())
                .languageCode(transcript.getLanguageCode())
                .isAvailable(transcript.getTranscriptText() != null)
                .build();
    }

    public static YoutubeTranscriptDto toUnavailableTranscriptDto(String youtubeVideoId) {
        return YoutubeTranscriptDto.builder()
                .youtubeVideoId(youtubeVideoId)
                .isAvailable(false)
                .build();
    }

    public static YoutubeTranscript toTranscriptEntity(YoutubeVideo video, String text, TranscriptSource source, String languageCode) {
        return YoutubeTranscript.builder()
                .youtubeVideo(video)
                .transcriptText(text)
                .transcriptSource(source)
                .languageCode(languageCode)
                .build();
    }

    public static YoutubeVideo toYoutubeVideoEntity(Video video) {
        return toYoutubeVideoEntity(video, null);
    }

    public static YoutubeVideo toYoutubeVideoEntity(Video video, ContentSource contentSource) {
        VideoSnippet snippet = video.getSnippet();
        VideoContentDetails contentDetails = video.getContentDetails();
        VideoStatistics statistics = video.getStatistics();
        String videoId = video.getId();

        return YoutubeVideo.builder()
                .contentSource(contentSource)
                .youtubeVideoId(videoId)
                .originalUrl("https://www.youtube.com/watch?v=" + videoId)
                .channelId(snippet.getChannelId())
                .channelName(snippet.getChannelTitle())
                .title(decodeTitle(snippet.getTitle()))
                .description(snippet.getDescription())
                .thumbnailUrl(extractThumbnailUrl(snippet))
                .publishedAt(parseDateTime(snippet.getPublishedAt()))
                .collectedAt(LocalDateTime.now())
                .defaultLanguageCode(snippet.getDefaultLanguage() != null
                        ? snippet.getDefaultLanguage()
                        : snippet.getDefaultAudioLanguage())
                .durationSeconds(contentDetails != null
                        ? parseDuration(contentDetails.getDuration()) : null)
                .isEmbeddable(contentDetails != null
                        ? contentDetails.getLicensedContent() : null)
                .viewCount(statistics != null && statistics.getViewCount() != null
                        ? statistics.getViewCount().longValue() : null)
                .likeCount(statistics != null && statistics.getLikeCount() != null
                        ? statistics.getLikeCount().longValue() : null)
                .commentCount(statistics != null && statistics.getCommentCount() != null
                        ? statistics.getCommentCount().longValue() : null)
                .build();
    }

    private static String decodeTitle(String title) {
        if (title == null) return null;
        try {
            return URLDecoder.decode(title, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return title;
        }
    }

    private static String extractThumbnailUrl(VideoSnippet snippet) {
        if (snippet.getThumbnails() == null) return null;
        if (snippet.getThumbnails().getHigh() != null) return snippet.getThumbnails().getHigh().getUrl();
        if (snippet.getThumbnails().getMedium() != null) return snippet.getThumbnails().getMedium().getUrl();
        if (snippet.getThumbnails().getDefault() != null) return snippet.getThumbnails().getDefault().getUrl();
        return null;
    }

    public static YoutubeComment toCommentEntity(CommentThread thread, YoutubeVideo video) {
        Comment topComment = thread.getSnippet().getTopLevelComment();
        CommentSnippet snippet = topComment.getSnippet();

        return YoutubeComment.builder()
                .youtubeVideo(video)
                .externalCommentId(thread.getId())
                .authorName(snippet.getAuthorDisplayName())
                .content(snippet.getTextDisplay())
                .likeCount(snippet.getLikeCount())
                .publishedAt(parseDateTime(snippet.getPublishedAt()))
                .isTopComment(true)
                .build();
    }

    private static LocalDateTime parseDateTime(DateTime dateTime) {
        if (dateTime == null) return null;
        return ZonedDateTime.parse(dateTime.toStringRfc3339()).toLocalDateTime();
    }

    private static Integer parseDuration(String isoDuration) {
        if (isoDuration == null) return null;
        try {
            return (int) Duration.parse(isoDuration).getSeconds();
        } catch (Exception e) {
            return null;
        }
    }
}
