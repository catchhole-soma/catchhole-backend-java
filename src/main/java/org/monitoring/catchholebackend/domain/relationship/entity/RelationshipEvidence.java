package org.monitoring.catchholebackend.domain.relationship.entity;

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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

/** 관계 판단 당시의 현재 설정 출처를 연결한다. 원문 인용을 중복 저장하지 않는다. */
@Getter
@Entity
@Table(name = "relationship_evidence", uniqueConstraints =
        @UniqueConstraint(name = "uk_relationship_evidence_candidate_fact",
                columnNames = {"candidate_id", "source_fact_id"}),
        indexes = @Index(name = "idx_relationship_evidence_source_fact", columnList = "source_fact_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RelationshipEvidence extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_relationship_evidence_candidate"))
    private RelationshipCandidate candidate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_fact_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_relationship_evidence_source_fact"))
    private CharacterFact sourceFact;

    // 슬롯 현재값과 전체 sourceFacts를 해시해 해당 슬롯의 변경만 감지한다.
    @Column(name = "source_token", nullable = false, updatable = false, length = 64)
    private String sourceToken;

    private RelationshipEvidence(RelationshipCandidate candidate, CharacterFact sourceFact, String sourceToken) {
        this.candidate = candidate;
        this.sourceFact = sourceFact;
        this.sourceToken = sourceToken;
    }

    public static RelationshipEvidence create(RelationshipCandidate candidate, CharacterFact sourceFact,
            String sourceToken) {
        return new RelationshipEvidence(candidate, sourceFact, sourceToken);
    }
}
