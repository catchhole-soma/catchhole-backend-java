package org.monitoring.catchholebackend.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenUsageRepository;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.entity.*;
import org.monitoring.catchholebackend.domain.character.processor.*;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.Submit;
import org.monitoring.catchholebackend.domain.chat.service.ChatService;
import org.monitoring.catchholebackend.domain.chat.type.ChatState;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "chat.enabled=true",
            "chat.capability-secret=http-chat-qa-secret-more-than-32-characters",
            "internal.api-key=http-chat-qa-private-key"
        })
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "GH238_AI_CHECKOUT", matches = ".+")
@DisplayName("실제 Spring 조회·원장과 Python 단일 질의응답 처리기를 연결한다")
class ChatQaHttpEndToEndTest {
    private static final String TEXT = "마력은 10이다.";
    private static final int PYTHON_PORT = ChatHttpEndToEndTest.freePort();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("chat.python-url", () -> "http://127.0.0.1:" + PYTHON_PORT);
    }

    @LocalServerPort int springPort;
    @Autowired ChatService service;
    @Autowired AiTokenUsageRepository usages;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired CharacterSnapshotAccessor snapshots;
    @Autowired CharacterSnapshotSourceManager sources;
    @MockitoBean ObjectStorageService storage;

    record Fixture(Long member, UUID work, UUID source) {}

    @ParameterizedTest(name = "인물 직접 근거={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("현재값·직접 인용 또는 수동 scope를 보존하고 두 모델 호출을 정산한다")
    void qaWithRealQueriesAndLedger(boolean character) throws Exception {
        jdbc.execute("create table if not exists episode_chunks (id uuid primary key, episode_id uuid not null)");
        when(storage.getText("qa-source")).thenReturn(TEXT);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(TEXT.getBytes(StandardCharsets.UTF_8)));
        Fixture fixture = tx.execute(status -> {
            var member = Member.register(UUID.randomUUID() + "@qa.test", "encoded",
                    "010" + String.format("%08d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100000000)), "작가");
            em.persist(member);
            var work = Work.create(member, "질의응답 통합", WorkGenre.FANTASY, "설명");
            em.persist(work);
            UUID sourceId = null;
            if (character) {
                var subject = WorkCharacter.create(work, "아리아", null, null, null, null, null, null, null, null, null);
                em.persist(subject);
                var episode = Episode.create(work, null, 1, "1화", "qa-source", "v1", hash, TEXT.length());
                em.persist(episode);
                var job = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
                em.persist(job);
                var nodes = JsonNodeFactory.instance;
                var spans = nodes.arrayNode().add(nodes.objectNode().put("quote", TEXT).put("startOffset", 0).put("endOffset", TEXT.length()));
                var candidate = SettingCandidate.create(work, episode, null, job, SettingEntityType.CHARACTER,
                        "아리아", "stats.mana", "10", SettingValueType.STRING, null, spans, BigDecimal.ONE, null);
                candidate.confirm();
                em.persist(candidate);
                var fact = CharacterFact.create(subject, candidate, CharacterFactType.STAT, "stats.mana", "10", "10",
                        null, episode, null, job, BigDecimal.ONE, 1);
                em.persist(fact);
                var slot = new CharacterSnapshotSlot(CharacterFactType.STAT, "stats.mana");
                snapshots.replace(subject, Map.of(slot, snapshots.entry(slot.factType(), slot.factKey(), "10", nodes.textNode("10"))));
                sources.replaceSources(subject, slot, List.of(fact));
                sourceId = fact.getId();
            } else {
                var world = WorldSetting.create(work, WorldSettingCategory.POWER_SYSTEM, "화염술", "일반 마법", "소모 마력", "10");
                world.addProperty("고급 마법", "소모 마력", "35");
                world.protectManualProperty("고급 마법", "소모 마력");
                em.persist(world);
            }
            return new Fixture(member.getId(), work.getId(), sourceId);
        });
        Path checkout = Path.of(System.getenv("GH238_AI_CHECKOUT"));
        Path temp = Files.createTempDirectory("gh238-http-");
        ProcessBuilder builder = new ProcessBuilder(checkout.resolve(".venv/bin/python").toString(),
                "tests/support/chat_qa_stub_server.py").directory(checkout.toFile())
                .redirectErrorStream(true).redirectOutput(temp.resolve("python.log").toFile());
        builder.environment().putAll(Map.of(
                "CHAT_TEST_ONLY", "true", "CHAT_TEST_PORT", String.valueOf(PYTHON_PORT),
                "SPRING_INTERNAL_API_KEY", "http-chat-qa-private-key",
                "SPRING_INTERNAL_API_BASE_URL", "http://127.0.0.1:" + springPort,
                "CHAT_OUTBOX_PATH", temp.resolve("outbox.db").toString(), "PYTHONPATH", checkout.toString()));
        // The test handler injects a scripted client. No live provider credential is needed.
        builder.environment().remove("LLM_API_KEY");
        var process = builder.start();
        try (var http = HttpClient.newHttpClient()) {
            boolean ready = false;
            for (int i = 0; i < 80 && !ready; i++) {
                try {
                    ready = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PYTHON_PORT + "/healthz"))
                            .timeout(Duration.ofSeconds(1)).build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
                } catch (Exception ignored) { }
                if (!ready) Thread.sleep(100);
            }
            assertThat(ready).as("FastAPI log: %s", temp.resolve("python.log")).isTrue();
            var view = service.submit(fixture.member(), fixture.work(), UUID.randomUUID().toString(),
                    new Submit(character ? "아리아의 현재 마력과 근거는?" : "화염술의 고급 마법 소모 마력과 근거는?", null, null));
            for (int i = 0; i < 150 && view.state() != ChatState.SUCCEEDED; i++) {
                Thread.sleep(100);
                view = service.get(fixture.member(), fixture.work(), view.questionId());
            }
            assertThat(view.state()).isEqualTo(ChatState.SUCCEEDED);
            if (character) {
                assertThat(view.result().text()).contains("현재 등록값", TEXT);
                assertThat(view.result().sources()).singleElement().satisfies(ref -> assertThat(ref.targetId()).isEqualTo(fixture.source()));
            } else {
                assertThat(view.result().text()).contains("고급 마법", "35", "사용자 직접 입력").doesNotContain("일반 마법");
                assertThat(view.result().sources()).isEmpty();
            }
            var run = view.attemptId();
            tx.executeWithoutResult(status -> {
                var calls = usages.findAllByChatRunId(run);
                assertThat(calls).hasSize(2);
                assertThat(calls).allSatisfy(call -> {
                    assertThat(call.getInputTokens()).isEqualTo(10);
                    assertThat(call.getOutputTokens()).isEqualTo(5);
                });
            });
        } finally {
            process.destroy();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }
}
