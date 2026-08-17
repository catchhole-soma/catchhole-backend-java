package org.monitoring.catchholebackend.domain.analysis.repository;

import java.util.UUID;

public interface AnalysisBatchActiveComparisonCounts {

    UUID getBatchId();

    long getActiveComparisonCount();
}
