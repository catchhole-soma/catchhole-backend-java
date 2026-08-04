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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenGrantType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Entity
@Table(name = "ai_token_grants")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiTokenGrant extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "member_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ai_token_grants_member"))
    private Member member;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "grant_type", nullable = false, length = 30)
    private AiTokenGrantType grantType;

    @Column(length = 255)
    private String note;

    private AiTokenGrant(Member member, long amount, AiTokenGrantType grantType, String note) {
        this.member = member;
        this.amount = amount;
        this.grantType = grantType;
        this.note = note;
    }

    public static AiTokenGrant create(Member member, long amount, AiTokenGrantType grantType, String note) {
        return new AiTokenGrant(member, amount, grantType, note);
    }
}
