package org.monitoring.catchholebackend.domain.feedback.repository;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.feedback.entity.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackRepository extends JpaRepository<Feedback, UUID> {

    long countByMemberId(Long memberId);
}
