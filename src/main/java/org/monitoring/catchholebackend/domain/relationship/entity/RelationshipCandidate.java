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
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.relationship.exception.RelationshipErrorCode;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipAssertion;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipCandidateStatus;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipCandidateOrigin;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
import org.monitoring.catchholebackend.global.exception.AppException;

@Getter
@Entity
@Table(name = "relationship_candidates", uniqueConstraints =
        @UniqueConstraint(name = "uk_relationship_candidates_request", columnNames = {"work_id", "request_id"}),
        indexes = {
                @Index(name = "idx_relationship_candidates_work_created", columnList = "work_id,created_at,id"),
                @Index(name = "idx_relationship_candidates_confirmed", columnList = "confirmed_relationship_id"),
                @Index(name = "idx_relationship_candidates_subject", columnList = "subject_character_id"),
                @Index(name = "idx_relationship_candidates_object", columnList = "object_character_id")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RelationshipCandidate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_relationship_candidates_work"))
    private Work work;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    // 수정 후에도 최초 생성 요청의 동일성을 검사할 수 있도록 원래 요청 해시를 보존한다.
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_character_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_relationship_candidates_subject"))
    private WorkCharacter subjectCharacter;

    // 미등록·동명이인 등으로 대상을 확정하지 못한 후보는 null로 검토 대기한다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "object_character_id", foreignKey = @ForeignKey(name = "fk_relationship_candidates_object"))
    private WorkCharacter objectCharacter;

    @Column(name = "object_mention", length = 200)
    private String objectMention;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mention_group_id", foreignKey = @ForeignKey(name = "fk_relationship_candidates_mention_group"))
    private RelationshipMentionGroup mentionGroup;

    public void assignMentionGroup(RelationshipMentionGroup group) {
        requirePending();
        if (objectCharacter != null || mentionGroup != null || group == null || !group.isActive()
                || !work.getId().equals(group.getWork().getId())) {
            throw new AppException(RelationshipErrorCode.RELATIONSHIP_INVALID_RELATIONSHIP);
        }
        mentionGroup = group;
        version++;
    }

    public void clearDisbandedMentionGroup() {
        requirePending();
        if (mentionGroup != null) {
            mentionGroup = null;
            version++;
        }
    }

    private void detachMentionGroupOnDecision() {
        if (mentionGroup != null) {
            mentionGroup.recordMembershipChange();
            mentionGroup = null;
        }
    }


    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30)
    private RelationshipKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "subtype", nullable = false, length = 30)
    private RelationshipSubtype subtype;

    @Enumerated(EnumType.STRING)
    @Column(name = "temporal_scope", nullable = false, length = 30)
    private RelationshipTemporalScope temporalScope;

    // 발견 회차로 추정하지 않는 작품 내 시점 설명이며 미상은 null이다.
    @Column(name = "time_label", length = 200)
    private String timeLabel;

    @Column(name = "generation_gap")
    private Integer generationGap;

    @Enumerated(EnumType.STRING)
    @Column(name = "assertion", nullable = false, length = 30)
    private RelationshipAssertion assertion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RelationshipCandidateStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_relationship_id",
            foreignKey = @ForeignKey(name = "fk_relationship_candidates_confirmed"))
    private CharacterRelationship confirmedRelationship;

    // 관계 수정으로 생긴 후보일 때만 이전 관계 ID를 남겨 수정 이력을 추적한다.
    @Column(name = "replaces_relationship_id", updatable = false)
    private UUID replacesRelationshipId;

    @Column(name = "version", nullable = false)
    private long version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RelationshipCandidateOrigin origin = RelationshipCandidateOrigin.MANUAL;

    @Column(nullable = false)
    private boolean automaticallyConfirmed;

    @Column(nullable = false)
    private long validatedInputVersion;

    public void validateAutomaticSupport(long inputVersion) {
        validatedInputVersion = inputVersion;
    }

    @Column(length = 50)
    private String automaticReviewReason;

    public void markRuleGenerated() {
        origin = RelationshipCandidateOrigin.CURRENT_SETTING_RULE;
    }

    public void holdAutomatically(String reason) {
        automaticReviewReason = reason;
    }

    public void confirmAutomatically(CharacterRelationship relationship) {
        confirm(relationship);
        automaticallyConfirmed = true;
        automaticReviewReason = null;
    }

    public boolean isAutomaticSupportBlocked() {
        return automaticallyConfirmed && automaticReviewReason != null;
    }


    // 확정·제외 직전 버전으로 동일 결정의 재전송만 멱등하게 판별한다.
    @Column(name = "decision_from_version")
    private Long decisionFromVersion;

    private RelationshipCandidate(Work work, UUID requestId, String requestHash, WorkCharacter subjectCharacter,
            WorkCharacter objectCharacter, String objectMention, RelationshipKind kind, RelationshipSubtype subtype,
            RelationshipTemporalScope temporalScope, String timeLabel, RelationshipAssertion assertion,
            UUID replacesRelationshipId) {
        this.work = work;
        this.requestId = requestId;
        this.requestHash = requestHash;
        this.subjectCharacter = subjectCharacter;
        this.objectCharacter = objectCharacter;
        this.objectMention = objectMention;
        this.kind = kind;
        this.subtype = subtype;
        this.temporalScope = temporalScope;
        this.timeLabel = timeLabel;
        this.assertion = assertion;
        this.replacesRelationshipId = replacesRelationshipId;
        this.status = RelationshipCandidateStatus.PENDING;
    }

    public static RelationshipCandidate create(Work work, UUID requestId, String requestHash,
            WorkCharacter subjectCharacter, WorkCharacter objectCharacter, String objectMention,
            RelationshipKind kind, RelationshipSubtype subtype, RelationshipTemporalScope temporalScope,
            String timeLabel, RelationshipAssertion assertion, UUID replacesRelationshipId) {
        return new RelationshipCandidate(work, requestId, requestHash, subjectCharacter, objectCharacter,
                objectMention, kind, subtype, temporalScope, timeLabel, assertion, replacesRelationshipId);
    }

    public void revise(WorkCharacter subjectCharacter, WorkCharacter objectCharacter, String objectMention,
            RelationshipKind kind, RelationshipSubtype subtype, RelationshipTemporalScope temporalScope,
            String timeLabel, RelationshipAssertion assertion) {
        requirePending();
        detachMentionGroupOnDecision();
        this.subjectCharacter = subjectCharacter;
        this.objectCharacter = objectCharacter;
        this.objectMention = objectMention;
        this.kind = kind;
        this.subtype = subtype;
        this.temporalScope = temporalScope;
        this.timeLabel = timeLabel;
        this.assertion = assertion;
        generationGap = null;
        automaticReviewReason = null;
        version++;
    }

    public static RelationshipCandidate create(Work work, UUID requestId, String requestHash,
            WorkCharacter subjectCharacter, WorkCharacter objectCharacter, String objectMention,
            RelationshipKind kind, RelationshipSubtype subtype, RelationshipTemporalScope temporalScope,
            String timeLabel, RelationshipAssertion assertion, UUID replacesRelationshipId, Integer generationGap) {
        var candidate = create(work, requestId, requestHash, subjectCharacter, objectCharacter, objectMention,
                kind, subtype, temporalScope, timeLabel, assertion, replacesRelationshipId);
        candidate.generationGap = generationGap;
        return candidate;
    }

    public void revise(WorkCharacter subjectCharacter, WorkCharacter objectCharacter, String objectMention,
            RelationshipKind kind, RelationshipSubtype subtype, RelationshipTemporalScope temporalScope,
            String timeLabel, RelationshipAssertion assertion, Integer generationGap) {
        revise(subjectCharacter, objectCharacter, objectMention, kind, subtype, temporalScope, timeLabel, assertion);
        this.generationGap = generationGap;
    }

    public void confirm(CharacterRelationship relationship) {
        requirePending();
        if (relationship == null) {
            throw new AppException(RelationshipErrorCode.RELATIONSHIP_INVALID_RELATIONSHIP);
        }
        detachMentionGroupOnDecision();
        confirmedRelationship = relationship;
        status = RelationshipCandidateStatus.CONFIRMED;
        decisionFromVersion = version++;
    }

    public void dismiss() {
        requirePending();
        detachMentionGroupOnDecision();
        status = RelationshipCandidateStatus.DISMISSED;
        decisionFromVersion = version++;
    }

    private void requirePending() {
        if (status != RelationshipCandidateStatus.PENDING) {
            throw new AppException(RelationshipErrorCode.RELATIONSHIP_INVALID_CANDIDATE_STATE);
        }
    }
}
