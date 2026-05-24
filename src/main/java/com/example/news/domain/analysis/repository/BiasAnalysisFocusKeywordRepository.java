package com.example.news.domain.analysis.repository;

import com.example.news.domain.analysis.entity.BiasAnalysisFocusKeyword;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BiasAnalysisFocusKeywordRepository extends JpaRepository<BiasAnalysisFocusKeyword, Long> {
    List<BiasAnalysisFocusKeyword> findAllByBiasAnalysisResultId(Long biasAnalysisResultId);
}
