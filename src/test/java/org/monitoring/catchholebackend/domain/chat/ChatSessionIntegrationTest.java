package org.monitoring.catchholebackend.domain.chat;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.monitoring.catchholebackend.domain.aitoken.repository.*;
import org.monitoring.catchholebackend.domain.aitoken.type.*;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.*;
import org.monitoring.catchholebackend.domain.chat.repository.*;
import org.monitoring.catchholebackend.domain.chat.service.*;
import org.monitoring.catchholebackend.domain.chat.type.ChatState;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.settingtool.dto.*;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(
        properties = {
            "chat.enabled=true",
            "work.purge.worker-drain=0s",
            "member.withdrawal.retry-delay=0s",
            "chat.capability-secret=chat-test-secret-with-more-than-32-characters",
            "chat.python-url=http://127.0.0.1:1",
            "chat.max-active-per-member=3",
            "internal.api-key=chat-test-key"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("일회성 대화의 순서·만료·본문 삭제·정산 계약")
class ChatSessionIntegrationTest {
    @Autowired ChatServiceImpl service;
    @Autowired ChatSessionService sessions;
    @Autowired ChatSessionRepository sessionRepository;
    @Autowired ChatRunRepository runs;
    @Autowired ChatQuestionRepository questions;
    @Autowired ChatToolAccess tools;
    @Autowired AiTokenUsageRepository usages;
    @Autowired AiTokenAccountRepository accounts;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JwtTokenProvider tokens;
    @Autowired MockMvc mvc;
    @MockitoBean ChatDispatcher dispatcher;
    @Autowired org.monitoring.catchholebackend.domain.work.service.WorkPurgeService purgeService;

    @Autowired
    org.monitoring.catchholebackend.domain.work.service.WorkPurgeProcessor purgeProcessor;

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean org.monitoring.catchholebackend.global.storage.ObjectStorage storage;
    @Autowired org.monitoring.catchholebackend.domain.episode.service.EpisodeService episodeService;

    @Autowired
    org.monitoring.catchholebackend.domain.member.service.MemberWithdrawalService withdrawal;

    @Autowired
    org.monitoring.catchholebackend.domain.member.service.MemberWithdrawalProcessor
            withdrawalProcessor;

    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    Long member;
    UUID work;
    String jwt;

    @BeforeEach
    void setup() {
        tx.executeWithoutResult(
                s -> {
                    var m =
                            Member.register(
                                    UUID.randomUUID() + "@chat.test",
                                    "encoded",
                                    "010"
                                            + String.format(
                                                    "%08d",
                                                    Math.abs(
                                                            UUID.randomUUID()
                                                                            .getLeastSignificantBits()
                                                                    % 100000000)),
                                    "작가");
                    em.persist(m);
                    member = m.getId();
                    jwt = tokens.generateAccessToken(m);
                    var w = Work.create(m, "채팅", WorkGenre.FANTASY, "설명");
                    em.persist(w);
                    work = w.getId();
                });
    }

    UUID session() {
        return sessions.createSession(member, work, UUID.randomUUID()).id();
    }

    View submit(UUID session, String text) {
        return service.submit(
                member, work, UUID.randomUUID().toString(), new Submit(text, null, null, session));
    }

    void finish(View v, String answer) {
        var dispatch = service.dispatch(v.attemptId());
        var runner = UUID.randomUUID();
        service.start(v.attemptId(), dispatch.capability(), runner);
        service.finish(
                v.attemptId(),
                dispatch.capability(),
                runner,
                new Finish(new Result(Outcome.ANSWER, answer, List.of(), List.of()), null));
    }

    @Test
    @DisplayName("새 대화 생성과 질문 재전송은 중복 행을 만들지 않는다")
    void idempotentCreationAndSubmission() {
        var key = UUID.randomUUID();
        var first = sessions.createSession(member, work, key);
        assertThat(sessions.createSession(member, work, key).id()).isEqualTo(first.id());
        var request = new Submit("아리아의 능력은?", null, null, first.id());
        var v = service.submit(member, work, "stable", request);
        assertThat(service.submit(member, work, "stable", request).questionId())
                .isEqualTo(v.questionId());
        assertThat(sessions.listMessages(member, work, first.id(), 0, 20).totalElements())
                .isEqualTo(1);
        assertThat(sessions.getSession(member, work, first.id()).expiresAt())
                .isBetween(Instant.now().plusSeconds(7190), Instant.now().plusSeconds(7201));
    }

    @Test
    @DisplayName("진행 중 질문이 있으면 다른 탭의 새 질문을 거절한다")
    void serialQuestions() {
        var id = session();
        submit(id, "첫 질문");
        assertThatThrownBy(() -> submit(id, "두번째 질문"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("앞선 답변");
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    @DisplayName("정산 대기는 실행 기한 이후에도 새 질문·재시도를 막고 정산 완료 후 허용한다")
    void settlementBlocksUntilReported(boolean expired, boolean retry) {
        var id = session();
        var failed = submit(id, "실패할 이전 질문");
        var prior = service.dispatch(failed.attemptId());
        var priorRunner = UUID.randomUUID();
        service.start(failed.attemptId(), prior.capability(), priorRunner);
        service.finish(
                failed.attemptId(),
                prior.capability(),
                priorRunner,
                new Finish(null, Failure.PROVIDER_FAILURE));

        var pending = submit(id, "정산을 기다리는 질문");
        var dispatch = service.dispatch(pending.attemptId());
        var runner = UUID.randomUUID();
        var call = UUID.randomUUID();
        service.start(pending.attemptId(), dispatch.capability(), runner);
        service.reserve(
                pending.attemptId(),
                dispatch.capability(),
                runner,
                new Reserve(call, AiTokenPurpose.CHAT_ANSWER, "fake", 100));
        service.finish(
                pending.attemptId(),
                dispatch.capability(),
                runner,
                new Finish(new Result(Outcome.ANSWER, "답변", List.of(), List.of()), null));
        if (expired) {
            tx.executeWithoutResult(
                    s ->
                            ReflectionTestUtils.setField(
                                    runs.findById(pending.attemptId()).orElseThrow(),
                                    "deadline",
                                    Instant.now().minusSeconds(1)));
        }
        assertThat(runs.findById(pending.attemptId()).orElseThrow().getState())
                .isEqualTo(ChatState.SETTLEMENT_PENDING);
        java.util.function.Supplier<View> next =
                () ->
                        retry
                                ? service.retry(
                                        member,
                                        work,
                                        failed.questionId(),
                                        new Retry(failed.attemptId()))
                                : submit(id, "다음 질문");
        assertThatThrownBy(next::get)
                .isInstanceOf(AppException.class)
                .hasMessageContaining("앞선 답변");

        service.report(
                pending.attemptId(),
                dispatch.capability(),
                runner,
                call,
                new Usage(ReportKind.SETTLE, 20L, 0L, 5L, AiTokenUsageOutcome.SUCCESS));
        assertThat(runs.findById(pending.attemptId()).orElseThrow().getState())
                .isEqualTo(ChatState.SUCCEEDED);
        assertThat(next.get().state()).isEqualTo(ChatState.QUEUED);
    }

    @Test
    @DisplayName("실행 기한을 넘긴 대기 질문은 새 질문 접수를 막지 않는다")
    void expiredQueuedQuestionDoesNotBlock() {
        var id = session();
        var expired = submit(id, "기한을 넘긴 질문");
        tx.executeWithoutResult(
                s ->
                        ReflectionTestUtils.setField(
                                runs.findById(expired.attemptId()).orElseThrow(),
                                "deadline",
                                Instant.now().minusSeconds(1)));
        assertThat(submit(id, "다음 질문").state()).isEqualTo(ChatState.QUEUED);
    }

    @Test
    @DisplayName("이전 질문과 답변을 최근 문맥으로 전달하고 새 질문 순서를 유지한다")
    void contextAndOrder() {
        var id = session();
        var first = submit(id, "아리아의 능력은?");
        finish(first, "빙결입니다.");
        var second = submit(id, "그건 언제 얻었어?");
        var d = service.dispatch(second.attemptId());
        var ctx = service.start(second.attemptId(), d.capability(), UUID.randomUUID());
        assertThat(ctx.conversation()).containsExactly(new ConversationTurn("아리아의 능력은?", "빙결입니다."));
        var page = sessions.listMessages(member, work, id, 0, 1);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.content().getFirst().sequence()).isEqualTo(1);
        assertThat(sessions.listMessages(member, work, id, 1, 1).content().getFirst().sequence())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("종료는 모든 본문을 지우며 늦은 답변과 새 호출을 차단하고 늦은 정산은 허용한다")
    void closePreservesSettlement() {
        var id = session();
        var v = submit(id, "아리아의 능력은?");
        var d = service.dispatch(v.attemptId());
        var runner = UUID.randomUUID();
        service.start(v.attemptId(), d.capability(), runner);
        var call = UUID.randomUUID();
        service.reserve(
                v.attemptId(),
                d.capability(),
                runner,
                new Reserve(call, AiTokenPurpose.CHAT_ANSWER, "fake", 100));
        sessions.closeSession(member, work, id);
        sessions.closeSession(member, work, id);
        assertThatThrownBy(() -> service.get(member, work, v.questionId()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("종료");
        assertThatThrownBy(
                        () ->
                                service.finish(
                                        v.attemptId(),
                                        d.capability(),
                                        runner,
                                        new Finish(
                                                new Result(
                                                        Outcome.ANSWER,
                                                        "늦은 답변",
                                                        List.of(),
                                                        List.of()),
                                                null)))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(
                        () ->
                                service.reserve(
                                        v.attemptId(),
                                        d.capability(),
                                        runner,
                                        new Reserve(
                                                UUID.randomUUID(),
                                                AiTokenPurpose.CHAT_ANSWER,
                                                "fake",
                                                100)))
                .isInstanceOf(AppException.class);
        service.report(
                v.attemptId(),
                d.capability(),
                runner,
                call,
                new Usage(ReportKind.SETTLE, 20L, 0L, 5L, AiTokenUsageOutcome.SUCCESS));
        tx.executeWithoutResult(
                s ->
                        assertThat(usages.findByRequestId(call).orElseThrow().getStatus())
                                .isEqualTo(AiTokenUsageStatus.SETTLED));
        tx.executeWithoutResult(
                s -> {
                    assertThat(questions.findById(v.questionId()).orElseThrow().getQuestion())
                            .isNull();
                    assertThat(runs.findById(v.attemptId()).orElseThrow().getResultJson()).isNull();
                    assertThat(runs.findById(v.attemptId()).orElseThrow().getReceiptJson())
                            .isNull();
                });
        assertThat(sessions.listSessions(member, work, 0, 20).content()).isEmpty();
    }

    @Test
    @DisplayName("만료는 정리 스케줄 전에도 조회와 질문을 막고 정리 시 본문을 삭제한다")
    void expiryAndCleanup() {
        var id = session();
        var v = submit(id, "질문");
        finish(v, "답변");
        tx.executeWithoutResult(
                s ->
                        ReflectionTestUtils.setField(
                                sessionRepository.findById(id).orElseThrow(),
                                "expiresAt",
                                Instant.now().minusSeconds(1)));
        assertThatThrownBy(() -> sessions.getSession(member, work, id))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> submit(id, "다음")).isInstanceOf(AppException.class);
        assertThat(sessions.expiredSessionIds()).contains(id);
        sessions.expireSession(id);
        assertThat(questions.findById(v.questionId()).orElseThrow().getQuestion()).isNull();
        assertThat(runs.findById(v.attemptId()).orElseThrow().getResultJson()).isNull();
    }

    @Test
    @DisplayName("조회는 만료를 연장하지 않고 새 답변은 2시간을 연장한다")
    void readsDoNotTouchExpiry() {
        var id = session();
        var initial = sessions.getSession(member, work, id).expiresAt();
        sessions.listMessages(member, work, id, 0, 20);
        assertThat(sessions.getSession(member, work, id).expiresAt()).isEqualTo(initial);
        var v = submit(id, "질문");
        tx.executeWithoutResult(
                s ->
                        ReflectionTestUtils.setField(
                                sessionRepository.findById(id).orElseThrow(),
                                "expiresAt",
                                Instant.now().plusSeconds(30)));
        finish(v, "답변");
        assertThat(sessions.getSession(member, work, id).expiresAt())
                .isAfter(Instant.now().plusSeconds(7190));
    }

    @Test
    @DisplayName("다른 작품에 속한 대화 연결과 조회를 거절한다")
    void crossWorkRejected() {
        var id = session();
        var other =
                tx.execute(
                        s -> {
                            var w =
                                    Work.create(
                                            em.find(Member.class, member),
                                            "다른 작품",
                                            WorkGenre.FANTASY,
                                            "");
                            em.persist(w);
                            return w.getId();
                        });
        assertThatThrownBy(() -> sessions.getSession(member, other, id))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(
                        () -> service.submit(member, other, "x", new Submit("질문", null, null, id)))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> sessions.listMessages(member + 999999, work, id, 0, 20))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("원문 무효화는 대화 본문과 후속 문맥까지 차단한다")
    void sourceInvalidationClosesSessions() {
        var id = session();
        var v = submit(id, "질문");
        finish(v, "이전 답변");
        service.invalidateWork(work);
        assertThatThrownBy(() -> sessions.listMessages(member, work, id, 0, 20))
                .isInstanceOf(AppException.class);
        assertThat(runs.findById(v.attemptId()).orElseThrow().getResultJson()).isNull();
        assertThat(questions.findById(v.questionId()).orElseThrow().getQuestion()).isNull();
    }

    @Test
    @DisplayName("문맥은 최대 6턴이며 이전 답변을 제한하고 생략 여부를 알린다")
    void boundedContext() {
        var id = session();
        for (int i = 0; i < 8; i++) {
            var v = submit(id, "질문 " + i);
            finish(v, "답".repeat(1200));
        }
        var v = submit(id, "다음 질문");
        var d = service.dispatch(v.attemptId());
        var ctx = service.start(v.attemptId(), d.capability(), UUID.randomUUID());
        assertThat(ctx.conversation()).hasSize(6);
        assertThat(ctx.conversation().getFirst().question()).isEqualTo("질문 2");
        assertThat(ctx.conversation().getFirst().answer()).hasSize(1000);
        assertThat(ctx.conversationTruncated()).isTrue();
    }

    @Test
    @DisplayName("API는 인증·페이지 상한·종료 상태와 공통 응답을 유지한다")
    void httpContract() throws Exception {
        var id = session();
        mvc.perform(get("/api/v1/works/{work}/chat-sessions/{id}/messages", work, id))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/api/v1/works/{work}/chat-sessions/{id}", work, id)
                                .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id.toString()));
        mvc.perform(
                        get("/api/v1/works/{work}/chat-sessions/{id}/messages?size=51", work, id)
                                .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        delete("/api/v1/works/{work}/chat-sessions/{id}", work, id)
                                .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());
        mvc.perform(
                        get("/api/v1/works/{work}/chat-sessions/{id}", work, id)
                                .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("CHAT_SESSION_CLOSED"));
    }

    @Test
    @DisplayName("서로 다른 탭의 동시 질문은 한 건만 접수한다")
    void concurrentSubmission() throws Exception {
        var id = session();
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++)
                tasks.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        submit(id, "동시 질문");
                                        return true;
                                    } catch (AppException rejected) {
                                        assertThat(rejected.getMessage()).contains("앞선 답변");
                                        return false;
                                    }
                                }));
            gate.countDown();
            int accepted = 0;
            for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1);
            assertThat(sessions.listMessages(member, work, id, 0, 20).totalElements()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("문맥을 자를 때 이모지의 UTF-16 쌍을 분리하지 않는다")
    void unicodeContextBoundary() {
        var id = session();
        var first = submit(id, "질문");
        finish(first, "가".repeat(999) + "😀뒤");
        var second = submit(id, "후속");
        var d = service.dispatch(second.attemptId());
        var ctx = service.start(second.attemptId(), d.capability(), UUID.randomUUID());
        assertThat(ctx.conversation().getFirst().answer()).isEqualTo("가".repeat(999));
        assertThat(ctx.conversationTruncated()).isTrue();
    }

    @Test
    @DisplayName("작품 파기 예약은 대화 노출을 막고 저장소 재시도 후 세션까지 삭제한다")
    void workPurgeRemovesSessionsAfterRetry() {
        var id = session();
        var v = submit(id, "질문");
        finish(v, "답변");
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS episode_chunks (id UUID PRIMARY KEY, episode_id UUID"
                        + " NOT NULL)");
        org.mockito.Mockito.when(
                        storage.purgePrefixes(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(
                        new org.monitoring.catchholebackend.global.storage.ObjectStoragePurgeResult(
                                1, 0, 1));
        var request =
                purgeService.requestPurge(
                        member,
                        work,
                        new org.monitoring.catchholebackend.domain.work.dto.request
                                .WorkPurgeCreateRequest("영구 삭제"));
        assertThatThrownBy(() -> sessions.listMessages(member, work, id, 0, 20))
                .isInstanceOf(AppException.class);
        assertThat(questions.findById(v.questionId()).orElseThrow().getQuestion()).isNull();
        purgeProcessor.processPendingRequests();
        assertThat(sessionRepository.findById(id)).isPresent();
        org.mockito.Mockito.when(
                        storage.purgePrefixes(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(
                        new org.monitoring.catchholebackend.global.storage.ObjectStoragePurgeResult(
                                1, 1, 0));
        purgeService.retryPurge(member, request.requestId());
        purgeProcessor.processPendingRequests();
        assertThat(sessionRepository.findById(id)).isEmpty();
        assertThat(questions.findById(v.questionId())).isEmpty();
        assertThat(runs.findById(v.attemptId())).isEmpty();
    }

    @Test
    @DisplayName("실제 회차 삭제 이벤트는 저장 답변과 후속 문맥을 함께 차단한다")
    void episodeDeletionRevokesConversation() {
        var id = session();
        var v = submit(id, "원문 질문");
        finish(v, "파기할 원문 답변");
        var episode =
                tx.execute(
                        s -> {
                            var e =
                                    org.monitoring.catchholebackend.domain.episode.entity.Episode
                                            .create(
                                                    em.find(Work.class, work),
                                                    null,
                                                    1,
                                                    "제목",
                                                    "synthetic/source.txt",
                                                    "v1",
                                                    "a".repeat(64),
                                                    10);
                            em.persist(e);
                            return e.getId();
                        });
        episodeService.deleteEpisode(member, work, episode);
        assertThatThrownBy(() -> sessions.listMessages(member, work, id, 0, 20))
                .isInstanceOf(AppException.class);
        assertThat(questions.findById(v.questionId()).orElseThrow().getQuestion()).isNull();
        assertThat(runs.findById(v.attemptId()).orElseThrow().getResultJson()).isNull();
        assertThatThrownBy(() -> submit(id, "후속 질문")).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("회원 탈퇴는 즉시 대화 접근을 막고 작품 파기 후 세션 행까지 제거한다")
    void withdrawalRemovesConversation() {
        var id = session();
        var v = submit(id, "질문");
        finish(v, "답변");
        tx.executeWithoutResult(
                s ->
                        ReflectionTestUtils.setField(
                                em.find(Member.class, member),
                                "passwordHash",
                                passwordEncoder.encode("test-password")));
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS episode_chunks (id UUID PRIMARY KEY, episode_id UUID"
                        + " NOT NULL)");
        org.mockito.Mockito.when(
                        storage.purgePrefixes(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(
                        new org.monitoring.catchholebackend.global.storage.ObjectStoragePurgeResult(
                                1, 1, 0));
        withdrawal.requestWithdrawal(
                member,
                new org.monitoring.catchholebackend.domain.member.dto.request
                        .MemberWithdrawalCreateRequest("test-password", "회원 탈퇴"));
        assertThatThrownBy(() -> sessions.listMessages(member, work, id, 0, 20))
                .isInstanceOf(AppException.class);
        withdrawalProcessor.processPendingRequests();
        purgeProcessor.processPendingRequests();
        withdrawalProcessor.processPendingRequests();
        assertThat(sessionRepository.findById(id)).isEmpty();
        assertThat(questions.findById(v.questionId())).isEmpty();
        assertThat(runs.findById(v.attemptId())).isEmpty();
        Member remaining = tx.execute(s -> em.find(Member.class, member));
        assertThat(remaining).isNull();
    }
}
