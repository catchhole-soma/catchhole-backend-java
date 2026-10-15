package org.monitoring.catchholebackend.domain.upload.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.monitoring.catchholebackend.global.storage.ObjectStoragePurgeResult;
import org.monitoring.catchholebackend.global.storage.StoredObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** No surrounding test transaction: the request must roll back and run its storage compensation. */
@SpringBootTest(properties = "internal.api-key=test-internal-api-key")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettingBookQueueTransactionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired WorkRepository works;
    @Autowired JwtTokenProvider jwt;
    @Autowired JdbcTemplate sql;
    @MockitoBean ObjectStorage storage;
    @MockitoSpyBean AnalysisRunStateService states;
    @Autowired org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository jobs;
    @Autowired org.monitoring.catchholebackend.domain.character.service.CharacterAnalysisStateService characterStates;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired org.monitoring.catchholebackend.domain.work.repository.WorkPurgeDataRepository purgeData;
    @Autowired org.monitoring.catchholebackend.domain.member.repository.MemberWithdrawalDataRepository withdrawalData;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "hashMismatch", "unavailable"})
    void frozenBookReadFailureRollsBackCompletionAndCleanRetryAppliesCanonicalExactlyOnce(String failure) throws Exception {
        sql.execute("create table if not exists episode_chunks(id uuid primary key,episode_id uuid not null)");
        var member = members.save(Member.register(UUID.randomUUID()+"@example.invalid", "test-only", null, "작가"));
        var work = works.save(Work.create(member, "원문 읽기 재시도", WorkGenre.FANTASY, null));
        var objects = new HashMap<String, byte[]>();
        when(storage.putBytes(anyString(), any(byte[].class), anyString())).thenAnswer(c -> {
            objects.put(c.getArgument(0), c.getArgument(1)); return new StoredObject(c.getArgument(0), "v1");
        });
        when(storage.putText(anyString(), anyString())).thenAnswer(c -> {
            objects.put(c.getArgument(0), c.<String>getArgument(1).getBytes(StandardCharsets.UTF_8)); return new StoredObject(c.getArgument(0), "v1");
        });
        when(storage.getText(anyString())).thenAnswer(c -> objects.containsKey(c.<String>getArgument(0))
                ? new String(objects.get(c.<String>getArgument(0)), StandardCharsets.UTF_8) : null);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var uploaded = mapper.readTree(mvc.perform(multipart("/api/v1/works/{workId}/setting-books", work.getId())
                .file(new MockMultipartFile("file", "설정.txt", MediaType.TEXT_PLAIN_VALUE, "하린은 기사다.".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer "+jwt.generateAccessToken(member))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        var claim = mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/internal/v1/analysis-jobs/claim")
                .header("X-Internal-Api-Key", "test-internal-api-key").contentType(MediaType.APPLICATION_JSON)
                .content("{\"allowedJobTypes\":[\"SETTING_EXTRACTION\"],\"supportsFrozenComparisonEvidence\":true,\"supportedAnalysisModes\":[\"ORDERED_PROVISIONAL\"],\"supportedSourceKinds\":[\"SETTING_BOOK\"],\"supportsSettingBookReferenceTime\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        var jobId = UUID.fromString(claim.path("analysisJobId").asText()); var lease = claim.path("leaseToken").asText();
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var contentKey = tx.execute(status -> {
            var job = jobs.findById(jobId).orElseThrow(); var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
            var discovery = org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.createCharacterDiscovery(job.getWork(), null, null, job, "하린", "하린", null,
                    org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.UNRESOLVED,
                    j.arrayNode().add(j.objectNode().put("quote", "하린").put("startOffset", 0).put("endOffset", 2)), java.math.BigDecimal.ONE, null);
            em.persist(discovery); em.flush();
            org.springframework.test.util.ReflectionTestUtils.setField(discovery, "provisionalSubjectKey", "provisional-character:"+discovery.getId());
            characterStates.prepareProvisionalCandidates(job);
            job.updateCheckpointStage(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED);
            return job.getSourceContentS3Key();
        });
        var original = objects.get(contentKey);
        if (failure.equals("missing")) objects.remove(contentKey);
        else if (failure.equals("hashMismatch")) objects.put(contentKey, "다른 원문".getBytes(StandardCharsets.UTF_8));
        else when(storage.getText(anyString())).thenThrow(new IllegalStateException("synthetic storage unavailable"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/internal/v1/analysis-jobs/{id}/complete", jobId)
                .header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isInternalServerError());
        assertThat(sql.queryForObject("select status from analysis_jobs where id=?", String.class, jobId)).isEqualTo("RUNNING");
        assertThat(sql.queryForObject("select journal_status from analysis_jobs where id=?", String.class, jobId)).isEqualTo("PENDING");
        assertThat(sql.queryForObject("select automatic_applied_at from analysis_jobs where id=?", java.time.LocalDateTime.class, jobId)).isNull();
        assertThat(sql.queryForObject("select count(*) from characters where work_id=?", Integer.class, work.getId())).isZero();
        assertThat(sql.queryForObject("select review_status from setting_candidates where analysis_job_id=?", String.class, jobId)).isEqualTo("PENDING_REVIEW");
        objects.put(contentKey, original);
        doAnswer(c -> new String(objects.get(c.<String>getArgument(0)), StandardCharsets.UTF_8)).when(storage).getText(anyString());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/internal/v1/analysis-jobs/{id}/complete", jobId)
                .header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        // Treat that successful acknowledgement as lost: repeating it cannot add canonical history.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/internal/v1/analysis-jobs/{id}/complete", jobId)
                .header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
        assertThat(sql.queryForObject("select count(*) from characters where work_id=?", Integer.class, work.getId())).isEqualTo(1);
        assertThat(sql.queryForObject("select count(*) from setting_candidates where analysis_job_id=? and review_status='CONFIRMED'", Integer.class, jobId)).isEqualTo(1);
        assertThat(sql.queryForObject("select status from analysis_jobs where id=?", String.class, jobId)).isEqualTo("SUCCEEDED");
        assertThat(sql.queryForObject("select journal_status from analysis_jobs where id=?", String.class, jobId)).isEqualTo("SEALED");
        tx.executeWithoutResult(status -> {
            purgeData.purgeWorkData(work.getId()); withdrawalData.purgeMemberReferences(member.getId(), java.time.LocalDateTime.now()); members.deleteById(member.getId());
        });
    }

    @Test void failedQueueCommitsNoMetadataAndCompensatesEveryNewObject() throws Exception {
        var member=members.save(Member.register(UUID.randomUUID()+"@example.invalid","test-only",null,"작가"));
        var work=works.save(Work.create(member,"롤백 검사",WorkGenre.FANTASY,null));
        Map<String,byte[]> objects=new HashMap<>();
        when(storage.putBytes(anyString(),any(byte[].class),anyString())).thenAnswer(c -> {
            objects.put(c.getArgument(0),c.getArgument(1));return new StoredObject(c.getArgument(0),"v1");
        });
        when(storage.putText(anyString(),anyString())).thenAnswer(c -> {
            objects.put(c.getArgument(0),c.<String>getArgument(1).getBytes(StandardCharsets.UTF_8));return new StoredObject(c.getArgument(0),"v1");
        });
        when(storage.purgePrefixes(anyCollection())).thenAnswer(c -> {
            Collection<String> prefixes=c.getArgument(0);int before=objects.size();
            objects.keySet().removeIf(key -> prefixes.stream().anyMatch(key::startsWith));
            return new ObjectStoragePurgeResult(before-objects.size(),before-objects.size(),0);
        });
        doThrow(new IllegalStateException("synthetic queue failure")).when(states).initializeRun(anyList());
        mvc.perform(multipart("/api/v1/works/{workId}/setting-books",work.getId())
                .file(new MockMultipartFile("file","설정.txt",MediaType.TEXT_PLAIN_VALUE,"하린은 기사다.".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization","Bearer "+jwt.generateAccessToken(member)))
                .andExpect(status().isInternalServerError());
        assertThat(sql.queryForObject("select count(*) from upload_batches where work_id=?",Integer.class,work.getId())).isZero();
        assertThat(sql.queryForObject("select count(*) from upload_files f join upload_batches b on b.id=f.batch_id where b.work_id=?",Integer.class,work.getId())).isZero();
        assertThat(sql.queryForObject("select count(*) from analysis_jobs where work_id=?",Integer.class,work.getId())).isZero();
        assertThat(sql.queryForObject("select count(*) from setting_book_revisions",Integer.class)).isZero();
        assertThat(objects).isEmpty();
        verify(storage).purgePrefixes(argThat(keys -> keys.size()==2));
    }
}
