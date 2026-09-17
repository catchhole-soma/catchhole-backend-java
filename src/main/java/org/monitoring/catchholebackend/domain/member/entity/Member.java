package org.monitoring.catchholebackend.domain.member.entity;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.member.exception.MemberErrorCode;
import org.monitoring.catchholebackend.domain.member.type.MemberRole;
import org.monitoring.catchholebackend.domain.member.type.MemberStatus;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
import org.monitoring.catchholebackend.global.exception.AppException;

@Getter
@Entity
@Table(
        name = "members",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_members_email", columnNames = "email"),
                @UniqueConstraint(name = "uk_members_phone_number", columnNames = "phone_number")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseEntity {

    @jakarta.persistence.Id
    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "age_requirement_confirmed_at")
    private LocalDateTime ageRequirementConfirmedAt;

    // 의견을 작성하지 않고 안내를 닫아도 계정당 한 번만 요청하기 위한 노출 선점 시각
    @Column(name = "feedback_prompt_shown_at")
    private LocalDateTime feedbackPromptShownAt;

    // 분석 방식 안내는 작품·기기에 관계없이 계정당 한 번만 자동 표시한다.
    @Column(name = "analysis_guide_shown_at")
    private LocalDateTime analysisGuideShownAt;

    // 분석/작품을 삭제하더라도 첫 사용자 안내가 다시 나타나지 않도록 보존한다.
    @Column(name = "first_analysis_started_at")
    private LocalDateTime firstAnalysisStartedAt;

    //화면에서 보이는 닉네임
    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Column(name = "profile_image_url", length = 2048)
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MemberStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MemberRole role;

    private Member(
            String email,
            String passwordHash,
            String phoneNumber,
            boolean phoneVerified,
            String displayName,
            String profileImageUrl,
            LocalDateTime ageRequirementConfirmedAt
    ) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.phoneNumber = phoneNumber;
        this.displayName = displayName;
        this.profileImageUrl = profileImageUrl;
        this.phoneVerified = phoneVerified;
        this.ageRequirementConfirmedAt = ageRequirementConfirmedAt;
        this.status = MemberStatus.ACTIVE;
        this.role = MemberRole.AUTHOR;
    }

    public static Member register(
            String email,
            String passwordHash,
            String phoneNumber,
            String displayName
    ) {
        return new Member(email, passwordHash, phoneNumber, false, displayName, null, null);
    }

    public static Member registerPhoneVerified(
            String email,
            String passwordHash,
            String phoneNumber,
            String displayName,
            LocalDateTime ageRequirementConfirmedAt
    ) {
        return new Member(
                email,
                passwordHash,
                phoneNumber,
                true,
                displayName,
                null,
                ageRequirementConfirmedAt
        );
    }

    public void markFeedbackPromptShown(LocalDateTime shownAt) {
        if (feedbackPromptShownAt == null) {
            feedbackPromptShownAt = shownAt;
        }
    }

    public void validateActive() {
        if (!isActive()) {
            throw new AppException(MemberErrorCode.MEMBER_INACTIVE);
        }
    }

    public void markAnalysisGuideShown(LocalDateTime shownAt) {
        if (analysisGuideShownAt == null) analysisGuideShownAt = shownAt;
    }

    public void markFirstAnalysisStarted(LocalDateTime startedAt) {
        if (firstAnalysisStartedAt == null) firstAnalysisStartedAt = startedAt;
    }

    public static Member registerEmailVerified(
            String email,
            String passwordHash,
            String displayName,
            LocalDateTime ageRequirementConfirmedAt
    ) {
        Member member = new Member(email, passwordHash, null, false, displayName, null,
                ageRequirementConfirmedAt);
        member.emailVerified = true;
        return member;
    }

    public boolean isActive() {
        return status == MemberStatus.ACTIVE;
    }

    public boolean isPurging() {
        return status == MemberStatus.PURGING;
    }

    public void startPurging() {
        validateActive();
        this.status = MemberStatus.PURGING;
    }
}
