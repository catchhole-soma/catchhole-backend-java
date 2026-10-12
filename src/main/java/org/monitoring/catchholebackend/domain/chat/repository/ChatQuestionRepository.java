package org.monitoring.catchholebackend.domain.chat.repository;

import org.monitoring.catchholebackend.domain.chat.entity.ChatQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface ChatQuestionRepository extends JpaRepository<ChatQuestion, UUID> {
    Optional<ChatQuestion> findByMemberIdAndWorkIdAndIdempotencyKey(
            Long memberId, UUID workId, String key);

    Optional<ChatQuestion> findByIdAndMemberIdAndWorkId(UUID id, Long memberId, UUID workId);
}
