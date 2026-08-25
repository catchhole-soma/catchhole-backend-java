package org.monitoring.catchholebackend.domain.aitoken.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.monitoring.catchholebackend.domain.aitoken.exception.AiTokenErrorCode;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenExtensionContext;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenExtensionStatus;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
import org.monitoring.catchholebackend.global.exception.AppException;

@Getter
@Entity
@Table(name = "ai_token_extension_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiTokenExtensionRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "member_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_ai_token_extension_requests_member")
    )
    private Member member;

    @Column(nullable = false, length = 1000)
    private String feedback;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_context", nullable = false, length = 40)
    private AiTokenExtensionContext context;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiTokenExtensionStatus status;

    @Column(name = "reviewed_by_member_id")
    private Long reviewedByMemberId;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "granted_amount")
    private Long grantedAmount;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    private AiTokenExtensionRequest(Member member, String feedback, AiTokenExtensionContext context) {
        this.member = member;
        this.feedback = feedback;
        this.context = context;
        this.status = AiTokenExtensionStatus.PENDING;
    }

    public static AiTokenExtensionRequest request(
            Member member,
            String feedback,
            AiTokenExtensionContext context
    ) {
        return new AiTokenExtensionRequest(member, feedback, context);
    }

    public void approve(Long reviewerMemberId, long amount, LocalDateTime reviewedAt) {
        validatePending();
        this.status = AiTokenExtensionStatus.APPROVED;
        this.reviewedByMemberId = reviewerMemberId;
        this.reviewedAt = reviewedAt;
        this.grantedAmount = amount;
    }

    public void reject(Long reviewerMemberId, String reason, LocalDateTime reviewedAt) {
        validatePending();
        this.status = AiTokenExtensionStatus.REJECTED;
        this.reviewedByMemberId = reviewerMemberId;
        this.reviewedAt = reviewedAt;
        this.rejectionReason = reason;
    }

    public boolean isPending() {
        return status == AiTokenExtensionStatus.PENDING;
    }

    public boolean isApproved() {
        return status == AiTokenExtensionStatus.APPROVED;
    }

    public boolean isRejected() {
        return status == AiTokenExtensionStatus.REJECTED;
    }

    private void validatePending() {
        if (!isPending()) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_EXTENSION_REVIEW_CONFLICT);
        }
    }
}
