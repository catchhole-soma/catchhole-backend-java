package org.monitoring.catchholebackend.domain.aitoken.repository;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenGrant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiTokenGrantRepository extends JpaRepository<AiTokenGrant, UUID> {
}
