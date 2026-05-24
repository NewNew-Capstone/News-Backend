package com.example.news.domain.comparison.service;

import com.example.news.domain.comparison.dto.CompareOnClickRequest;
import com.example.news.domain.comparison.dto.CompareOnClickResponse;
import com.example.news.domain.comparison.dto.collect.MultilingualKeywordExpandResponse;
import com.example.news.domain.content.dto.YoutubeVideoDto;
import com.example.news.domain.content.entity.YoutubeVideo;
import com.example.news.domain.content.service.TitleTranslationService;
import com.example.news.domain.content.service.YoutubeSearchService;
import com.example.news.domain.content.service.YoutubeVideoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompareOnClickServiceTest {

    @Mock YoutubeVideoService youtubeVideoService;
    @Mock YoutubeSearchService youtubeSearchService;
    @Mock TitleTranslationService titleTranslationService;
    @Mock ComparisonProxyService comparisonProxyService;

    @InjectMocks CompareOnClickService compareOnClickService;

    @Test
    void compareOnClick_returnsOtherTwoCountrySectionsWithKoreanTitles() {
        when(youtubeVideoService.getOrFetchVideoEntity("source1")).thenReturn(
                YoutubeVideo.builder()
                        .id(1L)
                        .youtubeVideoId("source1")
                        .title("트럼프 대만")
                        .defaultLanguageCode("ko")
                        .build()
        );
        when(comparisonProxyService.expandMultilingualKeywords("트럼프 대만"))
                .thenReturn(new MultilingualKeywordExpandResponse(
                        "트럼프 대만",
                        new MultilingualKeywordExpandResponse.ExpandedKeywords(
                                List.of("트럼프 대만"),
                                List.of("trump taiwan"),
                                List.of("特朗普 台湾")
                        )
                ));
        when(youtubeSearchService.searchByRegion(
                eq("trump taiwan"),
                eq("US"),
                eq("en"),
                isNull(LocalDateTime.class),
                eq(6)
        )).thenReturn(List.of(
                YoutubeVideoDto.VideoCard.builder()
                        .youtubeVideoId("us1")
                        .title("US title")
                        .channelName("US News")
                        .build()
        ));
        when(youtubeSearchService.searchByRegion(
                eq("特朗普 台湾"),
                eq("CN"),
                eq("zh"),
                isNull(LocalDateTime.class),
                eq(6)
        )).thenReturn(List.of(
                YoutubeVideoDto.VideoCard.builder()
                        .youtubeVideoId("cn1")
                        .title("CN title")
                        .channelName("CN News")
                        .build()
        ));
        when(titleTranslationService.translateToKorean("US title")).thenReturn("미국 제목");
        when(titleTranslationService.translateToKorean("CN title")).thenReturn("중국 제목");

        CompareOnClickResponse response = compareOnClickService.compareOnClick(
                new CompareOnClickRequest(
                        "source1",
                        null,
                        "트럼프 대만 #뉴스",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "ko",
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                3
        );

        assertThat(response.sourceCountryCode()).isEqualTo("KR");
        assertThat(response.searchKeyword()).isEqualTo("트럼프 대만");
        assertThat(response.sections()).hasSize(2);
        assertThat(response.sections()).extracting(CompareOnClickResponse.CountryVideoSection::countryCode)
                .containsExactly("US", "CN");
        assertThat(response.sections().get(0).videos().get(0).getTitle()).isEqualTo("미국 제목");
        assertThat(response.sections().get(0).videos().get(0).getCountryCode()).isEqualTo("US");
        assertThat(response.sections().get(1).videos().get(0).getTitle()).isEqualTo("중국 제목");
        assertThat(response.sections().get(1).videos().get(0).getCountryCode()).isEqualTo("CN");
    }

    @Test
    void recommendByKeyword_returnsAllThreeCountrySections() {
        when(comparisonProxyService.expandMultilingualKeywords("트럼프 대만"))
                .thenReturn(new MultilingualKeywordExpandResponse(
                        "트럼프 대만",
                        new MultilingualKeywordExpandResponse.ExpandedKeywords(
                                List.of("트럼프 대만"),
                                List.of("trump taiwan"),
                                List.of("特朗普 台湾")
                        )
                ));
        when(youtubeSearchService.searchByRegion(
                eq("트럼프 대만"),
                eq("KR"),
                eq("ko"),
                isNull(LocalDateTime.class),
                eq(6)
        )).thenReturn(List.of(
                YoutubeVideoDto.VideoCard.builder()
                        .youtubeVideoId("kr1")
                        .title("한국 제목")
                        .build()
        ));
        when(youtubeSearchService.searchByRegion(
                eq("trump taiwan"),
                eq("US"),
                eq("en"),
                isNull(LocalDateTime.class),
                eq(6)
        )).thenReturn(List.of(
                YoutubeVideoDto.VideoCard.builder()
                        .youtubeVideoId("us1")
                        .title("US title")
                        .build()
        ));
        when(youtubeSearchService.searchByRegion(
                eq("特朗普 台湾"),
                eq("CN"),
                eq("zh"),
                isNull(LocalDateTime.class),
                eq(6)
        )).thenReturn(List.of(
                YoutubeVideoDto.VideoCard.builder()
                        .youtubeVideoId("cn1")
                        .title("CN title")
                        .build()
        ));
        when(titleTranslationService.translateToKorean("US title")).thenReturn("미국 제목");
        when(titleTranslationService.translateToKorean("CN title")).thenReturn("중국 제목");

        CompareOnClickResponse response = compareOnClickService.recommendByKeyword("트럼프 대만", 3);

        assertThat(response.searchKeyword()).isEqualTo("트럼프 대만");
        assertThat(response.sections()).hasSize(3);
        assertThat(response.sections()).extracting(CompareOnClickResponse.CountryVideoSection::countryCode)
                .containsExactly("KR", "US", "CN");
        assertThat(response.sections().get(0).videos().get(0).getTitle()).isEqualTo("한국 제목");
        assertThat(response.sections().get(1).videos().get(0).getTitle()).isEqualTo("미국 제목");
        assertThat(response.sections().get(2).videos().get(0).getTitle()).isEqualTo("중국 제목");
    }
}
