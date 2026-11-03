package org.monitoring.catchholebackend.domain.relationship.repository;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.AnalysisStatus;

/** Deliberately excludes frozen context and all large AnalysisJob input/journal columns. */
public record RelationshipHoldReviewRow(UUID analysisId, int episodeNo, AnalysisStatus status,
        String completionHash, LocalDateTime updatedAt, JsonNode decisions, JsonNode bindings,
        AnalysisJobStatus jobStatus, boolean sourceAvailable) {}
