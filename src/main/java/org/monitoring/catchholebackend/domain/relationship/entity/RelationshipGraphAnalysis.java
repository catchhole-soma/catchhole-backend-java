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

    @Column(nullable = false)
    private int rejectedObservationCount;

    @Column(length = 40)
    private String failureCode;

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
        status = AnalysisStatus.PREPARED;
    }

    public void complete(JsonNode decisions, String hash) {
        this.decisions = decisions;
        completionHash = hash;
        status = AnalysisStatus.COMPLETED;
    }

    public void fail(String code) {
        failureCode = code;
        status = AnalysisStatus.FAILED;
    }

    public void apply() {
        status = AnalysisStatus.APPLIED;
    }

    public void invalidate() {
        status = AnalysisStatus.INVALIDATED;
    }
}
