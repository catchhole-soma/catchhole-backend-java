package org.monitoring.catchholebackend.domain.chat.service;

import static org.monitoring.catchholebackend.domain.chat.exception.ChatErrorCode.*;
import static org.monitoring.catchholebackend.global.exception.CommonErrorCode.REQUEST_VALIDATION_FAILED;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenUsageRepository;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageStatus;
import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.*;
import org.monitoring.catchholebackend.domain.chat.dto.ChatSessionContract.*;
import org.monitoring.catchholebackend.domain.chat.entity.*;
import org.monitoring.catchholebackend.domain.chat.mapper.ChatMapper;
import org.monitoring.catchholebackend.domain.chat.repository.*;
import org.monitoring.catchholebackend.domain.chat.type.ChatState;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.monitoring.catchholebackend.global.config.chat.ChatProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatSessionServiceImpl implements ChatSessionService {
    private static final Duration TTL = Duration.ofHours(2);
    private static final List<ChatState> IN_FLIGHT = List.of(ChatState.QUEUED, ChatState.RUNNING);
    private final ChatSessionRepository sessions;
    private final ChatQuestionRepository questions;
    private final ChatRunRepository runs;
    private final AiTokenUsageRepository usages;
    private final WorkRepository works;
    private final MemberRepository members;
    private final ChatProperties properties;
    private final ChatMapper mapper;
    private final EntityManager em;

    @Override
    @Transactional
    public Session createSession(Long member, UUID work, UUID requestId) {
        if (!properties.enabled()) throw new AppException(CHAT_DISABLED);
        if (requestId == null) throw new AppException(REQUEST_VALIDATION_FAILED);
        members.findByIdForUpdate(member)
                .orElseThrow(() -> new AppException(CHAT_NOT_FOUND))
                .validateActive();
        var owned = works.getOwnedWorkForUpdate(work, member);
        var previous = sessions.findByWorkIdAndCreationKey(work, requestId);
        if (previous.isPresent()) {
            open(previous.get());
            return mapper.session(previous.get());
        }
        if (sessions.countByWorkMemberIdAndClosedAtIsNullAndExpiresAtAfter(member, Instant.now())
                >= 20) throw new AppException(CHAT_SESSION_LIMIT);
        return mapper.session(
                sessions.save(ChatSession.create(owned, requestId, Instant.now(), TTL)));
    }

    @Override
    @Transactional
    public Session getSession(Long member, UUID work, UUID session) {
        return mapper.session(owned(member, work, session, true));
    }

    @Override
    @Transactional
    public PageResponse<Session> listSessions(Long member, UUID work, int page, int size) {
        works.getOwnedWorkForUpdate(work, member).getMember().validateActive();
        var found =
                sessions.findByWorkIdAndClosedAtIsNullAndExpiresAtAfterOrderByCreatedAtAscIdAsc(
                        work, Instant.now(), page(page, size));
        return PageResponse.from(found, found.map(mapper::session).getContent());
    }

    @Override
    @Transactional
    public PageResponse<Message> listMessages(
            Long member, UUID work, UUID session, int page, int size) {
        owned(member, work, session, true);
        var found = questions.findBySessionIdOrderBySequenceNoAsc(session, page(page, size));
        var content =
                found.map(
                                q -> {
                                    var run = runs.findById(q.getCurrentAttemptId()).orElseThrow();
                                    if (!Instant.now().isBefore(run.getDeadline())) run.timeout();
                                    return mapper.message(
                                            q, run, usages.findAllByChatRunId(run.getId()));
                                })
                        .getContent();
        return PageResponse.from(found, content);
    }

    @Override
    @Transactional
    public void closeSession(Long member, UUID work, UUID session) {
        erase(owned(member, work, session, false));
    }

    @Override
    @Transactional
    public void attachQuestion(ChatQuestion q, UUID sessionId) {
        if (sessionId == null) return;
        var s =
                sessions.findById(sessionId)
                        .filter(v -> v.getWork().getId().equals(q.getWork().getId()))
                        .orElseThrow(() -> new AppException(CHAT_NOT_FOUND));
        open(s);
        idle(s);
        if (s.getNextSequence() > 200) throw new AppException(CHAT_SESSION_LIMIT);
        var now = Instant.now();
        q.attachSession(s, s.appendQuestion(now, TTL), now);
    }

    @Override
    public void requireOpen(ChatQuestion q) {
        if (q != null && q.getSession() != null) open(q.getSession());
    }

    @Override
    public void requireIdle(ChatQuestion q) {
        if (q.getSession() != null) {
            open(q.getSession());
            idle(q.getSession());
        }
    }

    @Override
    @Transactional
    public void touchAfterAnswer(ChatQuestion q) {
        if (q != null && q.getSession() != null) {
            open(q.getSession());
            q.getSession().touch(Instant.now(), TTL);
        }
    }

    @Override
    public Context addConversation(Context context, ChatQuestion q) {
        if (q == null || q.getSession() == null) return context;
        requireOpen(q);
        var recent =
                questions.findBySessionIdAndSequenceNoLessThanOrderBySequenceNoDesc(
                        q.getSession().getId(), q.getSequenceNo(), PageRequest.of(0, 7));
        var turns = new ArrayList<ConversationTurn>();
        int remaining = 8000;
        boolean truncated = recent.size() > 6;
        for (var previous : recent) {
            if (turns.size() == 6) break;
            var run = runs.findById(previous.getCurrentAttemptId()).orElseThrow();
            var result = mapper.result(run.getResultJson());
            if (previous.getQuestion() == null
                    || result == null
                    || run.getFailure() != null
                    || run.getState() == ChatState.INVALIDATED) continue;
            var answer = result.text();
            if (answer.length() > 1000) {
                int end = Character.isHighSurrogate(answer.charAt(999)) ? 999 : 1000;
                answer = answer.substring(0, end);
                truncated = true;
            }
            int length = previous.getQuestion().length() + answer.length();
            if (length > remaining) {
                truncated = true;
                break;
            }
            remaining -= length;
            turns.add(new ConversationTurn(previous.getQuestion(), answer));
        }
        Collections.reverse(turns);
        return context.withConversation(List.copyOf(turns), truncated);
    }

    @Override
    @Transactional
    public void closeWorkSessions(UUID work) {
        for (var s : sessions.findAllByWorkIdAndClosedAtIsNull(work)) erase(s);
    }

    @Override
    public List<UUID> expiredSessionIds() {
        return sessions.expiredIds(Instant.now(), PageRequest.of(0, 100));
    }

    @Override
    @Transactional
    public void expireSession(UUID id) {
        var s = sessions.findById(id).orElse(null);
        if (s == null) return;
        works.findByIdForUpdate(s.getWork().getId())
                .orElseThrow(() -> new AppException(CHAT_NOT_FOUND));
        em.refresh(s);
        if (!s.isOpen(Instant.now())) erase(s);
    }

    private void erase(ChatSession s) {
        s.close(Instant.now());
        for (var q : questions.findAllBySessionId(s.getId())) q.erase();
        for (var run : runs.findAllByQuestionSessionId(s.getId())) {
            boolean pending =
                    usages.findAllByChatRunId(run.getId()).stream()
                            .anyMatch(u -> u.getStatus() == AiTokenUsageStatus.RESERVED);
            run.eraseConversation(pending);
        }
    }

    private ChatSession owned(Long member, UUID work, UUID id, boolean requireOpen) {
        works.getOwnedWorkForUpdate(work, member).getMember().validateActive();
        var s =
                sessions.findById(id)
                        .filter(v -> v.getWork().getId().equals(work))
                        .orElseThrow(() -> new AppException(CHAT_NOT_FOUND));
        if (requireOpen) open(s);
        return s;
    }

    private void open(ChatSession s) {
        if (!s.isOpen(Instant.now())) throw new AppException(CHAT_SESSION_CLOSED);
    }

    private void idle(ChatSession s) {
        if (runs.existsByQuestionSessionIdAndStateInAndDeadlineAfter(
                s.getId(), IN_FLIGHT, Instant.now())) throw new AppException(CHAT_SESSION_BUSY);
    }

    private PageRequest page(int page, int size) {
        if (page < 0 || size < 1 || size > 50) throw new AppException(REQUEST_VALIDATION_FAILED);
        return PageRequest.of(page, size);
    }
}
