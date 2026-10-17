package org.monitoring.catchholebackend.domain.relationship.entity;

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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

/** 확정된 관계의 정규화된 식별자이다. 출처는 확정 후보와 근거 연결을 통해 보존한다. */
@Getter
@Entity
@Table(name = "character_relationships", indexes = {
        @Index(name = "idx_character_relationships_work_created", columnList = "work_id,created_at,id"),
        @Index(name = "idx_character_relationships_subject", columnList = "subject_character_id"),
        @Index(name = "idx_character_relationships_object", columnList = "object_character_id")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterRelationship extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_character_relationships_work"))
    private Work work;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_character_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_character_relationships_subject"))
    private WorkCharacter subjectCharacter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "object_character_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_character_relationships_object"))
    private WorkCharacter objectCharacter;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 30)
    private RelationshipKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "subtype", nullable = false, updatable = false, length = 30)
    private RelationshipSubtype subtype;

    @Enumerated(EnumType.STRING)
    @Column(name = "temporal_scope", nullable = false, updatable = false, length = 30)
    private RelationshipTemporalScope temporalScope;

    // 작품 내 명시된 시점만 보존한다. null을 발견 회차나 현재 시점으로 보충하지 않는다.
    @Column(name = "time_label", updatable = false, length = 200)
    private String timeLabel;

    // 정규화된 양 끝점·유형·세부 유형·작품 내 시점을 함께 해시한 중복 판정 키이다.
    @Column(name = "canonical_key", nullable = false, updatable = false, length = 64)
    private String canonicalKey;

    // 사용자 철회 상태이다. 출처 소실 또는 작품 내 관계 종료와 같은 의미로 사용하지 않는다.
    @Column(name = "withdrawn", nullable = false)
    private boolean withdrawn;

    // Work 잠금 안에서 변경하고 API의 오래된 수정 요청을 판별하는 수동 버전이다.
    @Column(name = "version", nullable = false)
    private long version;

    private CharacterRelationship(Work work, WorkCharacter subjectCharacter, WorkCharacter objectCharacter,
            RelationshipKind kind, RelationshipSubtype subtype, RelationshipTemporalScope temporalScope,
            String timeLabel, String canonicalKey) {
        this.work = work;
        this.subjectCharacter = subjectCharacter;
        this.objectCharacter = objectCharacter;
        this.kind = kind;
        this.subtype = subtype;
        this.temporalScope = temporalScope;
        this.timeLabel = timeLabel;
        this.canonicalKey = canonicalKey;
    }

    public static CharacterRelationship create(Work work, WorkCharacter subjectCharacter,
            WorkCharacter objectCharacter, RelationshipKind kind, RelationshipSubtype subtype,
            RelationshipTemporalScope temporalScope, String timeLabel, String canonicalKey) {
        return new CharacterRelationship(work, subjectCharacter, objectCharacter, kind, subtype, temporalScope,
                timeLabel, canonicalKey);
    }

    public void withdraw() {
        if (!withdrawn) {
            withdrawn = true;
            version++;
        }
    }

    public void recordSupportChange() {
        version++;
    }
}
