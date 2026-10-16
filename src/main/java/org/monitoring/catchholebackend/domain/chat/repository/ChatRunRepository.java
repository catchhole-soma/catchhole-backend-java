package org.monitoring.catchholebackend.domain.chat.repository;

import org.monitoring.catchholebackend.domain.chat.entity.ChatRun;
import org.monitoring.catchholebackend.domain.chat.type.ChatState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

import java.time.Instant;
import java.util.*;

public interface ChatRunRepository extends JpaRepository<ChatRun, UUID> {
    List<ChatRun> findAllByWorkId(UUID workId);

    List<ChatRun> findAllByQuestionSessionId(UUID sessionId);

    @Query(
            "select distinct r.question.session.id from ChatRun r"
                + " where r.question.session.id in :sessions and"
                + " ((r.state in :states and r.deadline > :now) or r.state ="
                + " org.monitoring.catchholebackend.domain.chat.type.ChatState.SETTLEMENT_PENDING)")
    List<UUID> findBlockingSessionIds(
            Collection<UUID> sessions, Collection<ChatState> states, Instant now);

    long countByQuestionIsNotNullAndState(ChatState state);

    long countByWorkMemberIdAndQuestionIsNotNullAndStateIn(
            Long memberId, Collection<ChatState> states);

    List<ChatRun> findByStateInAndQuestionIsNotNullOrderByDeadlineAsc(
            Collection<ChatState> states, Pageable page);

    @Query(
            "select r.id from ChatRun r where (r.state in :states and r.state <>"
                + " org.monitoring.catchholebackend.domain.chat.type.ChatState.SETTLEMENT_PENDING"
                + " and r.deadline < :now) or (r.usageDeadline < :now and exists (select u from"
                + " AiTokenUsage u where u.chatRun = r and u.status ="
                + " org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageStatus.RESERVED))"
                + " order by r.deadline")
    List<UUID> findRecoverable(Collection<ChatState> states, Instant now, Pageable page);
}
