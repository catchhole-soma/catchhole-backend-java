package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.type.*;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationDataRequest;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.service.ValidationDataService;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.storage.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.docker.compose.enabled=false", "internal.api-key=gh306-synthetic-internal"})
@ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
@DisplayName("GH306 고정 기준·실제 원문 읽기 경계")
class ValidationDataIntegrationTest {
    @Autowired EntityManager em;
    @Autowired AnalysisStateJournal journal;
    @Autowired ObjectStorageService hashes;
    @Autowired ValidationDataService service;
    @Autowired MockMvc mvc;
    ObjectMapper json = new ObjectMapper();
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ObjectStorage storage;
    @LocalServerPort int port;
    @Autowired PlatformTransactionManager transactions;
    Member member; Work work; Episode prior; Episode target; ValidationReport report;
    String text = "😀민수는 검을 쥐었다. 민수는 검을 쥐었다.";

    @BeforeEach void fixture() { prepareFixture(2, true); }
    void prepareFixture(int targetNo, boolean includePrior) {
        member = Member.register(UUID.randomUUID()+"@test.invalid", "test-only", null, "합성 작가"); em.persist(member);
        work = Work.create(member, "검수 자료", WorkGenre.FANTASY, "합성"); em.persist(work);
        var batch = UploadBatch.create(work, member, UploadType.SINGLE_EPISODE, UploadSourceType.FILE); em.persist(batch);
        prior = null;
        if (includePrior) { prior = Episode.create(work, null, 1, "앞 회차", "synthetic/prior", "v1", hashes.contentHash(text), 10); em.persist(prior); }
        target = Episode.create(work, null, targetNo, "대상", "synthetic/target", "v1", hashes.contentHash(text), 10); em.persist(target);
        report = ValidationReport.create(work, target, batch, "a".repeat(64)); em.persist(report);
        var seed = JsonNodeFactory.instance.objectNode().put("formatVersion", 1);
        seed.putObject("target").put("episodeId", target.getId().toString()).put("episodeNo", targetNo)
                .put("contentHash", target.getContentHash()).put("contentS3Key", target.getContentS3Key()).put("contentS3Version", "v1");
        seed.putObject("characters"); seed.putObject("worldSettings"); seed.putArray("worldHistory");
        seed.putArray("sources");
        if (includePrior) {
        var manifest = ((com.fasterxml.jackson.databind.node.ArrayNode) seed.path("sources")).addObject().put("sourceKind", "EPISODE")
                .put("episodeId", prior.getId().toString()).put("episodeNo", 1)
                .put("contentHash", prior.getContentHash()).put("contentS3Key", prior.getContentS3Key()).put("contentS3Version", "v1");
        em.persist(ValidationBaselineSource.create(report, prior, null, manifest));
        }
        report.captureSeed(seed, journal.hash(seed), "b".repeat(64));
        var entries = JsonNodeFactory.instance.arrayNode();
        entries.addObject().put("domain", "characters").put("subjectRef", "character:synthetic")
                .putObject("state").put("name", "민수").putObject("slots").putObject("STATUS:손")
                .put("factType", "STATUS").put("factKey", "손").put("factValue", "왼손 상실");
        report.baselineReady(entries);
        em.flush();
        when(storage.getText(anyString())).thenReturn(text);
    }
    ValidationDataRequest request(String operation, String source, String expectedSource, Integer start, Integer end) {
        return new ValidationDataRequest(report.getBaselineHash(), report.getSourceContentHash(), operation,
                0, 100, source, expectedSource, "v1", start, end);
    }
    JsonNode read(String operation) { return service.readValidationData(member.getId(), work.getId(), report.getId(), request(operation, null, null, null, null)); }

    @Test @DisplayName("고정 항목 조회는 현재값 조회나 추출 쓰기를 하지 않는다")
    void readsFrozenEntriesWithoutWrites() {
        assertThat(read("ENTRIES").at("/entries/0/state/slots/STATUS:손/factValue").asText()).isEqualTo("왼손 상실");
        assertThat(read("INPUT").at("/sources/0/sourceRef").asText()).isEqualTo("R1");
        for (String table : List.of("analysis_jobs", "setting_candidates", "world_setting_candidates", "character_facts"))
            assertThat(jdbc.queryForObject("select count(*) from "+table, Integer.class)).isZero();
    }
    @Test @DisplayName("반복 인용·이모지의 Unicode code point 위치를 정확히 읽는다")
    void readsExactUnicodeSlice() {
        var result = service.readValidationData(member.getId(), work.getId(), report.getId(), request("READ_SOURCE", "T1", target.getContentHash(), 1, 3));
        assertThat(result.path("status").asText()).isEqualTo("READY");
        assertThat(result.path("text").asText()).isEqualTo("민수");
        assertThat(result.path("start").asInt()).isEqualTo(1);
        assertThat(result.path("totalCodePoints").asInt()).isEqualTo(24);
    }
    @Test @DisplayName("읽는 동안의 교체는 이전 본문을 정상 결과로 반환하지 않는다")
    void replacementDuringReadInvalidatesResponse() {
        when(storage.getText("synthetic/prior")).thenAnswer(call -> {
            jdbc.update("update episodes set content_hash = ? where id = ?", "c".repeat(64), prior.getId());
            return text;
        });
        var result = service.readValidationData(member.getId(), work.getId(), report.getId(), request("READ_SOURCE", "R1", prior.getContentHash(), 0, 2));
        assertThat(result.path("status").asText()).isEqualTo("SOURCE_CHANGED");
        assertThat(result.hasNonNull("text")).isFalse();
    }
    @Test @DisplayName("필수 원문 장애는 빈 성공 결과와 구별한다")
    void unavailableSourceIsExplicit() {
        when(storage.getText(anyString())).thenThrow(new IllegalStateException("private-storage-detail"));
        var result = service.readValidationData(member.getId(), work.getId(), report.getId(), request("READ_SOURCE", "R1", prior.getContentHash(), 0, 2));
        assertThat(result.path("status").asText()).isEqualTo("SOURCE_UNREADABLE");
        assertThat(result.toString()).doesNotContain("private-storage-detail");
    }
    @Test @DisplayName("다른 소유권·없는 참조·틀린 fingerprint는 허용하지 않는다")
    void rejectsForgedScopeAndSource() {
        assertThatThrownBy(() -> service.readValidationData(member.getId()+1, work.getId(), report.getId(), request("INPUT", null, null, null, null))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.readValidationData(member.getId(), work.getId(), report.getId(), request("READ_SOURCE", "R99", prior.getContentHash(), 0, 2))).isInstanceOf(RuntimeException.class);
        var result = service.readValidationData(member.getId(), work.getId(), report.getId(), request("READ_SOURCE", "R1", "c".repeat(64), 0, 2));
        assertThat(result.path("status").asText()).isEqualTo("SOURCE_CHANGED");
    }
    @Test @DisplayName("내부 read endpoint는 API key 없이 원문을 노출하지 않는다")
    void internalAuthenticationIsRequired() throws Exception {
        mvc.perform(post("/api/internal/v1/works/"+work.getId()+"/validation-reports/"+report.getId()+"/data")
                .header("X-Validation-Member-Id", member.getId()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request("INPUT", null, null, null, null))))
                .andExpect(status().isUnauthorized());
    }

    @Test @DisplayName("첫 회차는 추출하지 않은 설정집의 실제 고정 revision을 읽는다")
    void firstEpisodeReadsUnanalyzedSettingBookRevision() {
        prepareFixture(1, false);
        var book=org.monitoring.catchholebackend.domain.upload.entity.UploadFile.create(report.getBatch(), UploadFileRole.SETTING_BOOK,
                "설정집.txt", "text/plain", "s3://synthetic/original", 20); em.persist(book);
        var revision=org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(book, 1,
                new StoredTextObject("synthetic/book", "v1", hashes.contentHash(text), 24), SettingBookReferenceTimeKind.WORK_START, null);
        em.persist(revision); book.linkCurrentRevision(revision);
        var seed=(com.fasterxml.jackson.databind.node.ObjectNode)report.getBaselineSeed().deepCopy();
        var manifest=((com.fasterxml.jackson.databind.node.ArrayNode)seed.path("sources")).addObject()
                .put("sourceKind", "SETTING_BOOK").put("settingBookId", book.getId().toString()).put("revisionId", revision.getId().toString())
                .put("revisionNo", 1).put("contentHash", revision.getContentHash()).put("contentS3Key", revision.getContentS3Key())
                .put("contentS3Version", "v1").put("referenceTimeKind", "WORK_START").putNull("referenceEpisodeNo");
        org.springframework.test.util.ReflectionTestUtils.setField(report, "baselineSeed", seed);
        org.springframework.test.util.ReflectionTestUtils.setField(report, "baselineHash", journal.hash(seed));
        em.persist(ValidationBaselineSource.create(report, null, revision, manifest)); em.flush();
        var input=read("INPUT");
        assertThat(input.at("/target/episodeNo").asInt()).isEqualTo(1);
        assertThat(input.at("/sources/0/sourceKind").asText()).isEqualTo("SETTING_BOOK");
        assertThat(input.at("/sources/0").has("episodeNo")).isFalse();
        var result=service.readValidationData(member.getId(), work.getId(), report.getId(), request("READ_SOURCE", "R1", revision.getContentHash(), 1, 3));
        assertThat(result.path("text").asText()).isEqualTo("민수");
        assertThat(jdbc.queryForObject("select count(*) from analysis_jobs", Integer.class)).isZero();
    }

    @Test @DisplayName("읽는 동안 기준 무효화·원문 파기를 정상 결과로 반환하지 않는다")
    void baselineInvalidationAndDeletionDuringReadAreRejected() {
        when(storage.getText("synthetic/prior")).thenAnswer(call -> {
            jdbc.update("update validation_reports set status='INVALIDATED',baseline_status='INVALIDATED',baseline_hash=null,baseline_seed=null,baseline_entries=null where id=?", report.getId());
            return text;
        });
        assertThatThrownBy(() -> service.readValidationData(member.getId(), work.getId(), report.getId(), request("READ_SOURCE", "R1", prior.getContentHash(), 0, 2)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test @DisplayName("실제 Java HTTP 응답을 Python 고정 원문 adapter가 읽는다")
    @EnabledIfEnvironmentVariable(named="GH306_AI_CHECKOUT", matches=".+")
    void actualPythonHttpAdapterReadsFrozenSource() throws Exception {
        var memberId=member.getId(); var workId=work.getId();
        var reportId=report.getId(); var baseline=report.getBaselineHash(); var hash=target.getContentHash();
        TestTransaction.flagForCommit(); TestTransaction.end();
        try {
            String checkout=System.getenv("GH306_AI_CHECKOUT");
            String python=System.getenv("GH306_PYTHON");
            assertThat(python).isNotBlank();
            var builder=new ProcessBuilder(python, checkout+"/tests/support/validation_data_http_client.py",
                    "http://127.0.0.1:"+port, memberId.toString(), workId.toString(), reportId.toString(), baseline, hash);
            builder.environment().put("PYTHONPATH", checkout);
            builder.directory(new java.io.File(checkout)); builder.redirectErrorStream(true);
            var process=builder.start();
            assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String output=new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            assertThat(output).contains("GH306 actual Java-Python HTTP contract passed");
        } finally {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update("delete from validation_baseline_sources where report_id=?", reportId);
                jdbc.update("delete from validation_reports where id=?", reportId);
                jdbc.update("delete from episodes where work_id=?", workId);
                jdbc.update("delete from upload_batches where work_id=?", workId);
                jdbc.update("delete from works where id=?", workId);
                jdbc.update("delete from members where id=?", memberId);
            });
        }
    }
}
