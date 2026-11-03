package org.monitoring.catchholebackend.domain.relationship.entity;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.AnalysisStatus;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

import java.util.UUID;

@Getter
@Entity
@Table(name = "relationship_graph_analyses")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RelationshipGraphAnalysis extends BaseEntity {
    @Id private UUID id;

    @Column(nullable = false, updatable = false)
    private long generation;

    public void assignGeneration(long generation) {
        this.generation = generation;
    }

    @Column(nullable = false, updatable = false)
    private UUID workId;

    @Column(nullable = false, updatable = false)
    private UUID episodeId;

    @Column(nullable = false, updatable = false)
    private int episodeNo;

    @Column(nullable = false, updatable = false, length = 64)
    private String sourceHash;

    @Column(length = 64)
    private String requestHash;

    @Column(length = 64)
    private String contextToken;

    @Column(length = 64)
    private String completionHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnalysisStatus status;

    // 모델용 local refs와 실제 식별자 매핑을 분리해 동결한다. 외부에는 context만 보낸다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode context;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode bindings;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode decisions;

    // 같은 인물 쌍의 새 관측 참조와 허용된 실패 코드만 보존한다. null은 이전 저장 형태, 빈 배열은 부분 실패 없음이다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode failures;

    @Column(nullable = false)
    private int rejectedObservationCount;

    @Column(length = 40)
    private String failureCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode failureDiagnostics;

    @Column(nullable = false)
    private boolean evidencePurged;

    public static RelationshipGraphAnalysis prepare(
            UUID id,
            UUID workId,
            UUID episodeId,
            int episodeNo,
            String sourceHash,
            String requestHash,
            String contextToken,
            JsonNode context,
            JsonNode bindings,
            int rejected) {
        var value = new RelationshipGraphAnalysis();
        value.id = id;
        value.workId = workId;
        value.episodeId = episodeId;
        value.episodeNo = episodeNo;
        value.sourceHash = sourceHash;
        value.requestHash = requestHash;
        value.contextToken = contextToken;
        value.context = context;
        value.bindings = bindings;
        value.rejectedObservationCount = rejected;
        value.status = AnalysisStatus.PREPARED;
        return value;
    }

    public static RelationshipGraphAnalysis failed(
            UUID id, UUID workId, UUID episodeId, int episodeNo, String hash, String code) {
        var value = prepare(id, workId, episodeId, episodeNo, hash, null, null, null, null, 0);
        value.fail(code);
        return value;
    }

    public static RelationshipGraphAnalysis pending(
            UUID id, UUID workId, UUID episodeId, int episodeNo, String hash) {
        var value = prepare(id, workId, episodeId, episodeNo, hash, null, null, null, null, 0);
        value.status = AnalysisStatus.PENDING;
        return value;
    }

    public void prepareContext(
            String requestHash, String token, JsonNode context, JsonNode bindings, int rejected) {
        this.requestHash = requestHash;
        this.contextToken = token;
        this.context = context;
        this.bindings = bindings;
        this.rejectedObservationCount = rejected;
        failureCode = null;
        failureDiagnostics = null;
        decisions = null;
        failures = null;
        completionHash = null;
        status = AnalysisStatus.PREPARED;
    }

    /** The service validates current source, ownership and saved phase before restoring a retry. */
    public void resumeInvalidated(AnalysisStatus phase) {
        if (status != AnalysisStatus.INVALIDATED || evidencePurged
                || (phase != AnalysisStatus.PENDING && phase != AnalysisStatus.PREPARED
                    && phase != AnalysisStatus.COMPLETED && phase != AnalysisStatus.FAILED))
            throw new IllegalStateException("Invalid relationship retry phase.");
        status = phase;
    }

    public void complete(JsonNode decisions, String hash) {
        complete(decisions, null, hash);
    }

    public void complete(JsonNode decisions, JsonNode failures, String hash) {
        this.decisions = decisions;
        this.failures = failures;
        this.failureCode = hasFailures() ? "PARTIAL_COMPARISON_FAILED" : null;
        completionHash = hash;
        status = AnalysisStatus.COMPLETED;
    }

    public void fail(String code) {
        failureCode = code;
        failureDiagnostics = null;
        status = AnalysisStatus.FAILED;
    }

    public void fail(String code, JsonNode diagnostics) {
        fail(code);
        failureDiagnostics = diagnostics;
    }

    public void apply() {
        status = AnalysisStatus.APPLIED;
    }

    public boolean hasFailures() { return failures != null && !failures.isEmpty(); }

    public void invalidate() {
        status = AnalysisStatus.INVALIDATED;
    }
}
