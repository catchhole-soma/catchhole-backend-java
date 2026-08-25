package org.monitoring.catchholebackend.domain.aitoken.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.monitoring.catchholebackend.domain.aitoken.exception.AiTokenErrorCode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
import org.monitoring.catchholebackend.global.exception.AppException;

@Getter
@Entity
@Table(name = "ai_token_accounts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiTokenAccount extends BaseEntity {

    @Id
    @Column(name = "member_id")
    private Long memberId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "member_id",
            foreignKey = @ForeignKey(name = "fk_ai_token_accounts_member")
    )
    private Member member;

    @Column(name = "granted_tokens", nullable = false)
    private long grantedTokens;

    @Column(name = "used_tokens", nullable = false)
    private long usedTokens;

    @Column(name = "reserved_tokens", nullable = false)
    private long reservedTokens;

    private AiTokenAccount(Member member, long defaultGrant) {
        this.member = member;
        this.grantedTokens = defaultGrant;
    }

    public static AiTokenAccount create(Member member, long defaultGrant) {
        return new AiTokenAccount(member, defaultGrant);
    }

    public long remainingTokens() {
        return Math.max(0, grantedTokens - usedTokens - reservedTokens);
    }

    public void reserve(long tokens) {
        if (tokens <= 0 || remainingTokens() < tokens) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_QUOTA_EXHAUSTED);
        }
        reservedTokens += tokens;
    }

    public void settle(long reserved, long actual) {
        reservedTokens = Math.max(0, reservedTokens - reserved);
        usedTokens += actual;
    }

    public void release(long reserved) {
        reservedTokens = Math.max(0, reservedTokens - reserved);
    }

    public void grant(long tokens) {
        if (tokens <= 0) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_USAGE_INVALID);
        }
        grantedTokens += tokens;
    }
}
