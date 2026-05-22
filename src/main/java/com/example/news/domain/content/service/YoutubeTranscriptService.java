package com.example.news.domain.content.service;

import com.example.news.domain.content.converter.YoutubeConverter;
import com.example.news.domain.content.dto.YoutubeTranscriptDto;
import com.example.news.domain.content.entity.YoutubeTranscript;
import com.example.news.domain.content.entity.YoutubeVideo;
import com.example.news.domain.content.enums.TranscriptSource;
import com.example.news.domain.content.repository.YoutubeTranscriptRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class YoutubeTranscriptService {

    private final YoutubeTranscriptRepository youtubeTranscriptRepository;
    private final RestTemplate restTemplate;
    private final YoutubeVideoService youtubeVideoService;

    @Value("${ai-pipeline.base-url}")
    private String aiPipelineBaseUrl;

    @Value("${ai-pipeline.transcript.min-call-interval-ms:450}")
    private long minCallIntervalMs;

    @Value("${ai-pipeline.transcript.max-retries:3}")
    private int maxRetries;

    @Value("${ai-pipeline.transcript.initial-backoff-ms:700}")
    private long initialBackoffMs;

    @Value("${ai-pipeline.transcript.no-transcript-cooldown-ms:900000}")
    private long noTranscriptCooldownMs;

    private static final Map<String, String> REGION_TO_LANG = Map.of(
            "KR", "ko",
            "US", "en",
            "JP", "ja"
    );
    private static final String[] TRANSCRIPT_REGIONS = new String[]{"KR", "US"};

    private final ConcurrentMap<String, ReentrantLock> videoLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> noTranscriptCooldown = new ConcurrentHashMap<>();
    private final Object callIntervalMonitor = new Object();
    private volatile long lastPipelineCallAtMs = 0L;

    @Transactional
    public YoutubeTranscriptDto getTranscript(String youtubeVideoId) {
        log.info("[Transcript] 요청 - videoId={}", youtubeVideoId);

        // 영상 엔티티 조회
        YoutubeVideo video = youtubeVideoService.getOrFetchVideoEntity(youtubeVideoId);

        if (isUnderNoTranscriptCooldown(youtubeVideoId)) {
            log.info("[Transcript] no-transcript cooldown active - videoId={}", youtubeVideoId);
            return YoutubeConverter.toUnavailableTranscriptDto(youtubeVideoId);
        }

        // DB 캐시 확인
        Optional<YoutubeTranscript> existing = youtubeTranscriptRepository.findTopByYoutubeVideoOrderByCreatedAtDesc(video);
        if (existing.isPresent()) {
            log.info("[Transcript] DB 캐시 히트 - videoId={}, transcriptId={}", youtubeVideoId, existing.get().getId());
            return YoutubeConverter.toTranscriptDto(existing.get());
        }
        log.debug("[Transcript] DB 캐시 없음 - videoId={}", youtubeVideoId);

        YoutubeTranscript transcript = fetchAndPersistTranscript(video);
        if (transcript != null) {
            return YoutubeConverter.toTranscriptDto(transcript);
        }

        return YoutubeConverter.toUnavailableTranscriptDto(youtubeVideoId);
    }

    /**
     * transcript entity를 반환한다. 없으면 Python AI Pipeline에서 fetch 후 저장.
     * DB id, transcriptText 등 entity 전체가 필요한 경우 사용.
     *
     * @return YoutubeTranscript entity, 자막 없으면 null
     */
    @Transactional
    public YoutubeTranscript getOrFetchTranscriptEntity(String youtubeVideoId) {
        return getOrFetchTranscriptEntity(youtubeVideoId, false);
    }

    @Transactional
    public YoutubeTranscript getOrFetchTranscriptEntity(String youtubeVideoId, boolean priority) {
        log.info("[TranscriptEntity] 요청 - videoId={}, priority={}", youtubeVideoId, priority);

        YoutubeVideo video = youtubeVideoService.getOrFetchVideoEntity(youtubeVideoId);

        if (isUnderNoTranscriptCooldown(youtubeVideoId)) {
            log.info("[TranscriptEntity] no-transcript cooldown active - videoId={}", youtubeVideoId);
            return null;
        }

        Optional<YoutubeTranscript> existing = youtubeTranscriptRepository.findTopByYoutubeVideoOrderByCreatedAtDesc(video);
        if (existing.isPresent()) {
            log.info("[TranscriptEntity] DB 캐시 히트 - videoId={}, transcriptId={}", youtubeVideoId, existing.get().getId());
            return existing.get();
        }
        log.debug("[TranscriptEntity] DB 캐시 없음 - videoId={}", youtubeVideoId);

        return fetchAndPersistTranscript(video, priority);
    }

    // DB 캐시 우선 확인 후 AI Pipeline 호출로 자막 존재 여부 반환
    @Transactional
    public boolean hasTranscript(YoutubeVideo video) {
        if (youtubeTranscriptRepository.existsByYoutubeVideoId(video.getId())) return true;
        if (isUnderNoTranscriptCooldown(video.getYoutubeVideoId())) return false;
        return fetchAndPersistTranscript(video) != null;
    }

    private YoutubeTranscript fetchAndPersistTranscript(YoutubeVideo video) {
        return fetchAndPersistTranscript(video, false);
    }

    private YoutubeTranscript fetchAndPersistTranscript(YoutubeVideo video, boolean priority) {
        String youtubeVideoId = video.getYoutubeVideoId();
        ReentrantLock lock = videoLocks.computeIfAbsent(youtubeVideoId, key -> new ReentrantLock());
        lock.lock();
        try {
            Optional<YoutubeTranscript> existing = youtubeTranscriptRepository.findTopByYoutubeVideoOrderByCreatedAtDesc(video);
            if (existing.isPresent()) return existing.get();

            boolean hitRateLimit = false;
            for (String regionCode : TRANSCRIPT_REGIONS) {
                log.debug("[Transcript] AI Pipeline 호출 시도 - videoId={}, region={}, priority={}", youtubeVideoId, regionCode, priority);
                FetchResult fetchResult = fetchFromAiPipeline(youtubeVideoId, regionCode, priority);
                AiPipelineTranscriptResponse response = fetchResult.response();

                if (response != null && "success".equals(response.transcriptStatus())
                        && response.transcript() != null && !response.transcript().isBlank()) {
                    String langCode = REGION_TO_LANG.get(regionCode);
                    YoutubeTranscript transcript = youtubeTranscriptRepository.save(
                            YoutubeConverter.toTranscriptEntity(video, response.transcript(), TranscriptSource.YOUTUBE_CAPTION, langCode)
                    );
                    noTranscriptCooldown.remove(youtubeVideoId);
                    log.info("[Transcript] AI Pipeline 성공 - videoId={}, region={}, lang={}, transcriptLength={}",
                            youtubeVideoId, regionCode, langCode, response.transcript().length());
                    return transcript;
                }

                if (fetchResult.rateLimited()) {
                    hitRateLimit = true;
                    log.warn("[Transcript] rate limited - videoId={}, region={}", youtubeVideoId, regionCode);
                    break;
                }
            }

            markNoTranscriptCooldown(youtubeVideoId);
            if (hitRateLimit) {
                log.warn("[Transcript] 자막 조회 중 rate limit 감지, fallback 중단 - videoId={}", youtubeVideoId);
            } else {
                log.warn("[Transcript] 자막 없음 - videoId={}", youtubeVideoId);
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    // Python api 호출 (backoff/retry 포함)
    private FetchResult fetchFromAiPipeline(String videoId, String regionCode, boolean priority) {
        long backoffMs = initialBackoffMs;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            waitForCallInterval();
            String url = UriComponentsBuilder.fromHttpUrl(aiPipelineBaseUrl)
                    .path("/content/transcript")
                    .queryParam("video_id", videoId)
                    .queryParam("region_code", regionCode)
                    .queryParam("priority", priority)
                    .toUriString();
        try {
            log.debug("[Transcript] AI Pipeline URL - {}", url);
                AiPipelineTranscriptResponse response = restTemplate.getForObject(url, AiPipelineTranscriptResponse.class);
                return new FetchResult(response, false);
            } catch (HttpStatusCodeException e) {
                HttpStatusCode statusCode = e.getStatusCode();
                int rawStatusCode = statusCode.value();
                boolean rateLimited = rawStatusCode == 429 || rawStatusCode == 403;
                log.warn("[Transcript] API 상태 오류 - videoId={}, region={}, status={}, attempt={}/{}",
                        videoId, regionCode, rawStatusCode, attempt, maxRetries);

                if (!rateLimited || attempt == maxRetries) {
                    return new FetchResult(null, rateLimited);
                }
            } catch (Exception e) {
                log.warn("[Transcript] API 호출 예외 - videoId={}, region={}, attempt={}/{}, error={}",
                        videoId, regionCode, attempt, maxRetries, e.getMessage());
                if (attempt == maxRetries) {
                    return new FetchResult(null, false);
                }
            }
            sleepQuietly(backoffMs + (long) (Math.random() * 250));
            backoffMs *= 2;
        }
        return new FetchResult(null, false);
    }

    private boolean isUnderNoTranscriptCooldown(String videoId) {
        Long expiresAt = noTranscriptCooldown.get(videoId);
        if (expiresAt == null) return false;
        if (expiresAt <= System.currentTimeMillis()) {
            noTranscriptCooldown.remove(videoId);
            return false;
        }
        return true;
    }

    private void markNoTranscriptCooldown(String videoId) {
        noTranscriptCooldown.put(videoId, System.currentTimeMillis() + noTranscriptCooldownMs);
    }

    private void waitForCallInterval() {
        if (minCallIntervalMs <= 0) return;
        synchronized (callIntervalMonitor) {
            long now = System.currentTimeMillis();
            long waitMs = minCallIntervalMs - (now - lastPipelineCallAtMs);
            if (waitMs > 0) {
                sleepQuietly(waitMs);
            }
            lastPipelineCallAtMs = System.currentTimeMillis();
        }
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(Math.max(millis, 0));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private record AiPipelineTranscriptResponse(
            @JsonProperty("video_id") String videoId,
            String transcript,
            @JsonProperty("transcript_status") String transcriptStatus
    ) {}

    private record FetchResult(
            AiPipelineTranscriptResponse response,
            boolean rateLimited
    ) {}
}
