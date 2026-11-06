package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.*;
import jakarta.persistence.EntityManager;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobWorkerService;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.validation.service.ValidationSettingsApplicationService;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Private snapshots stay outside Git. Only a newly created disposable loopback DB is accepted. No provider or remote storage calls. */
@SpringBootTest(properties={"spring.config.import=","spring.docker.compose.enabled=false"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="GH312_LOCAL_REPLAY_DIR",matches=".+")
@EnabledIfEnvironmentVariable(named="GH310_EXECUTION_POSTGRES_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh310_execution_test")
@DisplayName("격리 PostgreSQL에서 비공개 실제 검수 후 추출 snapshot 재생")
class ValidationLocalSnapshotReplayTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AnalysisJobWorkerService worker;
    @Autowired AnalysisJobRepository jobs;
    @Autowired ValidationSettingsApplicationService applications;
    @MockitoBean ObjectStorage storage;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->System.getenv("GH310_EXECUTION_POSTGRES_JDBC_URL"));
        p.add("spring.datasource.username",()->"gh310");p.add("spring.datasource.password",()->"gh310-disposable-only");
        p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        p.add("spring.jpa.hibernate.ddl-auto",()->"validate");p.add("spring.flyway.enabled",()->"true");
    }

    @Test @DisplayName("실제 원문·확정 설정·후보·비교 결과로 Worker 완료를 재생하고 후보별 예상 상태와 근거를 검증한다")
    void replaysActualExtractionCompletionWithoutAnotherModelCall() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var directory=Path.of(System.getenv("GH312_LOCAL_REPLAY_DIR"));
        JsonNode manifest=json.readTree(directory.resolve("manifest.json").toFile());
        JsonNode snapshot;
        try(var in=new GZIPInputStream(Files.newInputStream(directory.resolve("snapshot.json.gz")))) {snapshot=json.readTree(in);}
        var tables=new HashMap<String,JsonNode>();snapshot.forEach(row->tables.put(row.path("table").asText(),row.path("rows")));
        var tx=new TransactionTemplate(transactions);
        assertThat(jdbc.queryForObject("select count(*) from works",Long.class)).as("new isolated DB only").isZero();
        long member=tx.execute(s->{var m=Member.register("local-replay@example.invalid","local-only","01012345678","로컬 검증");em.persist(m);em.flush();return m.getId();});
        jdbc.execute("truncate table character_setting_schemas");
        var order=List.of("works","upload_batches","upload_files","episodes","characters","world_settings","character_setting_schemas","analysis_jobs",
                "character_fact_comparison_batches","setting_candidates","world_setting_comparison_batches","world_setting_comparison_decisions","world_setting_candidates",
                "world_setting_comparison_decision_sources","character_facts","character_snapshot_sources","analysis_job_episode_targets","analysis_review_continuations",
                "validation_reports","validation_jobs","validation_review_completions","validation_settings_applications","relationship_graph_analyses");
        for(String table:order) for(JsonNode source:tables.get(table)) {
            var row=source.deepCopy();
            for(String field:List.of("member_id","reviewed_by")) if(row.hasNonNull(field))((com.fasterxml.jackson.databind.node.ObjectNode)row).put(field,member);
            if(table.equals("analysis_jobs")) {((com.fasterxml.jackson.databind.node.ObjectNode)row).putNull("setting_candidate_id");((com.fasterxml.jackson.databind.node.ObjectNode)row).putNull("world_setting_candidate_id");}
            jdbc.update("insert into "+table+" select (jsonb_populate_record(null::"+table+",cast(? as jsonb))).*",row.toString());
        }
        for(JsonNode row:tables.get("analysis_jobs")) jdbc.update("update analysis_jobs set setting_candidate_id=?::uuid,world_setting_candidate_id=?::uuid where id=?::uuid",
                nullable(row,"setting_candidate_id"),nullable(row,"world_setting_candidate_id"),row.path("id").asText());
        UUID work=UUID.fromString(manifest.path("workId").asText()),job=UUID.fromString(manifest.path("jobId").asText()),report=UUID.fromString(manifest.path("reportId").asText());
        var source=Files.readString(directory.resolve("source.txt"));
        assertThat(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isEqualTo(manifest.path("sourceHash").asText());
        String sourceKey=jdbc.queryForObject("select source_content_s3_key from analysis_jobs where id=?",String.class,job);
        when(storage.getText(anyString())).thenAnswer(call->{assertThat((String)call.getArgument(0)).isEqualTo(sourceKey);return source;});
        var before=states(job);
        assertThat(jdbc.queryForObject("select status from validation_settings_applications where report_id=?",String.class,report)).isEqualTo("HELD_REVIEW");
        assertThat(jdbc.queryForObject("select count(*) from setting_candidates where analysis_job_id=? and review_status='PENDING_REVIEW'",Long.class,job)
                +jdbc.queryForObject("select count(*) from world_setting_candidates where analysis_job_id=? and review_status='PENDING_REVIEW'",Long.class,job))
                .isEqualTo(manifest.path("pendingBefore").asLong());
        // Rewind only local completion markers. Source, frozen input, journal claims and comparison results are unchanged.
        UUID lease=UUID.randomUUID();
        jdbc.update("delete from validation_settings_applications where report_id=?",report);
        jdbc.update("update analysis_jobs set status='RUNNING',journal_status='PENDING',review_application_held_at=null,lease_token=?,lease_expires_at=now()+interval '1 hour' where id=?",lease,job);
        worker.completeAnalysisJob(job,lease,new WorkerAnalysisJobCompleteRequest("{}",null,null));
        var after=states(job);
        var output=json.createObjectNode();output.set("before",json.valueToTree(before));output.set("after",json.valueToTree(after));
        output.set("application",json.valueToTree(jdbc.queryForMap("select status,attempt,trigger,applied_at,hold_reasons,last_error_code from validation_settings_applications where report_id=?",report)));
        output.set("job",json.valueToTree(jdbc.queryForMap("select status,journal_status,automatic_applied_at,review_application_held_at from analysis_jobs where id=?",job)));
        output.set("characterFacts",json.valueToTree(jdbc.queryForList("select c.name,f.fact_type,f.fact_key,f.fact_value from character_facts f join characters c on c.id=f.character_id where f.extracted_by_job_id=?",job)));
        Files.writeString(directory.resolve("replay-result.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(output));
        var expected=manifest.path("expectedCandidates");
        for(var row:after)assertThat(row.get("review_status")).as(row.get("id").toString()).isEqualTo(expected.path(row.get("id").toString()).path("status").asText());
        assertThat(after).hasSize(expected.size());
        assertThat(jdbc.queryForObject("select status from validation_settings_applications where report_id=?",String.class,report)).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("select automatic_applied_at is not null from analysis_jobs where id=?",Boolean.class,job)).isTrue();
        assertThat(jdbc.queryForObject("select status||'/'||journal_status from analysis_jobs where id=?",String.class,job)).isEqualTo("SUCCEEDED/SEALED");
        for(JsonNode expectedFact:manifest.path("expectedCharacterFacts")) {
            var actual=jdbc.queryForMap("select fact_type,fact_key,fact_value from character_facts where setting_candidate_id=?::uuid",expectedFact.path("candidateId").asText());
            assertThat(actual).containsEntry("fact_type",expectedFact.path("factType").asText())
                    .containsEntry("fact_key",expectedFact.path("factKey").asText()).containsEntry("fact_value",expectedFact.path("factValue").asText());
        }
        for(JsonNode expectedProperty:manifest.path("expectedWorldProperties")) {
            JsonNode actual=json.readTree(jdbc.queryForObject("select properties_json::text from world_settings where id=?::uuid",String.class,expectedProperty.path("targetId").asText()));
            for(JsonNode key:expectedProperty.path("path"))actual=actual.path(key.asText());
            assertThat(actual.asText()).isEqualTo(expectedProperty.path("value").asText());
        }
        for(JsonNode preserved:manifest.path("preservedWorldSettingIds")) {
            JsonNode original=null;for(JsonNode row:tables.get("world_settings"))if(row.path("id").asText().equals(preserved.asText()))original=row.path("properties_json");
            assertThat(json.readTree(jdbc.queryForObject("select properties_json::text from world_settings where id=?::uuid",String.class,preserved.asText())))
                    .as("a saved manual decision is not applied automatically").isEqualTo(original);
        }
        tx.executeWithoutResult(s->applications.applyAutomatically(jobs.findById(job).orElseThrow(),null));
        assertThat(states(job)).as("automatic receipt replay does not apply candidates twice").isEqualTo(after);
        output.put("verified",true);output.put("verifiedCharacterFacts",manifest.path("expectedCharacterFacts").size());
        output.put("verifiedWorldProperties",manifest.path("expectedWorldProperties").size());
        Files.writeString(directory.resolve("replay-result.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(output));
    }

    static String nullable(JsonNode row,String key) {return row.hasNonNull(key)?row.get(key).asText():null;}
    List<Map<String,Object>> states(UUID job) {
        return jdbc.queryForList("select id,review_status,automatic_review_hold_reason from setting_candidates where analysis_job_id=? union all select id,review_status,automatic_review_hold_reason from world_setting_candidates where analysis_job_id=? order by id",job,job);
    }
}
