package org.monitoring.catchholebackend.domain.analysis.repository;

import java.time.LocalDateTime;
import java.util.UUID;

public interface AnalysisBatchPageRow {

    UUID getBatchId();

    org.monitoring.catchholebackend.domain.upload.type.UploadType getUploadType();

    LocalDateTime getFirstRequestedAt();

    LocalDateTime getLastRequestedAt();
}
