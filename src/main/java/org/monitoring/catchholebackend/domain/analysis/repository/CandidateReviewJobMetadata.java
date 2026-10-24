package org.monitoring.catchholebackend.domain.analysis.repository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisSourceResponse;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisReviewState;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind;

/** 목록 요청이 필요로 하는 원본·비교 Job의 scalar 값만 보존한다. */
public record CandidateReviewJobMetadata(UUID id, UUID workId, UUID batchId, AnalysisJobType jobType,
        AnalysisMode analysisMode, AnalysisReviewMode reviewMode, AnalysisJobStatus status,
        AnalysisJournalStatus journalStatus, LocalDateTime automaticAppliedAt, AnalysisSourceKind sourceKind,
        UUID episodeId, Integer sourceEpisodeNo, Integer episodeNo, UUID settingBookId, String settingBookName,
        UUID revisionId, Integer revisionNo, String contentHash, SettingBookReferenceTimeKind referenceTimeKind,
        Integer referenceEpisodeNo, String characterComparisonInputHash, boolean currentSourceVersion, boolean latestOrigin) {
    public AnalysisReviewState reviewState() {
        return new AnalysisReviewState(analysisMode, reviewMode, status, journalStatus, automaticAppliedAt, currentSourceVersion);
    }
    public boolean settingBookSource() { return sourceKind == AnalysisSourceKind.SETTING_BOOK; }
    public boolean actionable() { return !settingBookSource() || currentSourceVersion && latestOrigin; }
    public AnalysisSourceResponse sourceResponse() {
        return settingBookSource()
                ? new AnalysisSourceResponse(sourceKind, null, null, settingBookId, settingBookName, revisionId, revisionNo,
                    contentHash, referenceTimeKind, referenceEpisodeNo)
                : new AnalysisSourceResponse(AnalysisSourceKind.EPISODE, episodeId, sourceEpisodeNo, null, null, null, null, contentHash);
    }
    public static CandidateReviewJobMetadata require(Map<UUID, CandidateReviewJobMetadata> metadata, UUID id) {
        return Objects.requireNonNull(metadata.get(id), "검토 Job 메타데이터가 없습니다: " + id);
    }
}
