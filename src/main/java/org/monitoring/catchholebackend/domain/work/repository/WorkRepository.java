package org.monitoring.catchholebackend.domain.work.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkRepository extends JpaRepository<Work, UUID> {

    Optional<Work> findByIdAndMemberId(UUID id, Long memberId);

    List<Work> findAllByMemberIdOrderByCreatedAtDesc(Long memberId);
}
