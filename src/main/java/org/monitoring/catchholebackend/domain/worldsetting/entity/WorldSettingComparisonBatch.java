package org.monitoring.catchholebackend.domain.worldsetting.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonBatchStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

/** Worker 호출 한 번이 원자적으로 처리하는 세계관 후보 묶음이다. */
@Getter
@Entity
@Table(
        name = "world_setting_comparison_batches",
        indexes = @Index(
                name = "idx_world_setting_comparison_batches_job_status",
                columnList = "analysis_job_id,status,created_at,id"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldSettingComparisonBatch extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "work_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_world_setting_comparison_batches_work")
    )
    private Work work;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "source_episode_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_world_setting_comparison_batches_episode")
    )
    private Episode sourceEpisode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "analysis_job_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_world_setting_comparison_batches_job")
    )
    private AnalysisJob analysisJob;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, updatable = false, length = 40)
    private WorldSettingCategory category;

    @Column(name = "raw_scope_name", updatable = false, length = 100)
    private String rawScopeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_resolution_type", nullable = false, updatable = false, length = 20)
    private WorldSettingSubjectResolutionType subjectResolutionType;

    @Column(name = "canonical_subject_key", nullable = false, updatable = false, length = 150)
    private String canonicalSubjectKey;

    @Column(name = "canonical_subject_name", nullable = false, updatable = false, length = 100)
    private String canonicalSubjectName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "resolved_target_world_setting_ids",
            nullable = false,
            updatable = false,
            columnDefinition = "jsonb"
    )
    private JsonNode resolvedTargetWorldSettingIds;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private WorldSettingComparisonBatchStatus status;

    @Column(name = "candidate_count", nullable = false, updatable = false)
    private int candidateCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_snapshot_json", columnDefinition = "jsonb")
    private JsonNode contextSnapshotJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_completion_json", columnDefinition = "jsonb")
    private JsonNode rawCompletionJson;

    @Column(name = "completion_hash", length = 64)
    private String completionHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 60)
    private AnalysisFailureCode failureCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    private WorldSettingComparisonBatch(
            Work work,
            Episode sourceEpisode,
            AnalysisJob analysisJob,
            WorldSettingCategory category,
            String rawScopeName,
            WorldSettingSubjectResolutionType subjectResolutionType,
            String canonicalSubjectKey,
            String canonicalSubjectName,
            JsonNode resolvedTargetWorldSettingIds,
            int candidateCount
    ) {
        if (candidateCount < 1) {
            throw new IllegalArgumentException("candidateCount must be positive.");
        }
        this.work = Objects.requireNonNull(work);
        this.sourceEpisode = Objects.requireNonNull(sourceEpisode);
        this.analysisJob = Objects.requireNonNull(analysisJob);
        this.category = Objects.requireNonNull(category);
        this.rawScopeName = rawScopeName;
        this.subjectResolutionType = Objects.requireNonNull(subjectResolutionType);
        this.canonicalSubjectKey = Objects.requireNonNull(canonicalSubjectKey);
        this.canonicalSubjectName = Objects.requireNonNull(canonicalSubjectName);
        this.resolvedTargetWorldSettingIds = Objects.requireNonNull(
                resolvedTargetWorldSettingIds
        ).deepCopy();
        if (!resolvedTargetWorldSettingIds.isArray()) {
            throw new IllegalArgumentException("resolvedTargetWorldSettingIds must be an array.");
        }
        int targetCount = resolvedTargetWorldSettingIds.size();
        if ((subjectResolutionType == WorldSettingSubjectResolutionType.NEW
                && targetCount != 0)
                || (subjectResolutionType == WorldSettingSubjectResolutionType.EXISTING
                && targetCount != 1)
                || (subjectResolutionType == WorldSettingSubjectResolutionType.AMBIGUOUS
                && targetCount < 2)) {
            throw new IllegalArgumentException(
                    "resolved target count does not match subjectResolutionType."
            );
        }
        this.candidateCount = candidateCount;
        this.status = WorldSettingComparisonBatchStatus.PROCESSING;
    }

    public static WorldSettingComparisonBatch create(
            Work work,
            Episode sourceEpisode,
            AnalysisJob analysisJob,
            WorldSettingCategory category,
            String rawScopeName,
            WorldSettingSubjectResolutionType subjectResolutionType,
            String canonicalSubjectKey,
            String canonicalSubjectName,
            JsonNode resolvedTargetWorldSettingIds,
            int candidateCount
    ) {
        return new WorldSettingComparisonBatch(
                work,
                sourceEpisode,
                analysisJob,
                category,
                rawScopeName,
                subjectResolutionType,
                canonicalSubjectKey,
                canonicalSubjectName,
                resolvedTargetWorldSettingIds,
                candidateCount
        );
    }

    public void recordContext(JsonNode contextSnapshotJson) {
        requireProcessing();
        this.contextSnapshotJson = Objects.requireNonNull(contextSnapshotJson);
    }

    public void complete(String completionHash, JsonNode rawCompletionJson) {
        requireProcessing();
        this.status = WorldSettingComparisonBatchStatus.COMPLETED;
        this.completionHash = Objects.requireNonNull(completionHash);
        this.rawCompletionJson = rawCompletionJson;
        this.failureCode = null;
        this.errorMessage = null;
    }

    public void requireReview(JsonNode rawCompletionJson) {
        requireProcessing();
        this.status = WorldSettingComparisonBatchStatus.REVIEW_REQUIRED;
        this.rawCompletionJson = rawCompletionJson;
    }

    public void fail(AnalysisFailureCode failureCode, String errorMessage) {
        requireProcessing();
        this.status = WorldSettingComparisonBatchStatus.FAILED;
        this.failureCode = AnalysisFailureCode.orUnexpected(failureCode);
        this.errorMessage = Objects.requireNonNull(errorMessage);
    }

    public boolean isProcessing() {
        return status == WorldSettingComparisonBatchStatus.PROCESSING;
    }

    public boolean isCompletedWith(String requestHash) {
        return status == WorldSettingComparisonBatchStatus.COMPLETED
                && Objects.equals(completionHash, requestHash);
    }

    private void requireProcessing() {
        if (!isProcessing()) {
            throw new IllegalStateException("Comparison batch is not processing.");
        }
    }
}
