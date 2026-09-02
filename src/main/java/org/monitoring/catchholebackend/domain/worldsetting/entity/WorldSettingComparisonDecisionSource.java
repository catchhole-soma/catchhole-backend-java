package org.monitoring.catchholebackend.domain.worldsetting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** 최종 설정안이 어떤 원본 후보와 근거에서 만들어졌는지 보존하는 ordered membership이다. */
@Getter
@Entity
@Table(
        name = "world_setting_comparison_decision_sources",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_world_setting_comparison_sources_candidate",
                        columnNames = {"comparison_batch_id", "candidate_id"}
                ),
                @UniqueConstraint(
                        name = "uk_world_setting_comparison_sources_ref",
                        columnNames = {"comparison_batch_id", "candidate_ref"}
                ),
                @UniqueConstraint(
                        name = "uk_world_setting_comparison_sources_order",
                        columnNames = {"comparison_decision_id", "source_order"}
                )
        },
        indexes = @Index(
                name = "idx_world_setting_comparison_sources_decision",
                columnList = "comparison_decision_id,source_order,id"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldSettingComparisonDecisionSource extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "comparison_batch_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_world_setting_comparison_sources_batch")
    )
    private WorldSettingComparisonBatch comparisonBatch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "comparison_decision_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_world_setting_comparison_sources_decision")
    )
    private WorldSettingComparisonDecision comparisonDecision;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "candidate_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_world_setting_comparison_sources_candidate")
    )
    private WorldSettingCandidate candidate;

    @Column(name = "candidate_ref", nullable = false, updatable = false, length = 20)
    private String candidateRef;

    @Column(name = "source_order", nullable = false, updatable = false)
    private int sourceOrder;

    private WorldSettingComparisonDecisionSource(
            WorldSettingComparisonBatch comparisonBatch,
            WorldSettingComparisonDecision comparisonDecision,
            WorldSettingCandidate candidate,
            String candidateRef,
            int sourceOrder
    ) {
        if (sourceOrder < 0) {
            throw new IllegalArgumentException("sourceOrder must not be negative.");
        }
        this.comparisonBatch = Objects.requireNonNull(comparisonBatch);
        this.comparisonDecision = Objects.requireNonNull(comparisonDecision);
        this.candidate = Objects.requireNonNull(candidate);
        this.candidateRef = Objects.requireNonNull(candidateRef);
        this.sourceOrder = sourceOrder;
    }

    public static WorldSettingComparisonDecisionSource create(
            WorldSettingComparisonBatch comparisonBatch,
            WorldSettingComparisonDecision comparisonDecision,
            WorldSettingCandidate candidate,
            String candidateRef,
            int sourceOrder
    ) {
        return new WorldSettingComparisonDecisionSource(
                comparisonBatch,
                comparisonDecision,
                candidate,
                candidateRef,
                sourceOrder
        );
    }
}
