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

import org.monitoring.catchholebackend.domain.relationship.type.RelationshipBackfillWorkState;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

import java.util.UUID;

@Entity
@Table(name = "relationship_backfill_works")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RelationshipBackfillWork extends BaseEntity {
    @Id private UUID workId;

    @Column(nullable = false, length = 80)
    private String campaignKey;

    @Column(nullable = false)
    private long publishedGeneration;

    @Column(nullable = false)
    private long buildingGeneration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RelationshipBackfillWorkState state;

    @Column(length = 80)
    private String blockedReason;

    public static RelationshipBackfillWork create(UUID workId, String campaignKey) {
        var w = new RelationshipBackfillWork();
        w.workId = workId;
        w.campaignKey = campaignKey;
        w.buildingGeneration = 1;
        w.state = RelationshipBackfillWorkState.BUILDING;
        return w;
    }

    public boolean active() {
        return state != RelationshipBackfillWorkState.COMPLETE;
    }

    public void block(String reason) {
        state = RelationshipBackfillWorkState.BLOCKED;
        blockedReason = reason;
    }

    public void publish() {
        publishedGeneration = buildingGeneration;
        state = RelationshipBackfillWorkState.COMPLETE;
        blockedReason = null;
    }
}
