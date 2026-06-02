package com.example.news.domain.analysis.service;

import com.example.news.domain.analysis.dto.AnalyzeRawTextRequestDto;
import com.example.news.domain.analysis.dto.BiasAnalysisResultResponse;
import com.example.news.domain.analysis.dto.ScoreReasonRequestDto;
import com.example.news.domain.analysis.dto.ScoreReasonResponseDto;
import com.example.news.domain.analysis.dto.SummaryRequestDto;
import com.example.news.domain.analysis.dto.SummaryResponseDto;
import com.example.news.domain.analysis.entity.AnalysisJob;
import com.example.news.domain.content.entity.YoutubeTranscript;
import com.example.news.domain.content.entity.YoutubeVideo;
import com.example.news.domain.content.repository.YoutubeVideoRepository;
import com.example.news.domain.content.service.YoutubeTranscriptService;
import com.example.news.domain.analysis.entity.BiasAnalysisFocusKeyword;
import com.example.news.domain.analysis.entity.BiasAnalysisKeyword;
import com.example.news.domain.analysis.entity.BiasAnalysisResult;
import com.example.news.domain.analysis.entity.BiasEvidence;
import com.example.news.domain.analysis.entity.ContentSentence;
import com.example.news.domain.analysis.entity.HighlightResult;
import com.example.news.domain.analysis.entity.HighlightSpan;
import com.example.news.domain.analysis.enums.BiasKeywordType;
import com.example.news.domain.analysis.enums.EvidenceType;
import com.example.news.domain.analysis.enums.JobStatus;
import com.example.news.domain.analysis.enums.JobType;
import com.example.news.domain.analysis.enums.SentenceLabelType;
import com.example.news.domain.analysis.enums.SentenceTargetType;
import com.example.news.domain.analysis.enums.TargetType;
import com.example.news.domain.analysis.repository.AnalysisJobRepository;
import com.example.news.domain.analysis.repository.BiasAnalysisFocusKeywordRepository;
import com.example.news.domain.analysis.repository.BiasAnalysisKeywordRepository;
import com.example.news.domain.analysis.repository.BiasAnalysisResultRepository;
import com.example.news.domain.analysis.repository.BiasEvidenceRepository;
import com.example.news.domain.analysis.repository.ContentSentenceRepository;
import com.example.news.domain.analysis.event.AnalysisCompletedEvent;
import com.example.news.domain.analysis.repository.HighlightResultRepository;
import com.example.news.domain.analysis.repository.HighlightSpanRepository;
import com.example.news.domain.analysis.repository.SentenceBiasLabelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {
    private static final List<String> SCORE_REASON_FALLBACK_PREFIXES = List.of(
            "이 영상은 사실을 전달하는 문장이 비교적 많아",
            "이 영상은 일부 문장에서 보도자의 해석이나 주장이 나타나",
            "이 영상은 사실 전달과 보도자의 해석이나 주장이 함께 나타나"
    );

    private static final List<String> SUMMARY_FALLBACK_PREFIXES = List.of(
            "영상 요약이 아직 생성되지 않았습니다",
            "[LLM 안 탐]"
    );

    private final AnalysisJobRepository analysisJobRepository;
    private final ContentSentenceRepository contentSentenceRepository;
    private final BiasAnalysisResultRepository biasAnalysisResultRepository;
    private final BiasAnalysisFocusKeywordRepository biasAnalysisFocusKeywordRepository;
    private final BiasAnalysisKeywordRepository biasAnalysisKeywordRepository;
    private final SentenceBiasLabelRepository sentenceBiasLabelRepository;
    private final BiasEvidenceRepository biasEvidenceRepository;
    private final HighlightResultRepository highlightResultRepository;
    private final HighlightSpanRepository highlightSpanRepository;
    private final WebClient webClient;
    private final ApplicationEventPublisher eventPublisher;
    private final YoutubeVideoRepository youtubeVideoRepository;
    private final YoutubeTranscriptService youtubeTranscriptService;

    @Value("${python.base-url}")
    private String pythonBaseUrl;

    public record AnalysisExecutionResult(
            AnalysisJob job,
            BiasAnalysisResultResponse analysisResult
    ) {}

    @Transactional
    public AnalysisExecutionResult getOrCreateAnalysisExecutionResult(YoutubeTranscript transcript) {
        Long videoId = transcript.getYoutubeVideo().getId();
        Optional<BiasAnalysisResult> existing = biasAnalysisResultRepository
                .findTopByTargetIdAndTargetTypeOrderByCreatedAtDesc(videoId, TargetType.YOUTUBE_VIDEO);
        if (existing.isPresent() && existing.get().getAnalysisJob() != null) {
            BiasAnalysisResult existingResult = existing.get();
            log.info("분석 결과 캐시 히트 - videoId={}, jobId={}", videoId, existingResult.getAnalysisJob().getId());
            return new AnalysisExecutionResult(existingResult.getAnalysisJob(), null);
        }
        return createAnalysisExecutionFromRawText(transcript, true);
    }

    @Transactional
    public AnalysisJob getOrCreateAnalysisJob(YoutubeTranscript transcript) {
        return getOrCreateAnalysisExecutionResult(transcript).job();
    }

    public void enrichSummaryText(BiasAnalysisResult result, YoutubeTranscript transcript) {
        try {
            SummaryRequestDto request = new SummaryRequestDto(
                    result.getTargetId(),
                    transcript.getYoutubeVideo().getTitle(),
                    transcript.getLanguageCode(),
                    transcript.getTranscriptText());

            SummaryResponseDto response = webClient.post()
                    .uri(pythonBaseUrl + "/analyze/summary")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(SummaryResponseDto.class)
                    .block();

            if (response != null && hasText(response.summaryText())) {
                result.updateSummaryText(response.summaryText());
                log.info("영상 요약 보강 완료 - resultId={}, videoId={}", result.getId(), result.getTargetId());
            }
        } catch (Exception e) {
            log.warn("영상 요약 보강 실패 - resultId={}, videoId={}", result.getId(), result.getTargetId(), e);
        }
    }

    public void enrichDisplayTextIfNeeded(BiasAnalysisResult result, YoutubeTranscript transcript) {
        if (transcript == null) {
            enrichScoreReasonSummaryIfNeeded(result, "ko");
            return;
        }

        if (needsSummaryTextEnrichment(result.getSummaryText())) {
            enrichSummaryText(result, transcript);
        }
        enrichScoreReasonSummaryIfNeeded(result, transcript.getLanguageCode());
    }

    public void enrichScoreReasonSummaryIfNeeded(BiasAnalysisResult result, String language) {
        if (needsScoreReasonSummaryEnrichment(result.getScoreReasonSummary())) {
            enrichScoreReasonSummary(result, language);
        }
    }

    public void enrichScoreReasonSummary(BiasAnalysisResult result, String language) {
        try {
            ScoreReasonRequestDto request = new ScoreReasonRequestDto(
                    result.getTargetId(),
                    language,
                    result.getOverallBiasScore(),
                    result.getOpinionScore(),
                    result.getEmotionScore(),
                    result.getFactRatio(),
                    result.getHeadlineBodyGapScore(),
                    result.getScoreEvidence());

            ScoreReasonResponseDto response = webClient.post()
                    .uri(pythonBaseUrl + "/analyze/score-reason")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(ScoreReasonResponseDto.class)
                    .block();

            if (response != null && hasText(response.scoreReasonSummary())) {
                result.updateScoreReasonSummary(response.scoreReasonSummary());
                log.info("점수 근거 요약 보강 완료 - resultId={}, videoId={}", result.getId(), result.getTargetId());
            }
        } catch (Exception e) {
            log.warn("점수 근거 요약 보강 실패 - resultId={}, videoId={}", result.getId(), result.getTargetId(), e);
        }
    }

    private void enrichScoreReasonSummary(BiasAnalysisResult result, YoutubeTranscript transcript) {
        enrichScoreReasonSummary(result, transcript.getLanguageCode());
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean needsSummaryTextEnrichment(String summaryText) {
        if (!hasText(summaryText)) {
            return true;
        }
        String normalized = summaryText.trim();
        return SUMMARY_FALLBACK_PREFIXES.stream().anyMatch(normalized::startsWith);
    }

    private boolean needsScoreReasonSummaryEnrichment(String scoreReasonSummary) {
        if (!hasText(scoreReasonSummary)) {
            return true;
        }
        String normalized = scoreReasonSummary.trim();
        return SCORE_REASON_FALLBACK_PREFIXES.stream().anyMatch(normalized::startsWith);
    }

    @Transactional
    public AnalysisJob createAnalysisJobFromRawText(YoutubeTranscript transcript) {
        return createAnalysisExecutionFromRawText(transcript, false).job();
    }

    @Transactional
    public AnalysisJob createAnalysisJobFromRawText(YoutubeTranscript transcript, boolean priority) {
        return createAnalysisExecutionFromRawText(transcript, priority).job();
    }

    @Transactional
    public AnalysisExecutionResult createAnalysisExecutionFromRawText(YoutubeTranscript transcript, boolean priority) {

        Long transcriptId = transcript.getId();
        Long youtubeVideoId = transcript.getYoutubeVideo().getId();

        AnalysisJob job = AnalysisJob.builder()
                .targetId(youtubeVideoId)
                .targetType(TargetType.YOUTUBE_VIDEO)
                .jobType(JobType.VIDEO_BIAS_ANALYSIS)
                .status(JobStatus.PENDING)
                .build();

        final AnalysisJob savedJob = analysisJobRepository.save(job);

        AnalysisCompletedEvent completedEvent = null;
        BiasAnalysisResultResponse result = null;

        try {
            // 1. RUNNING 전이
            savedJob.start();

            // 2. Python /analyze/raw 호출
            AnalyzeRawTextRequestDto request = new AnalyzeRawTextRequestDto(
                    transcriptId,
                    transcript.getYoutubeVideo().getTitle(),
                    transcript.getLanguageCode(),
                    transcript.getTranscriptText(),
                    "YOUTUBE_VIDEO",
                    transcriptId,
                    transcript.getYoutubeVideo().getCountryCode(),
                    priority);

            result = webClient.post()
                    .uri(pythonBaseUrl + "/analyze/raw")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(BiasAnalysisResultResponse.class)
                    .block();
            log.info("python focusKeywords size={}",
                    result == null ? null : result.focusKeywords().size());
            if (result == null) {
                throw new IllegalStateException("Python analysis response is null");
            }

            // 3. ContentSentence 저장 + pythonId(sentenceOrder) → DB ID 매핑 생성
            Map<Long, Long> pythonIdToDbId = new HashMap<>();
            if (result.sentences() != null && !result.sentences().isEmpty()) {
                List<ContentSentence> savedSentences = contentSentenceRepository.saveAll(
                        result.sentences().stream()
                                .map(s -> ContentSentence.builder()
                                        .targetId(transcriptId)
                                        .targetType(SentenceTargetType.YOUTUBE_TRANSCRIPT)
                                        .sentenceOrder(s.sentenceOrder())
                                        .sentenceText(s.sentenceText())
                                        .build())
                                .toList()
                );
                pythonIdToDbId = savedSentences.stream()
                        .collect(Collectors.toMap(
                                s -> s.getSentenceOrder().longValue(),
                                ContentSentence::getId
                        ));
            }

            // 4. BiasAnalysisResult 저장
            BiasAnalysisResult savedResult = biasAnalysisResultRepository.save(
                    BiasAnalysisResult.builder()
                            .analysisJob(savedJob)
                            .targetId(savedJob.getTargetId())
                            .targetType(savedJob.getTargetType())
                            .overallBiasScore(result.overallBiasScore())
                            .opinionScore(result.opinionScore())
                            .emotionScore(result.emotionScore())
                            .headlineBodyGapScore(result.headlineBodyGapScore())
                            .headlineBodyGapStd(result.headlineBodyGapStd())
                            .headlineBodyGapLead(result.headlineBodyGapLead())
                            .headlineBodyGapTail(result.headlineBodyGapTail())
                            .headlineBodyGapLabel(result.headlineBodyGapLabel())
                            .scoreReasonSummary(result.scoreReasonSummary())
                            .summaryText(result.summaryText())
                            .factRatio(result.factRatio())
                            .scoreEvidence(result.scoreEvidence())
                            .build()
            );

            // 5. BiasAnalysisKeyword 저장
            if (result.keywords() != null) {
                biasAnalysisKeywordRepository.saveAll(
                        result.keywords().stream()
                                .map(k -> BiasAnalysisKeyword.builder()
                                        .biasAnalysisResult(savedResult)
                                        .keywordText(k.keywordText())
                                        .keywordType(BiasKeywordType.valueOf(k.keywordType().toUpperCase()))
                                        .score(k.score())
                                        .build())
                                .toList()
                );
            }

            if (result.focusKeywords() != null && !result.focusKeywords().isEmpty()) {
                biasAnalysisFocusKeywordRepository.saveAll(
                        result.focusKeywords().stream()
                                .filter(k -> hasText(k.keywordText()))
                                .map(k -> BiasAnalysisFocusKeyword.builder()
                                        .biasAnalysisResult(savedResult)
                                        .keywordText(k.keywordText())
                                        .score(k.score())
                                        .occurrenceCount(k.occurrenceCount())
                                        .sentenceCount(k.sentenceCount())
                                        .build())
                                .toList()
                );
            }

            // 6. SentenceBiasLabel: Python sentence_labels는 span 데이터(offset 포함)이므로
            //    classifier 결과(FACT_LIKE/OPINION_LIKE)가 생기기 전까지 저장하지 않음.
            //    span 데이터는 step 8의 HighlightSpan에만 저장한다.
            final Map<Long, Long> idMap = pythonIdToDbId;

            // 7. BiasEvidence 저장 (contentSentenceId가 없어도 evidence 자체는 저장)
            if (result.evidences() != null) {
                biasEvidenceRepository.saveAll(
                        result.evidences().stream()
                                .map(e -> {
                                    ContentSentence cs = (e.contentSentenceId() != null && idMap.containsKey(e.contentSentenceId()))
                                            ? contentSentenceRepository.getReferenceById(idMap.get(e.contentSentenceId()))
                                            : null;
                                    return BiasEvidence.builder()
                                            .biasAnalysisResult(savedResult)
                                            .contentSentence(cs)
                                            .evidenceType(EvidenceType.valueOf(e.evidenceType().toUpperCase()))
                                            .title(e.title())
                                            .description(e.description())
                                            .sourceText(e.sourceText())
                                            .confidenceScore(e.confidenceScore())
                                            .build();
                                })
                                .toList()
                );
            }

            // 8. HighlightResult / HighlightSpan 저장
            if (result.sentenceLabels() != null && !result.sentenceLabels().isEmpty()) {
                HighlightResult highlightResult = highlightResultRepository.save(
                        HighlightResult.builder()
                                .biasAnalysisResult(savedResult)
                                .build()
                );
                highlightSpanRepository.saveAll(
                        result.sentenceLabels().stream()
                                .filter(l -> l.startOffset() != null && l.endOffset() != null)
                                .filter(l -> idMap.containsKey(l.contentSentenceId()))
                                .map(l -> HighlightSpan.builder()
                                        .highlightResult(highlightResult)
                                        .contentSentence(contentSentenceRepository.getReferenceById(idMap.get(l.contentSentenceId())))
                                        .startOffset(l.startOffset())
                                        .endOffset(l.endOffset())
                                        .labelType(SentenceLabelType.valueOf(l.labelType().toUpperCase()))
                                        .score(l.score())
                                        .matchedWord(l.matchedWord())
                                        .build())
                                .toList()
                );
            }

            // 9. SUCCESS 전이
            savedJob.complete();

            // 10. 이벤트 페이로드 준비
            List<String> analysisKeywords = result.keywords() != null
                    ? result.keywords().stream().map(k -> k.keywordText()).toList()
                    : List.of();
            completedEvent = new AnalysisCompletedEvent(
                    savedJob.getTargetId(),
                    savedJob.getId(),
                    savedResult.getId(),
                    JobStatus.SUCCESS.name(),
                    result.overallBiasScore(),
                    analysisKeywords,
                    result.summaryText(),
                    result.biasTypeScores()
            );

        } catch (Exception e) {
            log.error("Raw text analysis failed for job {}: {}", savedJob.getId(), e.getMessage());
            savedJob.fail(e.getMessage());
        }

        if (completedEvent != null) {
            try {
                eventPublisher.publishEvent(completedEvent);
            } catch (Exception e) {
                log.error("AnalysisCompletedEvent 발행 실패 (jobId={})", completedEvent.analysisJobId(), e);
            }
        }

        return new AnalysisExecutionResult(
                savedJob,
                savedJob.getStatus() == JobStatus.SUCCESS ? result : null
        );
    }

    @Async
    @Transactional
    public void triggerAnalysisAsync(Long videoId) {
        if (biasAnalysisResultRepository.existsByTargetTypeAndTargetId(TargetType.YOUTUBE_VIDEO, videoId)) {
            return;
        }
        Optional<YoutubeVideo> videoOpt = youtubeVideoRepository.findById(videoId);
        if (videoOpt.isEmpty()) return;

        try {
            YoutubeTranscript transcript =
                    youtubeTranscriptService.getOrFetchTranscriptEntity(videoOpt.get().getYoutubeVideoId());
            if (transcript == null) {
                log.warn("백그라운드 분석 스킵 - 자막 없음 (videoId={})", videoId);
                return;
            }
            createAnalysisJobFromRawText(transcript);
        } catch (Exception e) {
            log.warn("백그라운드 분석 실패 (videoId={}): {}", videoId, e.getMessage());
        }
    }

}
