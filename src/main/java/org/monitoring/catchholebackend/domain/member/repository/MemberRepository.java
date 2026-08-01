package org.monitoring.catchholebackend.domain.member.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.exception.MemberErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByPhoneNumber(String phoneNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select member from Member member where member.id = :memberId")
    Optional<Member> findByIdForUpdate(@Param("memberId") Long memberId);

    default Member getByIdOrThrow(Long memberId) {
        return findById(memberId)
                .orElseThrow(() -> new AppException(MemberErrorCode.MEMBER_NOT_FOUND));
    }
}
