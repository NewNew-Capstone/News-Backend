package com.example.news.domain.analysis.util;

import java.util.List;

public final class SummaryTextSanitizer {

    private static final List<String> SUMMARY_FALLBACK_PREFIXES = List.of(
            "영상 요약이 아직 생성되지 않았습니다",
            "[LLM 안 됨]",
            "[LLM 안 탐]"
    );

    private SummaryTextSanitizer() {
    }

    public static String clean(String summaryText) {
        if (summaryText == null || summaryText.trim().isEmpty()) {
            return "";
        }
        return isFallback(summaryText) ? "" : summaryText;
    }

    public static boolean hasRealSummary(String summaryText) {
        return summaryText != null && !summaryText.trim().isEmpty() && !isFallback(summaryText);
    }

    public static boolean isFallback(String summaryText) {
        if (summaryText == null || summaryText.trim().isEmpty()) {
            return false;
        }
        String normalized = summaryText.trim();
        return SUMMARY_FALLBACK_PREFIXES.stream().anyMatch(normalized::startsWith);
    }
}
