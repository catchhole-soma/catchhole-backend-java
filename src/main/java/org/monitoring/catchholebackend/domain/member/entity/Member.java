package org.monitoring.catchholebackend.domain.member.entity;

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

    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified;

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
            String displayName,
            String profileImageUrl
    ) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.phoneNumber = phoneNumber;
        this.displayName = displayName;
        this.profileImageUrl = profileImageUrl;
        this.phoneVerified = false;
        this.status = MemberStatus.ACTIVE;
        this.role = MemberRole.AUTHOR;
    }

    public static Member register(
            String email,
            String passwordHash,
            String phoneNumber,
            String displayName
    ) {
        return new Member(email, passwordHash, phoneNumber, displayName, null);
    }

    public void validateActive() {
        if (!isActive()) {
            throw new AppException(MemberErrorCode.MEMBER_INACTIVE);
        }
    }

    public boolean isActive() {
        return status == MemberStatus.ACTIVE;
    }
}
