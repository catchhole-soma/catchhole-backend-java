package org.monitoring.catchholebackend.domain.character.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.processor.GeneralEvidenceProjection;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.repository.StoredCharacterEvidenceRepository;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.monitoring.catchholebackend.support.PostgresFixtureCleanup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PostgreSQL 저장 근거 검증과 원문 삭제·교체 수명")
class StoredCharacterEvidencePostgresIntegrationTest {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_USER", "evidence_test").withEnv("POSTGRES_PASSWORD", "test-only")
            .withEnv("POSTGRES_DB", "evidence_test").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    static String url(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> url("evidence_test"));
        p.add("spring.datasource.username", () -> "evidence_test");
        p.add("spring.datasource.password", () -> "test-only");
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        p.add("spring.flyway.enabled", () -> "true");
        p.add("spring.docker.compose.enabled", () -> "false");
        p.add("spring.config.import", () -> "");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StoredCharacterEvidenceRepository receipts;
    @Autowired CharacterCurrentSupportingEvidenceResolver resolver;
    @Autowired GeneralEvidenceProjection projection;
    @Autowired PlatformTransactionManager transactions;
    @PersistenceContext EntityManager em;
    @MockitoBean ObjectStorageService storage;
    final ObjectMapper json = new ObjectMapper();
    final String text = "😀 하나는 문을 열었다.";
    final String quote = "하나는";
    UUID work, episode, job, chunk, candidate;

    @BeforeEach
    void seed() {
        PostgresFixtureCleanup.truncate(jdbc, "TRUNCATE TABLE works, members CASCADE");
        work = UUID.randomUUID(); episode = UUID.randomUUID(); job = UUID.randomUUID();
        chunk = UUID.randomUUID(); candidate = UUID.randomUUID();
        seed(jdbc);
    }

    void seed(JdbcTemplate db) {
        db.update("""
            INSERT INTO members(id,email,password_hash,phone_verified,email_verified,display_name,status,role,created_at,updated_at)
            VALUES (1,'evidence@example.test','test-only',false,true,'합성 작가','ACTIVE','AUTHOR',now(),now())
            """);
        db.update("INSERT INTO works(id,member_id,title,genre,latest_episode_no,created_at,updated_at) VALUES (?,1,'합성 작품','FANTASY',1,now(),now())", work);
        db.update("""
            INSERT INTO episodes(id,work_id,episode_no,char_count,status,content_s3_key,content_hash,content_updated_at,created_at,updated_at)
            VALUES (?,?,1,?,'ANALYZED','synthetic/v1',repeat('a',64),now(),now(),now())
            """, episode, work, text.codePointCount(0,text.length()));
        db.update("""
            INSERT INTO analysis_jobs(id,work_id,episode_id,source_episode_no,job_type,status,source_content_s3_key,source_content_hash,created_at,updated_at)
            VALUES (?,?,?,1,'SETTING_EXTRACTION','SUCCEEDED','synthetic/v1',repeat('a',64),now(),now())
            """, job, work, episode);
        db.update("""
            INSERT INTO episode_chunks(id,episode_id,chunk_index,chunk_text,start_offset,end_offset,paragraph_start_index,paragraph_end_index,created_at,updated_at)
            VALUES (?,?,0,?,0,?,0,0,now(),now())
            """, chunk, episode, text, text.codePointCount(0,text.length()));
        db.update("""
            INSERT INTO setting_candidates(id,work_id,episode_id,analysis_job_id,source_chunk_id,source_content_s3_key,entity_type,entity_name,
                match_status,attribute_name,attribute_value,value_type,review_status,comparison_status,evidence_spans,narrative_time,created_at,updated_at)
            VALUES (?,?,?,?,?,'synthetic/v1','CHARACTER','하나','UNRESOLVED','성격','용감하다','STRING','CONFIRMED','COMPLETED',?::jsonb,?::jsonb,now(),now())
            """, candidate, work, episode, job, chunk, spans(), time());
    }

    String spans() { return "[{\"quote\":\""+quote+"\",\"start_offset\":2,\"end_offset\":5}]"; }
    String time() { return "{\"factTime\":null,\"referenceTime\":{\"description\":\"문을 열 때\",\"evidenceSpans\":[{\"quote\":\""+quote+"\",\"startOffset\":2,\"endOffset\":5,\"sourceChunkId\":\""+chunk+"\"}]}}"; }
    JsonNode receipt() { return parse(jdbc.queryForObject("SELECT stored_evidence_validation FROM setting_candidates WHERE id=?",String.class,candidate)); }
    JsonNode parse(String value) {
        try { return value==null ? json.nullNode() : json.readTree(value); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    JsonNode capture() {
        return new TransactionTemplate(transactions).execute(s -> {
            em.clear();
            SettingCandidate c=em.find(SettingCandidate.class,candidate);
            assertThat(projection.forProcessingCandidate(c)).isEqualTo(receipts.find(c).spans());
            return resolver.captureCandidate(resolver.begin(c.getWork()),c);
        });
    }
    void purge(String retained) {
        jdbc.update("""
            INSERT INTO episode_source_purge_requests(id,episode_id,work_id,previous_episode_no,previous_content_key,retained_content_key,status,requested_at,created_at,updated_at)
            VALUES (?,?,?,1,'synthetic/v1',?,'REQUESTED',now(),now(),now())
            """,UUID.randomUUID(),episode,work,retained);
    }

    @Test @DisplayName("저장할 때 Unicode 위치를 검증하고 비교는 S3 없이 재사용한다")
    void storesUnicodeProofAndReadsWithoutStorage() {
        assertThat(receipt().path("evidenceSpans").get(0).path("startOffset").intValue()).isEqualTo(2);
        assertThat(receipt().path("narrativeTimeValid").booleanValue()).isTrue();
        assertThat(capture().path("availability").asText()).isEqualTo("VERIFIED");
        assertThat(capture().path("narrativeTime")).isEqualTo(parse(time()));
        verifyNoInteractions(storage);
    }

    @Test @DisplayName("같은 원문 재청킹과 상태 저장은 이전 검증을 지우지 않는다")
    void retainsProofAfterRechunkAndRejectsForgedReceipt() {
        JsonNode proof=receipt();
        jdbc.update("DELETE FROM episode_chunks WHERE episode_id=?",episode);
        jdbc.update("UPDATE setting_candidates SET evidence_spans=evidence_spans,review_status='CONFIRMED',stored_evidence_validation='{}' WHERE id=?",candidate);
        assertThat(receipt()).isEqualTo(proof);
        assertThat(capture().path("availability").asText()).isEqualTo("VERIFIED");
        // Actual evidence changes cannot inherit the old verification after the chunks are gone.
        jdbc.update("UPDATE setting_candidates SET evidence_spans='[{\"quote\":\"위조\",\"startOffset\":2,\"endOffset\":4}]' WHERE id=?",candidate);
        assertThat(receipt().path("evidenceSpans")).isEmpty();
        verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(strings={
            "[{\"quote\":\"위조문\",\"startOffset\":2,\"endOffset\":5}]",
            "[{\"quote\":\"하나는\",\"startOffset\":3,\"endOffset\":6}]",
            "[{\"quote\":\"하나는\",\"startOffset\":2,\"start_offset\":3,\"endOffset\":5}]",
            "[{\"quote\":\"하나는\",\"startOffset\":\"2\",\"endOffset\":5}]",
            "[{\"quote\":\"하나는\",\"startOffset\":2.0,\"endOffset\":5}]",
            "[{\"quote\":\"하나는\",\"startOffset\":null,\"endOffset\":5}]",
            "[null]", "{}"})
    @DisplayName("일치하지 않거나 잘못된 위치의 인용을 인증하지 않는다")
    void rejectsInvalidQuotes(String evidence) {
        jdbc.update("UPDATE setting_candidates SET evidence_spans=?::jsonb WHERE id=?",evidence,candidate);
        assertThat(receipt().path("evidenceSpans")).isEmpty();
        assertThat(capture().path("availability").asText()).isEqualTo("UNAVAILABLE");
        verifyNoInteractions(storage);
    }

    @Test @DisplayName("다른 회차 청크와 잘못된 길이는 새 근거를 인증하지 않는다")
    void rejectsForeignAndMalformedChunks() {
        jdbc.update("UPDATE setting_candidates SET source_chunk_id=? WHERE id=?",UUID.randomUUID(),candidate);
        assertThat(receipt().path("evidenceSpans")).isEmpty();
        jdbc.update("UPDATE episode_chunks SET end_offset=end_offset+1 WHERE id=?",chunk);
        jdbc.update("UPDATE setting_candidates SET source_chunk_id=? WHERE id=?",chunk,candidate);
        assertThat(receipt().path("evidenceSpans")).isEmpty();
        jdbc.update("UPDATE setting_candidates SET narrative_time=?::jsonb WHERE id=?",time().replace(chunk.toString(),UUID.randomUUID().toString()),candidate);
        assertThat(receipt().path("narrativeTimeValid").booleanValue()).isFalse();
    }

    @Test @DisplayName("삭제 접수 즉시 근거를 숨기고 파기 완료 뒤에도 주소를 되살리지 않는다")
    void invalidatesAtRequestAndPreservesConfirmedValue() {
        purge(null);
        assertThat(jdbc.queryForObject("SELECT source_reference_invalidated_at IS NOT NULL FROM analysis_jobs WHERE id=?",Boolean.class,job)).isTrue();
        JsonNode result=capture();
        assertThat(result.path("unavailableReason").asText()).isEqualTo("SOURCE_EVIDENCE_PURGED");
        assertThat(result.has("storageBinding")).isFalse();
        assertThat(result.path("evidenceSpans")).isEmpty();
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            SettingCandidate c=em.find(SettingCandidate.class,candidate);
            assertThat(projection.forCandidate(c)).isEmpty();
        });
        jdbc.update("UPDATE episode_source_purge_requests SET status='PROCESSING',attempt_count=2");
        jdbc.update("DELETE FROM episode_source_purge_requests");
        assertThat(capture().path("unavailableReason").asText()).isEqualTo("SOURCE_EVIDENCE_PURGED");
        assertThat(jdbc.queryForObject("SELECT attribute_value FROM setting_candidates WHERE id=?",String.class,candidate)).isEqualTo("용감하다");
        assertThat(jdbc.queryForObject("SELECT source_content_s3_key FROM analysis_jobs WHERE id=?",String.class,job)).isEqualTo("synthetic/v1");
        verifyNoInteractions(storage);
    }

    @Test @DisplayName("삭제 요청 롤백은 원문 참조 무효화도 롤백한다")
    void rollsBackInvalidationWithRequest() {
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            purge(null);
            assertThat(capture().path("unavailableReason").asText()).isEqualTo("SOURCE_EVIDENCE_PURGED");
            s.setRollbackOnly();
        });
        assertThat(capture().path("availability").asText()).isEqualTo("VERIFIED");
        verifyNoInteractions(storage);
    }

    @Test @DisplayName("원문 교체는 이전 Job만 무효화하고 새 원문 근거는 유지한다")
    void replacementKeepsNewSource() {
        UUID oldJob=job, oldCandidate=candidate;
        jdbc.update("UPDATE episodes SET content_s3_key='synthetic/v2',content_hash=repeat('b',64) WHERE id=?",episode);
        job=UUID.randomUUID(); candidate=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO analysis_jobs(id,work_id,episode_id,source_episode_no,job_type,status,source_content_s3_key,source_content_hash,created_at,updated_at)
            VALUES (?,?,?,1,'SETTING_EXTRACTION','SUCCEEDED','synthetic/v2',repeat('b',64),now(),now())
            """, job,work,episode);
        jdbc.update("""
            INSERT INTO setting_candidates(id,work_id,episode_id,analysis_job_id,source_chunk_id,source_content_s3_key,entity_type,entity_name,match_status,attribute_name,
                attribute_value,value_type,review_status,comparison_status,evidence_spans,narrative_time,created_at,updated_at)
            SELECT ?,work_id,episode_id,?,source_chunk_id,'synthetic/v2',entity_type,entity_name,match_status,attribute_name,attribute_value,value_type,
                review_status,comparison_status,evidence_spans,narrative_time,now(),now() FROM setting_candidates WHERE id=?
            """,candidate,job,oldCandidate);
        purge("synthetic/v2");
        assertThat(capture().path("availability").asText()).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("SELECT source_reference_invalidated_at FROM analysis_jobs WHERE id=?",Object.class,job)).isNull();
        candidate=oldCandidate; job=oldJob;
        assertThat(capture().path("unavailableReason").asText()).isEqualTo("SOURCE_EVIDENCE_PURGED");
        verifyNoInteractions(storage);
    }

    @Test @DisplayName("원문 버전 불일치와 누락된 검증은 저장소 재조회로 보완하지 않는다")
    void changedManifestAndMissingReceiptNeverFetchStorage() {
        jdbc.update("UPDATE episodes SET content_s3_version='replaced' WHERE id=?",episode);
        assertThat(capture().path("unavailableReason").asText()).isEqualTo("SOURCE_EVIDENCE_PURGED");
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            em.clear();
            assertThat(projection.forCandidate(em.find(SettingCandidate.class,candidate))).isEmpty();
        });
        jdbc.update("UPDATE episodes SET content_s3_version=NULL WHERE id=?",episode);
        jdbc.update("UPDATE setting_candidates SET source_content_s3_key=NULL,narrative_time='{\"factTime\":null,\"referenceTime\":null}' WHERE id=?",candidate);
        assertThat(receipt().isNull()).isTrue();
        assertThat(capture().path("unavailableReason").asText()).isEqualTo("EVIDENCE_UNAVAILABLE");
        verifyNoInteractions(storage);
    }

    @Test @DisplayName("설정집 검증은 원 revision과 Job 청크에 결속하고 부모 프로필 근거도 보존한다")
    void verifiesSettingBookRevisionAndIdentityProof() {
        UUID batch=UUID.randomUUID(), book=UUID.randomUUID(), revision=UUID.randomUUID();
        jdbc.update("INSERT INTO upload_batches(id,work_id,member_id,upload_type,source_type,status,file_count,created_at,updated_at) VALUES (?, ?, 1, 'SETTING_BOOK','FILE','COMPLETED',1,now(),now())",batch,work);
        jdbc.update("INSERT INTO upload_files(id,batch_id,file_role,original_filename,file_size,parse_status,created_at,updated_at) VALUES (?,?,'SETTING_BOOK','synthetic.txt',20,'PARSED',now(),now())",book,batch);
        jdbc.update("INSERT INTO setting_book_revisions(id,setting_book_id,revision_no,content_s3_key,content_hash,char_count,created_at,updated_at) VALUES (?,?,1,'synthetic/v1',repeat('a',64),20,now(),now())",revision,book);
        jdbc.update("UPDATE analysis_jobs SET source_kind='SETTING_BOOK',episode_id=NULL,source_episode_no=NULL,source_setting_book_id=?,source_setting_book_revision_id=? WHERE id=?",book,revision,job);
        jdbc.update("INSERT INTO setting_book_chunks(id,analysis_job_id,setting_book_revision_id,chunk_index,chunk_text,start_offset,end_offset,paragraph_start_index,paragraph_end_index,created_at,updated_at) VALUES (?,?,?,0,?,0,?,0,0,now(),now())",chunk,job,revision,text,text.codePointCount(0,text.length()));
        var proof=json.createObjectNode();
        var identity=proof.putObject("setting_book_identity_proof").put("parentTargetRef","C1");
        identity.putArray("members").addObject().put("targetRef","C1").set("profileEvidence",parse(spans()).get(0));
        jdbc.update("UPDATE setting_candidates SET source_kind='SETTING_BOOK',episode_id=NULL,source_setting_book_id=?,source_setting_book_revision_id=?,raw_ai_result_json=?::jsonb WHERE id=?",book,revision,proof.toString(),candidate);
        assertThat(receipt().path("evidenceSpans").size()).isEqualTo(1);
        assertThat(receipt().path("identityEvidence").size()).isEqualTo(1);
        assertThat(capture().path("availability").asText()).isEqualTo("VERIFIED");
        // Same quote, but a chunk owned by another extraction cannot certify this job.
        UUID otherJob=UUID.randomUUID();
        jdbc.update("INSERT INTO analysis_jobs(id,work_id,source_kind,source_setting_book_id,source_setting_book_revision_id,job_type,status,source_content_s3_key,source_content_hash,created_at,updated_at) VALUES (?,?,'SETTING_BOOK',?,?,'SETTING_EXTRACTION','SUCCEEDED','synthetic/v1',repeat('a',64),now(),now())",otherJob,work,book,revision);
        jdbc.update("UPDATE setting_book_chunks SET analysis_job_id=? WHERE id=?",otherJob,chunk);
        jdbc.update("UPDATE setting_candidates SET narrative_time=NULL WHERE id=?",candidate);
        assertThat(receipt().path("evidenceSpans")).isEmpty();
        assertThat(receipt().path("identityEvidence")).isEmpty();
        verifyNoInteractions(storage);
    }

    @Test @DisplayName("V111에서 업그레이드할 때 검증 가능한 과거 근거만 이관하고 삭제된 출처는 무효화한다")
    void migratesLegacySources() {
        jdbc.execute("CREATE DATABASE evidence_legacy");
        String legacyUrl=url("evidence_legacy");
        Flyway.configure().dataSource(legacyUrl,"evidence_test","test-only").target("111").load().migrate();
        var legacy=new JdbcTemplate(new DriverManagerDataSource(legacyUrl,"evidence_test","test-only"));
        seed(legacy);
        UUID missing=UUID.randomUUID(), archived=UUID.randomUUID();
        legacy.update("""
            INSERT INTO setting_candidates(id,work_id,episode_id,analysis_job_id,source_content_s3_key,source_chunk_id,entity_type,entity_name,
              match_status,attribute_name,value_type,review_status,evidence_spans,created_at,updated_at)
            SELECT ?,work_id,episode_id,analysis_job_id,source_content_s3_key,?,entity_type,entity_name,match_status,attribute_name,value_type,review_status,evidence_spans,now(),now()
              FROM setting_candidates WHERE id=?
            """,missing,UUID.randomUUID(),candidate);
        legacy.update("""
            INSERT INTO analysis_jobs(id,work_id,episode_id,source_episode_no,job_type,status,source_content_s3_key,source_content_hash,created_at,updated_at)
            VALUES (?,?,?,1,'SETTING_EXTRACTION','SUCCEEDED','synthetic/deleted',repeat('c',64),now(),now())
            """,archived,work,episode);
        Flyway.configure().dataSource(legacyUrl,"evidence_test","test-only").load().migrate();
        assertThat(parse(legacy.queryForObject("SELECT stored_evidence_validation FROM setting_candidates WHERE id=?",String.class,candidate)).path("evidenceSpans").size()).isEqualTo(1);
        assertThat(parse(legacy.queryForObject("SELECT stored_evidence_validation FROM setting_candidates WHERE id=?",String.class,missing)).path("evidenceSpans")).isEmpty();
        assertThat(legacy.queryForObject("SELECT source_reference_invalidated_at IS NOT NULL FROM analysis_jobs WHERE id=?",Boolean.class,archived)).isTrue();
    }
}
