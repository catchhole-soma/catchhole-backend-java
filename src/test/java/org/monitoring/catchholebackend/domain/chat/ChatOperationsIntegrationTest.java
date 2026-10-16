package org.monitoring.catchholebackend.domain.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.*;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenAccount;
import org.monitoring.catchholebackend.domain.aitoken.repository.*;
import org.monitoring.catchholebackend.domain.aitoken.type.*;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.*;
import org.monitoring.catchholebackend.domain.chat.repository.*;
import org.monitoring.catchholebackend.domain.chat.service.*;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.config.chat.ChatProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

@SpringBootTest(properties = {
    "chat.enabled=false",
    "chat.operations-enabled=true",
    "chat.python-url=",
    "chat.capability-secret=operations-test-secret-more-than-32-characters",
    "internal.api-key=operations-test-internal-key"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("공개 채팅 비활성 상태의 운영 백필·인증·복구 계약")
class ChatOperationsIntegrationTest {
    private static final String ROOT = "/api/internal/v1/chat-runs";
    private static final String KEY = "operations-test-internal-key";
    @Autowired ChatServiceImpl service;
    @Autowired ChatRunRepository runs;
    @Autowired ChatQuestionRepository questions;
    @Autowired AiTokenUsageRepository usages;
    @Autowired AiTokenAccountRepository accounts;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JwtTokenProvider tokens;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean ChatProperties properties;
    @MockitoBean ChatDispatcher dispatcher;
    Long member;
    UUID work;
    String jwt;

    @BeforeEach
    void setup() {
        tx.executeWithoutResult(status -> {
            var m = Member.register(UUID.randomUUID() + "@operations.test", "encoded", null, "작가");
            em.persist(m);
            member = m.getId();
            jwt = tokens.generateAccessToken(m);
            var w = Work.create(m, "운영 백필", WorkGenre.FANTASY, null);
            em.persist(w);
            work = w.getId();
            var account = AiTokenAccount.create(m, 1000);
            account.reserve(25);
            account.settle(25, 10);
            account.reserve(15);
            em.persist(account);
        });
    }

    @Test
    @DisplayName("준비 상태는 내부 키를 인증하고 생성·차감 없이 실제 게이트 설정만 반환한다")
    void readinessAuthenticatesWithoutAdmission() throws Exception {
        long runCount = runs.count(), questionCount = questions.count(), usageCount = usages.count();
        mvc.perform(get(ROOT + "/operations/readiness")).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT + "/operations/readiness").header("X-Internal-Api-Key", "invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT + "/operations/readiness").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isUnauthorized());
        mvc.perform(internal(get(ROOT + "/operations/readiness")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operationsEnabled").value(true))
                .andExpect(jsonPath("$.data.publicChatEnabled").value(false))
                .andExpect(jsonPath("$.data.capabilityConfigured").value(true))
                .andExpect(jsonPath("$.data.capabilitySecret").doesNotExist());
        doReturn(false).when(properties).operationsEnabled();
        mvc.perform(internal(get(ROOT + "/operations/readiness")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operationsEnabled").value(false));
        assertThat(runs.count()).isEqualTo(runCount);
        assertThat(questions.count()).isEqualTo(questionCount);
        assertThat(usages.count()).isEqualTo(usageCount);
        assertBalanceUnchanged();
        verifyNoInteractions(dispatcher);
    }

    @Test
    @DisplayName("공개 접수는 닫혀 있어도 인증된 운영 백필을 실행·정산하고 사용자 잔액은 유지한다")
    void backfillWhilePublicChatDisabled() throws Exception {
        mvc.perform(post("/api/v1/works/{work}/chat-questions", work)
                        .header("Authorization", "Bearer " + jwt).header("Idempotency-Key", "closed")
                        .contentType("application/json").content("{\"question\":\"질문\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CHAT_DISABLED"));
        mvc.perform(post("/api/v1/works/{work}/chat-sessions", work)
                        .header("Authorization", "Bearer " + jwt).contentType("application/json")
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isServiceUnavailable());
        var id = UUID.randomUUID();
        var request = operationRequest();
        mvc.perform(operation(id, request)).andExpect(status().isUnauthorized());
        mvc.perform(operation(id, request).header("X-Internal-Api-Key", "invalid"))
                .andExpect(status().isUnauthorized());
        var response = mvc.perform(internal(operation(id, request))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attemptId").value(id.toString())).andReturn();
        String capability = json.readTree(response.getResponse().getContentAsString()).at("/data/capability").asText();
        var runner = UUID.randomUUID();
        mvc.perform(internal(post(ROOT + "/{id}/start", id)).header("X-Chat-Runner", runner))
                .andExpect(status().isForbidden());
        mvc.perform(internal(post(ROOT + "/{id}/start", id)).header("X-Chat-Runner", runner)
                        .header("X-Chat-Capability", capability))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.operational").value(true));
        var call = UUID.randomUUID();
        var reserve = new Reserve(call, AiTokenPurpose.OPERATIONAL_BACKFILL, "embedding", 100);
        mvc.perform(internal(post(ROOT + "/{id}/calls", id)).header("X-Chat-Runner", UUID.randomUUID())
                        .header("X-Chat-Capability", capability).contentType("application/json")
                        .content(json.writeValueAsString(reserve)))
                .andExpect(status().isForbidden());
        service.reserve(id, capability, runner, reserve);
        service.report(id, capability, runner, call,
                new Usage(ReportKind.SETTLE, 40L, 0L, 0L, AiTokenUsageOutcome.SUCCESS));
        AiTokenPurpose purpose = tx.execute(status -> usages.findByRequestId(call).orElseThrow().getPurpose());
        assertThat(purpose).isEqualTo(AiTokenPurpose.OPERATIONAL_BACKFILL);
        assertBalanceUnchanged();
        verifyNoInteractions(dispatcher);
    }

    @Test
    @DisplayName("운영 접수를 닫아도 같은 요청 재전송은 유지하고 공개 채팅과 독립적으로 차단한다")
    void closedGateBlocksOnlyNewOperations() throws Exception {
        var id = UUID.randomUUID();
        var request = operationRequest();
        var first = service.operation(id, request);
        doReturn(false).when(properties).operationsEnabled();
        doReturn(true).when(properties).enabled();
        mvc.perform(internal(operation(UUID.randomUUID(), request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CHAT_DISABLED"));
        mvc.perform(internal(operation(id, request))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capability").value(first.capability()));
        mvc.perform(internal(operation(id, new Operation(work, "different", request.policy(), request.tokenBudget()))))
                .andExpect(status().isConflict());
        assertThat(service.submit(member, work, UUID.randomUUID().toString(), new Submit("질문", null, null)))
                .isNotNull();
        mvc.perform(internal(get(ROOT + "/operations/readiness")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operationsEnabled").value(false))
                .andExpect(jsonPath("$.data.publicChatEnabled").value(true));
        assertBalanceUnchanged();
    }

    @Test
    @DisplayName("두 게이트를 닫아도 기존 정산·해제·UNKNOWN 복구와 늦은 보고는 계속한다")
    void reportsAndRecoveryContinueWithBothGatesDisabled() {
        var id = UUID.randomUUID();
        var d = service.operation(id, operationRequest());
        var runner = UUID.randomUUID();
        service.start(id, d.capability(), runner);
        var settled = UUID.randomUUID();
        var released = UUID.randomUUID();
        var late = UUID.randomUUID();
        for (var call : new UUID[] {settled, released, late}) {
            service.reserve(id, d.capability(), runner,
                    new Reserve(call, AiTokenPurpose.OPERATIONAL_BACKFILL, "embedding", 100));
        }
        doReturn(false).when(properties).operationsEnabled();
        service.report(id, d.capability(), runner, settled,
                new Usage(ReportKind.SETTLE, 30L, 0L, 0L, AiTokenUsageOutcome.SUCCESS));
        service.report(id, d.capability(), runner, released,
                new Usage(ReportKind.RELEASE, null, null, null, null));
        tx.executeWithoutResult(status -> {
            var run = runs.findById(id).orElseThrow();
            ReflectionTestUtils.setField(run, "deadline", Instant.now().minusSeconds(2));
            ReflectionTestUtils.setField(run, "usageDeadline", Instant.now().minusSeconds(1));
        });
        service.recover(id);
        assertThat(usageStatus(late)).isEqualTo(AiTokenUsageStatus.UNKNOWN);
        service.report(id, d.capability(), runner, late,
                new Usage(ReportKind.SETTLE, 20L, 0L, 0L, AiTokenUsageOutcome.SUCCESS));
        assertThat(usageStatus(late)).isEqualTo(AiTokenUsageStatus.SETTLED);
        assertThat(usageStatus(released)).isEqualTo(AiTokenUsageStatus.RELEASED);
        assertBalanceUnchanged();
    }

    private Operation operationRequest() {
        return new Operation(work, "operator", "RETRIEVAL_INDEX_SERVICE_PAID_V1", 1000);
    }

    private MockHttpServletRequestBuilder operation(UUID id, Operation request) {
        return post(ROOT + "/operations/{id}", id).contentType("application/json")
                .content(json.writeValueAsString(request));
    }

    private MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
        return request.header("X-Internal-Api-Key", KEY);
    }

    private void assertBalanceUnchanged() {
        tx.executeWithoutResult(status -> {
            var account = accounts.findByMemberId(member).orElseThrow();
            assertThat(account.getUsedTokens()).isEqualByComparingTo("10");
            assertThat(account.getReservedTokens()).isEqualByComparingTo("15");
        });
    }

    private AiTokenUsageStatus usageStatus(UUID call) {
        return tx.execute(status -> usages.findByRequestId(call).orElseThrow().getStatus());
    }
}
