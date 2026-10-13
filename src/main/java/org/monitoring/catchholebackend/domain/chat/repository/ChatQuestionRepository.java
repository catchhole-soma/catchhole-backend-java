package org.monitoring.catchholebackend.domain.chat.repository;

import org.monitoring.catchholebackend.domain.chat.entity.ChatQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface ChatQuestionRepository extends JpaRepository<ChatQuestion, UUID> {
    Optional<ChatQuestion> findByMemberIdAndWorkIdAndIdempotencyKey(
            Long memberId, UUID workId, String key);

    org.springframework.data.domain.Page<ChatQuestion> findBySessionIdOrderBySequenceNoAsc(
            UUID session, org.springframework.data.domain.Pageable page);

    List<ChatQuestion> findBySessionIdAndSequenceNoLessThanOrderBySequenceNoDesc(
            UUID session, Long before, org.springframework.data.domain.Pageable page);

    List<ChatQuestion> findAllBySessionId(UUID session);

    Optional<ChatQuestion> findByIdAndMemberIdAndWorkId(UUID id, Long memberId, UUID workId);
}
