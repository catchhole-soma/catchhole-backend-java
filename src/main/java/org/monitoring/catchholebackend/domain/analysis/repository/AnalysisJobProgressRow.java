package org.monitoring.catchholebackend.domain.analysis.repository;

import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;

public record AnalysisJobProgressRow(
        UUID id, UUID episodeId, AnalysisJobType jobType, AnalysisJobStatus status,
        AnalysisReviewMode reviewMode, LocalDateTime automaticAppliedAt, AnalysisFailureCode failureCode,
        AnalysisMode analysisMode, UUID analysisRunId, Long runGeneration, Integer runSequence,
        UUID predecessorJobId, AnalysisJournalStatus journalStatus, String journalInvalidationReason,
        AnalysisJobCheckpointStage checkpointStage
) {}
