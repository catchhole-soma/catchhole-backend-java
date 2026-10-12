package org.monitoring.catchholebackend.domain.chat.service;

import static org.monitoring.catchholebackend.domain.chat.exception.ChatErrorCode.*;
import static org.monitoring.catchholebackend.global.exception.CommonErrorCode.REQUEST_VALIDATION_FAILED;

import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.domain.aitoken.entity.*;
import org.monitoring.catchholebackend.domain.aitoken.repository.*;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.aitoken.type.*;
import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.*;
import org.monitoring.catchholebackend.domain.chat.entity.*;
import org.monitoring.catchholebackend.domain.chat.mapper.ChatMapper;
import org.monitoring.catchholebackend.domain.chat.repository.*;
import org.monitoring.catchholebackend.domain.chat.type.ChatState;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.config.chat.ChatProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatServiceImpl implements ChatService {
    private static final List<ChatState> ACTIVE =
            List.of(ChatState.QUEUED, ChatState.RUNNING, ChatState.SETTLEMENT_PENDING);
    private final ChatQuestionRepository questions;
    private final ChatRunRepository runs;
    private final WorkRepository works;
    private final MemberRepository members;
    private final AiTokenUsageRepository usages;
    private final AiTokenAccountRepository accounts;
    private final AiTokenService tokenService;
    private final ChatProperties properties;
    private final ChatCapability capability;
    private final ChatMapper mapper;
    private final ApplicationEventPublisher events;

    public record Ready(UUID attemptId) {}

    @Override
    @Transactional
    public View submit(Long member, UUID work, String key, Submit request) {
        enabled();
        validate(request);
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}"))
            throw new AppException(REQUEST_VALIDATION_FAILED);
        members.findByIdForUpdate(member)
                .orElseThrow(() -> new AppException(CHAT_NOT_FOUND))
                .validateActive();
        var owned = works.getOwnedWorkForUpdate(work, member);
        String hash = capability.hash(mapper.encode(request));
        var existing = questions.findByMemberIdAndWorkIdAndIdempotencyKey(member, work, key);
        if (existing.isPresent()) {
            if (!existing.get().getInputHash().equals(hash)) throw new AppException(CHAT_CONFLICT);
            return view(runs.findById(existing.get().getCurrentAttemptId()).orElseThrow());
        }
        capacity(member);
        tokenService.getUsage(member);
        var q = ChatQuestion.create(owned, key, hash, request);
        var run = newRun(owned, q, properties.attemptTokenBudget(), null, null);
        questions.save(q);
        runs.save(run);
        events.publishEvent(new Ready(run.getId()));
        return view(run);
    }

    @Override
    @Transactional
    public View get(Long member, UUID work, UUID question) {
        var q = ownedQuestion(member, work, question);
        var run = runs.findById(q.getCurrentAttemptId()).orElseThrow();
        recoverLocked(run);
        return view(run);
    }

    @Override
    @Transactional
    public View retry(Long member, UUID work, UUID question, Retry request) {
        enabled();
        members.findByIdForUpdate(member)
                .orElseThrow(() -> new AppException(CHAT_NOT_FOUND))
                .validateActive();
        var q = ownedQuestion(member, work, question);
        var previous =
                runs.findById(request.previousAttemptId())
                        .orElseThrow(() -> new AppException(CHAT_NOT_FOUND));
        if (previous.getQuestion() == null || !previous.getQuestion().getId().equals(q.getId()))
            throw new AppException(CHAT_SCOPE_INVALID);
        var current = runs.findById(q.getCurrentAttemptId()).orElseThrow();
        if (!current.getId().equals(previous.getId()))
            return view(current); // Lost retry response: no third attempt.
        recoverLocked(current);
        if (q.getQuestion() == null
                || (current.getState() != ChatState.FAILED
                        && current.getState() != ChatState.TIMED_OUT))
            throw new AppException(CHAT_CONFLICT);
        capacity(member);
        var next = newRun(q.getWork(), q, properties.attemptTokenBudget(), null, null);
        runs.save(next);
        events.publishEvent(new Ready(next.getId()));
        return view(next);
    }

    @Override
    @Transactional
    public Context start(UUID id, String token, UUID runner) {
        var run = authorized(id, token);
        activeWork(run);
        if (run.getState() == ChatState.QUEUED && Instant.now().isBefore(run.getDeadline()))
            run.start(runner);
        running(run, runner);
        return mapper.toContext(run, properties);
    }

    @Override
    @Transactional
    public Context context(UUID id, String token, UUID runner) {
        var run = authorized(id, token);
        running(run, runner);
        return mapper.toContext(run, properties);
    }

    @Override
    @Transactional
    public View finish(UUID id, String token, UUID runner, Finish request) {
        var run = authorized(id, token);
        activeWork(run);
        owner(run, runner);
        if ((request.result() == null) == (request.failure() == null))
            throw new AppException(REQUEST_VALIDATION_FAILED);
        String hash = capability.hash(mapper.encode(request));
        if (run.getFinishHash() != null) {
            if (!run.getFinishHash().equals(hash)) throw new AppException(CHAT_CONFLICT);
            return view(run);
        }
        running(run, runner);
        if (request.result() != null) {
            var receipts = mapper.receipts(run.getReceiptJson());
            for (var ref : request.result().sources())
                if (ref.tool() == null
                        || ref.targetId() == null
                        || !receipts.contains(ref.tool() + ":" + ref.targetId()))
                    throw new AppException(CHAT_SCOPE_INVALID);
        }
        run.finish(
                request.result() == null ? null : mapper.encode(request.result()),
                hash,
                request.failure(),
                pending(run));
        return view(run);
    }

    @Override
    @Transactional
    public CallView reserve(UUID id, String token, UUID runner, Reserve request) {
        var run = authorized(id, token);
        running(run, runner);
        var existing = usages.findByRequestId(request.callId());
        if (existing.isPresent()) {
            var u = existing.get();
            if (u.getChatRun() == null
                    || !u.getChatRun().getId().equals(id)
                    || u.getPurpose() != request.purpose()
                    || !u.getModelName().equals(request.model())
                    || u.getReservedTokens() != request.reservedTokens())
                throw new AppException(CHAT_CONFLICT);
            return mapper.call(u);
        }
        boolean allowed =
                run.operational()
                        ? request.purpose() == AiTokenPurpose.OPERATIONAL_BACKFILL
                        : List.of(
                                        AiTokenPurpose.CHAT_INTERPRETATION,
                                        AiTokenPurpose.CHAT_ANSWER,
                                        AiTokenPurpose.CHAT_QUERY_EMBEDDING)
                                .contains(request.purpose());
        if (!allowed) throw new AppException(CHAT_SCOPE_INVALID);
        if (request.reservedTokens() < 1
                || run.getCallCount() >= properties.maxCalls()
                || request.reservedTokens() > run.getTokenBudget() - run.getAllocatedTokens())
            throw new AppException(CHAT_LIMIT_EXCEEDED);
        if (!run.operational()) account(run).reserve(request.reservedTokens());
        run.allocate(request.reservedTokens());
        return mapper.call(
                usages.save(
                        AiTokenUsage.reserveChat(
                                request.callId(),
                                run,
                                request.purpose(),
                                request.model(),
                                request.reservedTokens())));
    }

    @Override
    @Transactional
    public CallView report(UUID id, String token, UUID runner, UUID call, Usage request) {
        var run = authorized(id, token);
        activeWork(run);
        owner(run, runner);
        if (run.getState() == ChatState.INVALIDATED
                || Instant.now().isAfter(run.getUsageDeadline().plus(Duration.ofHours(24))))
            throw new AppException(CHAT_SCOPE_INVALID);
        var u = usages.findByRequestId(call).orElseThrow(() -> new AppException(CHAT_NOT_FOUND));
        if (u.getChatRun() == null || !u.getChatRun().getId().equals(id))
            throw new AppException(CHAT_SCOPE_INVALID);
        if (request.kind() == ReportKind.SETTLE) {
            if (request.inputTokens() == null
                    || request.cachedInputTokens() == null
                    || request.outputTokens() == null
                    || request.outcome() == null) throw new AppException(REQUEST_VALIDATION_FAILED);
            if (u.getStatus() == AiTokenUsageStatus.SETTLED) {
                if (!Objects.equals(u.getInputTokens(), request.inputTokens())
                        || !Objects.equals(u.getCachedInputTokens(), request.cachedInputTokens())
                        || !Objects.equals(u.getOutputTokens(), request.outputTokens())
                        || u.getOutcome() != request.outcome())
                    throw new AppException(CHAT_CONFLICT);
            } else {
                long held =
                        u.getStatus() == AiTokenUsageStatus.RESERVED ? u.getReservedTokens() : 0;
                long total =
                        u.settleChat(
                                request.inputTokens(),
                                request.cachedInputTokens(),
                                request.outputTokens(),
                                request.outcome());
                if (!run.operational()) account(run).settle(held, total);
            }
        } else {
            if (request.inputTokens() != null
                    || request.cachedInputTokens() != null
                    || request.outputTokens() != null
                    || request.outcome() != null) throw new AppException(REQUEST_VALIDATION_FAILED);
            if (request.kind() == ReportKind.RELEASE) {
                if (u.getStatus() == AiTokenUsageStatus.RESERVED) {
                    if (!run.operational()) account(run).release(u.getReservedTokens());
                    u.release(AiTokenUsageOutcome.USAGE_UNAVAILABLE);
                } else if (u.getStatus() != AiTokenUsageStatus.RELEASED)
                    throw new AppException(CHAT_CONFLICT);
            } else if (u.getStatus() != AiTokenUsageStatus.RESERVED
                    && u.getStatus() != AiTokenUsageStatus.UNKNOWN)
                throw new AppException(CHAT_CONFLICT);
            // UNKNOWN reports leave the reservation held until the grace deadline. Missing usage is
            // never zero.
        }
        if (!pending(run)) run.settlementComplete();
        return mapper.call(u);
    }

    @Override
    @Transactional
    public Dispatch operation(UUID id, Operation request) {
        enabled();
        var work =
                works.findByIdForUpdate(request.workId())
                        .orElseThrow(() -> new AppException(CHAT_NOT_FOUND));
        work.requireActive();
        work.getMember().validateActive();
        var old = runs.findById(id);
        if (old.isPresent()) {
            var r = old.get();
            if (!r.operational()
                    || !r.getWork().getId().equals(request.workId())
                    || !r.getActor().equals(request.actor())
                    || !r.getPolicy().equals(request.policy())
                    || r.getTokenBudget() != request.tokenBudget())
                throw new AppException(CHAT_CONFLICT);
            return dispatchOf(r);
        }
        // Administrative bounded attribution only. No indexing or user-account debit occurs here.
        var r = newRun(work, null, request.tokenBudget(), request.actor(), request.policy());
        r.useOperationId(id);
        runs.save(r);
        return dispatchOf(r);
    }

    @Transactional
    public void recover(UUID id) {
        recoverLocked(lock(id));
    }

    @Transactional
    public void invalidateWork(UUID workId) {
        works.findByIdForUpdate(workId).orElseThrow(() -> new AppException(CHAT_NOT_FOUND));
        for (var r : runs.findAllByWorkId(workId)) {
            for (var u : usages.findAllByChatRunId(r.getId()))
                if (u.getStatus() == AiTokenUsageStatus.RESERVED) {
                    if (!r.operational()) account(r).release(u.getReservedTokens());
                    u.markChatUnknown();
                }
            r.invalidate();
        }
    }

    public Map<String, Long> metricSnapshot() {
        var snapshot = new HashMap<String, Long>();
        for (var state : ChatState.values())
            snapshot.put(state.name(), runs.countByQuestionIsNotNullAndState(state));
        snapshot.put(
                "usage_pending",
                usages.countByChatRunIsNotNullAndStatus(AiTokenUsageStatus.RESERVED));
        snapshot.put(
                "usage_unknown",
                usages.countByChatRunIsNotNullAndStatus(AiTokenUsageStatus.UNKNOWN));
        return snapshot;
    }

    public List<UUID> recoveryIds() {
        return runs.findRecoverable(ACTIVE, Instant.now(), PageRequest.of(0, 100));
    }

    public List<UUID> queuedIds() {
        return runs
                .findByStateInAndQuestionIsNotNullOrderByDeadlineAsc(
                        List.of(ChatState.QUEUED), PageRequest.of(0, 100))
                .stream()
                .map(ChatRun::getId)
                .toList();
    }

    @Transactional
    public Dispatch dispatch(UUID id) {
        var r = lock(id);
        if (r.getState() != ChatState.QUEUED || !Instant.now().isBefore(r.getDeadline()))
            throw new AppException(CHAT_CONFLICT);
        activeWork(r);
        return dispatchOf(r);
    }

    public Dispatch dispatchOf(ChatRun r) {
        return mapper.toDispatch(r, capability.issue(r));
    }

    // All run mutations serialize on Work. Source deletion takes the same lock.
    ChatRun authorized(UUID id, String token) {
        var run = lock(id);
        capability.check(run, token);
        return run;
    }

    ChatRun lock(UUID id) {
        var r = runs.findById(id).orElseThrow(() -> new AppException(CHAT_NOT_FOUND));
        works.findByIdForUpdate(r.getWork().getId())
                .orElseThrow(() -> new AppException(CHAT_NOT_FOUND));
        // Re-read after acquiring Work: a competing transaction may have changed this managed
        // entity.
        entityManager.refresh(r);
        return r;
    }

    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    void running(ChatRun r, UUID runner) {
        activeWork(r);
        owner(r, runner);
        if (r.getState() != ChatState.RUNNING
                || !Instant.now().isBefore(r.getDeadline())
                || (!r.operational() && !r.getId().equals(r.getQuestion().getCurrentAttemptId())))
            throw new AppException(CHAT_CONFLICT);
    }

    void owner(ChatRun r, UUID runner) {
        if (runner == null || !runner.equals(r.getRunnerId()))
            throw new AppException(CHAT_SCOPE_INVALID);
    }

    void activeWork(ChatRun r) {
        r.getWork().requireActive();
        r.getWork().getMember().validateActive();
    }

    void recoverLocked(ChatRun r) {
        if (!Instant.now().isBefore(r.getDeadline())) r.timeout();
        if (!Instant.now().isBefore(r.getUsageDeadline())) {
            for (var u : usages.findAllByChatRunId(r.getId()))
                if (u.getStatus() == AiTokenUsageStatus.RESERVED) {
                    if (!r.operational()) account(r).release(u.getReservedTokens());
                    u.markChatUnknown();
                }
            if (!pending(r)) r.settlementComplete();
        }
    }

    private ChatQuestion ownedQuestion(Long member, UUID work, UUID question) {
        var w = works.getOwnedWorkForUpdate(work, member);
        w.getMember().validateActive();
        return questions
                .findByIdAndMemberIdAndWorkId(question, member, work)
                .orElseThrow(() -> new AppException(CHAT_NOT_FOUND));
    }

    private ChatRun newRun(Work w, ChatQuestion q, long budget, String actor, String policy) {
        Instant deadline = Instant.now().plus(properties.executionTimeout());
        return ChatRun.create(
                w, q, deadline, deadline.plus(properties.usageGrace()), budget, actor, policy);
    }

    private AiTokenAccount account(ChatRun r) {
        return accounts.findByMemberId(r.getWork().getMember().getId()).orElseThrow();
    }

    private boolean pending(ChatRun r) {
        return usages.findAllByChatRunId(r.getId()).stream()
                .anyMatch(u -> u.getStatus() == AiTokenUsageStatus.RESERVED);
    }

    private View view(ChatRun r) {
        return mapper.view(r, usages.findAllByChatRunId(r.getId()));
    }

    private void capacity(Long member) {
        if (runs.countByWorkMemberIdAndQuestionIsNotNullAndStateIn(member, ACTIVE)
                >= properties.maxActivePerMember()) throw new AppException(CHAT_LIMIT_EXCEEDED);
    }

    private void enabled() {
        if (!properties.enabled()) throw new AppException(CHAT_DISABLED);
    }

    private void validate(Submit r) {
        if (r.question() == null
                || r.question().isBlank()
                || r.question().length() > properties.maxQuestionCharacters()
                || (r.episodeFrom() != null && r.episodeFrom() < 1)
                || (r.episodeTo() != null && r.episodeTo() < 1)
                || (r.episodeFrom() != null
                        && r.episodeTo() != null
                        && r.episodeFrom() > r.episodeTo()))
            throw new AppException(REQUEST_VALIDATION_FAILED);
    }
}
