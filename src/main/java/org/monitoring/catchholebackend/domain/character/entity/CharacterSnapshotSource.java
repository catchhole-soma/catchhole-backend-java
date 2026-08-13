package org.monitoring.catchholebackend.domain.character.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
import org.monitoring.catchholebackend.global.exception.AppException;

/** 현재 snapshot 한 칸을 구성한 append-only CharacterFact의 출처 연결이다. */
@Getter
@Entity
@Table(
        name = "character_snapshot_sources",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_character_snapshot_sources_slot_source",
                        columnNames = {"character_id", "fact_type", "fact_key", "source_fact_id"}
                ),
                @UniqueConstraint(
                        name = "uk_character_snapshot_sources_slot_order",
                        columnNames = {"character_id", "fact_type", "fact_key", "source_order"}
                )
        },
        indexes = @Index(name = "idx_character_snapshot_sources_source_fact", columnList = "source_fact_id")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterSnapshotSource extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "character_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_character_snapshot_sources_character")
    )
    private WorkCharacter workCharacter;

    @Enumerated(EnumType.STRING)
    @Column(name = "fact_type", nullable = false, updatable = false, length = 30)
    private CharacterFactType factType;

    @Column(name = "fact_key", nullable = false, updatable = false, length = 150)
    private String factKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "source_fact_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_character_snapshot_sources_character_fact")
    )
    private CharacterFact sourceFact;

    @Column(name = "source_order", nullable = false, updatable = false)
    private int sourceOrder;

    private CharacterSnapshotSource(
            WorkCharacter workCharacter,
            CharacterFactType factType,
            String factKey,
            CharacterFact sourceFact,
            int sourceOrder
    ) {
        if (workCharacter == null || factType == null || factKey == null || sourceFact == null) {
            throw new AppException(CharacterErrorCode.CHARACTER_SNAPSHOT_SOURCE_INVALID);
        }
        String normalizedFactKey = factKey.trim();
        WorkCharacter factCharacter = sourceFact.getWorkCharacter();
        boolean sameCharacter = factCharacter == workCharacter
                || factCharacter != null
                && workCharacter.getId() != null
                && Objects.equals(workCharacter.getId(), factCharacter.getId());
        if (sourceOrder < 0
                || normalizedFactKey.isEmpty()
                || !sameCharacter
                || sourceFact.getFactType() != factType
                || !Objects.equals(sourceFact.getFactKey(), normalizedFactKey)) {
            throw new AppException(CharacterErrorCode.CHARACTER_SNAPSHOT_SOURCE_INVALID);
        }
        this.workCharacter = workCharacter;
        this.factType = factType;
        this.factKey = normalizedFactKey;
        this.sourceFact = sourceFact;
        this.sourceOrder = sourceOrder;
    }

    public static CharacterSnapshotSource create(
            WorkCharacter workCharacter,
            CharacterFactType factType,
            String factKey,
            CharacterFact sourceFact,
            int sourceOrder
    ) {
        return new CharacterSnapshotSource(workCharacter, factType, factKey, sourceFact, sourceOrder);
    }
}
