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
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Direction;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.EdgeStatus;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

import java.util.UUID;

@Getter
@Entity
@Table(name = "relationship_graph_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RelationshipGraphEvent extends BaseEntity {
    @Id private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID workId;

    @Column(nullable = false, updatable = false)
    private UUID analysisId;

    @Column(nullable = false, updatable = false)
    private UUID edgeId;

    @Column(nullable = false, updatable = false)
    private UUID episodeId;

    @Column(nullable = false, updatable = false)
    private int episodeNo;

    @Column(nullable = false, length = 100, updatable = false)
    private String sourceName;

    @Column(nullable = false, length = 100, updatable = false)
    private String targetName;

    @Column(nullable = false, length = 60, updatable = false)
    private String label;

    @Column(nullable = false, length = 500, updatable = false)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private EdgeStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode evidence;

    public static RelationshipGraphEvent create(
            UUID workId,
            UUID analysisId,
            UUID edgeId,
            UUID episodeId,
            int episodeNo,
            String sourceName,
            String targetName,
            String label,
            String summary,
            Direction direction,
            EdgeStatus status,
            JsonNode evidence) {
        var value = new RelationshipGraphEvent();
        value.id = UUID.randomUUID();
        value.workId = workId;
        value.analysisId = analysisId;
        value.edgeId = edgeId;
        value.episodeId = episodeId;
        value.episodeNo = episodeNo;
        value.sourceName = sourceName;
        value.targetName = targetName;
        value.label = label;
        value.summary = summary;
        value.direction = direction;
        value.status = status;
        value.evidence = evidence;
        return value;
    }
}
