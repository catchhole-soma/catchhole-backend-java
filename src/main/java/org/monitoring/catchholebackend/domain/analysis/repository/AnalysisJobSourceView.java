package org.monitoring.catchholebackend.domain.analysis.repository;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind;

/** Scalar source/state view: no managed Job, input state or journal is materialized. */
public interface AnalysisJobSourceView {
    UUID getId();
    UUID getWorkId();
    AnalysisJobType getJobType();
    AnalysisJobStatus getStatus();
    AnalysisMode getAnalysisMode();
    AnalysisJournalStatus getJournalStatus();
    AnalysisReviewMode getReviewMode();
    LocalDateTime getAutomaticAppliedAt();
    LocalDateTime getReviewApplicationHeldAt();
    UUID getAnalysisRunId();
    Long getRunGeneration();
    Integer getRunSequence();
    UUID getPredecessorJobId();
    AnalysisSourceKind getSourceKind();
    Integer getSourceEpisodeNo();
    String getSourceContentHash();
    String getSourceContentS3Key();
    String getSourceContentS3Version();
    UUID getEpisodeId();
    UUID getEpisodeWorkId();
    Integer getEpisodeNo();
    EpisodeStatus getEpisodeStatus();
    String getEpisodeContentHash();
    String getEpisodeContentS3Key();
    String getEpisodeContentS3Version();
    UUID getSourceSettingBookId();
    String getSourceSettingBookName();
    UUID getSourceSettingBookWorkId();
    LocalDateTime getSettingBookArchivedAt();
    UUID getCurrentSettingBookRevisionId();
    UUID getSourceSettingBookRevisionId();
    UUID getRevisionSettingBookId();
    Integer getSourceSettingBookRevisionNo();
    SettingBookReferenceTimeKind getSourceReferenceTimeKind();
    Integer getSourceReferenceEpisodeNo();
    String getRevisionContentHash();
    String getRevisionContentS3Key();
    String getRevisionContentS3Version();

    default boolean isSettingBookSource() { return getSourceKind() == AnalysisSourceKind.SETTING_BOOK; }
    default boolean isOrderedProvisional() { return getAnalysisMode() == AnalysisMode.ORDERED_PROVISIONAL; }
    default boolean isAutomaticReview() { return getReviewMode() == AnalysisReviewMode.AUTOMATIC; }

    default boolean isCompletedOrderedAnalysis() {
        return isOrderedProvisional() && getStatus() == AnalysisJobStatus.SUCCEEDED
                && getJournalStatus() == AnalysisJournalStatus.SEALED
                && (!isAutomaticReview() || getAutomaticAppliedAt() != null || getReviewApplicationHeldAt() != null);
    }

    /** Same source-version contract as AnalysisJob; confirmed-only episode jobs retain its legacy behavior. */
    default boolean hasCurrentSourceVersion() {
        if (isSettingBookSource()) {
            return getSourceSettingBookId() != null && getSourceSettingBookRevisionId() != null
                    && getSettingBookArchivedAt() == null && getCurrentSettingBookRevisionId() != null
                    && Objects.equals(getSourceSettingBookRevisionId(), getCurrentSettingBookRevisionId())
                    && Objects.equals(getSourceContentHash(), getRevisionContentHash())
                    && Objects.equals(getSourceContentS3Key(), getRevisionContentS3Key())
                    && Objects.equals(getSourceContentS3Version(), getRevisionContentS3Version());
        }
        return !isOrderedProvisional() || episodeSourceMatches();
    }

    /** Advisory references require a retained episode source in either analysis mode. */
    default boolean hasCurrentReferenceSource() {
        if (isSettingBookSource()) return hasCurrentSourceVersion();
        return getSourceContentHash() != null && !getSourceContentHash().isBlank()
                && getSourceContentS3Key() != null && !getSourceContentS3Key().isBlank()
                && episodeSourceMatches();
    }

    private boolean episodeSourceMatches() {
        return getEpisodeId() != null && getEpisodeStatus() != EpisodeStatus.ARCHIVED
                && Objects.equals(getSourceEpisodeNo(), getEpisodeNo())
                && Objects.equals(getSourceContentHash(), getEpisodeContentHash())
                && Objects.equals(getSourceContentS3Key(), getEpisodeContentS3Key())
                && Objects.equals(getSourceContentS3Version(), getEpisodeContentS3Version());
    }
}
