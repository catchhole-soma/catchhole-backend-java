package org.monitoring.catchholebackend.domain.worldimage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter
@Entity
@Table(name = "private_image_vaults")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PrivateImageVault extends BaseEntity {
    @Id private UUID id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private Member member;
    // 브라우저에서 키가 맞는지 확인하는 인증 암호문이며 키 자체나 비밀번호가 아니다.
    @Column(name = "key_check", nullable = false, length = 512)
    private String keyCheck;
    @Version private Long version;

    public static PrivateImageVault create(UUID id, Member member, String keyCheck) {
        PrivateImageVault vault = new PrivateImageVault();
        vault.id = id;
        vault.member = member;
        vault.keyCheck = keyCheck;
        return vault;
    }
}
