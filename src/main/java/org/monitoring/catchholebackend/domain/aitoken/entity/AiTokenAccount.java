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
import java.math.BigDecimal;
import java.math.RoundingMode;
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

    @Column(name = "granted_tokens", nullable = false, precision = 22, scale = 2)
    private BigDecimal grantedTokens;

    @Column(name = "used_tokens", nullable = false, precision = 22, scale = 2)
    private BigDecimal usedTokens = BigDecimal.ZERO;

    @Column(name = "reserved_tokens", nullable = false, precision = 22, scale = 2)
    private BigDecimal reservedTokens = BigDecimal.ZERO;

    // 사용자에게 보여 줄 남은 비율의 100% 기준. 지급받은 순간의 잔량이다.
    @Column(name = "percent_basis_tokens", nullable = false, precision = 22, scale = 2)
    private BigDecimal percentBasisTokens;

    private AiTokenAccount(Member member, long defaultGrant) {
        this.member = member;
        this.grantedTokens = BigDecimal.valueOf(defaultGrant);
        this.percentBasisTokens = this.grantedTokens;
    }

    public static AiTokenAccount create(Member member, long defaultGrant) {
        return new AiTokenAccount(member, defaultGrant);
    }

    public BigDecimal remainingTokens() {
        return grantedTokens.subtract(usedTokens).subtract(reservedTokens).max(BigDecimal.ZERO);
    }

    /**
     * 지급받은 순간의 잔량을 100%로 본 현재 잔량 비율. 지급 때 예약 중이던 양이 해제되어 기준을 넘어도 100%로 제한한다.
     */
    public double remainingPercent() {
        BigDecimal remaining = remainingTokens();
        if (remaining.signum() == 0) {
            return 0;
        }
        if (percentBasisTokens.signum() <= 0) {
            return 100;
        }
        return remaining.min(percentBasisTokens)
                .multiply(BigDecimal.valueOf(100))
                .divide(percentBasisTokens, 2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    public void reserve(long tokens) {
        reserve(BigDecimal.valueOf(tokens));
    }

    public void reserve(BigDecimal tokens) {
        if (tokens.signum() <= 0 || remainingTokens().compareTo(tokens) < 0) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_QUOTA_EXHAUSTED);
        }
        reservedTokens = reservedTokens.add(tokens);
    }

    public void settle(long reserved, long actual) {
        settle(BigDecimal.valueOf(reserved), BigDecimal.valueOf(actual));
    }

    public void settle(BigDecimal reserved, BigDecimal actual) {
        release(reserved);
        usedTokens = usedTokens.add(actual);
    }

    public void release(long reserved) {
        release(BigDecimal.valueOf(reserved));
    }

    public void release(BigDecimal reserved) {
        reservedTokens = reservedTokens.subtract(reserved).max(BigDecimal.ZERO);
    }

    public void grant(long tokens) {
        if (tokens <= 0) {
            throw new AppException(AiTokenErrorCode.AI_TOKEN_USAGE_INVALID);
        }
        grantedTokens = grantedTokens.add(BigDecimal.valueOf(tokens));
        percentBasisTokens = remainingTokens();
    }
}
