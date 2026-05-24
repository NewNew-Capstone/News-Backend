package com.example.news.domain.analysis.controller;

import com.example.news.domain.analysis.entity.AnalysisJob;
import com.example.news.domain.analysis.enums.JobStatus;
import com.example.news.domain.analysis.enums.JobType;
import com.example.news.domain.analysis.enums.TargetType;
import com.example.news.domain.analysis.service.AnalysisService;
import com.example.news.domain.analysis.service.BiasAnalysisResultService;
import com.example.news.domain.content.entity.YoutubeTranscript;
import com.example.news.domain.content.entity.YoutubeVideo;
import com.example.news.domain.content.service.YoutubeTranscriptService;
import com.example.news.global.config.SecurityConfig;
import com.example.news.global.jwt.JwtAuthenticationFilter;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Security는 이 테스트의 관심사가 아니므로 SecurityConfig와 Security 자동설정을 모두 제외
@WebMvcTest(
        controllers = AnalysisController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class, JwtAuthenticationFilter.class}
        )
)
class AnalysisControllerTest {

    @Autowired
    MockMvc mockMvc;

    // @EnableJpaAuditing이 @SpringBootApplication에 있어 @WebMvcTest 시 JPA 컨텍스트 없이 실패 → mock으로 해결
    @MockBean
    JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @MockBean
    AnalysisService analysisService;

    @MockBean
    BiasAnalysisResultService biasAnalysisResultService;

    @MockBean
    YoutubeTranscriptService youtubeTranscriptService;

    @Test
    void analyze_returns200_andJobIdStatus() throws Exception {
        // given
        YoutubeTranscript transcript = transcript();
        AnalysisJob job = AnalysisJob.builder()
                .targetId(10L)
                .targetType(TargetType.YOUTUBE_VIDEO)
                .jobType(JobType.VIDEO_BIAS_ANALYSIS)
                .status(JobStatus.SUCCESS)
                .build();
        when(youtubeTranscriptService.getOrFetchTranscriptEntity("abc123", true)).thenReturn(transcript);
        when(analysisService.getOrCreateAnalysisJob(any(YoutubeTranscript.class))).thenReturn(job);

        // when & then
        mockMvc.perform(post("/api/v1/analysis/analyze/abc123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.status").value("SUCCESS"));
    }

    @Test
    void analyze_returns200_whenJobFailed() throws Exception {
        // given — Python 호출 실패 시에도 job은 FAILED 상태로 정상 반환 (예외 throw 아님)
        YoutubeTranscript transcript = transcript();
        AnalysisJob job = AnalysisJob.builder()
                .targetId(10L)
                .targetType(TargetType.YOUTUBE_VIDEO)
                .jobType(JobType.VIDEO_BIAS_ANALYSIS)
                .status(JobStatus.FAILED)
                .build();
        when(youtubeTranscriptService.getOrFetchTranscriptEntity("abc123", true)).thenReturn(transcript);
        when(analysisService.getOrCreateAnalysisJob(any(YoutubeTranscript.class))).thenReturn(job);

        mockMvc.perform(post("/api/v1/analysis/analyze/abc123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.status").value("FAILED"));
    }

    private YoutubeTranscript transcript() {
        YoutubeVideo video = YoutubeVideo.builder()
                .id(10L)
                .youtubeVideoId("abc123")
                .title("테스트 제목")
                .countryCode("KR")
                .build();
        return YoutubeTranscript.builder()
                .id(1L)
                .youtubeVideo(video)
                .languageCode("ko")
                .transcriptText("테스트 자막")
                .build();
    }
}
