package org.monitoring.catchholebackend.domain.chat.repository;

import org.monitoring.catchholebackend.domain.chat.entity.ChatSession;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

import java.time.Instant;
import java.util.*;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {
    Optional<ChatSession> findByWorkIdAndCreationKey(UUID work, UUID key);

    Page<ChatSession> findByWorkIdAndClosedAtIsNullAndExpiresAtAfterOrderByCreatedAtAscIdAsc(
            UUID work, Instant now, Pageable page);

    long countByWorkMemberIdAndClosedAtIsNullAndExpiresAtAfter(Long member, Instant now);

    @Query(
            "select s.id from ChatSession s where s.closedAt is null and s.expiresAt <= :now order"
                + " by s.expiresAt")
    List<UUID> expiredIds(Instant now, Pageable page);

    List<ChatSession> findAllByWorkIdAndClosedAtIsNull(UUID work);
}
