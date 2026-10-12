package org.monitoring.catchholebackend.domain.chat;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.*;
import org.monitoring.catchholebackend.domain.aitoken.repository.*;
import org.monitoring.catchholebackend.domain.aitoken.type.*;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.*;
import org.monitoring.catchholebackend.domain.chat.repository.*;
import org.monitoring.catchholebackend.domain.chat.service.*;
import org.monitoring.catchholebackend.domain.chat.type.ChatState;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.settingtool.dto.*;
import org.monitoring.catchholebackend.domain.settingtool.type.SettingTool;
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
            "chat.capability-secret=chat-test-secret-with-more-than-32-characters",
            "chat.python-url=http://127.0.0.1:1",
            "chat.max-active-per-member=3",
            "internal.api-key=chat-test-key"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("채팅 실행의 멱등성·권한·정산 통합 계약")
class ChatExecutionIntegrationTest {
    @Autowired ChatServiceImpl service;
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

    View submit() {
        return service.submit(
                member, work, UUID.randomUUID().toString(), new Submit("아리아의 마력은?", 2, 5));
    }

    record Running(View view, String token, UUID runner) {}

    Running start() {
        var v = submit();
        var d = service.dispatch(v.attemptId());
        var runner = UUID.randomUUID();
        service.start(v.attemptId(), d.capability(), runner);
        return new Running(v, d.capability(), runner);
    }

    CallView reserve(Running r, UUID call, long amount) {
        return service.reserve(
                r.view.attemptId(),
                r.token,
                r.runner,
                new Reserve(call, AiTokenPurpose.CHAT_ANSWER, "test-model", amount));
    }

    void settle(Running r, UUID call) {
        service.report(
                r.view.attemptId(),
                r.token,
                r.runner,
                call,
                new Usage(ReportKind.SETTLE, 20L, 10L, 5L, AiTokenUsageOutcome.SUCCESS));
    }

    Finish answer() {
        return new Finish(
                new Result(Outcome.INSUFFICIENT_EVIDENCE, "확인된 근거가 부족합니다.", List.of(), List.of()),
                null);
    }

    long[] balance() {
        return tx.execute(
                s -> {
                    var a = accounts.findByMemberId(member).orElseThrow();
                    return new long[] {a.getUsedTokens(), a.getReservedTokens()};
                });
    }

    @Autowired org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService analysisTokens;
    @Autowired org.springframework.context.ApplicationEventPublisher events;
    @Autowired org.monitoring.catchholebackend.domain.work.service.WorkPurgeService purge;

    @Test
    @DisplayName("분석과 채팅의 동시 예약이 같은 마지막 잔액을 중복 소비하지 않는다")
    void analysisAndChatShareAccountLock() throws Exception {
        var r = start();
        var job =
                tx.execute(
                        status -> {
                            var w = em.find(Work.class, work);
                            var episode =
                                    org.monitoring.catchholebackend.domain.episode.entity.Episode
                                            .create(w, null, 1, "1화", "key", "v1", "hash", 10);
                            em.persist(episode);
                            var j =
                                    org.monitoring.catchholebackend.domain.analysis.entity
                                            .AnalysisJob.create(
                                            w,
                                            null,
                                            episode,
                                            org.monitoring.catchholebackend.domain.analysis.type
                                                    .AnalysisJobType.SETTING_EXTRACTION);
                            var lease =
                                    j.claim("test", "test", LocalDateTime.now().plusMinutes(10));
                            em.persist(j);
                            var a = accounts.findByMemberId(member).orElseThrow();
                            a.reserve(a.remainingTokens() - 100);
                            return new UUID[] {j.getId(), lease};
                        });
        var probe = UUID.randomUUID();
        analysisTokens.reserve(
                new org.monitoring.catchholebackend.domain.aitoken.dto.request
                        .AiTokenReserveRequest(
                        probe, job[0], AiTokenPurpose.SETTING_EXTRACTION, 1, "test", 100),
                job[1]);
        analysisTokens.release(
                probe,
                new org.monitoring.catchholebackend.domain.aitoken.dto.request
                        .AiTokenReleaseRequest(AiTokenUsageOutcome.USAGE_UNAVAILABLE));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first =
                    pool.submit(
                            () -> {
                                try {
                                    reserve(r, UUID.randomUUID(), 100);
                                    return true;
                                } catch (AppException e) {
                                    return false;
                                }
                            });
            var second =
                    pool.submit(
                            () -> {
                                try {
                                    analysisTokens.reserve(
                                            new org.monitoring.catchholebackend.domain.aitoken.dto
                                                    .request.AiTokenReserveRequest(
                                                    UUID.randomUUID(),
                                                    job[0],
                                                    AiTokenPurpose.SETTING_EXTRACTION,
                                                    1,
                                                    "test",
                                                    100),
                                            job[1]);
                                    return true;
                                } catch (AppException e) {
                                    return false;
                                }
                            });
            assertThat(
                            java.util.List.of(
                                    first.get(10, TimeUnit.SECONDS),
                                    second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("원문 파기 이벤트와 같은 커밋에서 저장 답변이 무효화된다")
    void sourcePurgeHook() {
        var r = start();
        service.finish(r.view.attemptId(), r.token, r.runner, answer());
        tx.executeWithoutResult(
                status -> {
                    var episode =
                            org.monitoring.catchholebackend.domain.episode.entity.Episode.create(
                                    em.find(Work.class, work),
                                    null,
                                    1,
                                    "1화",
                                    "key",
                                    "v1",
                                    "hash",
                                    10);
                    em.persist(episode);
                    var request =
                            org.monitoring.catchholebackend.domain.episode.entity
                                    .EpisodeSourcePurgeRequest.requestDeletion(episode, null);
                    em.persist(request);
                    events.publishEvent(
                            new org.monitoring.catchholebackend.domain.episode.event
                                    .EpisodeSourcePurgeRequestedEvent(request.getId()));
                });
        assertThat(service.get(member, work, r.view.questionId()).state())
                .isEqualTo(ChatState.INVALIDATED);
    }

    @Test
    @DisplayName("작품 파기 진입 시 채팅 예약이 반환되고 내부 호출이 중단된다")
    void workPurgeHook() {
        var r = start();
        reserve(r, UUID.randomUUID(), 100);
        purge.requestPurge(
                member,
                work,
                new org.monitoring.catchholebackend.domain.work.dto.request.WorkPurgeCreateRequest(
                        "영구 삭제"));
        assertThat(balance()).containsExactly(0, 0);
        assertThatThrownBy(() -> service.context(r.view.attemptId(), r.token, r.runner))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("동시에 접수한 같은 키는 실행 하나이고 입력이 바뀌면 충돌한다")
    void concurrentIdempotency() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<Future<View>>();
            for (int i = 0; i < 4; i++)
                futures.add(
                        pool.submit(
                                () ->
                                        service.submit(
                                                member,
                                                work,
                                                "same",
                                                new Submit("질문", null, null))));
            var ids = new HashSet<UUID>();
            for (var f : futures) ids.add(f.get(15, TimeUnit.SECONDS).attemptId());
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThatThrownBy(
                        () -> service.submit(member, work, "same", new Submit("다른 질문", null, null)))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("완료 재전송은 같은 결과이며 정산 중복은 잔액을 다시 바꾸지 않는다")
    void settlementAndResultReplay() {
        var r = start();
        var id = UUID.randomUUID();
        reserve(r, id, 100);
        reserve(r, id, 100);
        var saved = service.finish(r.view.attemptId(), r.token, r.runner, answer());
        assertThat(saved.state()).isEqualTo(ChatState.SETTLEMENT_PENDING);
        settle(r, id);
        settle(r, id);
        assertThat(balance()).containsExactly(25, 0);
        assertThat(service.finish(r.view.attemptId(), r.token, r.runner, answer()).state())
                .isEqualTo(ChatState.SUCCEEDED);
        assertThatThrownBy(
                        () ->
                                service.report(
                                        r.view.attemptId(),
                                        r.token,
                                        r.runner,
                                        id,
                                        new Usage(
                                                ReportKind.SETTLE,
                                                21L,
                                                10L,
                                                5L,
                                                AiTokenUsageOutcome.SUCCESS)))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("다른 실행·runner·위조 인증·잘못된 목적의 예약은 거절한다")
    void scopeChecks() {
        var a = start();
        var b = start();
        var id = UUID.randomUUID();
        reserve(a, id, 100);
        assertThatThrownBy(() -> service.start(a.view.attemptId(), a.token, UUID.randomUUID()))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.context(a.view.attemptId(), "bad", a.runner))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> reserve(b, id, 100)).isInstanceOf(AppException.class);
        assertThatThrownBy(
                        () ->
                                service.reserve(
                                        a.view.attemptId(),
                                        a.token,
                                        a.runner,
                                        new Reserve(
                                                UUID.randomUUID(),
                                                AiTokenPurpose.SETTING_EXTRACTION,
                                                "model",
                                                100)))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> reserve(a, id, 101)).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("예약량 부족이면 호출 권한을 주지 않고 기존 예약도 유지한다")
    void quota() {
        var a = start();
        tx.executeWithoutResult(
                s -> {
                    var account = accounts.findByMemberId(member).orElseThrow();
                    account.reserve(account.remainingTokens() - 50);
                });
        assertThatThrownBy(() -> reserve(a, UUID.randomUUID(), 100))
                .isInstanceOf(AppException.class);
        int count = tx.execute(s -> usages.findAllByChatRunId(a.view.attemptId()).size());
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("타임아웃 재시도 후 과거 답변은 거절하고 과거의 실측 사용량은 정산한다")
    void staleGenerationAndUnknown() {
        var r = start();
        var id = UUID.randomUUID();
        reserve(r, id, 100);
        service.report(
                r.view.attemptId(),
                r.token,
                r.runner,
                id,
                new Usage(ReportKind.UNKNOWN, null, null, null, null));
        assertThat(balance()).containsExactly(0, 100);
        tx.executeWithoutResult(
                s -> {
                    var run = runs.findById(r.view.attemptId()).orElseThrow();
                    ReflectionTestUtils.setField(run, "deadline", Instant.now().minusSeconds(20));
                    ReflectionTestUtils.setField(
                            run, "usageDeadline", Instant.now().minusSeconds(1));
                });
        service.recover(r.view.attemptId());
        assertThat(balance()).containsExactly(0, 0);
        var next = service.retry(member, work, r.view.questionId(), new Retry(r.view.attemptId()));
        assertThat(
                        service.retry(
                                        member,
                                        work,
                                        r.view.questionId(),
                                        new Retry(r.view.attemptId()))
                                .attemptId())
                .isEqualTo(next.attemptId());
        assertThatThrownBy(() -> service.finish(r.view.attemptId(), r.token, r.runner, answer()))
                .isInstanceOf(AppException.class);
        settle(r, id);
        assertThat(balance()).containsExactly(25, 0);
    }

    @Test
    @DisplayName("호출 전 해제는 멱등이며 해제 뒤 실측 정산으로 재사용할 수 없다")
    void release() {
        var r = start();
        var id = UUID.randomUUID();
        reserve(r, id, 100);
        var release = new Usage(ReportKind.RELEASE, null, null, null, null);
        service.report(r.view.attemptId(), r.token, r.runner, id, release);
        service.report(r.view.attemptId(), r.token, r.runner, id, release);
        assertThat(balance()).containsExactly(0, 0);
        assertThatThrownBy(() -> settle(r, id)).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("작품 무효화는 답변 본문·질문·근거를 지우고 늦은 완료를 막는다")
    void invalidation() {
        var r = start();
        var id = UUID.randomUUID();
        reserve(r, id, 100);
        service.finish(r.view.attemptId(), r.token, r.runner, answer());
        service.invalidateWork(work);
        var v = service.get(member, work, r.view.questionId());
        assertThat(v.state()).isEqualTo(ChatState.INVALIDATED);
        assertThat(v.result()).isNull();
        assertThat(balance()).containsExactly(0, 0);
        tx.executeWithoutResult(
                s -> {
                    assertThat(questions.findById(r.view.questionId()).orElseThrow().getQuestion())
                            .isNull();
                    assertThat(runs.findById(r.view.attemptId()).orElseThrow().getReceiptJson())
                            .isNull();
                });
        assertThatThrownBy(() -> service.finish(r.view.attemptId(), r.token, r.runner, answer()))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("운영 백필은 사용자 잔액과 분리하고 예산과 귀속을 고정한다")
    void operation() {
        var q = submit();
        var id = UUID.randomUUID();
        var operation = new Operation(work, "operator", "historical-index-no-user-charge", 200);
        var d = service.operation(id, operation);
        var runner = UUID.randomUUID();
        assertThat(service.start(id, d.capability(), runner).operational()).isTrue();
        var call = UUID.randomUUID();
        service.reserve(
                id,
                d.capability(),
                runner,
                new Reserve(call, AiTokenPurpose.OPERATIONAL_BACKFILL, "embedding", 100));
        service.report(
                id,
                d.capability(),
                runner,
                call,
                new Usage(ReportKind.SETTLE, 10L, 0L, 0L, AiTokenUsageOutcome.SUCCESS));
        assertThat(balance()).containsExactly(0, 0);
        assertThat(service.operation(id, operation).attemptId()).isEqualTo(id);
        assertThatThrownBy(() -> service.operation(id, new Operation(work, "other", "same", 200)))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("출처 범위를 좁히고 조회하지 않은 인용은 저장할 수 없다")
    void toolScope() {
        var r = start();
        var query =
                new SettingToolQuery(
                        SettingTool.CHARACTER_HISTORY,
                        UUID.randomUUID(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        20);
        var a = tools.begin(r.view.attemptId(), r.token, r.runner, query);
        assertThat(a.query().episodeFrom()).isEqualTo(2);
        assertThat(a.query().episodeTo()).isEqualTo(5);
        var result =
                new Result(
                        Outcome.ANSWER,
                        "답변",
                        List.of(
                                new SettingToolResponse.Reference(
                                        SettingTool.CHARACTER_EVIDENCE, UUID.randomUUID())),
                        List.of());
        assertThatThrownBy(
                        () ->
                                service.finish(
                                        r.view.attemptId(),
                                        r.token,
                                        r.runner,
                                        new Finish(result, null)))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("공개 API는 JWT와 멱등성 키를 요구하고 내부 API는 실행 인증도 요구한다")
    void httpContract() throws Exception {
        String url = "/api/v1/works/" + work + "/chat-questions";
        mvc.perform(post(url).contentType("application/json").content("{\"question\":\"질문\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post(url)
                                .header("Authorization", "Bearer " + jwt)
                                .header("Idempotency-Key", "http-one")
                                .contentType("application/json")
                                .content("{\"question\":\"질문\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.state").value("QUEUED"));
        var r = start();
        mvc.perform(
                        post("/api/internal/v1/chat-runs/" + r.view.attemptId() + "/start")
                                .header("X-Internal-Api-Key", "chat-test-key"))
                .andExpect(status().isForbidden());
    }
}
