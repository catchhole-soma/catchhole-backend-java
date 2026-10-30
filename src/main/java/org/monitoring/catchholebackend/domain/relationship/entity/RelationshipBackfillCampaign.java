package org.monitoring.catchholebackend.domain.relationship.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "relationship_backfill_campaigns")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RelationshipBackfillCampaign {
    @Id
    @Column(length = 80)
    private String id;

    @Column(nullable = false)
    private boolean initialized;

    public static RelationshipBackfillCampaign create(String id) {
        var c = new RelationshipBackfillCampaign();
        c.id = id;
        return c;
    }

    public void initialize() {
        initialized = true;
    }
}
