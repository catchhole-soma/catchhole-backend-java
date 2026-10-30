package org.monitoring.catchholebackend.domain.relationship.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.monitoring.catchholebackend.domain.episode.entity.Episode;

import java.util.UUID;

@Entity
@Table(
        name = "relationship_backfill_items",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_relationship_backfill_item",
                        columnNames = {"work_id", "episode_id", "generation", "source_hash"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RelationshipBackfillItem {
    public enum State {
        WAITING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        SKIPPED
    }

    @Id private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID workId;

    @Column(nullable = false, updatable = false)
    private UUID episodeId;

    @Column(nullable = false, updatable = false)
    private int episodeNo;

    @Column(nullable = false, updatable = false)
    private long generation;

    @Column(nullable = false, updatable = false, length = 64)
    private String sourceHash;

    @Column(nullable = false, updatable = false, length = 2048)
    private String sourceKey;

    @Column(updatable = false, length = 255)
    private String sourceVersion;

    @Column(nullable = false, updatable = false)
    private boolean servicePaid;

    private UUID jobId;
    private UUID leaseToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private State state;

    @Column(length = 80)
    private String reason;

    public static RelationshipBackfillItem create(Episode e, long generation, boolean paid) {
        var i = new RelationshipBackfillItem();
        i.id = UUID.randomUUID();
        i.workId = e.getWork().getId();
        i.episodeId = e.getId();
        i.episodeNo = e.getEpisodeNo();
        i.generation = generation;
        i.sourceHash = e.getContentHash();
        i.sourceKey = e.getContentS3Key();
        i.sourceVersion = e.getContentS3Version();
        i.servicePaid = paid;
        i.state = State.WAITING;
        return i;
    }

    public void running(UUID id, UUID lease) {
        jobId = id;
        leaseToken = lease;
        state = State.RUNNING;
        reason = null;
    }

    public void succeeded() {
        state = State.SUCCEEDED;
        reason = null;
    }

    public void fail(String reason) {
        state = State.FAILED;
        this.reason = reason;
    }

    public void skip(String reason) {
        state = State.SKIPPED;
        this.reason = reason;
    }

    public void requeue() {
        state = State.WAITING;
    }
}
