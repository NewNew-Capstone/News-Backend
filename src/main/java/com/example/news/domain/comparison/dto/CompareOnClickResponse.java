package com.example.news.domain.comparison.dto;

import com.example.news.domain.content.dto.YoutubeVideoDto;
import lombok.Builder;

import java.util.List;

@Builder
public record CompareOnClickResponse(
        YoutubeVideoDto.VideoDetail selectedVideo,
        String sourceCountryCode,
        String searchKeyword,
        List<CountryVideoSection> sections
) {
    @Builder
    public record CountryVideoSection(
            String countryCode,
            String countryName,
            String languageCode,
            List<YoutubeVideoDto.VideoCard> videos
    ) {
    }
}
