package com.example.news.domain.content.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Slf4j
@Service
@RequiredArgsConstructor
public class TitleTranslationService {

    private static final String TRANSLATE_URL = "https://translation.googleapis.com/language/translate/v2";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${google.translate.api-key}")
    private String apiKey;

    public String translateFromKorean(String text, String targetLang) {
        if (text == null || text.isBlank()) return text;
        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(TRANSLATE_URL)
                    .queryParam("q", text)
                    .queryParam("source", "ko")
                    .queryParam("target", targetLang)
                    .encode()
                    .build()
                    .toUri();
            String response = restTemplate.getForObject(uri, String.class);
            JsonNode root = objectMapper.readTree(response);
            String translated = root.path("data")
                    .path("translations")
                    .get(0)
                    .path("translatedText")
                    .asText();
            return HtmlUtils.htmlUnescape(translated);
        } catch (Exception e) {
            log.warn("키워드 번역 실패, 원본 유지: {}", e.getMessage());
            return text;
        }
    }

    public String translateToKorean(String text) {
        if (text == null || text.isBlank()) return text;

        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(TRANSLATE_URL)
                    .queryParam("q", text)
                    .queryParam("target", "ko")
                    .queryParam("key", apiKey)
                    .encode()
                    .build()
                    .toUri();
            String response = restTemplate.getForObject(uri, String.class);
            JsonNode root = objectMapper.readTree(response);
            String translated = root.path("data")
                    .path("translations")
                    .get(0)
                    .path("translatedText")
                    .asText();
            return HtmlUtils.htmlUnescape(translated);
        } catch (Exception e) {
            log.warn("제목 번역 실패, 원본 유지: {}", e.getMessage());
            return text;
        }
    }
}
