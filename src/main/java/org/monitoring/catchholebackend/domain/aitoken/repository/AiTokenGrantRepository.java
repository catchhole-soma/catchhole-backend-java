package org.monitoring.catchholebackend.domain.aitoken.repository;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenGrant;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenGrantType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiTokenGrantRepository extends JpaRepository<AiTokenGrant, UUID> {

    long countByMemberIdAndGrantType(Long memberId, AiTokenGrantType grantType);
}
