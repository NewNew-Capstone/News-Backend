package com.example.news.domain.comparison.controller;

import com.example.news.domain.comparison.dto.CompareOnClickRequest;
import com.example.news.domain.comparison.dto.CompareOnClickResponse;
import com.example.news.domain.comparison.service.CompareOnClickService;
import com.example.news.domain.content.dto.YoutubeVideoDto;
import com.example.news.global.config.SecurityConfig;
import com.example.news.global.jwt.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = CompareOnClickController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class, JwtAuthenticationFilter.class}
        )
)
class CompareOnClickControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean JpaMetamodelMappingContext jpaMetamodelMappingContext;
    @MockBean CompareOnClickService compareOnClickService;

    @Test
    void compareOnClick_returnsCountrySections() throws Exception {
        CompareOnClickResponse response = CompareOnClickResponse.builder()
                .sourceCountryCode("KR")
                .searchKeyword("트럼프 대만")
                .sections(List.of(
                        CompareOnClickResponse.CountryVideoSection.builder()
                                .countryCode("US")
                                .countryName("미국")
                                .languageCode("en")
                                .videos(List.of(
                                        YoutubeVideoDto.VideoCard.builder()
                                                .youtubeVideoId("us1")
                                                .title("미국 제목")
                                                .countryCode("US")
                                                .build()
                                ))
                                .build()
                ))
                .build();
        when(compareOnClickService.compareOnClick(any(CompareOnClickRequest.class), eq(3)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/comparison/compare-on-click")
                        .contentType("application/json")
                        .content("""
                                {"youtubeVideoId":"source1","title":"트럼프 대만","defaultLanguageCode":"ko"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.sourceCountryCode").value("KR"))
                .andExpect(jsonPath("$.body.sections[0].countryCode").value("US"))
                .andExpect(jsonPath("$.body.sections[0].videos[0].title").value("미국 제목"));
    }

    @Test
    void compareOnClick_returns400_whenYoutubeVideoIdMissing() throws Exception {
        mockMvc.perform(post("/api/v1/comparison/compare-on-click")
                        .contentType("application/json")
                        .content("""
                                {"title":"트럼프 대만"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status.statusCode").value("C007"));
    }

    @Test
    void countryRecommendations_returnsThreeCountrySections() throws Exception {
        CompareOnClickResponse response = CompareOnClickResponse.builder()
                .searchKeyword("트럼프 대만")
                .sections(List.of(
                        CompareOnClickResponse.CountryVideoSection.builder()
                                .countryCode("KR")
                                .countryName("한국")
                                .languageCode("ko")
                                .videos(List.of())
                                .build(),
                        CompareOnClickResponse.CountryVideoSection.builder()
                                .countryCode("US")
                                .countryName("미국")
                                .languageCode("en")
                                .videos(List.of())
                                .build(),
                        CompareOnClickResponse.CountryVideoSection.builder()
                                .countryCode("CN")
                                .countryName("중국")
                                .languageCode("zh")
                                .videos(List.of())
                                .build()
                ))
                .build();
        when(compareOnClickService.recommendByKeyword("트럼프 대만", 3)).thenReturn(response);

        mockMvc.perform(get("/api/v1/comparison/country-recommendations")
                        .param("keyword", "트럼프 대만"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.searchKeyword").value("트럼프 대만"))
                .andExpect(jsonPath("$.body.sections[0].countryCode").value("KR"))
                .andExpect(jsonPath("$.body.sections[1].countryCode").value("US"))
                .andExpect(jsonPath("$.body.sections[2].countryCode").value("CN"));
    }
}
