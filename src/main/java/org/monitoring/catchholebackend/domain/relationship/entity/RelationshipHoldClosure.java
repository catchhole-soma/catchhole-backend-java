package org.monitoring.catchholebackend.domain.relationship.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

/** An explicit continuation closes a hold without rewriting its original decision or evidence. */
@Getter
@Entity
@Table(name = "relationship_hold_closures", uniqueConstraints =
        @UniqueConstraint(name = "uk_relationship_hold_closure", columnNames = {"analysis_id", "observation_ref"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RelationshipHoldClosure extends BaseEntity {
    @Id private UUID id;
    @Column(nullable = false, updatable = false) private UUID workId;
    @Column(nullable = false, updatable = false) private UUID analysisId;
    @Column(nullable = false, updatable = false, length = 40) private String observationRef;
    @Column(nullable = false, updatable = false) private UUID continuationRequestId;

    public static RelationshipHoldClosure close(UUID workId, UUID analysisId, String observationRef, UUID requestId) {
        if (workId == null || analysisId == null || requestId == null
                || observationRef == null || !observationRef.matches("O[1-9][0-9]*")
                || observationRef.length() > 40) throw new IllegalArgumentException("유효한 관계 보류 출처가 필요합니다.");
        var value = new RelationshipHoldClosure();
        value.id = UUID.randomUUID();
        value.workId = workId;
        value.analysisId = analysisId;
        value.observationRef = observationRef;
        value.continuationRequestId = requestId;
        return value;
    }
}
