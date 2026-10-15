package org.monitoring.catchholebackend.domain.upload.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.monitoring.catchholebackend.global.storage.StoredObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Requests commit independently so two tabs and work-row locking cross a real transaction boundary. */
@SpringBootTest(properties = "internal.api-key=test-internal-api-key")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("설정집 편집 시작 revision의 원자 저장 검증")
class SettingBookRevisionCasIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired WorkRepository works;
    @Autowired JwtTokenProvider jwt;
    @Autowired JdbcTemplate sql;
    @Autowired PlatformTransactionManager transactions;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired org.monitoring.catchholebackend.domain.work.repository.WorkPurgeDataRepository purgeData;
    @Autowired org.monitoring.catchholebackend.domain.member.repository.MemberWithdrawalDataRepository withdrawalData;
    @MockitoBean ObjectStorage storage;
    final ObjectMapper json = new ObjectMapper();
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final AtomicInteger puts = new AtomicInteger();
    Member member;
    Work work;
    String bearer;

    @BeforeEach void setup() {
        sql.execute("create table if not exists episode_chunks(id uuid primary key,episode_id uuid not null)");
        member = members.save(Member.register(UUID.randomUUID()+"@example.invalid", "test-only", null, "두 탭 작가"));
        work = works.save(Work.create(member, "원문 충돌 합성 검증", WorkGenre.FANTASY, null)); bearer = "Bearer "+jwt.generateAccessToken(member);
        when(storage.putBytes(anyString(), any(byte[].class), any())).thenAnswer(c -> {
            puts.incrementAndGet(); objects.put(c.getArgument(0), c.getArgument(1)); return new StoredObject(c.getArgument(0), "v1");
        });
        when(storage.putText(anyString(), anyString())).thenAnswer(c -> {
            puts.incrementAndGet(); objects.put(c.getArgument(0), c.<String>getArgument(1).getBytes(StandardCharsets.UTF_8)); return new StoredObject(c.getArgument(0), "v1");
        });
        when(storage.getBytes(anyString())).thenAnswer(c -> objects.get(c.<String>getArgument(0)));
        when(storage.getText(anyString())).thenAnswer(c -> new String(objects.get(c.<String>getArgument(0)), StandardCharsets.UTF_8));
    }

    @AfterEach void cleanup() {
        if (work == null) return;
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            purgeData.purgeWorkData(work.getId()); withdrawalData.purgeMemberReferences(member.getId(), java.time.LocalDateTime.now()); members.deleteById(member.getId());
        });
        objects.clear();
    }

    @ParameterizedTest @ValueSource(strings = {"text", "reference", "identical", "roundTrip"})
    @DisplayName("오래된 탭은 내용·시점·동일값·A→B→A 저장에서도 실행 lease와 저장 객체를 변경하지 않는다")
    void staleEditingBaseRejectsBeforeNoopInvalidationOrStorage(String kind) throws Exception {
        JsonNode initial = upload(); UUID id = UUID.fromString(initial.path("id").asText()); JsonNode tabB = getBook(id);
        JsonNode tabA = save(id, Map.of("content", "원래 원문", "referenceTimeKind", "EPISODE", "referenceEpisodeNo", 3, "expectedRevisionNo", 1), 200);
        JsonNode latest = kind.equals("roundTrip") ? save(id, Map.of("content", "원래 원문", "referenceTimeKind", "WORK_START", "expectedRevisionNo", 2), 200) : tabA;
        JsonNode active = response(mvc.perform(post("/api/v1/works/{work}/setting-books/{id}/analysis-jobs", work.getId(), id)
                .header("Authorization", bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode lease = response(mvc.perform(post("/api/internal/v1/analysis-jobs/claim").header("X-Internal-Api-Key", "test-internal-api-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"allowedJobTypes\":[\"SETTING_EXTRACTION\"],\"supportsFrozenComparisonEvidence\":true,\"supportedAnalysisModes\":[\"ORDERED_PROVISIONAL\"],\"supportedSourceKinds\":[\"SETTING_BOOK\"],\"supportsSettingBookReferenceTime\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(lease.path("analysisJobId").asText()).isEqualTo(active.path("id").asText());
        Map<String,Object> request = new LinkedHashMap<>(); request.put("content", kind.equals("text") ? "흑연필 새 원문" : "원래 원문");
        request.put("referenceTimeKind", kind.equals("identical") ? "EPISODE" : "WORK_START");
        if (kind.equals("identical")) request.put("referenceEpisodeNo", 3);
        request.put("expectedRevisionNo", tabB.path("revisionNo").asInt());
        var before = state(); int beforePuts = puts.get(); var oldBytes = storedBytes();
        JsonNode rejected = save(id, request, 409);
        assertThat(rejected.path("success").asBoolean()).isFalse();
        assertThat(rejected.path("error").path("code").asText()).isEqualTo("UPLOAD_SETTING_BOOK_REVISION_CONFLICT");
        assertThat(state()).isEqualTo(before); assertThat(puts.get()).isEqualTo(beforePuts); assertThat(storedBytes()).isEqualTo(oldBytes);
        JsonNode after = getBook(id); assertThat(after.path("revisionId")).isEqualTo(latest.path("revisionId"));
        assertThat(after.path("referenceTimeKind")).isEqualTo(latest.path("referenceTimeKind")); assertThat(after.path("content").asText()).isEqualTo("원래 원문");
        assertThat(sql.queryForObject("select status from analysis_jobs where id=?", String.class, UUID.fromString(active.path("id").asText()))).isEqualTo("RUNNING");
        assertThat(sql.queryForObject("select lease_token from analysis_jobs where id=?", UUID.class, UUID.fromString(active.path("id").asText()))).isEqualTo(UUID.fromString(lease.path("leaseToken").asText()));
    }

    @ParameterizedTest @ValueSource(strings = {"omitted", "null", "negative"})
    @DisplayName("편집 기준 번호 누락·null·음수는 모든 저장 부수효과 전에 400으로 거절한다")
    void invalidExpectedRevisionIsRejectedWithoutAnyMutation(String invalid) throws Exception {
        UUID id = UUID.fromString(upload().path("id").asText()); Map<String,Object> request = new LinkedHashMap<>(); request.put("content", "저장하면 안 되는 원문");
        if (!invalid.equals("omitted")) request.put("expectedRevisionNo", invalid.equals("null") ? null : -1);
        var before = state(); int beforePuts = puts.get(); var bytes = storedBytes(); save(id, request, 400);
        assertThat(state()).isEqualTo(before); assertThat(puts.get()).isEqualTo(beforePuts); assertThat(storedBytes()).isEqualTo(bytes);
    }

    @Test @DisplayName("정상 최신 번호의 동일 저장은 버전·작업·저장 객체를 만들지 않는다")
    void currentNoopDoesNotCreateARevisionOrAnalysis() throws Exception {
        JsonNode book = upload(); var before = state(); int beforePuts = puts.get();
        JsonNode saved = save(UUID.fromString(book.path("id").asText()), Map.of("content", "원래 원문", "referenceTimeKind", "WORK_START", "expectedRevisionNo", 1), 200);
        assertThat(saved.path("revisionId")).isEqualTo(book.path("revisionId")); assertThat(state()).isEqualTo(before); assertThat(puts.get()).isEqualTo(beforePuts);
    }

    @Test @DisplayName("불변 revision 없는 legacy의 0은 첫 저장에만 유효하고 동일 내용 재시도도 stale다")
    void legacyZeroCanSaveOnceAndDoesNotNeedAReaderMutation() throws Exception {
        UUID id = new TransactionTemplate(transactions).execute(status -> {
            var owned = em.find(Work.class, work.getId()); var batch = UploadBatch.create(owned, owned.getMember(), UploadType.SETTING_BOOK, UploadSourceType.FILE);
            batch.startProcessing(); batch.updateFileCount(1); batch.complete(); em.persist(batch);
            String key = "synthetic/legacy/"+UUID.randomUUID()+".txt"; objects.put(key, "기존 원문".getBytes(StandardCharsets.UTF_8));
            var legacy = UploadFile.create(batch, UploadFileRole.SETTING_BOOK, "기존 설정.txt", MediaType.TEXT_PLAIN_VALUE, "s3://"+key, 13); legacy.markParsed(); em.persist(legacy); em.flush(); return legacy.getId();
        });
        assertThat(getBook(id).path("revisionNo").isNull()).isTrue(); assertThat(sql.queryForObject("select count(*) from setting_book_revisions where setting_book_id=?", Integer.class, id)).isZero();
        JsonNode first = save(id, Map.of("content", "기존 원문", "expectedRevisionNo", 0), 200); assertThat(first.path("revisionNo").asInt()).isEqualTo(1);
        var before = state(); int beforePuts = puts.get(); save(id, Map.of("content", "기존 원문", "expectedRevisionNo", 0), 409);
        assertThat(state()).isEqualTo(before); assertThat(puts.get()).isEqualTo(beforePuts);
        assertThat(sql.queryForObject("select count(*) from analysis_jobs where work_id=?", Integer.class, work.getId())).isZero();
    }

    @Test @DisplayName("실제 두 저장 트랜잭션은 같은 기준 번호에서 한 번만 성공한다")
    void competingSavedRevisionsUseTheSameWorkLockAndOnlyOneWins() throws Exception {
        UUID id = UUID.fromString(upload().path("id").asText()); int beforePuts = puts.get();
        var ready = new java.util.concurrent.CountDownLatch(2); var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.List.of("탭 A 원문", "탭 B 원문").stream().map(text -> executor.submit(() -> {
                ready.countDown(); if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("합성 동시 저장 시작 실패");
                return mvc.perform(patch("/api/v1/works/{work}/setting-books/{id}", work.getId(), id).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("content", text, "expectedRevisionNo", 1)))).andReturn().getResponse();
            })).toList();
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); start.countDown();
            var responses = java.util.List.of(tasks.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS), tasks.get(1).get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(responses.stream().map(org.springframework.mock.web.MockHttpServletResponse::getStatus)).containsExactlyInAnyOrder(200, 409);
            String winner = response(responses.stream().filter(r -> r.getStatus() == 200).findFirst().orElseThrow().getContentAsString()).path("content").asText();
            assertThat(getBook(id).path("content").asText()).isEqualTo(winner); assertThat(getBook(id).path("revisionNo").asInt()).isEqualTo(2);
        }
        assertThat(puts.get()-beforePuts).isEqualTo(1); assertThat(sql.queryForObject("select count(*) from setting_book_revisions where setting_book_id=?", Integer.class, id)).isEqualTo(2);
        assertThat(sql.queryForObject("select count(*) from analysis_jobs where work_id=?", Integer.class, work.getId())).isEqualTo(1);
    }

    @Test @DisplayName("실제 OpenAPI는 필수 편집 기준 번호와 저장 충돌 응답을 게시한다")
    void exportActualCasOpenApiContract() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode api = json.readTree(body); JsonNode schema = api.path("components").path("schemas").path("SettingBookUpdateRequest");
        assertThat(schema.path("required")).contains(json.valueToTree("content"), json.valueToTree("expectedRevisionNo"));
        assertThat(schema.path("properties").path("expectedRevisionNo").path("minimum").asInt()).isZero();
        assertThat(api.path("paths").path("/api/v1/works/{workId}/setting-books/{settingBookId}").path("patch").path("responses").has("409")).isTrue();
        String output = System.getenv("GH221_EXPORT_DIR");
        if (output != null) {
            var directory = java.nio.file.Path.of(output); java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.writeString(directory.resolve("openapi.json"), body); java.nio.file.Files.writeString(directory.resolve("openapi-cas.json"), body);
        }
    }

    JsonNode upload() throws Exception {
        return response(mvc.perform(multipart("/api/v1/works/{work}/setting-books", work.getId()).file(new MockMultipartFile("file", "설정.txt", MediaType.TEXT_PLAIN_VALUE, "원래 원문".getBytes(StandardCharsets.UTF_8)))
                .param("referenceTimeKind", "WORK_START").header("Authorization", bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    JsonNode getBook(UUID id) throws Exception {
        return response(mvc.perform(get("/api/v1/works/{work}/setting-books/{id}", work.getId(), id).header("Authorization", bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    JsonNode save(UUID id, Map<String,Object> request, int expectedStatus) throws Exception {
        String body = mvc.perform(patch("/api/v1/works/{work}/setting-books/{id}", work.getId(), id).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request))).andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return expectedStatus == 200 ? response(body) : json.readTree(body);
    }

    JsonNode response(String body) throws Exception { return json.readTree(body).path("data"); }

    Map<String,Object> state() {
        return Map.of("jobs", sql.queryForList("select id,status,lease_token,journal_status,source_setting_book_revision_id from analysis_jobs where work_id=? order by id", work.getId()),
                "books", sql.queryForList("select f.id,f.current_setting_book_revision_id,f.archived_at,f.content_storage_url from upload_files f join upload_batches b on b.id=f.batch_id where b.work_id=? order by f.id", work.getId()),
                "revisions", sql.queryForList("select r.id,r.revision_no,r.content_hash,r.reference_time_kind,r.reference_episode_no from setting_book_revisions r join upload_files f on f.id=r.setting_book_id join upload_batches b on b.id=f.batch_id where b.work_id=? order by r.id", work.getId()));
    }

    Map<String,String> storedBytes() { var copy = new LinkedHashMap<String,String>(); objects.forEach((key,value) -> copy.put(key, new String(value, StandardCharsets.UTF_8))); return copy; }
}
