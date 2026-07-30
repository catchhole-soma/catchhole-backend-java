package org.monitoring.catchholebackend.domain.analysis.repository;

import java.time.LocalDateTime;
import java.util.UUID;

public interface AnalysisBatchPageRow {

    UUID getBatchId();

    LocalDateTime getFirstRequestedAt();

    LocalDateTime getLastRequestedAt();
}
