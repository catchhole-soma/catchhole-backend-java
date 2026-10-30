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

import java.util.UUID;

@Entity
@Table(name = "relationship_backfill_works")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RelationshipBackfillWork {
    public enum State {
        BUILDING,
        BLOCKED,
        COMPLETE
    }

    @Id private UUID workId;

    @Column(nullable = false, length = 80)
    private String campaignKey;

    @Column(nullable = false)
    private long publishedGeneration;

    @Column(nullable = false)
    private long buildingGeneration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private State state;

    @Column(length = 80)
    private String blockedReason;

    public static RelationshipBackfillWork create(UUID workId, String campaignKey) {
        var w = new RelationshipBackfillWork();
        w.workId = workId;
        w.campaignKey = campaignKey;
        w.buildingGeneration = 1;
        w.state = State.BUILDING;
        return w;
    }

    public boolean active() {
        return state != State.COMPLETE;
    }

    public void block(String reason) {
        state = State.BLOCKED;
        blockedReason = reason;
    }

    public void publish() {
        publishedGeneration = buildingGeneration;
        state = State.COMPLETE;
        blockedReason = null;
    }
}
