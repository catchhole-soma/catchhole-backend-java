package org.monitoring.catchholebackend.domain.relationship.repository;

import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Direction;

import java.util.UUID;

/** GH391 정리용 event 위치. 근거 jsonb·이름·설명은 읽지 않는다. */
public interface RelationshipGraphEventSlotRow {
    UUID getId();

    UUID getEdgeId();

    UUID getAnalysisId();

    int getEpisodeNo();

    Direction getDirection();
}
