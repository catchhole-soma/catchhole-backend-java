package org.monitoring.catchholebackend.domain.auth.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.monitoring.catchholebackend.domain.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findAllByMemberId(Long memberId);

    @Modifying(flushAutomatically = true)
    @Query("""
            update RefreshToken token
            set token.revokedAt = :revokedAt,
                token.updatedAt = :revokedAt
            where token.member.id = :memberId
              and token.revokedAt is null
            """)
    int revokeAllByMemberId(
            @Param("memberId") Long memberId,
            @Param("revokedAt") LocalDateTime revokedAt
    );
}
