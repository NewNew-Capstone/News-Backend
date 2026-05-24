package com.example.news.domain.issue.service;

import com.example.news.domain.analysis.entity.BiasAnalysisResult;
import com.example.news.domain.analysis.enums.TargetType;
import com.example.news.domain.analysis.event.AnalysisCompletedEvent;
import com.example.news.domain.analysis.repository.BiasAnalysisKeywordRepository;
import com.example.news.domain.analysis.repository.BiasAnalysisResultRepository;
import com.example.news.domain.content.entity.YoutubeVideo;
import com.example.news.domain.content.repository.YoutubeVideoRepository;
import com.example.news.domain.issue.dto.IssueCandidateDto;
import com.example.news.domain.issue.entity.ComparisonCountryItem;
import com.example.news.domain.issue.entity.ComparisonResult;
import com.example.news.domain.issue.entity.IssueCluster;
import com.example.news.domain.issue.entity.IssueClusterItem;
import com.example.news.domain.issue.enums.ClusterStatus;
import com.example.news.domain.issue.enums.IssueClusterItemSourceType;
import com.example.news.domain.issue.enums.IssueClusterType;
import com.example.news.domain.issue.repository.ComparisonCountryItemRepository;
import com.example.news.domain.issue.repository.ComparisonResultRepository;
import com.example.news.domain.issue.repository.IssueClusterItemRepository;
import com.example.news.domain.issue.repository.IssueClusterRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IssueComparisonService {
    private static final String UNKNOWN_COUNTRY_CODE = "UNKNOWN";
    private static final List<String> CURATION_COUNTRIES = List.of("KR", "US", "CN");
    private static final int MIN_ANALYZED_PER_COUNTRY = 10;


    private final IssueClusterRepository issueClusterRepository;
    private final IssueClusterItemRepository issueClusterItemRepository;
    private final ComparisonResultRepository comparisonResultRepository;
    private final ComparisonCountryItemRepository comparisonCountryItemRepository;
    private final YoutubeVideoRepository youtubeVideoRepository;
    private final BiasAnalysisResultRepository biasAnalysisResultRepository;
    private final BiasAnalysisKeywordRepository biasAnalysisKeywordRepository;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${python.base-url}")
    private String pythonBaseUrl;

    // 내부 데이터 구조로 따로 DTO로 안 뺐습니다.
    private record ScoredCandidate(
            IssueCandidateDto candidate,
            double similarityScore,
            boolean isRepresentative,
            int rankNo
    ) {}

    private static class ClusterGroup {
        final IssueCluster baseCluster;
        final List<ScoredCandidate> members = new ArrayList<>();

        ClusterGroup(IssueCluster base, IssueCandidateDto first) {
            this.baseCluster = base;
            members.add(new ScoredCandidate(first, 1.0, false, 0));
        }

        IssueCandidateDto representative() {
            return members.get(0).candidate();
        }
    }

    private record SnapshotCountryItem(
            String countryCode,
            Long representativeVideoId,
            String title,
            String summaryText,
            Double biasScore,
            Integer rankNo
    ) {}

    private record ClusterSimilarityRequest(
            String title_a, String title_b, String summary_a, String summary_b
    ) {}

    private record ClusterSimilarityResponse(
            Double title_similarity, Double summary_similarity
    ) {}

    // 이벤트 핸들러
    // TODO : 이벤트 실패/재시도 전략 필요
    @EventListener
    @Async
    @Transactional
    public void handleAnalysisCompleted(AnalysisCompletedEvent event) {
        if (!"SUCCESS".equals(event.status())) {
            log.info("skip non-success analysis event (targetId={}, status={})", event.targetId(), event.status());
            return;
        }

        List<IssueClusterItem> matchedItems = issueClusterItemRepository.findByYoutubeVideoId(event.targetId());
        log.info("received analysis event (targetId={}, matchedItems={})", event.targetId(), matchedItems.size());
        if (matchedItems.isEmpty()) {
            log.info("skip analysis event because target video is not mapped to any issue cluster (targetId={})",
                    event.targetId());
            return;
        }

        Set<Long> clusterIds = matchedItems.stream()
                .map(item -> item.getIssueCluster().getId())
                .collect(Collectors.toSet());

        // 큐레이션 세트(ANALYZING)는 국가별 최소 분석 성공 수를 충족하면 READY_FOR_REPORT로 전이한다.
        List<IssueCluster> analyzingClusters = issueClusterRepository.findAllById(clusterIds).stream()
                .filter(c -> c.getClusterType() == IssueClusterType.CURATION_MANUAL)
                .filter(c -> c.getStatus() == ClusterStatus.ANALYZING)
                .toList();
        for (IssueCluster cluster : analyzingClusters) {
            tryAdvanceCurationCluster(cluster);
        }

        List<IssueCluster> targetClusters = issueClusterRepository.findAllById(clusterIds).stream()
                .filter(c -> c.getClusterType() == IssueClusterType.SEARCH_AUTO)
                .filter(c -> c.getStatus() == ClusterStatus.PENDING)
                .toList();

        if (targetClusters.isEmpty()) {
            log.info("skip analysis event because no PENDING cluster matched (targetId={})", event.targetId());
            return;
        }

        for (IssueCluster cluster : targetClusters) {
            // 반대 영상 도출에 최소 2개 필요 — 미달이면 다음 이벤트에서 재시도
            List<IssueClusterItem> allItems = issueClusterItemRepository.findByIssueClusterId(cluster.getId());
            List<Long> allVideoIds = allItems.stream().map(IssueClusterItem::getYoutubeVideoId).distinct().toList();
            long analyzedCount = biasAnalysisResultRepository
                    .findByTargetTypeAndTargetIdIn(TargetType.YOUTUBE_VIDEO, allVideoIds).size();
            if (analyzedCount < 2) {
                log.info("clustering deferred: only {}/{} analyzed (clusterId={})",
                        analyzedCount, allVideoIds.size(), cluster.getId());
                continue;
            }

            // 동시 이벤트가 같은 클러스터를 중복 처리하지 않도록 선점한다.
            int claimed = issueClusterRepository.updateStatusIfCurrent(
                    cluster.getId(), ClusterStatus.PENDING, ClusterStatus.PROCESSING
            );
            if (claimed == 0) {
                log.info("cluster already claimed by another worker (clusterId={})", cluster.getId());
                continue;
            }

            try {
                List<IssueCandidateDto> candidates = prepareComparisonInputsForCluster(cluster); // 후보 생성
                if (candidates.isEmpty()) {
                    log.warn("no analyzable candidates for cluster (clusterId={})", cluster.getId());
                    issueClusterRepository.updateStatus(cluster.getId(), ClusterStatus.FAIL);
                    continue;
                }
                List<ClusterGroup> groups = buildClusterGroups(candidates, cluster); // 그룹화
                selectRepresentativeVideos(groups, cluster.getSearchKeyword()); // 국가별 대표 비디오 선정
                saveClusterResults(cluster, groups);
                log.info("cluster comparison completed (clusterId={}, candidates={}, groups={})",
                        cluster.getId(), candidates.size(), groups.size());
            } catch (Exception e) {
                log.error("클러스터링 실패 (clusterId={}): {}", cluster.getId(), e.getMessage(), e);
                issueClusterRepository.updateStatus(cluster.getId(), ClusterStatus.FAIL);
            }
        }
    }

    private void tryAdvanceCurationCluster(IssueCluster cluster) {
        List<IssueClusterItem> items = issueClusterItemRepository.findByIssueClusterId(cluster.getId());
        List<Long> videoIds = items.stream()
                .map(IssueClusterItem::getYoutubeVideoId)
                .distinct()
                .toList();
        Map<Long, BiasAnalysisResult> analyzed = biasAnalysisResultRepository
                .findByTargetTypeAndTargetIdIn(TargetType.YOUTUBE_VIDEO, videoIds)
                .stream()
                .collect(Collectors.toMap(
                        BiasAnalysisResult::getTargetId,
                        r -> r,
                        (a, b) -> a.getCreatedAt() != null && b.getCreatedAt() != null && b.getCreatedAt().isAfter(a.getCreatedAt()) ? b : a
                ));

        Map<String, Integer> successByCountry = new LinkedHashMap<>();
        CURATION_COUNTRIES.forEach(c -> successByCountry.put(c, 0));
        for (IssueClusterItem item : items) {
            String country = normalizeCountryCode(item.getCountryCode());
            if (!CURATION_COUNTRIES.contains(country)) continue;
            if (analyzed.containsKey(item.getYoutubeVideoId())) {
                successByCountry.put(country, successByCountry.get(country) + 1);
            }
        }
        boolean ready = CURATION_COUNTRIES.stream()
                .allMatch(country -> successByCountry.getOrDefault(country, 0) >= MIN_ANALYZED_PER_COUNTRY);
        if (!ready) {
            return;
        }

        int claimed = issueClusterRepository.updateStatusIfCurrent(
                cluster.getId(),
                ClusterStatus.ANALYZING,
                ClusterStatus.READY_FOR_REPORT
        );
        if (claimed == 0) {
            return;
        }
        saveCurationComparisonResult(cluster, items, analyzed);
        log.info("curation set ready for report (clusterId={}, countrySuccess={})", cluster.getId(), successByCountry);
    }

    private void saveCurationComparisonResult(IssueCluster cluster,
                                              List<IssueClusterItem> items,
                                              Map<Long, BiasAnalysisResult> analyzed) {
        List<Long> videoIds = items.stream().map(IssueClusterItem::getYoutubeVideoId).distinct().toList();
        Map<Long, YoutubeVideo> videoMap = youtubeVideoRepository.findAllById(videoIds).stream()
                .collect(Collectors.toMap(YoutubeVideo::getId, v -> v));

        List<ComparisonResult> existingResults = comparisonResultRepository.findByIssueClusterId(cluster.getId());
        for (ComparisonResult existing : existingResults) {
            comparisonCountryItemRepository.deleteByComparisonResultId(existing.getId());
        }
        comparisonResultRepository.deleteAll(existingResults);

        ComparisonResult result = comparisonResultRepository.save(
                ComparisonResult.builder()
                        .issueCluster(cluster)
                        .keyword(cluster.getSearchKeyword())
                        .periodStart(cluster.getPeriodStartDate())
                        .periodEnd(cluster.getPeriodEndDate())
                        .snapshotJson(buildCurationSnapshot(items, videoMap, analyzed))
                        .build()
        );

        List<ComparisonCountryItem> countryItems = CURATION_COUNTRIES.stream()
                .map(country -> pickRepresentativeByCountry(country, items, videoMap, analyzed))
                .filter(Objects::nonNull)
                .map(rep -> ComparisonCountryItem.builder()
                        .comparisonResult(result)
                        .countryCode(rep.countryCode())
                        .representativeVideoId(rep.video().getId())
                        .title(rep.video().getTitle())
                        .summaryText(rep.analysis().getSummaryText())
                        .biasScore(rep.analysis().getOverallBiasScore())
                        .build())
                .toList();
        comparisonCountryItemRepository.saveAll(countryItems);
    }

    private String buildCurationSnapshot(List<IssueClusterItem> items,
                                         Map<Long, YoutubeVideo> videoMap,
                                         Map<Long, BiasAnalysisResult> analyzed) {
        List<SnapshotCountryItem> payload = items.stream()
                .filter(item -> analyzed.containsKey(item.getYoutubeVideoId()))
                .map(item -> {
                    YoutubeVideo video = videoMap.get(item.getYoutubeVideoId());
                    BiasAnalysisResult analysis = analyzed.get(item.getYoutubeVideoId());
                    return new SnapshotCountryItem(
                            normalizeCountryCode(item.getCountryCode()),
                            item.getYoutubeVideoId(),
                            video != null ? video.getTitle() : null,
                            analysis != null ? analysis.getSummaryText() : null,
                            analysis != null ? analysis.getOverallBiasScore() : null,
                            item.getRankNo()
                    );
                })
                .toList();
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("curation snapshotJson 생성 실패: {}", e.getMessage());
            return "[]";
        }
    }

    private record RepresentativeByCountry(String countryCode, YoutubeVideo video, BiasAnalysisResult analysis) {}

    private RepresentativeByCountry pickRepresentativeByCountry(String countryCode,
                                                                List<IssueClusterItem> items,
                                                                Map<Long, YoutubeVideo> videoMap,
                                                                Map<Long, BiasAnalysisResult> analyzed) {
        return items.stream()
                .filter(item -> countryCode.equals(normalizeCountryCode(item.getCountryCode())))
                .filter(item -> analyzed.containsKey(item.getYoutubeVideoId()))
                .map(item -> new RepresentativeByCountry(
                        countryCode,
                        videoMap.get(item.getYoutubeVideoId()),
                        analyzed.get(item.getYoutubeVideoId())))
                .filter(rep -> rep.video() != null && rep.analysis() != null)
                .max(Comparator.comparingLong(rep -> rep.video().getViewCount() == null ? 0L : rep.video().getViewCount()))
                .orElse(null);
    }

    // prepareComparisonInputs
    /**
     * 키워드 + 기간으로 이슈 클러스터를 찾아 후보 영상 목록 반환.
     * 분석이 완료된 영상만 포함 (BiasAnalysisResult 없는 영상 제외).
     */
    public List<IssueCandidateDto> prepareComparisonInputs(
            String keyword, List<String> countries, LocalDate start, LocalDate end) {
        Optional<IssueCluster> clusterOpt = issueClusterRepository
                .findBySearchKeywordAndPeriodStartDateAndPeriodEndDate(keyword, start, end);
        if (clusterOpt.isEmpty()) {
            return List.of();
        }
        return prepareComparisonInputsForCluster(clusterOpt.get());
    }


    /**
     * 클러스터의 영상 목록을 가져와 BiasAnalysisResult가 있는 영상만 후보로 사용한다.
     * 제목, 요약, 편향 점수, 키워드, 게시일 등을 IssueCandidateDto로 조립
     * @param cluster
     * @return
     */
    private List<IssueCandidateDto> prepareComparisonInputsForCluster(IssueCluster cluster) {
        List<IssueClusterItem> items =
                issueClusterItemRepository.findByIssueClusterId(cluster.getId());
        List<Long> videoIds = items.stream().map(IssueClusterItem::getYoutubeVideoId).toList();
        Map<Long, String> itemCountryCodeMap = items.stream()
                .collect(Collectors.toMap(
                        IssueClusterItem::getYoutubeVideoId,
                        IssueClusterItem::getCountryCode,
                        (a, b) -> a
                ));

        Map<Long, YoutubeVideo> videoMap = youtubeVideoRepository.findAllById(videoIds).stream()
                .collect(Collectors.toMap(YoutubeVideo::getId, v -> v));
        Map<Long, BiasAnalysisResult> analysisResultMap = biasAnalysisResultRepository
                .findByTargetTypeAndTargetIdIn(TargetType.YOUTUBE_VIDEO, videoIds)
                .stream()
                .collect(Collectors.toMap(
                        BiasAnalysisResult::getTargetId,
                        r -> r,
                        (a, b) -> {
                            if (a.getCreatedAt() == null) return b;
                            if (b.getCreatedAt() == null) return a;
                            return a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b;
                        }
                ));

        List<IssueCandidateDto> candidates = new ArrayList<>();
        for (Long videoId : videoIds) {
            YoutubeVideo video = videoMap.get(videoId);
            if (video == null) continue;

            BiasAnalysisResult result = analysisResultMap.get(videoId);
            if (result == null) continue; // 미분석 영상 제외
            List<String> keywords = biasAnalysisKeywordRepository
                    .findAllByBiasAnalysisResultId(result.getId())
                    .stream()
                    .map(k -> k.getKeywordText())
                    .toList();

            String countryCode = normalizeCountryCode(video.getCountryCode());
            if (countryCode == null) {
                countryCode = normalizeCountryCode(itemCountryCodeMap.get(videoId));
            }
            if (countryCode == null) {
                log.warn("countryCode가 없어 후보 제외 (clusterId={}, videoId={})", cluster.getId(), videoId);
                continue;
            }

            candidates.add(IssueCandidateDto.builder()
                    .videoId(videoId)
                    .countryCode(countryCode)
                    .title(video.getTitle())
                    .summaryText(result.getSummaryText())
                    .overallBiasScore(result.getOverallBiasScore())
                    .opinionScore(result.getOpinionScore())
                    .analysisKeywords(keywords)
                    .publishedAt(video.getPublishedAt())
                    .build());
        }
        return candidates;
    }

    /**
     * 후보 영상 목록을 cluster_score 기반으로 그룹핑. 스텁 API 호환용.
     */
    public List<IssueCluster> buildIssueClusters(List<IssueCandidateDto> candidates) {
        if (candidates.isEmpty()) return List.of();
        IssueCluster stub = IssueCluster.builder()
                .searchKeyword("")
                .status(ClusterStatus.PENDING)
                .clusterType(IssueClusterType.SEARCH_AUTO)
                .build();
        return buildClusterGroups(candidates, stub).stream()
                .map(g -> g.baseCluster)
                .toList();
    }

    private List<ClusterGroup> buildClusterGroups(List<IssueCandidateDto> candidates, IssueCluster base) {
        List<ClusterGroup> groups = new ArrayList<>();

        for (IssueCandidateDto candidate : candidates) {
            boolean assigned = false;
            for (ClusterGroup group : groups) {
                double score = computeClusterScore(
                        candidate, group.representative(), base.getSearchKeyword());
                if (score >= 0.5) {
                    group.members.add(new ScoredCandidate(candidate, score, false, 0));
                    assigned = true;
                    break;
                }
            }
            if (!assigned) {
                groups.add(new ClusterGroup(base, candidate));
            }
        }
        return groups;
    }

    /**
     * 
     * 점수 구성은 아래와 같다.
     * 키워드 겹침, 파이썬에서 /issue/cluster-similarity 호출 결과(제목/요약 유사도),
     * 게시일 근접도, 검색어 주제 일치도로 점수를 계산한다.
     * 
     * TODO 
     * 임계값이 >= 0.5면 같은 그룹, 아니면 새 그룹을 생성한다.(이건 운영 데이터를 보면서 튜닝해야 한다.)
     * -> MVP 연결 후 sameIssueThreshold, candidateThreshold 두 단계로 관리 예정
     * 
     * @param candidateDtoA
     * @param candidateDtoB
     * @param searchKeyword
     * @return
     */
    private double computeClusterScore(IssueCandidateDto candidateDtoA, IssueCandidateDto candidateDtoB, String searchKeyword) {
        double keywordOverlap = computeKeywordOverlap(candidateDtoA.getAnalysisKeywords(), candidateDtoB.getAnalysisKeywords());

        double[] similarities = callClusterSimilarity(
                candidateDtoA.getTitle(), candidateDtoB.getTitle(), candidateDtoA.getSummaryText(), candidateDtoB.getSummaryText());

        double timeProximity = computeTimeProximity(candidateDtoA.getPublishedAt(), candidateDtoB.getPublishedAt());

        double topicConsistency = (computeTopicConsistency(candidateDtoA.getAnalysisKeywords(), searchKeyword)
                + computeTopicConsistency(candidateDtoB.getAnalysisKeywords(), searchKeyword)) / 2.0;

        return 0.35 * keywordOverlap
                + 0.25 * similarities[0]
                + 0.20 * similarities[1]
                + 0.10 * timeProximity
                + 0.10 * topicConsistency;
    }

    private double computeKeywordOverlap(List<String> ka, List<String> kb) {
        if (ka == null || kb == null || ka.isEmpty() || kb.isEmpty()) return 0.0;
        Set<String> setA = new HashSet<>(ka);
        Set<String> setB = new HashSet<>(kb);
        long intersection = setA.stream().filter(setB::contains).count();
        long union = setA.size() + setB.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private double computeTimeProximity(LocalDateTime a, LocalDateTime b) {
        if (a == null || b == null) return 0.0;
        long days = Math.abs(ChronoUnit.DAYS.between(a.toLocalDate(), b.toLocalDate()));
        if (days == 0) return 1.0;
        if (days >= 14) return 0.0;
        return 1.0 - (days / 14.0);
    }

    private double computeTopicConsistency(List<String> keywords, String searchKeyword) {
        if (keywords == null || keywords.isEmpty() || searchKeyword == null) return 0.0;
        String lower = searchKeyword.toLowerCase();
        long matched = keywords.stream()
                .filter(k -> k != null && k.toLowerCase().contains(lower))
                .count();
        return (double) matched / keywords.size();
    }

    private double[] callClusterSimilarity(String titleA, String titleB,
                                            String summaryA, String summaryB) {
        try {
            ClusterSimilarityResponse response = webClient.post()
                    .uri(pythonBaseUrl + "/issue/cluster-similarity")
                    .bodyValue(new ClusterSimilarityRequest(
                            titleA != null ? titleA : "",
                            titleB != null ? titleB : "",
                            summaryA != null ? summaryA : "",
                            summaryB != null ? summaryB : ""
                    ))
                    .retrieve()
                    .bodyToMono(ClusterSimilarityResponse.class)
                    .block();
            if (response == null) return new double[]{0.0, 0.0};
            return new double[]{
                    response.title_similarity() != null ? response.title_similarity() : 0.0,
                    response.summary_similarity() != null ? response.summary_similarity() : 0.0
            };
        } catch (Exception e) {
            log.warn("Python /issue/cluster-similarity 호출 실패: {}", e.getMessage());
            return new double[]{0.0, 0.0};
        }
    }

    /**
     * 그룹 내에서 국가별로 묶은 뒤 computeRepresentativeScore로 정렬한다.
     * 각 국가별 1등을 대표로 지정 후 rankNo를 부여한다.
     * @param groups
     * @param searchKeyword
     */
    private void selectRepresentativeVideos(List<ClusterGroup> groups, String searchKeyword) {
        for (ClusterGroup group : groups) {
            Map<String, List<ScoredCandidate>> byCountry = group.members.stream()
                    .collect(Collectors.groupingBy(sc -> resolveCountryCode(sc.candidate())));

            byCountry.forEach((country, members) -> {
                if (members.isEmpty()) return;

                LocalDateTime minDate = members.stream()
                        .map(sc -> sc.candidate().getPublishedAt())
                        .filter(Objects::nonNull)
                        .min(LocalDateTime::compareTo).orElse(null);
                LocalDateTime maxDate = members.stream()
                        .map(sc -> sc.candidate().getPublishedAt())
                        .filter(Objects::nonNull)
                        .max(LocalDateTime::compareTo).orElse(null);

                List<ScoredCandidate> ranked = members.stream()
                        .sorted(Comparator.comparingDouble((ScoredCandidate sc) ->
                                computeRepresentativeScore(sc, minDate, maxDate, searchKeyword))
                                .reversed())
                        .toList();

                for (int i = 0; i < ranked.size(); i++) {
                    ScoredCandidate sc = ranked.get(i);
                    int idx = group.members.indexOf(sc);
                    if (idx >= 0) {
                        group.members.set(idx,
                                new ScoredCandidate(sc.candidate(), sc.similarityScore(), i == 0, i + 1));
                    }
                }
            });
        }
    }

    private double computeRepresentativeScore(ScoredCandidate sc,
                                               LocalDateTime minDate, LocalDateTime maxDate,
                                               String searchKeyword) {
        IssueCandidateDto c = sc.candidate();

        double recencyScore = 0.0;
        if (minDate != null && maxDate != null && c.getPublishedAt() != null) {
            long range = ChronoUnit.MINUTES.between(minDate, maxDate);
            recencyScore = range > 0
                    ? (double) ChronoUnit.MINUTES.between(minDate, c.getPublishedAt()) / range
                    : 1.0;
        }

        double summaryQuality = c.getSummaryText() != null
                ? Math.min(c.getSummaryText().length() / 200.0, 1.0)
                : 0.0;

        double sourceQuality = c.getOverallBiasScore() != null
                ? Math.max(0.0, 1.0 - c.getOverallBiasScore())
                : 0.0;

        double keywordMatch = 0.0;
        if (c.getAnalysisKeywords() != null && searchKeyword != null) {
            String lower = searchKeyword.toLowerCase();
            keywordMatch = c.getAnalysisKeywords().stream()
                    .anyMatch(k -> k != null && k.toLowerCase().contains(lower)) ? 1.0 : 0.0;
        }

        return 0.30 * sc.similarityScore()
                + 0.20 * recencyScore
                + 0.20 * summaryQuality
                + 0.15 * sourceQuality
                + 0.10 * keywordMatch;
        // duplication_penalty (0.05) — MVP 미적용, 아직 중복을 판단할 기준이 없음 그래서 일단은 가중치 계산에서 안 잡음
    }

    /**
     * 기존 IssueClusterItem은 전부 삭제 후 재저장을 한다.
     * 첫 그룹은 기존 IssueCluster 재사용 + COMPLETED
     * 나머지 그룹은 새 IssueCluster를 추가 생성해서 COMPLETED
     * 그룹 멤버를 IssueClusterItem으로 저장(similarityScore, isRepresentative, rankNo 포함)
     * @param originalCluster
     * @param groups
     */
    private void saveClusterResults(IssueCluster originalCluster, List<ClusterGroup> groups) {
        if (groups.isEmpty()) {
            issueClusterRepository.updateStatus(originalCluster.getId(), ClusterStatus.FAIL);
            return;
        }

        // 분석된 영상의 기존 아이템만 삭제 — 미분석 영상은 클러스터에 유지해 반대 영상 탐색에 활용
        Set<Long> analyzedVideoIds = groups.stream()
                .flatMap(g -> g.members.stream())
                .map(sc -> sc.candidate().getVideoId())
                .collect(Collectors.toSet());
        issueClusterItemRepository.deleteByIssueClusterIdAndYoutubeVideoIdIn(
                originalCluster.getId(), analyzedVideoIds);
        issueClusterItemRepository.flush();

        for (int gi = 0; gi < groups.size(); gi++) {
            ClusterGroup group = groups.get(gi);
            List<ScoredCandidate> uniqueMembers = deduplicateByVideoId(group.members);

            IssueCluster cluster;
            if (gi == 0) {
                // 첫 번째 그룹 → 기존 originalCluster 재사용
                issueClusterRepository.updateStatus(originalCluster.getId(), ClusterStatus.COMPLETED);
                cluster = originalCluster;
            } else {
                // 추가 그룹 → 새 IssueCluster 생성
                cluster = issueClusterRepository.save(
                        IssueCluster.builder()
                                .searchKeyword(originalCluster.getSearchKeyword())
                                .normalizedKeyword(originalCluster.getNormalizedKeyword())
                                .periodStartDate(originalCluster.getPeriodStartDate())
                                .periodEndDate(originalCluster.getPeriodEndDate())
                                .clusterLabel("group-" + (gi + 1))
                                .status(ClusterStatus.COMPLETED)
                                .clusterType(IssueClusterType.SEARCH_AUTO)
                                .build()
                );
            }

            IssueCluster finalCluster = cluster;
            for (ScoredCandidate sc : uniqueMembers) {
                issueClusterItemRepository.save(
                        IssueClusterItem.builder()
                                .issueCluster(finalCluster)
                                .youtubeVideoId(sc.candidate().getVideoId())
                                .countryCode(resolveCountryCode(sc.candidate()))
                                .similarityScore(sc.similarityScore())
                                .isRepresentative(sc.isRepresentative())
                                .rankNo(sc.rankNo())
                                .sourceType(IssueClusterItemSourceType.AUTO)
                                .build()
                );
            }

            // 그룹별 비교 결과를 저장해 /comparison 조회에서 사용한다.
            saveComparisonResult(finalCluster, uniqueMembers);
        }
    }

    private void saveComparisonResult(IssueCluster cluster, List<ScoredCandidate> members) {
        List<ComparisonResult> existingResults = comparisonResultRepository.findByIssueClusterId(cluster.getId());
        for (ComparisonResult existing : existingResults) {
            comparisonCountryItemRepository.deleteByComparisonResultId(existing.getId());
        }
        comparisonResultRepository.deleteAll(existingResults);

        String snapshotJson = buildSnapshotJson(members);
        ComparisonResult savedResult = comparisonResultRepository.save(
                ComparisonResult.builder()
                        .issueCluster(cluster)
                        .keyword(cluster.getSearchKeyword())
                        .periodStart(cluster.getPeriodStartDate())
                        .periodEnd(cluster.getPeriodEndDate())
                        .snapshotJson(snapshotJson)
                        .build()
        );

        Map<String, List<ScoredCandidate>> byCountry = members.stream()
                .collect(Collectors.groupingBy(sc -> resolveCountryCode(sc.candidate())));

        List<ComparisonCountryItem> countryItems = byCountry.values().stream()
                .map(this::pickRepresentative)
                .map(sc -> ComparisonCountryItem.builder()
                        .comparisonResult(savedResult)
                        .countryCode(resolveCountryCode(sc.candidate()))
                        .representativeVideoId(sc.candidate().getVideoId())
                        .title(sc.candidate().getTitle())
                        .summaryText(sc.candidate().getSummaryText())
                        .biasScore(sc.candidate().getOverallBiasScore())
                        .build())
                .toList();

        comparisonCountryItemRepository.saveAll(countryItems);
    }

    private ScoredCandidate pickRepresentative(List<ScoredCandidate> members) {
        return members.stream()
                .filter(ScoredCandidate::isRepresentative)
                .findFirst()
                .orElseGet(() -> members.stream()
                        .sorted(Comparator.comparingInt(ScoredCandidate::rankNo))
                        .findFirst()
                        .orElse(members.get(0)));
    }

    private String buildSnapshotJson(List<ScoredCandidate> members) {
        List<SnapshotCountryItem> payload = members.stream()
                .map(sc -> new SnapshotCountryItem(
                        resolveCountryCode(sc.candidate()),
                        sc.candidate().getVideoId(),
                        sc.candidate().getTitle(),
                        sc.candidate().getSummaryText(),
                        sc.candidate().getOverallBiasScore(),
                        sc.rankNo()
                ))
                .toList();
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("comparison snapshotJson 생성 실패: {}", e.getMessage());
            return "[]";
        }
    }

    private String normalizeCountryCode(String rawCountryCode) {
        if (rawCountryCode == null) {
            return null;
        }
        String normalized = rawCountryCode.trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private String resolveCountryCode(IssueCandidateDto candidate) {
        String normalized = normalizeCountryCode(candidate.getCountryCode());
        return normalized != null ? normalized : UNKNOWN_COUNTRY_CODE;
    }

    private List<ScoredCandidate> deduplicateByVideoId(List<ScoredCandidate> members) {
        Map<Long, ScoredCandidate> byVideoId = new LinkedHashMap<>();
        for (ScoredCandidate member : members) {
            Long videoId = member.candidate().getVideoId();
            ScoredCandidate existing = byVideoId.get(videoId);
            if (existing == null || member.similarityScore() > existing.similarityScore()) {
                byVideoId.put(videoId, member);
            }
        }
        return new ArrayList<>(byVideoId.values());
    }
}
