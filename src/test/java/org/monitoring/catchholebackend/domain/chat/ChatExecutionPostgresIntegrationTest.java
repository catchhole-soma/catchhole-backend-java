package org.monitoring.catchholebackend.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenReserveRequest;
import org.monitoring.catchholebackend.domain.aitoken.exception.AiTokenErrorCode;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.Reserve;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PostgreSQL 전체 Flyway·JPA validate와 채팅 계약")
class ChatExecutionPostgresIntegrationTest extends ChatExecutionIntegrationTest {
    @Container
    static final GenericContainer<?> POSTGRES =
            new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
                    .withEnv("POSTGRES_USER", "chat_test")
                    .withEnv("POSTGRES_PASSWORD", "test-only")
                    .withEnv("POSTGRES_DB", "chat_test")
                    .withExposedPorts(5432)
                    .waitingFor(
                            org.testcontainers.containers.wait.strategy.Wait.forLogMessage(
                                    ".*database system is ready to accept connections.*\\n", 2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRES.getHost()
                                + ":"
                                + POSTGRES.getMappedPort(5432)
                                + "/chat_test");
        r.add("spring.datasource.username", () -> "chat_test");
        r.add("spring.datasource.password", () -> "test-only");
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Test
    @DisplayName("분석 Job이 없는 채팅 사용량도 정확히 하나의 소유자 FK를 가진다")
    void usageOwnerConstraint() {
        var r = start();
        reserve(r, java.util.UUID.randomUUID(), 100);
        tx.executeWithoutResult(
                s -> {
                    var usage = usages.findAllByChatRunId(r.view().attemptId()).getFirst();
                    assertThat(usage.getAnalysisJob()).isNull();
                    assertThat(usage.getChatRun().getId()).isEqualTo(r.view().attemptId());
                });
    }

    @Test
    @DisplayName("분석 해소와 채팅의 Luna 예약은 마지막 0.05 잔액을 중복 소비하지 않는다")
    void lunaAnalysisAndChatShareTheLastFractionalBalance() throws Exception {
        var running = start();
        var job = tx.execute(status -> {
            var owner = em.find(Work.class, work);
            var episode = Episode.create(owner, null, 1, "합성 회차", "test-key", "v1", "hash", 10);
            em.persist(episode);
            var analysis = AnalysisJob.create(owner, null, episode, AnalysisJobType.SETTING_EXTRACTION);
            var lease = analysis.claim("test", "test", LocalDateTime.now().plusMinutes(10));
            em.persist(analysis);
            var account = accounts.findByMemberId(member).orElseThrow();
            account.reserve(account.remainingTokens().subtract(new BigDecimal("0.05")));
            return new UUID[] {analysis.getId(), lease};
        });
        UUID chatCall = UUID.randomUUID();
        UUID analysisCall = UUID.randomUUID();
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var chat = pool.submit(() -> {
                ready.countDown();
                assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    service.reserve(running.view().attemptId(), running.token(), running.runner(),
                            new Reserve(chatCall, AiTokenPurpose.CHAT_ANSWER, "gpt-6-luna", 1));
                    return true;
                } catch (AppException error) {
                    assertThat(error.getResultCode()).isEqualTo(AiTokenErrorCode.AI_TOKEN_QUOTA_EXHAUSTED);
                    return false;
                }
            });
            var analysis = pool.submit(() -> {
                ready.countDown();
                assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    analysisTokens.reserve(new AiTokenReserveRequest(analysisCall, job[0],
                            AiTokenPurpose.SUBJECT_RESOLUTION, 1, "gpt-6-luna", 1), job[1]);
                    return true;
                } catch (AppException error) {
                    assertThat(error.getResultCode()).isEqualTo(AiTokenErrorCode.AI_TOKEN_QUOTA_EXHAUSTED);
                    return false;
                }
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(List.of(chat.get(10, TimeUnit.SECONDS), analysis.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            go.countDown();
            pool.shutdownNow();
        }
        tx.executeWithoutResult(status -> {
            var account = accounts.findByMemberId(member).orElseThrow();
            assertThat(account.remainingTokens()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(account.getReservedTokens()).isEqualByComparingTo(account.getGrantedTokens());
            var saved = usages.findAllById(List.of(chatCall, analysisCall));
            assertThat(saved).hasSize(1);
            var usage = saved.getFirst();
            assertThat(usage.getReservedTokens()).isEqualTo(1);
            assertThat(usage.getReservedCharge()).isEqualByComparingTo("0.05");
            assertThat(usage.getChargeDenominator()).isEqualTo(20);
            assertThat(usage.getChargePolicyVersion()).isEqualTo("MODEL_WEIGHTED_V1");
        });
    }
}
