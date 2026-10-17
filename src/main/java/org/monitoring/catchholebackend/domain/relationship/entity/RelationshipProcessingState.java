package org.monitoring.catchholebackend.domain.relationship.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter
@Entity
@Table(name = "relationship_processing_states", uniqueConstraints =
        @UniqueConstraint(name = "uk_relationship_processing_work", columnNames = "work_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RelationshipProcessingState extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_relationship_processing_work"))
    private Work work;

    @Column(nullable = false) private long processedVersion;
    @Column(nullable = false) private long attemptedVersion;
    private LocalDateTime lastProcessedAt;
    private LocalDateTime retryAt;
    @Column(length = 50) private String failureCode;
    @Column(nullable = false) private int autoConfirmedCount;
    @Column(nullable = false) private int reviewRequiredCount;
    @Column(nullable = false) private int unsupportedSourceCount;

    public static RelationshipProcessingState create(Work work) {
        var state = new RelationshipProcessingState();
        state.work = work;
        return state;
    }

    public void complete(long version, int automatic, int review, int unsupported, LocalDateTime now) {
        processedVersion = version;
        attemptedVersion = version;
        autoConfirmedCount = automatic;
        reviewRequiredCount = review;
        unsupportedSourceCount = unsupported;
        lastProcessedAt = now;
        clearFailure();
    }

    public void fail(long version, LocalDateTime now) {
        attemptedVersion = version;
        failureCode = "RELATIONSHIP_PROCESSING_FAILED";
        retryAt = now.plusMinutes(1);
    }

    public void clearFailure() {
        failureCode = null;
        retryAt = null;
    }
}
