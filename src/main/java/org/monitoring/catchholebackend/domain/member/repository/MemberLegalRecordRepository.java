package org.monitoring.catchholebackend.domain.member.repository;

import java.util.List;
import org.monitoring.catchholebackend.domain.member.entity.MemberLegalRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberLegalRecordRepository extends JpaRepository<MemberLegalRecord, Long> {

    List<MemberLegalRecord> findAllByMemberIdOrderByRecordedAtAsc(Long memberId);
}
