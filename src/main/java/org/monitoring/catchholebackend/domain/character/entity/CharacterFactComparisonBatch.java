package org.monitoring.catchholebackend.domain.character.entity;

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
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonBatchStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

/** 동일 분석 Job·캐릭터·FactType 후보를 Worker 호출 한 번으로 처리하는 묶음이다. */
@Getter
@Entity
@Table(
        name = "character_fact_comparison_batches",
        indexes = @Index(
                name = "idx_character_fact_comparison_batches_job_status",
                columnList = "analysis_job_id,status,created_at,id"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterFactComparisonBatch extends BaseEntity {

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
            foreignKey = @ForeignKey(name = "fk_character_fact_comparison_batches_work")
    )
    private Work work;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "source_episode_id",
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_character_fact_comparison_batches_episode")
    )
    private Episode sourceEpisode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "analysis_job_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_character_fact_comparison_batches_job")
    )
    private AnalysisJob analysisJob;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "matched_character_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_character_fact_comparison_batches_character")
    )
    private WorkCharacter matchedCharacter;

    @Enumerated(EnumType.STRING)
    @Column(name = "canonical_fact_type", nullable = false, updatable = false, length = 30)
    private CharacterFactType canonicalFactType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private CharacterFactComparisonBatchStatus status;

    @Column(name = "candidate_count", nullable = false, updatable = false)
    private int candidateCount;

    @Column(name = "base_snapshot_version", nullable = false)
    private long baseSnapshotVersion;

    @Column(name = "context_hash", length = 64)
    private String contextHash;

    @Column(name = "completion_hash", length = 64)
    private String completionHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_completion_json", columnDefinition = "jsonb")
    private JsonNode rawCompletionJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 60)
    private AnalysisFailureCode failureCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    private CharacterFactComparisonBatch(
            Work work,
            Episode sourceEpisode,
            AnalysisJob analysisJob,
            WorkCharacter matchedCharacter,
            CharacterFactType canonicalFactType,
            int candidateCount,
            long baseSnapshotVersion
    ) {
        if (candidateCount < 1 || baseSnapshotVersion < 0) {
            throw new IllegalArgumentException("candidateCount and baseSnapshotVersion must be valid.");
        }
        this.work = Objects.requireNonNull(work);
        this.sourceEpisode = sourceEpisode;
        this.analysisJob = Objects.requireNonNull(analysisJob);
        this.matchedCharacter = Objects.requireNonNull(matchedCharacter);
        this.canonicalFactType = Objects.requireNonNull(canonicalFactType);
        this.candidateCount = candidateCount;
        this.baseSnapshotVersion = baseSnapshotVersion;
        this.status = CharacterFactComparisonBatchStatus.PROCESSING;
    }

    public static CharacterFactComparisonBatch create(
            Work work,
            Episode sourceEpisode,
            AnalysisJob analysisJob,
            WorkCharacter matchedCharacter,
            CharacterFactType canonicalFactType,
            int candidateCount,
            long baseSnapshotVersion
    ) {
        return new CharacterFactComparisonBatch(
                work,
                sourceEpisode,
                analysisJob,
                matchedCharacter,
                canonicalFactType,
                candidateCount,
                baseSnapshotVersion
        );
    }

    public void recordContext(long baseSnapshotVersion, String contextHash) {
        requireProcessing();
        if (baseSnapshotVersion < 0) {
            throw new IllegalArgumentException("baseSnapshotVersion must not be negative.");
        }
        this.baseSnapshotVersion = baseSnapshotVersion;
        this.contextHash = requireHash(contextHash);
    }

    public void complete(String completionHash, JsonNode rawCompletionJson) {
        requireProcessing();
        this.status = CharacterFactComparisonBatchStatus.COMPLETED;
        this.completionHash = requireHash(completionHash);
        this.rawCompletionJson = rawCompletionJson == null ? null : rawCompletionJson.deepCopy();
        this.failureCode = null;
        this.errorMessage = null;
    }

    public void fail(AnalysisFailureCode failureCode, String errorMessage) {
        requireProcessing();
        this.status = CharacterFactComparisonBatchStatus.FAILED;
        this.failureCode = AnalysisFailureCode.orUnexpected(failureCode);
        this.errorMessage = Objects.requireNonNull(errorMessage).trim();
    }

    public boolean isProcessing() {
        return status == CharacterFactComparisonBatchStatus.PROCESSING;
    }

    public boolean isCompletedWith(String requestHash) {
        return status == CharacterFactComparisonBatchStatus.COMPLETED
                && Objects.equals(completionHash, requestHash);
    }

    private void requireProcessing() {
        if (!isProcessing()) {
            throw new IllegalStateException("Character fact comparison batch is not processing.");
        }
    }

    private String requireHash(String hash) {
        String normalized = Objects.requireNonNull(hash).trim();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("hash must be a lowercase SHA-256 value.");
        }
        return normalized;
    }
}
