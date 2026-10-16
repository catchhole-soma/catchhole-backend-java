package org.monitoring.catchholebackend.domain.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.*;
import org.monitoring.catchholebackend.domain.chat.exception.ChatErrorCode;
import org.monitoring.catchholebackend.domain.chat.mapper.ChatMapper;
import org.monitoring.catchholebackend.domain.chat.repository.ChatRunRepository;
import org.monitoring.catchholebackend.domain.chat.service.*;
import org.monitoring.catchholebackend.domain.chat.type.ChatState;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "chat.enabled=true", "chat.operations-enabled=true", "chat.python-url=http://127.0.0.1:1",
        "chat.capability-secret=chat-test-secret-with-more-than-32-characters",
        "chat.retrieval-embedding-version=gh240-test", "internal.api-key=chat-test-key"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("검색 인용의 실행 인증·실제 원문·최종 결과 PostgreSQL 계약")
class ChatRetrievalSourcesPostgresIntegrationTest {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_USER", "gh240").withEnv("POSTGRES_PASSWORD", "test-only")
            .withEnv("POSTGRES_DB", "gh240_test").withExposedPorts(5432)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(
                    ".*database system is ready to accept connections.*\\n", 2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/gh240_test");
        r.add("spring.datasource.username", () -> "gh240");
        r.add("spring.datasource.password", () -> "test-only");
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired ChatServiceImpl service;
    @Autowired ChatToolAccess access;
    @Autowired ChatRunRepository runs;
    @Autowired ChatMapper json;
    @Autowired MockMvc mvc;
    @LocalServerPort int springPort;
    @MockitoBean ChatDispatcher dispatcher;
    @MockitoBean ObjectStorageService storage;
    Long member;
    UUID work, episode, chunk;
    String key;
    static final String ORIGINAL = "서두😀아리아가 반지를 버렸다.\n아리아가 반지를 버렸다.";
    static final String QUOTE = "아리아가 반지를 버렸다.";

    @BeforeEach
    void source() {
        key = "private-source-" + UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            var m = Member.register(UUID.randomUUID() + "@gh240.test", "test", null, "작가");
            em.persist(m);
            member = m.getId();
            var w = Work.create(m, "합성 원고", WorkGenre.FANTASY, null);
            em.persist(w);
            work = w.getId();
            var e = Episode.create(w, null, 3, null, key, "source-v1", hash(ORIGINAL), ORIGINAL.length());
            em.persist(e);
            episode = e.getId();
        });
        jdbc.update("""
                INSERT INTO retrieval_sources VALUES (?,?,?,'source-v1',?,'cl100k-800-100-boundary-v1',1,localtimestamp)
                """, episode, work, key, hash(ORIGINAL));
        chunk = insertChunk(0);
        when(storage.getText(key)).thenReturn(ORIGINAL);
    }

    UUID insertChunk(int index) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO retrieval_chunks(id,episode_id,chunk_index,chunk_text,start_offset,end_offset,chunk_hash,token_count)
                VALUES (?,?,?, ?,0,?,?,30)
                """, id, episode, index, ORIGINAL, ORIGINAL.codePointCount(0, ORIGINAL.length()), hash(ORIGINAL));
        return id;
    }

    record Running(UUID id, String token, UUID runner) {}

    Running start(Integer from, Integer to) {
        var v = service.submit(member, work, UUID.randomUUID().toString(), new Submit("반지를 버린 장면은?", from, to));
        var d = service.dispatch(v.attemptId());
        UUID runner = UUID.randomUUID();
        service.start(v.attemptId(), d.capability(), runner);
        return new Running(v.attemptId(), d.capability(), runner);
    }

    RetrievalReference ref() {
        return ref(chunk, QUOTE, 3, 3 + QUOTE.codePointCount(0, QUOTE.length()));
    }

    RetrievalReference ref(UUID id, String quote, int start, int end) {
        return new RetrievalReference(id, episode, 3, hash(ORIGINAL), "source-v1", quote, start, end);
    }

    RetrievalSources register(Running r, RetrievalReference... refs) {
        return access.retrievalSources(r.id(), r.token(), r.runner(), new RetrievalSourcesRequest(List.of(refs)));
    }

    View finish(Running r, RetrievalReference... refs) {
        return service.finish(r.id(), r.token(), r.runner(), new Finish(
                new Result(Outcome.ANSWER, "아리아가 반지를 버린 장면입니다.", List.of(), List.of(), List.of(refs)), null));
    }

    @Test
    @DisplayName("임베딩 없는 키워드 근거도 Unicode 위치·실제 원문을 검증해 완료하고 원문 키는 노출하지 않는다")
    void unembeddedCitationRoundTrip() throws Exception {
        var r = start(2, 5);
        String response = mvc.perform(post("/api/internal/v1/chat-runs/{id}/retrieval-sources", r.id())
                        .header("X-Internal-Api-Key", "chat-test-key")
                        .header("X-Chat-Capability", r.token()).header("X-Chat-Runner", r.runner())
                        .contentType("application/json").content(json.encode(new RetrievalSourcesRequest(List.of(ref())))))
                .andExpect(status().isOk()).andExpect(jsonPath("data.sources[0].startOffset").value(3))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(key);
        var result = finish(r, ref());
        assertThat(result.state()).isEqualTo(ChatState.SUCCEEDED);
        assertThat(result.result().retrievalSources()).containsExactly(ref());
        verify(storage, times(2)).getText(key);
    }

    @Test
    @DisplayName("25만 자를 넘는 기존 한글 원문도 CRLF·보조 평면 문자의 원래 위치를 인용한다")
    void legacyLargeUnicodeCitationRoundTrip() {
        String quote = "아리아😀\r\n반지를 버렸다.";
        String passage = "앞 문맥\r\n" + quote + "\r\n뒷 문맥";
        String original = "가".repeat(250_001) + "\r\n😀" + passage;
        assertThat(original.codePointCount(0, original.length())).isGreaterThan(250_000);
        assertThat(original.getBytes(StandardCharsets.UTF_8).length).isLessThan(2_000_000);
        var source = replaceOriginal(original, passage, quote);
        var r = start(2, 5);

        assertThat(register(r, source).sources()).containsExactly(source);
        var result = finish(r, source);
        assertThat(result.state()).isEqualTo(ChatState.SUCCEEDED);
        assertThat(result.result().retrievalSources()).containsExactly(source);
        verify(storage, times(2)).getText(key);
    }

    @ParameterizedTest
    @CsvSource({"가,1999999", "가,2000000", "가,2000001", "😀,2000000", "😀,2000001"})
    @DisplayName("기존 원문은 UTF-8 200만 바이트 경계까지 허용하고 다중바이트 초과는 거절한다")
    void sourceByteLimitUsesUtf8Bytes(String unit, int bytes) {
        String quote = "아리아😀\r\n반지를 버렸다.";
        String passage = "앞\r\n" + quote + "\r\n뒤";
        int padding = bytes - passage.getBytes(StandardCharsets.UTF_8).length;
        int unitBytes = unit.getBytes(StandardCharsets.UTF_8).length;
        String original = unit.repeat(padding / unitBytes) + "a".repeat(padding % unitBytes) + passage;
        assertThat(original.getBytes(StandardCharsets.UTF_8)).hasSize(bytes);
        var source = replaceOriginal(original, passage, quote);
        var r = start(null, null);

        if (bytes <= 2_000_000) {
            assertThat(register(r, source).sources()).containsExactly(source);
            assertThat(finish(r, source).state()).isEqualTo(ChatState.SUCCEEDED);
        } else {
            assertScope(() -> register(r, source));
            assertThat(runs.findById(r.id()).orElseThrow().getReceiptJson()).isEqualTo("[]");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"content", "newline"})
    @DisplayName("큰 기존 원문도 본문 또는 CRLF가 바뀌면 원래 SHA256과 달라 완료를 거절한다")
    void legacySourceStillRequiresExactHash(String change) {
        String quote = "아리아😀\r\n반지를 버렸다.";
        String passage = "앞\r\n" + quote + "\r\n뒤";
        String original = "가".repeat(250_001) + "\r\n😀" + passage;
        var source = replaceOriginal(original, passage, quote);
        var r = start(null, null);
        register(r, source);
        // The content variant preserves every citation offset and changes only an uncited character.
        when(storage.getText(key)).thenReturn("content".equals(change)
                ? "나" + original.substring(1) : original.replace("\r\n", "\n"));

        assertScope(() -> finish(r, source));
        assertThat(runs.findById(r.id()).orElseThrow().getResultJson()).isNull();
    }

    RetrievalReference replaceOriginal(String original, String passage, String quote) {
        int start = original.codePointCount(0, original.indexOf(passage));
        int end = start + passage.codePointCount(0, passage.length());
        int quoteStart = start + passage.codePointCount(0, passage.indexOf(quote));
        String sourceHash = hash(original);
        jdbc.update("UPDATE episodes SET content_hash=?,char_count=? WHERE id=?", sourceHash,
                original.codePoints().filter(c -> !Character.isWhitespace(c)).count(), episode);
        jdbc.update("UPDATE retrieval_sources SET source_hash=? WHERE episode_id=?", sourceHash, episode);
        jdbc.update("""
                UPDATE retrieval_chunks SET chunk_text=?,start_offset=?,end_offset=?,chunk_hash=? WHERE id=?
                """, passage, start, end, hash(passage), chunk);
        when(storage.getText(key)).thenReturn(original);
        return new RetrievalReference(chunk, episode, 3, sourceHash, "source-v1", quote, quoteStart,
                quoteStart + quote.codePointCount(0, quote.length()));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "GH240_AI_CHECKOUT", matches = ".+")
    @DisplayName("실제 Python client가 Spring HTTP에서 인용 허가를 받아 최종 결과까지 저장한다")
    void pythonClientUsesRealSpringReceiptAndFinish() throws Exception {
        var view = service.submit(member, work, UUID.randomUUID().toString(), new Submit("반지를 버린 장면은?", 2, 5));
        var dispatch = service.dispatch(view.attemptId());
        // ChatMapper intentionally handles stored result JSON only, so use the HTTP mapper for Instant.
        String payload = new tools.jackson.databind.ObjectMapper().writeValueAsString(
                Map.of("dispatch", dispatch, "sources", List.of(ref())));
        Path checkout = Path.of(System.getenv("GH240_AI_CHECKOUT"));
        Path temp = Files.createTempDirectory("gh240-http-");
        var builder = new ProcessBuilder(checkout.resolve(".venv/bin/python").toString(),
                "tests/support/chat_retrieval_http_client.py")
                .directory(checkout.toFile()).redirectErrorStream(true)
                .redirectOutput(temp.resolve("python.log").toFile());
        builder.environment().putAll(Map.of("CHAT_TEST_ONLY", "true",
                "SPRING_INTERNAL_API_KEY", "chat-test-key",
                "SPRING_INTERNAL_API_BASE_URL", "http://127.0.0.1:" + springPort,
                "PYTHONPATH", checkout.toString()));
        builder.environment().remove("LLM_API_KEY");
        var process = builder.start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            assertThat(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS))
                    .as("Synthetic Python client timeout; log: %s", temp.resolve("python.log")).isTrue();
            assertThat(process.exitValue()).as("Synthetic Python client log: %s", temp.resolve("python.log")).isZero();
            var saved = service.get(member, work, view.questionId());
            assertThat(saved.state()).isEqualTo(ChatState.SUCCEEDED);
            assertThat(saved.result().retrievalSources()).containsExactly(ref());
            verify(storage, times(2)).getText(key);
        } finally {
            process.destroy();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }

    @Test
    @DisplayName("한 요청의 같은 회차 청크들은 S3를 한 번만 읽고 각 원래 청크 ID를 보존한다")
    void deduplicateSourceReads() {
        var r = start(null, null);
        UUID second = insertChunk(1);
        var refs = register(r, ref(), ref(second, QUOTE, 3, 3 + QUOTE.length())).sources();
        assertThat(refs).hasSize(2);
        verify(storage).getText(key);
    }

    @Test
    @DisplayName("조회한 적 없는 청크 ID는 존재해도 완료 결과에 인용할 수 없다")
    void noUnobservedCitation() {
        var r = start(null, null);
        assertScope(() -> finish(r, ref()));
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("동일 문자열의 다른 위치도 등록한 인용의 위치를 바꾸면 거부한다")
    void exactReceiptPreventsPositionSubstitution() {
        var r = start(null, null);
        register(r, ref());
        int next = 3 + QUOTE.length() + 1;
        assertScope(() -> finish(r, ref(chunk, QUOTE, next, next + QUOTE.length())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hash", "version", "key", "archive", "purge", "policy", "chunk-text", "chunk-hash", "offset", "embedding-model", "embedding-version"})
    @DisplayName("등록 뒤 현재 원문·청크·검색 규약이 달라지면 완료 단계에서 재검증한다")
    void finalRevalidation(String change) {
        var r = start(2, 5);
        register(r, ref());
        switch (change) {
            case "hash" -> jdbc.update("UPDATE episodes SET content_hash=? WHERE id=?", "a".repeat(64), episode);
            case "version" -> jdbc.update("UPDATE episodes SET content_s3_version='source-v2' WHERE id=?", episode);
            case "key" -> jdbc.update("UPDATE episodes SET content_s3_key='changed' WHERE id=?", episode);
            case "archive" -> jdbc.update("UPDATE episodes SET status='ARCHIVED' WHERE id=?", episode);
            case "purge" -> jdbc.update("""
                    INSERT INTO episode_source_purge_requests(id,episode_id,work_id,previous_episode_no,
                        retained_content_key,status,requested_at,attempt_count,created_at,updated_at)
                    VALUES (?,?,?,3,?,'REQUESTED',localtimestamp,0,localtimestamp,localtimestamp)
                    """, UUID.randomUUID(), episode, work, key);
            case "policy" -> jdbc.update("UPDATE retrieval_sources SET chunk_policy='other' WHERE episode_id=?", episode);
            case "chunk-text" -> jdbc.update("UPDATE retrieval_chunks SET chunk_text='다른 원문' WHERE id=?", chunk);
            case "chunk-hash" -> jdbc.update("UPDATE retrieval_chunks SET chunk_hash=? WHERE id=?", "b".repeat(64), chunk);
            case "offset" -> jdbc.update("UPDATE retrieval_chunks SET start_offset=1 WHERE id=?", chunk);
            case "embedding-model" -> vector("other", "gh240-test");
            case "embedding-version" -> vector("text-embedding-3-small", "wrong");
        }
        assertScope(() -> finish(r, ref()));
        assertThat(runs.findById(r.id()).orElseThrow().getResultJson()).isNull();
    }

    @Test
    @DisplayName("벡터가 있으면 서버가 설정한 embedding version만 허용한다")
    void configuredEmbeddingVersion() {
        vector("text-embedding-3-small", "gh240-test");
        assertThat(register(start(null, null), ref()).sources()).containsExactly(ref());
    }

    void vector(String model, String version) {
        String vector = "[" + String.join(",", Collections.nCopies(1536, "1")) + "]";
        jdbc.update("""
                UPDATE retrieval_chunks SET embedding=cast(? as vector), embedding_model=?,embedding_version=?,
                    embedded_at=localtimestamp,status='READY' WHERE id=?
                """, vector, model, version, chunk);
    }

    @Test
    @DisplayName("같은 DB manifest여도 실제 원문 내용이 달라지면 완료하지 않는다")
    void changedObject() {
        var r = start(null, null);
        register(r, ref());
        when(storage.getText(key)).thenReturn("다른 원문");
        assertScope(() -> finish(r, ref()));
    }

    @Test
    @DisplayName("원문 저장소 장애는 범위 오류와 구분하고 인용 허가를 저장하지 않는다")
    void storageFailure() {
        var r = start(null, null);
        when(storage.getText(key)).thenThrow(new IllegalStateException("합성 장애"));
        assertThatThrownBy(() -> register(r, ref())).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getResultCode()).isEqualTo(ChatErrorCode.CHAT_SOURCE_UNAVAILABLE));
        assertThat(runs.findById(r.id()).orElseThrow().getReceiptJson()).isEqualTo("[]");
    }

    @Test
    @DisplayName("저장소를 읽는 중 원문 metadata가 바뀌면 이전 조회의 근거를 등록하지 않는다")
    void changedDuringRead() {
        var r = start(null, null);
        when(storage.getText(key)).thenAnswer(invocation -> {
            jdbc.update("UPDATE episodes SET content_s3_version='source-v2' WHERE id=?", episode);
            return ORIGINAL;
        });
        assertScope(() -> register(r, ref()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"range", "work", "same-member-work", "runner", "capability", "quote", "unicode-offset", "tool-limit", "too-many", "too-long"})
    @DisplayName("실행 범위·인용 정확성·상한을 벗어나면 조회 허가를 남기지 않는다")
    void rejectedRequest(String variant) {
        var r = start("range".equals(variant) ? 4 : 2, 5);
        switch (variant) {
            case "work", "same-member-work" -> {
                var owner = tx.execute(s -> {
                    var m = "same-member-work".equals(variant) ? em.find(Member.class, member)
                            : Member.register(UUID.randomUUID() + "@other.test", "test", null, "다른 작가");
                    if (!"same-member-work".equals(variant)) em.persist(m);
                    var w = Work.create(m, "다른 작품", WorkGenre.FANTASY, null);
                    em.persist(w);
                    return new Object[] {m.getId(), w.getId()};
                });
                var foreign = service.submit((Long) owner[0], (UUID) owner[1], UUID.randomUUID().toString(),
                        new Submit("다른 작품의 인용", null, null));
                var d = service.dispatch(foreign.attemptId());
                UUID runner = UUID.randomUUID();
                service.start(d.attemptId(), d.capability(), runner);
                assertScope(() -> register(new Running(d.attemptId(), d.capability(), runner), ref()));
            }
            case "runner" -> assertScope(() -> register(new Running(r.id(), r.token(), UUID.randomUUID()), ref()));
            case "capability" -> assertScope(() -> register(new Running(r.id(), "wrong", r.runner()), ref()));
            case "quote" -> assertScope(() -> register(r, ref(chunk, "거짓 인용", 3, 8)));
            case "unicode-offset" -> assertScope(() -> register(r, ref(chunk, QUOTE, 4, 4 + QUOTE.length())));
            case "tool-limit" -> {
                jdbc.update("UPDATE chat_runs SET tool_count=20 WHERE id=?", r.id());
                assertThatThrownBy(() -> register(r, ref())).isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(ChatErrorCode.CHAT_LIMIT_EXCEEDED));
            }
            case "too-many" -> assertScope(() -> access.retrievalSources(r.id(), r.token(), r.runner(),
                    new RetrievalSourcesRequest(Collections.nCopies(13, ref()))));
            case "too-long" -> assertScope(() -> register(r, ref(chunk, "😀".repeat(4001), 0, 4001)));
            default -> assertScope(() -> register(r, ref()));
        }
        assertThat(runs.findById(r.id()).orElseThrow().getReceiptJson()).isEqualTo("[]");
    }

    @Test
    @DisplayName("HTTP 진입점은 내부 키와 실행 capability를 모두 요구한다")
    void endpointRequiresBothCredentials() throws Exception {
        var r = start(null, null);
        var body = json.encode(new RetrievalSourcesRequest(List.of(ref())));
        mvc.perform(post("/api/internal/v1/chat-runs/{id}/retrieval-sources", r.id())
                        .header("X-Chat-Capability", r.token()).header("X-Chat-Runner", r.runner())
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/internal/v1/chat-runs/{id}/retrieval-sources", r.id())
                        .header("X-Internal-Api-Key", "chat-test-key").header("X-Chat-Runner", r.runner())
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("운영 백필 실행은 검색 근거를 조회하거나 인용할 수 없다")
    void operationalRunRejected() {
        var d = service.operation(UUID.randomUUID(), new Operation(work, "test-operator", "test-policy", 1000));
        UUID runner = UUID.randomUUID();
        service.start(d.attemptId(), d.capability(), runner);
        assertScope(() -> register(new Running(d.attemptId(), d.capability(), runner), ref()));
    }

    static void assertScope(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getResultCode()).isEqualTo(ChatErrorCode.CHAT_SCOPE_INVALID));
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
