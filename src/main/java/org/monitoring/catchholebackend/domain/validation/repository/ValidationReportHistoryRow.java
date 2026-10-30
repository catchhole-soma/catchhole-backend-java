package org.monitoring.catchholebackend.domain.validation.repository;

import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.validation.type.*;

/** GH312 S4 이력 한 행. report·실행·두 receipt·release Job의 scalar 값만 읽으며 seed/entries/payload/근거는 읽지 않는다. */
public record ValidationReportHistoryRow(UUID reportId, long revision, boolean current, ValidationReportMode mode, ValidationReportStatus status,
        ValidationPreparationStatus baselineStatus, LocalDateTime requestedAt, ValidationExecutionStatus execution, UUID completionId,
        ValidationReviewTrigger trigger, LocalDateTime completedAt, AnalysisJobStatus extractionStatus, AnalysisJournalStatus extractionJournal,
        ValidationApplicationStatus application, LocalDateTime appliedAt, String releaseHoldCode, LocalDateTime releaseHoldAt,
        LocalDateTime invalidatedAt, UUID previousReportId) {}
