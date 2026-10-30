package org.monitoring.catchholebackend.domain.relationship.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Axis;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Kind;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

import java.util.UUID;

@Getter
@Entity
@Table(name = "relationship_graph_edges")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RelationshipGraphEdge extends BaseEntity {
    @Id private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID workId;

    @Column(nullable = false, updatable = false)
    private UUID sourceNodeId;

    @Column(nullable = false, updatable = false)
    private UUID targetNodeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private Axis axis;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private Kind kind;

    @Column(nullable = false, length = 64, updatable = false)
    private String canonicalKey;

    @Column(nullable = false)
    private long version;

    // 사용자의 오류 정정/삭제는 서사 이력이 아니므로 모든 회차에 적용하고 자동 재생성을 막는다.
    @Column(nullable = false)
    private boolean suppressed;

    @Column(length = 500)
    private String correctedSummary;

    public static RelationshipGraphEdge create(
            UUID workId, UUID source, UUID target, Axis axis, Kind kind, String key) {
        var value = new RelationshipGraphEdge();
        value.id = UUID.randomUUID();
        value.workId = workId;
        value.sourceNodeId = source;
        value.targetNodeId = target;
        value.axis = axis;
        value.kind = kind;
        value.canonicalKey = key;
        return value;
    }

    public void changed() {
        version++;
    }

    public void correct(String summary) {
        correctedSummary = summary;
        version++;
    }

    public void suppress() {
        if (!suppressed) {
            suppressed = true;
            version++;
        }
    }
}
