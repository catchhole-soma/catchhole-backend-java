package org.monitoring.catchholebackend.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenUsageRepository;
import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.*;
import org.monitoring.catchholebackend.domain.chat.service.ChatService;
import org.monitoring.catchholebackend.domain.chat.type.ChatState;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "chat.enabled=true",
            "chat.capability-secret=http-chat-test-secret-more-than-32-characters",
            "internal.api-key=http-chat-test-private-key"
        })
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "GH237_AI_CHECKOUT", matches = ".+")
@DisplayName("실제 Spring↔FastAPI 단일 질문 HTTP 왕복")
class ChatHttpEndToEndTest {
    static final int pythonPort = freePort();

    static int freePort() {
        try (var s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("chat.python-url", () -> "http://127.0.0.1:" + pythonPort);
    }

    @LocalServerPort int springPort;
    @Autowired ChatService service;
    @Autowired AiTokenUsageRepository usages;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager em;

    @Test
    @DisplayName("즉시 전달·실행 인증·기존 조회·예약·결과 저장·정산을 테스트 핸들러로 연결한다")
    void realHttpFlow() throws Exception {
        Path checkout = Path.of(System.getenv("GH237_AI_CHECKOUT"));
        Path temp = Files.createTempDirectory("gh237-http-");
        ProcessBuilder builder =
                new ProcessBuilder(
                                checkout.resolve(".venv/bin/python").toString(),
                                "tests/support/chat_stub_server.py")
                        .directory(checkout.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(temp.resolve("python.log").toFile());
        builder.environment()
                .putAll(
                        java.util.Map.of(
                                "CHAT_TEST_ONLY",
                                "true",
                                "CHAT_TEST_PORT",
                                String.valueOf(pythonPort),
                                "SPRING_INTERNAL_API_KEY",
                                "http-chat-test-private-key",
                                "SPRING_INTERNAL_API_BASE_URL",
                                "http://127.0.0.1:" + springPort,
                                "CHAT_OUTBOX_PATH",
                                temp.resolve("outbox.db").toString(),
                                "PYTHONPATH",
                                checkout.toString()));
        var process = builder.start();
        try (var http = HttpClient.newHttpClient()) {
            boolean ready = false;
            for (int i = 0; i < 50; i++) {
                try {
                    ready =
                            http.send(
                                                    HttpRequest.newBuilder(
                                                                    URI.create(
                                                                            "http://127.0.0.1:"
                                                                                    + pythonPort
                                                                                    + "/healthz"))
                                                            .timeout(Duration.ofSeconds(1))
                                                            .build(),
                                                    HttpResponse.BodyHandlers.discarding())
                                            .statusCode()
                                    == 200;
                } catch (Exception ignored) {
                }
                if (ready) break;
                Thread.sleep(100);
            }
            assertThat(ready)
                    .as("FastAPI health; local diagnostic log: %s", temp.resolve("python.log"))
                    .isTrue();
            var ids =
                    tx.execute(
                            s -> {
                                var m =
                                        Member.register(
                                                UUID.randomUUID() + "@http.test",
                                                "encoded",
                                                "01088889999",
                                                "작가");
                                em.persist(m);
                                var w = Work.create(m, "HTTP 테스트", WorkGenre.FANTASY, "설명");
                                em.persist(w);
                                return new Object[] {m.getId(), w.getId()};
                            });
            var view =
                    service.submit(
                            (Long) ids[0],
                            (UUID) ids[1],
                            "http-roundtrip",
                            new Submit("테스트 질문", null, null));
            for (int i = 0; i < 100 && view.state() != ChatState.SUCCEEDED; i++) {
                Thread.sleep(100);
                view = service.get((Long) ids[0], (UUID) ids[1], view.questionId());
            }
            assertThat(view.state()).isEqualTo(ChatState.SUCCEEDED);
            assertThat(view.result().text()).isEqualTo("테스트 실행 완료");
            var replay =
                    service.submit(
                            (Long) ids[0],
                            (UUID) ids[1],
                            "http-roundtrip",
                            new Submit("테스트 질문", null, null));
            assertThat(replay.attemptId()).isEqualTo(view.attemptId());
            var id = view.attemptId();
            tx.executeWithoutResult(
                    s -> {
                        var calls = usages.findAllByChatRunId(id);
                        assertThat(calls).hasSize(1);
                        assertThat(calls.getFirst().getInputTokens()).isEqualTo(12);
                        assertThat(calls.getFirst().getOutputTokens()).isEqualTo(3);
                    });
        } finally {
            process.destroy();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                process.destroyForcibly();
        }
    }
}
