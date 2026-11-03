package org.monitoring.catchholebackend.domain.analysis.repository;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisReviewContinuation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisReviewContinuationRepository extends JpaRepository<AnalysisReviewContinuation, UUID> {}
