package org.monitoring.catchholebackend.domain.upload.controller;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.character.entity.*;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.storage.*;
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

/** No enclosing transaction: phase acknowledgements, races and canonical completion actually commit. */
@SpringBootTest(properties = "internal.api-key=test-internal-api-key")
@AutoConfigureMockMvc @ActiveProfiles("test")
class SettingBookCommittedAcceptanceIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired WorkRepository works;
    @Autowired JwtTokenProvider jwt;
    @Autowired JdbcTemplate sql;
    @Autowired PlatformTransactionManager transactions;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired org.monitoring.catchholebackend.domain.work.repository.WorkPurgeDataRepository purgeData;
    @Autowired org.monitoring.catchholebackend.domain.member.repository.MemberWithdrawalDataRepository withdrawalData;
    @Autowired org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository jobs;
    @Autowired AnalysisRunStateService states;
    @Autowired org.monitoring.catchholebackend.domain.character.service.CharacterAnalysisStateService characterStates;
    @Autowired org.monitoring.catchholebackend.domain.character.service.CharacterFactComparisonBatchWorker characterWorker;
    @Autowired org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor accessor;
    @Autowired org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingWorkerService worldWorker;
    @MockitoBean ObjectStorage storage;
    // C02 projection does not yet attach supportingSources. Its advisory serialization is
    // outside these policy regressions; source preparation and comparison validation stay real.
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    org.monitoring.catchholebackend.domain.character.mapper.CharacterFactComparisonWorkerMapper comparisonMapper;
    final ObjectMapper json = new ObjectMapper();
    final Map<String,byte[]> objects = new ConcurrentHashMap<>();
    Member member; Work work; String bearer;

    @BeforeEach void setup() {
        org.mockito.Mockito.doReturn(null).when(comparisonMapper).toAdvisoryCurrentContext(any(), any(), any(), any());
        sql.execute("create table if not exists episode_chunks(id uuid primary key,episode_id uuid not null)");
        member = members.save(Member.register(UUID.randomUUID()+"@example.invalid", "synthetic-only", null, "트랜잭션 작가"));
        work = works.save(Work.create(member, "완료 응답과 수정 경합", WorkGenre.FANTASY, null));
        bearer = "Bearer "+jwt.generateAccessToken(member);
        when(storage.putBytes(anyString(), any(byte[].class), any())).thenAnswer(c -> {
            objects.put(c.getArgument(0), c.getArgument(1)); return new StoredObject(c.getArgument(0), "v1");
        });
        when(storage.putText(anyString(), anyString())).thenAnswer(c -> {
            objects.put(c.getArgument(0), c.<String>getArgument(1).getBytes(StandardCharsets.UTF_8)); return new StoredObject(c.getArgument(0), "v1");
        });
        when(storage.getText(anyString())).thenAnswer(c -> new String(objects.get(c.<String>getArgument(0)), StandardCharsets.UTF_8));
        when(storage.getBytes(anyString())).thenAnswer(c -> objects.get(c.<String>getArgument(0)));
    }
    @AfterEach void cleanup() {
        tx().executeWithoutResult(status -> {
            purgeData.purgeWorkData(work.getId()); withdrawalData.purgeMemberReferences(member.getId(), java.time.LocalDateTime.now()); members.deleteById(member.getId());
        }); objects.clear();
    }

    @ParameterizedTest @ValueSource(strings = {"CHARACTER_COMPARISONS_FINISHED", "WORLD_COMPARISONS_FINISHED", "FINAL"})
    void committedPhaseAndCompletionAckLossCannotDuplicateCanonicalFactsOrWorldHistory(String lostAck) throws Exception {
        var book = upload("하린은 18살이다. 엘프의 서식지는 북부다."); var claim = claim();
        UUID jobId = UUID.fromString(claim.path("analysisJobId").asText()); String lease = claim.path("leaseToken").asText();
        UUID factCandidate = characterPhase(jobId, 18);
        if (lostAck.equals("CHARACTER_COMPARISONS_FINISHED")) lease = failAndResume(book, claim, lostAck);
        UUID worldCandidate = worldPhase(jobId, lease, "북부");
        if (lostAck.equals("WORLD_COMPARISONS_FINISHED")) lease = failAndResume(book, json.createObjectNode().put("analysisJobId", jobId.toString()).put("leaseToken", lease), lostAck);
        assertThat(canonicalCounts()).containsEntry("characters", 0).containsEntry("facts", 0).containsEntry("worlds", 0);
        var beforeCandidates = candidateIds(jobId);
        assertThat(beforeCandidates).contains(factCandidate, worldCandidate);
        complete(jobId, lease, 200);
        var committed = canonicalCounts();
        assertThat(committed).containsEntry("characters", 1).containsEntry("facts", 1).containsEntry("worlds", 1);
        assertThat(sql.queryForObject("select current_age from characters where work_id=?", Integer.class, work.getId())).isEqualTo(18);
        assertThat(sql.queryForObject("select review_status from world_setting_candidates where id=?", String.class, worldCandidate)).isEqualTo("CONFIRMED");
        assertThat(sql.queryForObject("select status from analysis_jobs where id=?", String.class, jobId)).isEqualTo("SUCCEEDED");
        assertThat(sql.queryForObject("select journal_status from analysis_jobs where id=?", String.class, jobId)).isEqualTo("SEALED");
        assertThat(sql.queryForObject("select automatic_applied_at from analysis_jobs where id=?", java.time.LocalDateTime.class, jobId)).isNotNull();
        // Canonical commit succeeded but its HTTP acknowledgement was lost.
        complete(jobId, lease, 409);
        assertThat(canonicalCounts()).isEqualTo(committed); assertThat(candidateIds(jobId)).isEqualTo(beforeCandidates);
        assertThat(sql.queryForObject("select count(*) from setting_candidates where analysis_job_id=? and review_status='CONFIRMED'", Integer.class, jobId)).isEqualTo(2);
        assertThat(sql.queryForObject("select count(*) from world_setting_candidates where analysis_job_id=? and review_status='CONFIRMED'", Integer.class, jobId)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"text", "reference", "archive"})
    void sourceMutationWorkLockPreventsWaitingCompletionFromApplyingStaleCanonicalValues(String change) throws Exception {
        String sourceText = "하린은 18살이다. 엘프의 서식지는 북부다.";
        var book = upload(sourceText); var claim = claim();
        UUID jobId = UUID.fromString(claim.path("analysisJobId").asText()); String lease = claim.path("leaseToken").asText();
        characterPhase(jobId, 18); worldPhase(jobId, lease, "북부");
        var candidates = candidateIds(jobId); var oldBytes = objects.get(sql.queryForObject("select source_content_s3_key from analysis_jobs where id=?", String.class, jobId)).clone();
        race(() -> {
            if (change.equals("archive")) mvc.perform(delete("/api/v1/works/{work}/setting-books/{id}", work.getId(), book.path("id").asText()).header("Authorization", bearer)).andExpect(status().isOk());
            else {
                var request = new LinkedHashMap<String,Object>(); request.put("content", change.equals("text") ? "수정한 원문" : sourceText);
                request.put("referenceTimeKind", change.equals("reference") ? "EPISODE" : "WORK_START");
                if (change.equals("reference")) request.put("referenceEpisodeNo", 3);
                request.put("expectedRevisionNo", 1);
                mvc.perform(patch("/api/v1/works/{work}/setting-books/{id}", work.getId(), book.path("id").asText()).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request))).andExpect(status().isOk());
            }
        }, () -> complete(jobId, lease, 409));
        assertThat(canonicalCounts()).containsEntry("characters", 0).containsEntry("facts", 0).containsEntry("worlds", 0);
        assertThat(candidateIds(jobId)).isEqualTo(candidates);
        assertThat(sql.queryForObject("select status from analysis_jobs where id=?", String.class, jobId)).isEqualTo("CANCELED");
        assertThat(sql.queryForObject("select lease_token from analysis_jobs where id=?", UUID.class, jobId)).isNull();
        assertThat(sql.queryForObject("select count(*) from setting_candidates where analysis_job_id=? and review_status='CONFIRMED'", Integer.class, jobId)).isZero();
        assertThat(sql.queryForObject("select count(*) from world_setting_candidates where analysis_job_id=? and review_status='CONFIRMED'", Integer.class, jobId)).isZero();
        assertThat(objects.get(sql.queryForObject("select source_content_s3_key from analysis_jobs where id=?", String.class, jobId))).isEqualTo(oldBytes);
    }

    @ParameterizedTest @ValueSource(strings = {"character", "world"})
    void canonicalEditWorkLockRejectsWaitingCurrentOverrideWithoutPartialHistory(String domain) throws Exception {
        var book = upload("하린은 18살이다. 엘프의 서식지는 북부다."); var first = claim();
        UUID firstId = UUID.fromString(first.path("analysisJobId").asText()); String firstLease = first.path("leaseToken").asText();
        characterPhase(firstId, 18); worldPhase(firstId, firstLease, "북부"); complete(firstId, firstLease, 200);
        mvc.perform(patch("/api/v1/works/{work}/setting-books/{id}", work.getId(), book.path("id").asText()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("content", "하린은 21살이다. 엘프의 서식지는 남부다.", "referenceTimeKind", "WORK_START", "expectedRevisionNo", 1)))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/works/{work}/setting-books/{id}/analysis-jobs", work.getId(), book.path("id").asText()).header("Authorization", bearer)).andExpect(status().isOk());
        var second = claim(); UUID jobId = UUID.fromString(second.path("analysisJobId").asText()); String lease = second.path("leaseToken").asText();
        UUID ageId = characterPhase(jobId, 21); UUID worldId = worldPhase(jobId, lease, "남부"); complete(jobId, lease, 200);
        UUID characterId = sql.queryForObject("select id from characters where work_id=?", UUID.class, work.getId());
        UUID actualWorldId = sql.queryForObject("select id from world_settings where work_id=?", UUID.class, work.getId());
        long characterVersion = sql.queryForObject("select snapshot_version from characters where id=?", Long.class, characterId);
        long worldVersion = sql.queryForObject("select version from world_settings where id=?", Long.class, actualWorldId);
        var ageTime = sql.queryForObject("select updated_at from setting_candidates where id=?", java.time.LocalDateTime.class, ageId);
        var worldTime = sql.queryForObject("select updated_at from world_setting_candidates where id=?", java.time.LocalDateTime.class, worldId);
        var before = canonicalCounts();
        race(() -> {
            if (domain.equals("character")) mvc.perform(patch("/api/v1/works/{work}/characters/{id}", work.getId(), characterId).header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"하린\",\"currentAge\":77,\"profile\":[],\"stats\":[],\"skills\":[],\"items\":[],\"statuses\":[]}")).andExpect(status().isOk());
            else mvc.perform(patch("/api/v1/works/{work}/world-settings/{id}/properties", work.getId(), actualWorldId).header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("currentSettingName", "서식지", "settingName", "서식지", "settingValue", "작가가 정한 지역", "version", worldVersion)))).andExpect(status().isOk());
        }, () -> {
            if (domain.equals("character")) mvc.perform(post("/api/v1/works/{work}/setting-candidates/{id}/confirm", work.getId(), ageId).header("Authorization", bearer)
                    .param("sourceAnalysisJobId", jobId.toString()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("applicationMode", "APPLY_PROPOSAL", "applyToCurrent", true, "baseSnapshotVersion", characterVersion, "expectedUpdatedAt", ageTime.toString())))).andExpect(status().isConflict());
            else mvc.perform(post("/api/v1/works/{work}/world-setting-candidates/{id}/confirm", work.getId(), worldId).header("Authorization", bearer)
                    .param("sourceAnalysisJobId", jobId.toString()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("operation", "UPDATE", "category", "RACE", "subjectName", "엘프", "settingName", "서식지", "value", "남부",
                            "applyToCurrent", true, "expectedWorldSettingVersion", worldVersion, "expectedUpdatedAt", worldTime.toString())))).andExpect(status().isConflict());
        });
        assertThat(sql.queryForObject("select review_status from setting_candidates where id=?", String.class, ageId)).isEqualTo("PENDING_REVIEW");
        assertThat(sql.queryForObject("select review_status from world_setting_candidates where id=?", String.class, worldId)).isEqualTo("PENDING_REVIEW");
        assertThat(sql.queryForObject("select count(*) from character_facts where extracted_by_job_id=?", Integer.class, jobId)).isZero();
        assertThat(sql.queryForObject("select count(*) from world_setting_candidates where analysis_job_id=? and review_status='CONFIRMED'", Integer.class, jobId)).isZero();
        assertThat(canonicalCounts().get("worlds")).isEqualTo(before.get("worlds"));
        if (domain.equals("character")) {
            assertThat(sql.queryForObject("select current_age from characters where id=?", Integer.class, characterId)).isEqualTo(77);
            assertThat(tx().<Boolean>execute(s -> em.find(WorkCharacter.class, characterId).isManuallyEditedSlot(CharacterFactType.AGE, "age"))).isTrue();
        } else {
            assertThat(tx().<String>execute(s -> em.find(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.class, actualWorldId).getPropertyValue("서식지"))).isEqualTo("작가가 정한 지역");
            assertThat(tx().<Boolean>execute(s -> em.find(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.class, actualWorldId).isManuallyEdited(null, "서식지"))).isTrue();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"character", "world"})
    void canonicalEditWorkLockRejectsManualMutationBeforeWaitingAutomaticCompletion(String domain) throws Exception {
        var book = upload("하린은 18살이다. 엘프의 서식지는 북부다."); var first = claim();
        UUID firstId = UUID.fromString(first.path("analysisJobId").asText()); String firstLease = first.path("leaseToken").asText();
        characterPhase(firstId, 18); worldPhase(firstId, firstLease, "북부"); complete(firstId, firstLease, 200);
        mvc.perform(patch("/api/v1/works/{work}/setting-books/{id}", work.getId(), book.path("id").asText()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("content", "하린은 21살이다. 엘프의 서식지는 남부다.",
                        "referenceTimeKind", "EPISODE", "referenceEpisodeNo", 3, "expectedRevisionNo", 1)))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/works/{work}/setting-books/{id}/analysis-jobs", work.getId(), book.path("id").asText()).header("Authorization", bearer)).andExpect(status().isOk());
        var claimed = claim(); UUID jobId = UUID.fromString(claimed.path("analysisJobId").asText()); String lease = claimed.path("leaseToken").asText();
        UUID ageCandidate = characterPhase(jobId, 21); UUID worldCandidate = worldPhase(jobId, lease, "남부");
        UUID characterId = sql.queryForObject("select id from characters where work_id=?", UUID.class, work.getId());
        UUID worldId = sql.queryForObject("select id from world_settings where work_id=?", UUID.class, work.getId());
        long worldVersion = sql.queryForObject("select version from world_settings where id=?", Long.class, worldId);
        race(() -> {
            if (domain.equals("character")) mvc.perform(patch("/api/v1/works/{work}/characters/{id}", work.getId(), characterId).header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"하린\",\"currentAge\":77,\"profile\":[],\"stats\":[],\"skills\":[],\"items\":[],\"statuses\":[]}")).andExpect(status().isConflict());
            else mvc.perform(patch("/api/v1/works/{work}/world-settings/{id}/properties", work.getId(), worldId).header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("currentSettingName", "서식지", "settingName", "서식지", "settingValue", "작가가 정한 지역", "version", worldVersion))))
                    .andExpect(status().isConflict());
            // The rejected service marks this transaction rollback-only. Assert durable state after rollback,
            // rather than the dirty managed entity that has not yet been rolled back.
        }, () -> complete(jobId, lease, 200), true);
        assertThat(sql.queryForObject("select review_status from setting_candidates where id=?", String.class, ageCandidate)).isEqualTo("CONFIRMED");
        assertThat(sql.queryForObject("select review_status from world_setting_candidates where id=?", String.class, worldCandidate)).isEqualTo("CONFIRMED");
        assertThat(sql.queryForObject("select current_age from characters where id=?", Integer.class, characterId)).isEqualTo(21);
        assertThat(tx().<String>execute(s -> em.find(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.class, worldId).getPropertyValue("서식지")))
                .isEqualTo("남부");
        assertThat(manualMarkers(characterId, worldId).get("character")).isEmpty(); assertThat(manualMarkers(characterId, worldId).get("world")).isEmpty();
        assertThat(sql.queryForObject("select count(*) from character_facts where extracted_by_job_id=?", Integer.class, jobId)).isEqualTo(1);

        assertThat(sql.queryForObject("select count(*) from world_setting_candidates where analysis_job_id=? and review_status='CONFIRMED'", Integer.class, jobId)).isEqualTo(1);
        assertThat(sql.queryForObject("select status from analysis_jobs where id=?", String.class, jobId)).isEqualTo("SUCCEEDED");
        assertThat(sql.queryForObject("select journal_status from analysis_jobs where id=?", String.class, jobId)).isEqualTo("SEALED");
        assertThat(sql.queryForObject("select automatic_applied_at from analysis_jobs where id=?", java.time.LocalDateTime.class, jobId)).isNotNull();
    }

    @Test void manualMarkersSurviveSourceSaveCheckpointRetryAndDuplicateExcludeUntilTargetPurge() throws Exception {
        UUID characterId = tx().execute(s -> {
            var actual = WorkCharacter.create(em.find(Work.class, work.getId()), "하린", null, null, null, null, null, null, null, null, null);
            em.persist(actual); em.flush(); return actual.getId();
        });
        UUID worldId = tx().execute(s -> {
            var actual = org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.create(em.find(Work.class, work.getId()),
                    org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE, "엘프", "서식지", "북부");
            em.persist(actual); em.flush(); return actual.getId();
        });
        mvc.perform(patch("/api/v1/works/{work}/characters/{id}", work.getId(), characterId).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"하린\",\"currentAge\":77,\"profile\":[],\"stats\":[],\"skills\":[],\"items\":[],\"statuses\":[]}")).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/works/{work}/world-settings/{id}/properties", work.getId(), worldId).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("currentSettingName", "서식지", "settingName", "서식지", "settingValue", "북부",
                        "version", sql.queryForObject("select version from world_settings where id=?", Long.class, worldId))))).andExpect(status().isOk());
        var markers = manualMarkers(characterId, worldId);
        assertThat(markers.get("character")).isNotEmpty(); assertThat(markers.get("world")).isNotEmpty();
        int beforeFacts = sql.queryForObject("select count(*) from character_facts where character_id=?", Integer.class, characterId);
        String text = "하린은 77살이다. 엘프의 서식지는 북부다."; var book = upload(text);
        mvc.perform(patch("/api/v1/works/{work}/setting-books/{id}", work.getId(), book.path("id").asText()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("content", text, "referenceTimeKind", "EPISODE", "referenceEpisodeNo", 3, "expectedRevisionNo", 1))))
                .andExpect(status().isOk());
        assertThat(sql.queryForObject("select count(*) from analysis_jobs where work_id=?", Integer.class, work.getId())).isEqualTo(1);
        assertThat(manualMarkers(characterId, worldId)).isEqualTo(markers);
        mvc.perform(post("/api/v1/works/{work}/setting-books/{id}/analysis-jobs", work.getId(), book.path("id").asText()).header("Authorization", bearer)).andExpect(status().isOk());
        var claimed = claim(); UUID jobId = UUID.fromString(claimed.path("analysisJobId").asText()); String lease = claimed.path("leaseToken").asText();
        UUID characterCandidate = characterPhase(jobId, 77, true); UUID worldCandidate = worldPhase(jobId, lease, "북부", true);
        lease = failAndResume(book, claimed, "WORLD_COMPARISONS_FINISHED");
        assertThat(manualMarkers(characterId, worldId)).isEqualTo(markers);
        complete(jobId, lease, 200);
        assertThat(sql.queryForObject("select review_status from setting_candidates where id=?", String.class, characterCandidate)).isEqualTo("DISMISSED");
        assertThat(sql.queryForObject("select review_status from world_setting_candidates where id=?", String.class, worldCandidate)).isEqualTo("DISMISSED");
        assertThat(sql.queryForObject("select current_age from characters where id=?", Integer.class, characterId)).isEqualTo(77);
        assertThat(tx().<String>execute(s -> em.find(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.class, worldId).getPropertyValue("서식지"))).isEqualTo("북부");
        assertThat(sql.queryForObject("select count(*) from character_facts where character_id=?", Integer.class, characterId)).isEqualTo(beforeFacts);
        assertThat(sql.queryForObject("select count(*) from character_facts where extracted_by_job_id=?", Integer.class, jobId)).isZero();
        assertThat(manualMarkers(characterId, worldId)).isEqualTo(markers);
        mvc.perform(delete("/api/v1/works/{work}/characters/{id}", work.getId(), characterId).header("Authorization", bearer)).andExpect(status().isOk());
        assertThat(sql.queryForObject("select status from characters where id=?", String.class, characterId)).isEqualTo("ARCHIVED");
        assertThat(manualMarkers(characterId, worldId)).isEqualTo(markers);
        tx().executeWithoutResult(s -> purgeData.purgeWorkData(work.getId()));
        assertThat(sql.queryForObject("select count(*) from characters where id=?", Integer.class, characterId)).isZero();
        assertThat(sql.queryForObject("select count(*) from world_settings where id=?", Integer.class, worldId)).isZero();
        assertThat(sql.queryForObject("select count(*) from setting_book_revisions where setting_book_id=?", Integer.class, UUID.fromString(book.path("id").asText()))).isZero();
    }

    private Map<String,JsonNode> manualMarkers(UUID characterId, UUID worldId) {
        return tx().execute(s -> Map.of("character", em.find(WorkCharacter.class, characterId).getManuallyEditedSlots().deepCopy(),
                "world", em.find(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.class, worldId).getManuallyEditedPaths().deepCopy()));
    }

    /** Mutating request owns the work lock before the competing real HTTP request enters its service. */
    @Test void unanchoredCompatibleWorldMergeProjectsAndCommitsWithoutReplacingSupportingSources() throws Exception {
        upload("엘프의 서식지는 북부다."); var first = claim();
        UUID firstId = UUID.fromString(first.path("analysisJobId").asText()); String firstLease = first.path("leaseToken").asText();
        tx().executeWithoutResult(s -> jobs.findById(firstId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED));
        UUID original = worldPhase(firstId, firstLease, "북부"); complete(firstId, firstLease, 200);
        UUID worldId = sql.queryForObject("select id from world_settings where work_id=?", UUID.class, work.getId());

        upload("엘프는 북부의 숲에도 산다.", "UNSPECIFIED"); var second = claim();
        UUID jobId = UUID.fromString(second.path("analysisJobId").asText()); String lease = second.path("leaseToken").asText();
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED));
        UUID supplement = worldPhase(jobId, lease, "북부의 숲", false, "MERGE");
        assertThat(projectedWorldValue(jobId, worldId)).isEqualTo("북부의 숲");
        complete(jobId, lease, 200);
        assertThat(tx().<String>execute(s -> em.find(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.class, worldId).getPropertyValue("서식지")))
                .as("world automatic result %s", sql.queryForMap("select review_status, automatic_review_hold_reason, history_only from world_setting_candidates where id=?", supplement)).isEqualTo("북부의 숲");
        assertThat(sql.queryForList("select id from world_setting_candidates where target_world_setting_id=? and review_status='CONFIRMED'", UUID.class, worldId))
                .containsExactlyInAnyOrder(original, supplement);
    }

    @ParameterizedTest @ValueSource(strings = {"ADD", "UPDATE", "MERGE"})
    void laterWorldConflictRestoresOnlyItsPathAndKeepsUnrelatedProperty(String operation) throws Exception {
        UUID actualId = null, original = null;
        if (!operation.equals("ADD")) {
            upload("엘프의 서식지는 북부다.", "UNSPECIFIED"); var initial = claim();
            UUID id = UUID.fromString(initial.path("analysisJobId").asText()); String lease = initial.path("leaseToken").asText();
            tx().executeWithoutResult(s -> jobs.findById(id).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED));
            original = worldPhase(id, lease, "북부"); complete(id, lease, 200);
            actualId = sql.queryForObject("select id from world_settings where work_id=?", UUID.class, work.getId());
        }
        String firstValue = actualId == null ? "북부" : "동부";
        upload("엘프의 서식지는 "+firstValue+"다. 거주지는 남부, 삶의 터는 서부라고도 적혔다. 특징은 장수다.", "UNSPECIFIED"); var claimed = claim();
        UUID jobId = UUID.fromString(claimed.path("analysisJobId").asText()); String lease = claimed.path("leaseToken").asText();
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED));
        UUID leaseId = UUID.fromString(lease);
        var published = worldWorker.publishWorldSettingCandidates(jobId, leaseId, new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest(
                List.of(worldInput("서식지", firstValue), worldInput("거주지", "남부"), worldInput("삶의 터", "서부"), worldInput("특징", "장수"))));
        UUID first = published.get(0).candidateId(), conflict = published.get(1).candidateId(), otherConflict = published.get(2).candidateId(), unrelated = published.get(3).candidateId();
        String subject = "provisional-world:"+first; final UUID targetId = actualId;
        worldWorker.resolveWorldSettingSubjects(jobId, leaseId, new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest(
                published.stream().map(row -> new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput(row.candidateId(), targetId == null ? List.of() : List.of(targetId), targetId == null ? List.of(subject) : List.of(), false)).toList()));
        var batch = worldWorker.claimNextWorldSettingComparisonBatch(jobId, leaseId).orElseThrow();
        var context = worldWorker.getWorldSettingComparisonBatchContext(jobId, batch.comparisonBatchId(), leaseId,
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchContextRequest(targetId == null ? List.of() : List.of(targetId), targetId == null ? List.of(subject) : List.of()));
        String targetRef = targetId == null ? subject : "world:"+targetId;
        JsonNode baseline = tx().execute(s -> states.getInputState(jobs.findById(jobId).orElseThrow()).path("worldSettings").path(targetRef).path("provenanceByPath").deepCopy());
        var refs = new HashMap<String,String>(); context.candidates().forEach(row -> refs.put(row.settingName(), row.candidateRef()));
        var decisions = new ArrayList<Map<String,Object>>();
        for (String name : List.of("서식지", "특징", "거주지")) {
            var d = new LinkedHashMap<String,Object>(); boolean conflictDecision = name.equals("거주지");
            d.put("decisionRef", "D"+(decisions.size()+1)); d.put("sourceCandidateRefs", conflictDecision ? List.of(refs.get(name), refs.get("삶의 터")) : List.of(refs.get(name))); d.put("canonicalSubjectName", "엘프");
            if (targetId == null) d.put("provisionalSubjectKey", subject); else d.put("targetWorldSettingId", targetId);
            d.put("consolidationStatus", conflictDecision ? "CONFLICT" : "SINGLE"); d.put("suggestedOperation", conflictDecision ? operation : name.equals("서식지") && targetId != null ? "UPDATE" : "ADD");
            if (targetId != null && !name.equals("특징")) d.put("matchedPropertyName", "서식지");
            d.put("proposedSettingName", conflictDecision ? "서식지" : name); d.put("proposedValue", conflictDecision ? "남부 / 서부" : name.equals("서식지") ? firstValue : "장수"); d.put("comparisonReason", "deterministic typed comparison"); decisions.add(d);
        }
        var version = new LinkedHashMap<String,Object>(); version.put("version", context.targets().getFirst().version());
        if (targetId == null) version.put("provisionalSubjectKey", subject); else version.put("worldSettingId", targetId);
        mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/world-setting-comparison-batches/{batch}/complete", jobId, batch.comparisonBatchId())
                .header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("contextToken", context.contextToken(), "contextVersions", List.of(version), "decisions", decisions)))).andExpect(status().isOk());
        if (targetId == null) assertThat(projectedWorldProperties(jobId).path("서식지").isMissingNode()).isTrue();
        else {
            assertThat(projectedWorldValue(jobId, targetId)).isEqualTo("북부");
            JsonNode restored = tx().execute(s -> states.getProjectedState(jobs.findById(jobId).orElseThrow()).path("worldSettings").path(targetRef).path("provenanceByPath"));
            baseline.fields().forEachRemaining(entry -> assertThat(restored.path(entry.getKey())).isEqualTo(entry.getValue()));
        }
        assertThat(projectedWorldProperties(jobId).path("특징").asText()).isEqualTo("장수");
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED));
        complete(jobId, lease, 200);
        assertThat(sql.queryForList("select id from world_setting_candidates where analysis_job_id=? and review_status='PENDING_REVIEW'", UUID.class, jobId)).containsExactlyInAnyOrder(first, conflict, otherConflict);
        assertThat(sql.queryForObject("select review_status from world_setting_candidates where id=?", String.class, unrelated)).isEqualTo("CONFIRMED");
        UUID worldId = sql.queryForObject("select id from world_settings where work_id=?", UUID.class, work.getId());
        assertThat(tx().<String>execute(s -> em.find(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.class, worldId).getPropertyValue("서식지"))).isEqualTo(targetId == null ? null : "북부");
        assertThat(tx().<String>execute(s -> em.find(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.class, worldId).getPropertyValue("특징"))).isEqualTo("장수");
        assertThat(sql.queryForObject("select proposed_value from world_setting_candidates where id=?", String.class, conflict)).isEqualTo("남부 / 서부");
        assertThat(sql.queryForObject("select proposed_value from world_setting_candidates where id=?", String.class, otherConflict)).isEqualTo("남부 / 서부");
        if (original != null) assertThat(sql.queryForObject("select review_status from world_setting_candidates where id=?", String.class, original)).isEqualTo("CONFIRMED");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void existingDestinationMergeWithNoCurrentOriginStaysHeldInProjectionAndFinalCurrent(boolean sameValue) throws Exception {
        UUID characterId = tx().execute(s -> {
            var profile = json.createObjectNode().set("role", json.createObjectNode().put("value", "기록관"));
            var character = WorkCharacter.create(em.find(Work.class, work.getId()), "하린", null, null, null, profile, null, null, null, null, null);
            em.persist(character); em.flush(); return character.getId();
        });
        UUID worldId = tx().execute(s -> {
            var world = org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.create(em.find(Work.class, work.getId()),
                    org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE, "엘프", "서식지", "북부");
            em.persist(world); em.flush(); return world.getId();
        });
        upload("하린은 기록관이며 문서를 관리한다. 엘프의 서식지는 북부이며 숲에 거주한다.", "UNSPECIFIED"); var claimed = claim();
        UUID jobId = UUID.fromString(claimed.path("analysisJobId").asText()); String lease = claimed.path("leaseToken").asText();
        JsonNode baseline = projectedCharacterSlot(jobId, characterId, "role");
        assertThat(baseline.path("provenance").path("currentSourceTimes").get(0).path("temporalScope").asText()).isEqualTo("UNKNOWN");
        UUID role = characterCandidate(jobId, characterId, "role", sameValue ? "기록관" : "기록관이며 문서를 관리한다", SettingValueType.STRING); completeCharacterBatch(jobId, lease, "MERGE", true);
        JsonNode projectedRole = projectedCharacterSlot(jobId, characterId, "role").deepCopy();
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED));
        UUID home = worldPhase(jobId, lease, sameValue ? "북부" : "북부이며 숲에 거주한다", false, "MERGE"); String projectedHome = projectedWorldValue(jobId, worldId);
        complete(jobId, lease, 200);
        String actualRole = tx().execute(s -> accessor.read(em.find(WorkCharacter.class, characterId)).get(new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot(CharacterFactType.PROFILE, "role")).valueJson().path("value").asText());
        String actualHome = tx().execute(s -> em.find(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.class, worldId).getPropertyValue("서식지"));
        System.out.println("TASK2_UNKNOWN_MERGE="+json.writeValueAsString(Map.of("sameValue", sameValue, "projectedRole", projectedRole, "projectedHome", projectedHome, "actualRole", actualRole, "actualHome", actualHome)));
        assertSoftly(soft -> {
            soft.assertThat(projectedRole).isEqualTo(baseline); soft.assertThat(projectedHome).isEqualTo("북부");
            soft.assertThat(actualRole).isEqualTo("기록관"); soft.assertThat(actualHome).isEqualTo("북부");
            soft.assertThat(sql.queryForObject("select automatic_review_hold_reason from setting_candidates where id=?", String.class, role)).isEqualTo("SETTING_TIME_CONFIRMATION_REQUIRED");
            soft.assertThat(sql.queryForObject("select automatic_review_hold_reason from world_setting_candidates where id=?", String.class, home)).isEqualTo("SETTING_TIME_CONFIRMATION_REQUIRED");
            soft.assertThat(sql.queryForObject("select count(*) from character_facts where extracted_by_job_id=?", Integer.class, jobId)).isZero();
        });
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void compatibleDestinationMergeWithKnownSameOrEarlierBookTimeProjectsAndConfirms(int incomingEpisode) throws Exception {
        UUID characterId = emptyCharacter(); upload("하린은 기록관이다. 엘프의 서식지는 북부다.", "EPISODE", 2); var first = claim();
        UUID firstId = UUID.fromString(first.path("analysisJobId").asText()); String firstLease = first.path("leaseToken").asText();
        UUID originalRole = characterCandidate(firstId, characterId, "role", "기록관", SettingValueType.STRING); completeCharacterBatch(firstId, firstLease, "ADD", false);
        tx().executeWithoutResult(s -> jobs.findById(firstId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED));
        UUID originalHome = worldPhase(firstId, firstLease, "북부"); complete(firstId, firstLease, 200);
        UUID worldId = sql.queryForObject("select id from world_settings where work_id=?", UUID.class, work.getId());
        upload("하린은 기록관이며 문서를 관리한다. 엘프의 서식지는 북부이며 숲에 거주한다.", "EPISODE", incomingEpisode); var claimed = claim();
        UUID jobId = UUID.fromString(claimed.path("analysisJobId").asText()); String lease = claimed.path("leaseToken").asText();
        UUID role = characterCandidate(jobId, characterId, "role", "기록관이며 문서를 관리한다", SettingValueType.STRING); completeCharacterBatch(jobId, lease, "MERGE", true);
        assertThat(projectedCharacterSlot(jobId, characterId, "role").path("valueJson").path("value").asText()).isEqualTo("기록관이며 문서를 관리한다");
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED));
        UUID home = worldPhase(jobId, lease, "북부이며 숲에 거주한다", false, "MERGE");
        assertThat(projectedWorldValue(jobId, worldId)).isEqualTo("북부이며 숲에 거주한다"); complete(jobId, lease, 200);
        assertThat(sql.queryForObject("select review_status from setting_candidates where id=?", String.class, role)).isEqualTo("CONFIRMED");
        assertThat(sql.queryForObject("select review_status from world_setting_candidates where id=?", String.class, home)).isEqualTo("CONFIRMED");
        assertThat(sql.queryForObject("select review_status from setting_candidates where id=?", String.class, originalRole)).isEqualTo("CONFIRMED");
        assertThat(sql.queryForObject("select review_status from world_setting_candidates where id=?", String.class, originalHome)).isEqualTo("CONFIRMED");
        assertThat(sql.queryForObject("select count(*) from character_snapshot_sources where character_id=? and fact_key='role'", Integer.class, characterId)).isEqualTo(2);
    }

    @Test void newAutomaticAddWithNoPriorCurrentOriginsStillProjectsAndConfirms() throws Exception {
        UUID characterId = emptyCharacter(); upload("하린은 기록관이다. 엘프의 서식지는 북부다.", "UNSPECIFIED"); var claimed = claim();
        UUID jobId = UUID.fromString(claimed.path("analysisJobId").asText()); String lease = claimed.path("leaseToken").asText();
        assertThat(projectedCharacterSlot(jobId, characterId, "role").isMissingNode()).isTrue();
        UUID role = characterCandidate(jobId, characterId, "role", "기록관", SettingValueType.STRING); completeCharacterBatch(jobId, lease, "ADD", false);
        assertThat(projectedCharacterSlot(jobId, characterId, "role").path("valueJson").path("value").asText()).isEqualTo("기록관");
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED));
        UUID home = worldPhase(jobId, lease, "북부"); assertThat(projectedWorldProperties(jobId).path("서식지").asText()).isEqualTo("북부"); complete(jobId, lease, 200);
        assertThat(sql.queryForObject("select review_status from setting_candidates where id=?", String.class, role)).isEqualTo("CONFIRMED");
        assertThat(sql.queryForObject("select review_status from world_setting_candidates where id=?", String.class, home)).isEqualTo("CONFIRMED");
    }

    @Test void explicitPresentChangeUpdatesCharacterAndWorldWithUnspecifiedBookTimes() throws Exception {
        UUID characterId = emptyCharacter(); upload("하린은 기록관이다. 엘프의 서식지는 북부다.", "UNSPECIFIED"); var first = claim();
        UUID firstId = UUID.fromString(first.path("analysisJobId").asText()); String firstLease = first.path("leaseToken").asText();
        characterCandidate(firstId, characterId, "role", "기록관", SettingValueType.STRING); completeCharacterBatch(firstId, firstLease, "ADD", false);
        tx().executeWithoutResult(s -> jobs.findById(firstId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED));
        worldPhase(firstId, firstLease, "북부"); complete(firstId, firstLease, 200);
        UUID worldId = sql.queryForObject("select id from world_settings where work_id=?", UUID.class, work.getId());
        upload("하린은 대장에 임명되었다. 엘프는 남부로 이주했다.", "UNSPECIFIED"); var second = claim();
        UUID jobId = UUID.fromString(second.path("analysisJobId").asText()); String lease = second.path("leaseToken").asText();
        UUID role = characterCandidate(jobId, characterId, "role", "대장", SettingValueType.STRING); completeCharacterBatch(jobId, lease, "UPDATE", true);
        assertThat(projectedCharacterSlot(jobId, characterId, "role").path("valueJson").path("value").asText()).isEqualTo("대장");
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED));
        UUID relocation = worldPhase(jobId, lease, "남부"); assertThat(projectedWorldValue(jobId, worldId)).isEqualTo("남부");
        complete(jobId, lease, 200);
        assertThat(sql.queryForObject("select review_status from setting_candidates where id=?", String.class, role))
                .as("character automatic hold %s", sql.queryForMap("select automatic_review_hold_reason from setting_candidates where id=?", role)).isEqualTo("CONFIRMED");
        assertThat(sql.queryForObject("select review_status from world_setting_candidates where id=?", String.class, relocation)).isEqualTo("CONFIRMED");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void linkedSameDocumentConflictRestoresHeightAndKeepsUnrelatedItem(boolean reverse) throws Exception {
        UUID characterId = emptyCharacter(); upload("하린의 키는 168이다. 다른 곳에는 178이라고 적혀 있다. 머리색은 검정이다.");
        var claimed = claim(); UUID jobId = UUID.fromString(claimed.path("analysisJobId").asText()); String lease = claimed.path("leaseToken").asText();
        UUID first = characterCandidate(jobId, characterId, "height", reverse ? "178" : "168", SettingValueType.NUMBER);
        completeCharacterBatch(jobId, lease, "ADD", false);
        UUID conflict = characterCandidate(jobId, characterId, "height", reverse ? "168" : "178", SettingValueType.NUMBER);
        completeCharacterBatch(jobId, lease, "REVIEW_REQUIRED", true);
        assertThat(projectedCharacterSlot(jobId, characterId, "height").isMissingNode()).isTrue();
        UUID hair = characterCandidate(jobId, characterId, "hair", "검정", SettingValueType.STRING);
        completeCharacterBatch(jobId, lease, "ADD", false);
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED));
        complete(jobId, lease, 200);
        assertThat(sql.queryForList("select id from setting_candidates where analysis_job_id=? and review_status='PENDING_REVIEW'", UUID.class, jobId)).containsExactlyInAnyOrder(first, conflict);
        assertThat(sql.queryForObject("select review_status from setting_candidates where id=?", String.class, hair)).isEqualTo("CONFIRMED");
        assertThat(tx().<Boolean>execute(s -> accessor.read(em.find(WorkCharacter.class, characterId)).keySet().stream().noneMatch(slot -> slot.factKey().equals("height")))).isTrue();
        assertThat(sql.queryForObject("select count(*) from character_facts where extracted_by_job_id=?", Integer.class, jobId)).isEqualTo(1);
        if (!reverse) {
            JsonNode firstResponse = candidateDetail(jobId, first);
            JsonNode conflictResponse = candidateDetail(jobId, conflict);
            JsonNode detail = data(mvc.perform(get("/api/v1/works/{work}/characters/{id}", work.getId(), characterId).header("Authorization", bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(conflictResponse.path("snapshotChanges").isEmpty()).isTrue();
            assertThat(firstResponse.path("snapshotChanges").get(0).path("beforeFactValue").isNull()).isTrue();
            System.out.println("TASK2_CHARACTER_PUBLIC_PAYLOAD="+json.writeValueAsString(Map.of("first", firstResponse, "conflict", conflictResponse, "currentDetail", detail)));
        }
    }

    @Test void unlinkedPresentUncertaintyKeepsEarlierCleanHeight() throws Exception {
        UUID characterId = emptyCharacter(); upload("하린의 키는 168이다. 키에 관한 추가 정보는 불확실하다.");
        var claimed = claim(); UUID jobId = UUID.fromString(claimed.path("analysisJobId").asText()); String lease = claimed.path("leaseToken").asText();
        UUID first = characterCandidate(jobId, characterId, "height", "168", SettingValueType.NUMBER);
        completeCharacterBatch(jobId, lease, "ADD", false);
        characterCandidate(jobId, characterId, "height", "178", SettingValueType.NUMBER);
        completeCharacterBatch(jobId, lease, "REVIEW_REQUIRED", false);
        assertThat(projectedCharacterSlot(jobId, characterId, "height").path("valueJson").path("value").asInt()).isEqualTo(168);
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED));
        complete(jobId, lease, 200);
        assertThat(sql.queryForObject("select review_status from setting_candidates where id=?", String.class, first)).isEqualTo("CONFIRMED");
    }

    @Test void invalidatedOldComparisonPrefixCannotResumeAndFreshAnalysisUsesUnchangedSource() throws Exception {
        UUID characterId = emptyCharacter(); var book = upload("하린의 키는 168이다.", "UNSPECIFIED"); var claimed = claim();
        UUID jobId = UUID.fromString(claimed.path("analysisJobId").asText()); String lease = claimed.path("leaseToken").asText();
        characterCandidate(jobId, characterId, "height", "168", SettingValueType.NUMBER); completeCharacterBatch(jobId, lease, "ADD", false);
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED));
        String sourceHash = sql.queryForObject("select source_content_hash from analysis_jobs where id=?", String.class, jobId);
        JsonNode oldJournal = tx().execute(s -> jobs.findById(jobId).orElseThrow().getStateJournal().deepCopy());
        tx().executeWithoutResult(s -> states.invalidateFrom(jobs.findByIdForUpdate(jobId).orElseThrow(), "비교 정책 변경으로 미완료 결과를 새 원문 분석에서 다시 비교합니다."));
        mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/heartbeat", jobId).header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease)).andExpect(status().isConflict());
        complete(jobId, lease, 409);
        mvc.perform(post("/api/v1/works/{work}/analysis-jobs/{id}/retry", work.getId(), jobId).header("Authorization", bearer)).andExpect(status().isConflict());
        assertThat(sql.queryForObject("select journal_status from analysis_jobs where id=?", String.class, jobId)).isEqualTo("INVALIDATED");
        assertThat(tx().<JsonNode>execute(s -> jobs.findById(jobId).orElseThrow().getStateJournal())).isEqualTo(oldJournal);
        JsonNode created = data(mvc.perform(post("/api/v1/works/{work}/setting-books/{id}/analysis-jobs", work.getId(), book.path("id").asText()).header("Authorization", bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        UUID freshId = UUID.fromString(created.path("id").asText()); assertThat(freshId).isNotEqualTo(jobId);
        var fresh = claim(); assertThat(fresh.path("analysisJobId").asText()).isEqualTo(freshId.toString());
        assertThat(sql.queryForObject("select source_content_hash from analysis_jobs where id=?", String.class, freshId)).isEqualTo(sourceHash);
        assertThat(candidateIds(freshId)).isEmpty();
        assertThat(projectedCharacterSlot(freshId, characterId, "height").isMissingNode()).isTrue();
        assertThat(canonicalCounts().get("facts")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"manual", "source"})
    void staleComparisonCompletionAfterRealCurrentOrSourceMutationCannotReplayAnOldContext(String mutation) throws Exception {
        UUID characterId = emptyCharacter(); upload("하린의 키는 170이다.", "UNSPECIFIED"); var first = claim();
        UUID firstId = UUID.fromString(first.path("analysisJobId").asText()); String firstLease = first.path("leaseToken").asText();
        characterCandidate(firstId, characterId, "height", "170", SettingValueType.NUMBER); completeCharacterBatch(firstId, firstLease, "ADD", false);
        tx().executeWithoutResult(s -> jobs.findById(firstId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED)); complete(firstId, firstLease, 200);
        var book = upload("하린의 키는 168이다. 다른 곳에는 178이라고 적혔다.", "UNSPECIFIED"); var claimed = claim();
        UUID jobId = UUID.fromString(claimed.path("analysisJobId").asText()); String lease = claimed.path("leaseToken").asText();
        characterCandidate(jobId, characterId, "height", "168", SettingValueType.NUMBER); completeCharacterBatch(jobId, lease, "UPDATE", true);
        characterCandidate(jobId, characterId, "height", "178", SettingValueType.NUMBER);
        var batch = characterWorker.claimNext(jobId, UUID.fromString(lease)).orElseThrow(); var context = characterWorker.getContext(jobId, batch.comparisonBatchId(), UUID.fromString(lease));
        var decision = new LinkedHashMap<String,Object>(); decision.put("candidateRef", "C1"); decision.put("resolvedCanonicalFactKey", "height"); decision.put("operation", "REVIEW_REQUIRED");
        decision.put("targetSnapshotRef", context.snapshotEntries().getFirst().snapshotRef()); decision.put("temporalScope", "PRESENT"); decision.put("dependencyCandidateRefs", List.of()); decision.put("removedSnapshotRefs", List.of()); decision.put("comparisonReason", "linked value conflict");
        String body = json.writeValueAsString(Map.of("contextToken", context.contextToken(), "decisions", List.of(decision), "failures", List.of()));
        JsonNode journal = tx().execute(s -> jobs.findById(jobId).orElseThrow().getStateJournal().deepCopy());
        if (mutation.equals("manual")) {
            String manualBody = json.writeValueAsString(Map.of("name", "하린", "profile", List.of(Map.of("key", "height", "value", "172", "valueType", "NUMBER", "properties", List.of())), "stats", List.of(), "skills", List.of(), "items", List.of(), "statuses", List.of()));
            mvc.perform(patch("/api/v1/works/{work}/characters/{id}", work.getId(), characterId).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(manualBody)).andExpect(status().isConflict());
            // Existing service invalidation cancels the active lease; the following edit is the real public manual API.
            tx().executeWithoutResult(s -> states.invalidateFrom(jobs.findByIdForUpdate(jobId).orElseThrow(), "비교 정책 변경으로 이전 문맥을 취소합니다."));
            mvc.perform(patch("/api/v1/works/{work}/characters/{id}", work.getId(), characterId).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(manualBody)).andExpect(status().isOk());
        } else {
            mvc.perform(patch("/api/v1/works/{work}/setting-books/{id}", work.getId(), book.path("id").asText()).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("content", "하린의 키는 169이다.", "referenceTimeKind", "UNSPECIFIED", "expectedRevisionNo", 1)))).andExpect(status().isOk());
        }
        mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/character-fact-comparison-batches/{batch}/complete", jobId, batch.comparisonBatchId())
                .header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        assertThat(tx().<JsonNode>execute(s -> jobs.findById(jobId).orElseThrow().getStateJournal())).isEqualTo(journal);
        assertThat(tx().<Integer>execute(s -> accessor.read(em.find(WorkCharacter.class, characterId)).get(new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot(CharacterFactType.PROFILE, "height")).valueJson().path("value").asInt())).isEqualTo(mutation.equals("manual") ? 172 : 170);
        assertThat(sql.queryForObject("select count(*) from character_facts where extracted_by_job_id=?", Integer.class, jobId)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"PAST", "HYPOTHETICAL", "UNKNOWN"})
    void nonPresentReviewCannotAnchorAndBackholdCleanCurrent(String scope) throws Exception {
        UUID characterId = emptyCharacter(); upload("하린의 현재 키는 168이다. 다른 조건에서는 178이라고 적혔다."); var claimed = claim();
        UUID jobId = UUID.fromString(claimed.path("analysisJobId").asText()); String lease = claimed.path("leaseToken").asText();
        UUID first = characterCandidate(jobId, characterId, "height", "168", SettingValueType.NUMBER); completeCharacterBatch(jobId, lease, "ADD", false);
        characterCandidate(jobId, characterId, "height", "178", SettingValueType.NUMBER);
        var batch = characterWorker.claimNext(jobId, UUID.fromString(lease)).orElseThrow();
        var context = characterWorker.getContext(jobId, batch.comparisonBatchId(), UUID.fromString(lease));
        var decision = new LinkedHashMap<String,Object>(); decision.put("candidateRef", "C1"); decision.put("resolvedCanonicalFactKey", "height");
        decision.put("operation", "REVIEW_REQUIRED"); decision.put("temporalScope", scope); decision.put("targetSnapshotRef", context.snapshotEntries().getFirst().snapshotRef());
        decision.put("dependencyCandidateRefs", List.of()); decision.put("removedSnapshotRefs", List.of()); decision.put("comparisonReason", "different application condition");
        String endpoint = "/api/internal/v1/analysis-jobs/"+jobId+"/character-fact-comparison-batches/"+batch.comparisonBatchId()+"/complete";
        mvc.perform(post(endpoint).header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("contextToken", context.contextToken(), "decisions", List.of(decision), "failures", List.of())))).andExpect(status().isBadRequest());
        decision.remove("targetSnapshotRef");
        mvc.perform(post(endpoint).header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("contextToken", context.contextToken(), "decisions", List.of(decision), "failures", List.of())))).andExpect(status().isOk());
        assertThat(projectedCharacterSlot(jobId, characterId, "height").path("valueJson").path("value").asInt()).isEqualTo(168);
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED)); complete(jobId, lease, 200);
        assertThat(sql.queryForObject("select review_status from setting_candidates where id=?", String.class, first)).isEqualTo("CONFIRMED");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void existingWorldConflictWireMustDistinguishProvenConflictFromUncertainty(boolean provenConflict) throws Exception {
        upload("엘프의 서식지는 북부다.", "UNSPECIFIED"); var initial = claim();
        UUID initialId = UUID.fromString(initial.path("analysisJobId").asText()); String initialLease = initial.path("leaseToken").asText();
        tx().executeWithoutResult(s -> jobs.findById(initialId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED));
        worldPhase(initialId, initialLease, "북부"); complete(initialId, initialLease, 200);
        UUID worldId = sql.queryForObject("select id from world_settings where work_id=?", UUID.class, work.getId());
        upload("엘프는 동부로 이주했다. 거주지로 남부라는 내용도 있다.", "UNSPECIFIED"); var claimed = claim();
        UUID jobId = UUID.fromString(claimed.path("analysisJobId").asText()); String lease = claimed.path("leaseToken").asText(); UUID leaseId = UUID.fromString(lease);
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED));
        var published = worldWorker.publishWorldSettingCandidates(jobId, leaseId, new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest(
                List.of(worldInput("서식지", "동부"), worldInput("거주지", "남부"))));
        worldWorker.resolveWorldSettingSubjects(jobId, leaseId, new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest(
                published.stream().map(row -> new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput(row.candidateId(), List.of(worldId), List.of(), false)).toList()));
        var batch = worldWorker.claimNextWorldSettingComparisonBatch(jobId, leaseId).orElseThrow();
        var context = worldWorker.getWorldSettingComparisonBatchContext(jobId, batch.comparisonBatchId(), leaseId,
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchContextRequest(List.of(worldId), List.of()));
        var first = new LinkedHashMap<String,Object>(); first.put("decisionRef", "D1"); first.put("sourceCandidateRefs", List.of("C1")); first.put("canonicalSubjectName", "엘프");
        first.put("targetWorldSettingId", worldId); first.put("matchedPropertyName", "서식지"); first.put("consolidationStatus", "SINGLE"); first.put("suggestedOperation", "UPDATE");
        first.put("proposedSettingName", "서식지"); first.put("proposedValue", "동부"); first.put("comparisonReason", "explicit relocation");
        // Proven contradiction uses the existing CONFLICT state and a validated CURRENT anchor.
        var review = new LinkedHashMap<String,Object>(); review.put("decisionRef", "D2"); review.put("sourceCandidateRefs", List.of("C2")); review.put("canonicalSubjectName", "엘프");
        review.put("targetWorldSettingId", worldId); review.put("matchedPropertyName", "서식지"); review.put("consolidationStatus", "SINGLE"); review.put("suggestedOperation", "REVIEW_REQUIRED");
        review.put("comparisonReviewReason", "GENERAL_UNCERTAINTY"); review.put("proposedSettingName", "거주지"); review.put("proposedValue", "남부"); review.put("comparisonReason", "typed review");
        if (provenConflict) { review.put("consolidationStatus", "CONFLICT"); review.put("proposedSettingName", "서식지"); review.put("proposedValue", "북부 / 남부"); }
        if (provenConflict) {
            review.remove("comparisonReviewReason");
            mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/world-setting-comparison-batches/{batch}/complete", jobId, batch.comparisonBatchId())
                    .header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("contextToken", context.contextToken(), "contextVersions", List.of(Map.of("worldSettingId", worldId, "version", context.targets().getFirst().version())), "decisions", List.of(first, review))))).andExpect(status().isConflict());
            review.put("comparisonReviewReason", "GENERAL_UNCERTAINTY");
        }
        mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/world-setting-comparison-batches/{batch}/complete", jobId, batch.comparisonBatchId())
                .header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("contextToken", context.contextToken(), "contextVersions", List.of(Map.of("worldSettingId", worldId, "version", context.targets().getFirst().version())), "decisions", List.of(first, review))))).andExpect(status().isOk());
        assertThat(projectedWorldValue(jobId, worldId)).isEqualTo(provenConflict ? "북부" : "동부");
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED)); complete(jobId, lease, 200);
        assertThat(sql.queryForObject("select review_status from world_setting_candidates where id=?", String.class, published.getFirst().candidateId())).isEqualTo(provenConflict ? "PENDING_REVIEW" : "CONFIRMED");
        UUID uploadBatch = tx().execute(s -> jobs.findById(jobId).orElseThrow().getBatch().getId());
        JsonNode response = data(mvc.perform(get("/api/v1/works/{work}/world-setting-candidates/{id}", work.getId(), published.get(1).candidateId()).header("Authorization", bearer)
                .param("batchId", uploadBatch.toString()).param("sourceAnalysisJobId", jobId.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        System.out.println("TASK2_WORLD_PUBLIC_PAYLOAD_"+(provenConflict ? "CONFLICT" : "UNCERTAINTY")+"="+json.writeValueAsString(response));
    }

    @Test void sameBatchConflictHoldsMergeLineageAndRejectsLaterDerivativeWithIdempotentReplay() throws Exception {
        UUID characterId = emptyCharacter(); upload("하린의 키는 168이다. 같은 168을 보완했지만 178이라는 기록도 있다. 머리는 검정이다. 뒤에는 179도 적혔다.");
        var claimed = claim(); UUID jobId = UUID.fromString(claimed.path("analysisJobId").asText()); String lease = claimed.path("leaseToken").asText();
        UUID first = characterCandidate(jobId, characterId, "height", "168", SettingValueType.NUMBER);
        UUID merge = characterCandidate(jobId, characterId, "height", "168", SettingValueType.NUMBER);
        UUID conflict = characterCandidate(jobId, characterId, "height", "178", SettingValueType.NUMBER);
        UUID hair = characterCandidate(jobId, characterId, "hair", "검정", SettingValueType.STRING);
        var batch = characterWorker.claimNext(jobId, UUID.fromString(lease)).orElseThrow();
        var context = characterWorker.getContext(jobId, batch.comparisonBatchId(), UUID.fromString(lease));
        assertThat(context.candidates()).extracting(row -> row.attributeValue()).containsExactly("168", "168", "178", "검정");
        var decisions = new ArrayList<Map<String,Object>>();
        for (int index = 0; index < 4; index++) {
            var source = context.candidates().get(index); var d = new LinkedHashMap<String,Object>();
            d.put("candidateRef", source.candidateRef()); d.put("resolvedCanonicalFactKey", source.initialCanonicalFactKey());
            d.put("operation", index == 1 ? "MERGE" : index == 2 ? "REVIEW_REQUIRED" : "ADD"); d.put("removedSnapshotRefs", List.of());
            d.put("dependencyCandidateRefs", index == 1 ? List.of("C1") : index == 2 ? List.of("C1", "C2") : List.of());
            d.put("temporalScope", "PRESENT"); d.put("comparisonReason", "typed linked comparison");
            if (index == 1 || index == 2) d.put("targetSnapshotRef", "Q"+index);
            if (index != 2) { d.put("proposedFactValue", source.attributeValue()); d.put("proposedValueJson", source.valueJson()); }
            decisions.add(d);
        }
        String body = json.writeValueAsString(Map.of("contextToken", context.contextToken(), "decisions", decisions, "failures", List.of()));
        String endpoint = "/api/internal/v1/analysis-jobs/"+jobId+"/character-fact-comparison-batches/"+batch.comparisonBatchId()+"/complete";
        mvc.perform(post(endpoint).header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        JsonNode journal = tx().execute(s -> jobs.findById(jobId).orElseThrow().getStateJournal().deepCopy());
        mvc.perform(post(endpoint).header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertThat(tx().<JsonNode>execute(s -> jobs.findById(jobId).orElseThrow().getStateJournal())).isEqualTo(journal);
        assertThat(projectedCharacterSlot(jobId, characterId, "height").isMissingNode()).isTrue();
        UUID derivative = characterCandidate(jobId, characterId, "height", "179", SettingValueType.NUMBER); completeCharacterBatch(jobId, lease, "ADD", false);
        assertThat(projectedCharacterSlot(jobId, characterId, "height").isMissingNode()).isTrue();
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED));
        complete(jobId, lease, 200); complete(jobId, lease, 409);
        assertThat(sql.queryForList("select id from setting_candidates where analysis_job_id=? and review_status='PENDING_REVIEW'", UUID.class, jobId)).containsExactlyInAnyOrder(first, merge, conflict, derivative);
        assertThat(sql.queryForObject("select automatic_review_hold_reason from setting_candidates where id=?", String.class, derivative)).isEqualTo("DEPENDENCY_CONFIRMATION_REQUIRED");
        assertThat(sql.queryForObject("select review_status from setting_candidates where id=?", String.class, hair)).isEqualTo("CONFIRMED");
        assertThat(sql.queryForObject("select count(*) from character_facts where extracted_by_job_id=?", Integer.class, jobId)).isEqualTo(1);
        JsonNode mergeResponse = candidateDetail(jobId, merge);
        JsonNode detail = data(mvc.perform(get("/api/v1/works/{work}/characters/{id}", work.getId(), characterId).header("Authorization", bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(mergeResponse.path("snapshotChanges").get(0).path("beforeFactValue").asText()).isEqualTo("168");
        assertThat(mergeResponse.path("automaticReviewHoldReason").asText()).isEqualTo("REVIEW_REQUIRED");
        System.out.println("TASK2_CHARACTER_MERGE_PUBLIC_PAYLOAD="+json.writeValueAsString(Map.of("merge", mergeResponse, "currentDetail", detail)));
    }

    @Test void linkedConflictRestoresFrozenHeight170ProvenanceWithoutChangingCurrentVersion() throws Exception {
        UUID characterId = emptyCharacter(); upload("하린의 키는 170이다.", "UNSPECIFIED"); var initial = claim();
        UUID initialId = UUID.fromString(initial.path("analysisJobId").asText()); String initialLease = initial.path("leaseToken").asText();
        UUID original = characterCandidate(initialId, characterId, "height", "170", SettingValueType.NUMBER); completeCharacterBatch(initialId, initialLease, "ADD", false);
        tx().executeWithoutResult(s -> jobs.findById(initialId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED)); complete(initialId, initialLease, 200);
        long version = sql.queryForObject("select snapshot_version from characters where id=?", Long.class, characterId);
        upload("하린의 키가 168로 바뀌었다. 같은 항목에 178이라는 기록도 있다.", "UNSPECIFIED"); var current = claim();
        UUID jobId = UUID.fromString(current.path("analysisJobId").asText()); String lease = current.path("leaseToken").asText();
        JsonNode baseline = tx().execute(s -> states.getInputState(jobs.findById(jobId).orElseThrow()).path("characters").path("character:"+characterId).path("slots").path("PROFILE:height").deepCopy());
        UUID first = characterCandidate(jobId, characterId, "height", "168", SettingValueType.NUMBER); completeCharacterBatch(jobId, lease, "UPDATE", true);
        UUID conflict = characterCandidate(jobId, characterId, "height", "178", SettingValueType.NUMBER); completeCharacterBatch(jobId, lease, "REVIEW_REQUIRED", true);
        assertThat(projectedCharacterSlot(jobId, characterId, "height")).isEqualTo(baseline);
        assertThat(projectedCharacterSlot(jobId, characterId, "height").path("valueJson").path("value").asInt()).isEqualTo(170);
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED)); complete(jobId, lease, 200);
        assertThat(sql.queryForObject("select snapshot_version from characters where id=?", Long.class, characterId)).isEqualTo(version);
        assertThat(sql.queryForList("select f.setting_candidate_id from character_snapshot_sources s join character_facts f on f.id=s.source_fact_id where s.character_id=? and s.fact_key='height'", UUID.class, characterId)).containsExactly(original);
        assertThat(sql.queryForList("select id from setting_candidates where analysis_job_id=? and review_status='PENDING_REVIEW'", UUID.class, jobId)).containsExactlyInAnyOrder(first, conflict);
        assertThat(sql.queryForObject("select count(*) from character_facts where extracted_by_job_id=?", Integer.class, jobId)).isZero();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unlinkedTreatmentReviewKeepsPoisonWhileExplicitRecoveryRemovesIt(boolean recovered) throws Exception {
        UUID characterId = emptyCharacter(); upload("하린은 중독되었다.", "UNSPECIFIED"); var initial = claim();
        UUID initialId = UUID.fromString(initial.path("analysisJobId").asText()); String initialLease = initial.path("leaseToken").asText();
        var poisonValue = json.createObjectNode().put("name", "중독").put("active", true);
        characterCandidate(initialId, characterId, "status.poison", "중독", SettingValueType.JSON, CharacterFactType.STATUS, poisonValue); completeCharacterBatch(initialId, initialLease, "ADD", false);
        tx().executeWithoutResult(s -> jobs.findById(initialId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED)); complete(initialId, initialLease, 200);
        upload(recovered ? "하린의 해독이 완료되어 중독이 사라졌다." : "하린의 해독을 시도했다.", "UNSPECIFIED"); var current = claim();
        UUID jobId = UUID.fromString(current.path("analysisJobId").asText()); String lease = current.path("leaseToken").asText();
        UUID recovery = characterCandidate(jobId, characterId, "status.poison", "해독", SettingValueType.JSON, CharacterFactType.STATUS, json.createObjectNode().put("name", "해독").put("active", false));
        completeCharacterBatch(jobId, lease, recovered ? "REMOVE" : "REVIEW_REQUIRED", false);
        JsonNode projected = tx().execute(s -> states.getProjectedState(jobs.findById(jobId).orElseThrow()).path("characters").path("character:"+characterId).path("slots").path("STATUS:status.poison"));
        assertThat(projected.isMissingNode()).isEqualTo(recovered);
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED)); complete(jobId, lease, 200);
        assertThat(tx().<Boolean>execute(s -> accessor.read(em.find(WorkCharacter.class, characterId)).containsKey(new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot(CharacterFactType.STATUS, "status.poison")))).isEqualTo(!recovered);
        assertThat(sql.queryForObject("select review_status from setting_candidates where id=?", String.class, recovery)).isEqualTo(recovered ? "CONFIRMED" : "PENDING_REVIEW");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void linkedReviewRestoresHeldRecoveryRemovalAndKeepsUnrelatedSlot(boolean linked) throws Exception {
        UUID characterId = emptyCharacter(); upload("하린은 중독 상태다.", "UNSPECIFIED"); var first = claim();
        UUID firstId = UUID.fromString(first.path("analysisJobId").asText()); String firstLease = first.path("leaseToken").asText();
        characterCandidate(firstId, characterId, "status.poison", "중독", SettingValueType.JSON, CharacterFactType.STATUS, json.createObjectNode().put("name", "중독").put("active", true));
        completeCharacterBatch(firstId, firstLease, "ADD", false);
        tx().executeWithoutResult(s -> jobs.findById(firstId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED)); complete(firstId, firstLease, 200);
        upload("하린은 회복하여 중독이 사라졌다. 다른 곳에는 미회복이라고 적혔다. 머리는 검정이다.", "UNSPECIFIED"); var claimed = claim();
        UUID jobId = UUID.fromString(claimed.path("analysisJobId").asText()); String lease = claimed.path("leaseToken").asText(); String ref = "character:"+characterId;
        JsonNode baseline = tx().execute(s -> states.getInputState(jobs.findById(jobId).orElseThrow()).path("characters").path(ref).deepCopy());
        UUID recovery = characterCandidate(jobId, characterId, "status.recovered", "회복", SettingValueType.JSON, CharacterFactType.STATUS, json.createObjectNode().put("name", "회복").put("active", true));
        completeCharacterBatch(jobId, lease, "ADD", false, List.of("status.poison"));
        JsonNode removed = tx().execute(s -> states.getProjectedState(jobs.findById(jobId).orElseThrow()).path("characters").path(ref).deepCopy());
        assertThat(removed.path("slots").path("STATUS:status.poison").isMissingNode()).isTrue();
        JsonNode oldJournal = tx().execute(s -> jobs.findById(jobId).orElseThrow().getStateJournal().deepCopy());
        UUID conflict = characterCandidate(jobId, characterId, "status.recovered", "미회복", SettingValueType.JSON, CharacterFactType.STATUS, json.createObjectNode().put("name", "미회복").put("active", true));
        String body = completeCharacterBatch(jobId, lease, "REVIEW_REQUIRED", linked, List.of());
        UUID batchId = tx().execute(s -> em.find(SettingCandidate.class, conflict).getCharacterComparisonBatch().getId());
        JsonNode journal = tx().execute(s -> jobs.findById(jobId).orElseThrow().getStateJournal().deepCopy());
        for (int index = 0; index < oldJournal.path("changes").size(); index++) assertThat(journal.path("changes").get(index)).isEqualTo(oldJournal.path("changes").get(index));
        mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/character-fact-comparison-batches/{batch}/complete", jobId, batchId)
                .header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertThat(tx().<JsonNode>execute(s -> jobs.findById(jobId).orElseThrow().getStateJournal())).isEqualTo(journal);
        JsonNode projected = tx().execute(s -> states.getProjectedState(jobs.findById(jobId).orElseThrow()).path("characters").path(ref).deepCopy());
        UUID hair = characterCandidate(jobId, characterId, "hair", "검정", SettingValueType.STRING); completeCharacterBatch(jobId, lease, "ADD", false);
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED)); complete(jobId, lease, 200);
        var actual = tx().execute(s -> accessor.read(em.find(WorkCharacter.class, characterId)));
        System.out.println("TASK2_RECOVERY_RESTORE="+json.writeValueAsString(Map.of("linked", linked, "baseline", baseline, "projected", projected, "actualPoison", actual.containsKey(new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot(CharacterFactType.STATUS, "status.poison")))));
        assertSoftly(soft -> {
            soft.assertThat(projected.path("slots").path("STATUS:status.poison").isMissingNode()).isEqualTo(!linked);
            soft.assertThat(projected.path("slots").path("STATUS:status.recovered").isMissingNode()).isEqualTo(linked);
            if (linked) {
                soft.assertThat(projected.path("slots").path("STATUS:status.poison")).isEqualTo(baseline.path("slots").path("STATUS:status.poison"));
                soft.assertThat(projected.path("absences").path("STATUS:status.poison")).isEqualTo(baseline.path("absences").path("STATUS:status.poison"));
            }
            soft.assertThat(actual.containsKey(new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot(CharacterFactType.STATUS, "status.poison"))).isEqualTo(linked);
            soft.assertThat(sql.queryForObject("select review_status from setting_candidates where id=?", String.class, recovery)).isEqualTo(linked ? "PENDING_REVIEW" : "CONFIRMED");
            soft.assertThat(sql.queryForObject("select review_status from setting_candidates where id=?", String.class, hair)).isEqualTo("CONFIRMED");
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void linkedWorldReviewRestoresHeldRootMoveAndKeepsUnrelatedProperty(boolean linked) throws Exception {
        worldMoveReview(linked, false);
    }
    @Test void linkedWorldReviewAlsoHoldsAnUpdateDerivedFromTheHeldMove() throws Exception {
        worldMoveReview(true, true);
    }
    private void worldMoveReview(boolean linked, boolean derived) throws Exception {
        upload("엘프의 서식지는 북부다."); var first = claim(); UUID firstId = UUID.fromString(first.path("analysisJobId").asText()); String firstLease = first.path("leaseToken").asText();
        tx().executeWithoutResult(s -> jobs.findById(firstId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED)); worldPhase(firstId, firstLease, "북부"); complete(firstId, firstLease, 200);
        UUID worldId = sql.queryForObject("select id from world_settings where work_id=?", UUID.class, work.getId()); String targetRef = "world:"+worldId;
        upload("엘프의 거주집단은 장수하지만 단명이라는 기록도 있다. 특징은 정밀함이다. 이후 서식지는 동부로 바뀌었다.", "EPISODE", 3); var claimed = claim();
        UUID jobId = UUID.fromString(claimed.path("analysisJobId").asText()); String lease = claimed.path("leaseToken").asText(); UUID leaseId = UUID.fromString(lease);
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED));
        JsonNode baseline = tx().execute(s -> states.getInputState(jobs.findById(jobId).orElseThrow()).path("worldSettings").path(targetRef).deepCopy());
        var inputs = new ArrayList<org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.Candidate>();
        inputs.add(worldInput("수명", "장수")); inputs.add(worldInput("특징", "정밀함"));
        if (derived) inputs.add(worldInput("거주집단", "서식지", "동부"));
        inputs.add(worldInput("거주집단", "수명", "단명"));
        var published = worldWorker.publishWorldSettingCandidates(jobId, leaseId, new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest(inputs));
        worldWorker.resolveWorldSettingSubjects(jobId, leaseId, new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest(published.stream().map(row ->
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput(row.candidateId(), List.of(worldId))).toList()));
        JsonNode journal = null;
        for (int phase = 0; phase < 2; phase++) {
            var batch = worldWorker.claimNextWorldSettingComparisonBatch(jobId, leaseId).orElseThrow();
            var context = worldWorker.getWorldSettingComparisonBatchContext(jobId, batch.comparisonBatchId(), leaseId,
                    new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchContextRequest(List.of(worldId), List.of()));
            var decisions = new ArrayList<Map<String,Object>>();
            for (var source : context.candidates()) {
                int index = 0; while (!published.get(index).candidateId().equals(source.candidateId())) index++;
                boolean conflictDecision = index == published.size()-1, movedDerivative = derived && index == 2;
                var decision = new LinkedHashMap<String,Object>(); decision.put("decisionRef", "D"+(decisions.size()+1)); decision.put("sourceCandidateRefs", List.of(source.candidateRef()));
                decision.put("canonicalSubjectName", "엘프"); decision.put("targetWorldSettingId", worldId); decision.put("consolidationStatus", conflictDecision && linked ? "CONFLICT" : "SINGLE");
                decision.put("suggestedOperation", conflictDecision ? "REVIEW_REQUIRED" : movedDerivative ? "UPDATE" : "ADD"); decision.put("proposedSettingName", index == 1 ? "특징" : movedDerivative ? "서식지" : "수명");
                decision.put("proposedValue", index == 0 ? "장수" : index == 1 ? "정밀함" : movedDerivative ? "동부" : linked ? "장수 / 단명" : "단명"); decision.put("comparisonReason", "typed move and review fixture");
                if (index != 1) decision.put("proposedScopeName", "거주집단");
                if (index == 0) decision.put("existingRootPropertyNamesToMove", List.of("서식지"));
                if (conflictDecision) { decision.put("comparisonReviewReason", "GENERAL_UNCERTAINTY"); decision.put("matchedScopeName", "거주집단"); decision.put("matchedPropertyName", "수명"); }
                if (movedDerivative) { decision.put("matchedScopeName", "거주집단"); decision.put("matchedPropertyName", "서식지"); }
                decisions.add(decision);
            }
            String body = json.writeValueAsString(Map.of("contextToken", context.contextToken(), "contextVersions", List.of(Map.of("worldSettingId", worldId, "version", context.targets().getFirst().version())), "decisions", decisions));
            String endpoint = "/api/internal/v1/analysis-jobs/"+jobId+"/world-setting-comparison-batches/"+batch.comparisonBatchId()+"/complete";
            mvc.perform(post(endpoint).header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
            if (phase == 0) assertThat(projectedWorldProperties(jobId).path("거주집단").path("서식지").asText()).isEqualTo("북부");
            if (phase == 1) {
                JsonNode replay = tx().execute(s -> jobs.findById(jobId).orElseThrow().getStateJournal().deepCopy());
                for (int old = 0; old < journal.path("changes").size(); old++) assertThat(replay.path("changes").get(old)).isEqualTo(journal.path("changes").get(old));
                mvc.perform(post(endpoint).header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
                assertThat(tx().<JsonNode>execute(s -> jobs.findById(jobId).orElseThrow().getStateJournal())).isEqualTo(replay);
            }
            journal = tx().execute(s -> jobs.findById(jobId).orElseThrow().getStateJournal().deepCopy());
        }
        JsonNode projected = tx().execute(s -> states.getProjectedState(jobs.findById(jobId).orElseThrow()).path("worldSettings").path(targetRef).deepCopy());
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED)); complete(jobId, lease, 200);
        JsonNode actual = tx().execute(s -> em.find(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.class, worldId).getPropertiesJson().deepCopy());
        System.out.println("TASK2_WORLD_MOVE_RESTORE="+json.writeValueAsString(Map.of("linked", linked, "derived", derived, "baseline", baseline, "projected", projected, "actual", actual)));
        assertSoftly(soft -> {
            soft.assertThat(projected.path("propertiesJson").path("서식지").asText(null)).isEqualTo(linked ? "북부" : null);
            soft.assertThat(projected.path("propertiesJson").path("거주집단").path("서식지").asText(null)).isEqualTo(linked ? null : "북부");
            soft.assertThat(projected.path("propertiesJson").path("거주집단").path("수명").asText(null)).isEqualTo(linked ? null : "장수");
            soft.assertThat(projected.path("propertiesJson")).isEqualTo(actual);
            if (linked) soft.assertThat(projected.path("provenanceByPath").path(org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingAnalysisStateMapper.pathKey(null, "서식지"))).isEqualTo(baseline.path("provenanceByPath").path(org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingAnalysisStateMapper.pathKey(null, "서식지")));
            soft.assertThat(actual.path("특징").asText()).isEqualTo("정밀함");
            soft.assertThat(sql.queryForObject("select review_status from world_setting_candidates where id=?", String.class, published.get(0).candidateId())).isEqualTo(linked ? "PENDING_REVIEW" : "CONFIRMED");
            soft.assertThat(sql.queryForObject("select review_status from world_setting_candidates where id=?", String.class, published.get(1).candidateId())).isEqualTo("CONFIRMED");
            if (derived) soft.assertThat(sql.queryForObject("select automatic_review_hold_reason from world_setting_candidates where id=?", String.class, published.get(2).candidateId())).isEqualTo("DEPENDENCY_CONFIRMATION_REQUIRED");
        });
    }

    private UUID emptyCharacter() {
        return tx().execute(s -> {
            var character = WorkCharacter.create(em.find(Work.class, work.getId()), "하린", null, null, null, null, null, null, null, null, null);
            em.persist(character); em.flush(); return character.getId();
        });
    }

    // Publication seed only; decisions below pass the real context/HTTP completion validator.
    private UUID characterCandidate(UUID jobId, UUID characterId, String key, String value, SettingValueType type) {
        return characterCandidate(jobId, characterId, key, value, type, CharacterFactType.PROFILE, null);
    }
    private UUID characterCandidate(UUID jobId, UUID characterId, String key, String value, SettingValueType type, CharacterFactType factType, JsonNode valueJson) {
        return tx().execute(s -> {
            var job = jobs.findById(jobId).orElseThrow(); var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
            if (sql.queryForObject("select count(*) from character_setting_schemas where work_id=? and schema_key=?", Integer.class, work.getId(), key) == 0)
                em.persist(CharacterSettingSchema.create(job.getWork(), key, null, key, factType, type,
                        CharacterSettingValueSemantics.BASE_VALUE, CharacterSettingMergePolicy.REPLACE, j.arrayNode(), CharacterSettingSchemaSource.SYSTEM_SEED, true));
            var typed = j.objectNode(); if (type == SettingValueType.NUMBER) typed.put("value", Integer.parseInt(value)); else typed.put("value", value);
            var candidate = SettingCandidate.create(job.getWork(), null, null, job, SettingEntityType.CHARACTER, "하린", "하린", characterId,
                    SettingCandidateMatchStatus.MATCHED, key, value, type, valueJson == null ? typed : valueJson,
                    j.arrayNode().add(j.objectNode().put("quote", value).putNull("startOffset").putNull("endOffset")), java.math.BigDecimal.ONE, null);
            em.persist(candidate); job.updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_CANDIDATES_SAVED); em.flush(); return candidate.getId();
        });
    }

    private void completeCharacterBatch(UUID jobId, String lease, String operation, boolean comparedIncompatibleItem) throws Exception {
        completeCharacterBatch(jobId, lease, operation, comparedIncompatibleItem, List.of());
    }
    private String completeCharacterBatch(UUID jobId, String lease, String operation, boolean comparedIncompatibleItem, List<String> removedKeys) throws Exception {
        UUID leaseId = UUID.fromString(lease); var batch = characterWorker.claimNext(jobId, leaseId).orElseThrow();
        var context = characterWorker.getContext(jobId, batch.comparisonBatchId(), leaseId);
        assertThat(context.candidates()).hasSize(1); var candidate = context.candidates().getFirst();
        var decision = new LinkedHashMap<String,Object>(); decision.put("candidateRef", candidate.candidateRef()); decision.put("operation", operation);
        decision.put("resolvedCanonicalFactKey", candidate.initialCanonicalFactKey()); decision.put("removedSnapshotRefs", context.snapshotEntries().stream().filter(entry -> removedKeys.contains(entry.factKey()) || operation.equals("REMOVE") && entry.factKey().equals(candidate.initialCanonicalFactKey())).map(entry -> entry.snapshotRef()).toList()); decision.put("dependencyCandidateRefs", List.of());
        decision.put("temporalScope", "PRESENT"); decision.put("comparisonReason", "deterministic boundary fixture");
        if (comparedIncompatibleItem) decision.put("targetSnapshotRef", context.snapshotEntries().stream().filter(entry -> entry.factKey().equals(candidate.initialCanonicalFactKey())).findFirst().orElseThrow().snapshotRef());
        if (!operation.equals("REVIEW_REQUIRED") && !operation.equals("REMOVE")) { decision.put("proposedFactValue", candidate.attributeValue()); decision.put("proposedValueJson", candidate.valueJson()); }
        String body = json.writeValueAsString(Map.of("contextToken", context.contextToken(), "decisions", List.of(decision), "failures", List.of()));
        mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/character-fact-comparison-batches/{batch}/complete", jobId, batch.comparisonBatchId())
                .header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk());
        return body;
    }

    private JsonNode candidateDetail(UUID jobId, UUID candidateId) throws Exception {
        UUID batchId = tx().execute(s -> jobs.findById(jobId).orElseThrow().getBatch().getId());
        return data(mvc.perform(get("/api/v1/works/{work}/setting-candidates/{id}", work.getId(), candidateId).header("Authorization", bearer)
                .param("batchId", batchId.toString()).param("sourceAnalysisJobId", jobId.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode projectedCharacterSlot(UUID jobId, UUID characterId, String key) {
        return tx().execute(s -> states.getProjectedState(jobs.findById(jobId).orElseThrow()).path("characters").path("character:"+characterId).path("slots").path("PROFILE:"+key));
    }
    private String projectedWorldValue(UUID jobId, UUID worldId) {
        return tx().execute(s -> new org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingPropertyView(
                states.getProjectedState(jobs.findById(jobId).orElseThrow()).path("worldSettings").path("world:"+worldId).path("propertiesJson")).value(null, "서식지"));
    }
    private JsonNode projectedWorldProperties(UUID jobId) {
        return tx().execute(s -> states.getProjectedState(jobs.findById(jobId).orElseThrow()).path("worldSettings").elements().next().path("propertiesJson"));
    }

    private void race(ThrowingAction mutation, ThrowingAction waitingRequest) throws Exception {
        race(mutation, waitingRequest, false);
    }
    private void race(ThrowingAction mutation, ThrowingAction waitingRequest, boolean expectedMutationRollback) throws Exception {
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var waiting = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> tx().execute(s -> {
                works.getOwnedWorkForUpdate(work.getId(), member.getId()); locked.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test lock release timeout"); mutation.run(); }
                catch (Exception error) { throw new IllegalStateException(error); }
                if (expectedMutationRollback) s.setRollbackOnly();
                return true;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var reader = executor.submit(() -> { waiting.countDown(); waitingRequest.run(); return true; });
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
            try { assertThatThrownBy(() -> reader.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            assertThat(writer.get(10, TimeUnit.SECONDS)).isTrue(); assertThat(reader.get(10, TimeUnit.SECONDS)).isTrue();
        }
    }
    private String failAndResume(JsonNode book, JsonNode previous, String checkpoint) throws Exception {
        UUID id = UUID.fromString(previous.path("analysisJobId").asText()); String oldLease = previous.path("leaseToken").asText(); var ids = candidateIds(id);
        mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/fail", id).header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", oldLease)
                .contentType(MediaType.APPLICATION_JSON).content("{\"errorMessage\":\"synthetic acknowledgement lost after commit\"}")).andExpect(status().isOk());
        JsonNode same = data(mvc.perform(post("/api/v1/works/{work}/setting-books/{id}/analysis-jobs", work.getId(), book.path("id").asText()).header("Authorization", bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(same.path("id").asText()).isEqualTo(id.toString());
        JsonNode resumed = claim(); assertThat(resumed.path("checkpointStage").asText()).isEqualTo(checkpoint);
        assertThat(resumed.path("analysisJobId").asText()).isEqualTo(id.toString()); assertThat(resumed.path("leaseToken").asText()).isNotEqualTo(oldLease);
        assertThat(candidateIds(id)).isEqualTo(ids);
        mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/heartbeat", id).header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", oldLease)).andExpect(status().isConflict());
        return resumed.path("leaseToken").asText();
    }

    private UUID characterPhase(UUID jobId, int age) {
        return characterPhase(jobId, age, false);
    }
    private UUID characterPhase(UUID jobId, int age, boolean duplicate) {
        return tx().execute(s -> {
            var job = jobs.findById(jobId).orElseThrow(); var currentWork = job.getWork(); var j = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
            if (sql.queryForObject("select count(*) from character_setting_schemas where schema_key='age'", Integer.class) == 0)
                em.persist(CharacterSettingSchema.create(currentWork, "age", null, "나이", CharacterFactType.AGE, SettingValueType.NUMBER,
                        CharacterSettingValueSemantics.BASE_VALUE, CharacterSettingMergePolicy.REPLACE, j.arrayNode(), CharacterSettingSchemaSource.SYSTEM_SEED, true));
            var actualIds = sql.queryForList("select id from characters where work_id=?", UUID.class, work.getId());
            WorkCharacter actual = actualIds.isEmpty() ? null : em.find(WorkCharacter.class, actualIds.getFirst());
            String subject = null;
            if (actual == null) {
                var discovery = SettingCandidate.createCharacterDiscovery(currentWork, null, null, job, "하린", "하린", null, SettingCandidateMatchStatus.UNRESOLVED,
                        j.arrayNode().add(j.objectNode().put("quote", "하린").put("startOffset", 0).put("endOffset", 2)), java.math.BigDecimal.ONE, null);
                em.persist(discovery); em.flush(); subject = "provisional-character:"+discovery.getId();
                org.springframework.test.util.ReflectionTestUtils.setField(discovery, "provisionalSubjectKey", subject);
            }
            var row = SettingCandidate.create(currentWork, null, null, job, SettingEntityType.CHARACTER, "하린", "하린", actual == null ? null : actual.getId(),
                    actual == null ? SettingCandidateMatchStatus.UNRESOLVED : SettingCandidateMatchStatus.MATCHED, "age", Integer.toString(age), SettingValueType.NUMBER,
                    j.objectNode().put("value", age), j.arrayNode().add(j.objectNode().put("quote", "하린은 "+age+"살이다.").putNull("startOffset").putNull("endOffset")), java.math.BigDecimal.ONE, null);
            if (subject != null) org.springframework.test.util.ReflectionTestUtils.setField(row, "provisionalSubjectKey", subject);
            em.persist(row); em.flush(); characterStates.prepareProvisionalCandidates(job);
            var batch = actual == null ? CharacterFactComparisonBatch.createProvisional(currentWork, null, job, subject, CharacterFactType.AGE, 1)
                    : CharacterFactComparisonBatch.create(currentWork, null, job, actual, CharacterFactType.AGE, 1, actual.getSnapshotVersion());
            var context = j.objectNode(); var slots = context.putArray("slots");
            if (actual != null) accessor.read(actual).values().forEach(entry -> slots.add(j.objectNode().put("factType", entry.slot().factType().name()).put("factKey", entry.slot().factKey()).put("factValue", entry.factValue()).set("valueJson", entry.valueJson())));
            batch.recordAnalysisContext(context); em.persist(batch); row.startComparison(batch, "C1"); row.recordComparisonContext(actual == null ? 0 : actual.getSnapshotVersion(), job.getInputStateHash());
            row.completeComparison(duplicate ? CharacterFactOperation.EXCLUDE : actual == null ? CharacterFactOperation.ADD : CharacterFactOperation.UPDATE, CharacterFactType.AGE, "age", Integer.toString(age), j.objectNode().put("value", age),
                    j.arrayNode(), CharacterFactTemporalScope.PRESENT, "synthetic forced comparison", j.objectNode(), java.time.LocalDateTime.now(), "age", j.arrayNode());
            batch.complete("b".repeat(64), j.objectNode());
            characterStates.recordDecision(job, row, new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry(
                    new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot(CharacterFactType.AGE, "age"), Integer.toString(age), row.getValueJson(), true), List.of(), List.of());
            job.updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_COMPARISONS_FINISHED); em.flush(); return row.getId();
        });
    }
    private UUID worldPhase(UUID jobId, String leaseText, String value) throws Exception {
        return worldPhase(jobId, leaseText, value, false);
    }
    private UUID worldPhase(UUID jobId, String leaseText, String value, boolean duplicate) throws Exception {
        return worldPhase(jobId, leaseText, value, duplicate, null);
    }
    private UUID worldPhase(UUID jobId, String leaseText, String value, boolean duplicate, String operation) throws Exception {
        return worldPhase(jobId, leaseText, value, duplicate, operation, "서식지", "서식지", "SINGLE", value);
    }
    private UUID worldPhase(UUID jobId, String leaseText, String value, boolean duplicate, String operation,
            String rawName, String canonicalName, String consolidation, String proposedValue) throws Exception {
        UUID lease = UUID.fromString(leaseText);
        var published = worldWorker.publishWorldSettingCandidates(jobId, lease, new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest(List.of(
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.Candidate(
                        org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE, "엘프", null, rawName, value,
                        List.of(new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.EvidenceSpan(value, null, null)),
                        java.math.BigDecimal.ONE, Map.of("epistemic_status", "ASSERTED"), CharacterFactTemporalScope.PRESENT, null))));
        UUID id = published.getFirst().candidateId(); var actualIds = sql.queryForList("select id from world_settings where work_id=?", UUID.class, work.getId());
        List<String> provisional = actualIds.isEmpty() ? tx().execute(s -> {
            var refs = new ArrayList<String>(); states.getProjectedState(jobs.findById(jobId).orElseThrow()).path("worldSettings").fieldNames().forEachRemaining(refs::add); return refs;
        }) : List.of();
        worldWorker.resolveWorldSettingSubjects(jobId, lease, new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest(List.of(
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput(id, actualIds, provisional, false))));
        var batch = worldWorker.claimNextWorldSettingComparisonBatch(jobId, lease).orElseThrow();
        var context = worldWorker.getWorldSettingComparisonBatchContext(jobId, batch.comparisonBatchId(), lease,
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchContextRequest(batch.resolvedTargetWorldSettingIds(), batch.resolvedProvisionalSubjectKeys()));
        var decision = new LinkedHashMap<String,Object>(); decision.put("decisionRef", "D1"); decision.put("sourceCandidateRefs", List.of("C1"));
        decision.put("canonicalSubjectName", "엘프"); decision.put("consolidationStatus", consolidation); decision.put("suggestedOperation", operation != null ? operation : duplicate ? "EXCLUDE" : actualIds.isEmpty() ? "ADD" : "UPDATE");
        assertThat(context.targets()).hasSize(1);
        if (!actualIds.isEmpty()) { decision.put("targetWorldSettingId", context.targets().getFirst().worldSettingId()); decision.put("matchedPropertyName", canonicalName); }
        else decision.put("provisionalSubjectKey", context.targets().getFirst().provisionalSubjectKey());
        if ("UPDATE".equals(operation) || "MERGE".equals(operation)) decision.put("matchedPropertyName", canonicalName);
        decision.put("proposedSettingName", canonicalName); decision.put("proposedValue", proposedValue); decision.put("comparisonReason", "synthetic forced comparison");
        var versions = new ArrayList<Map<String,Object>>();
        for (var target : context.targets()) {
            var version = new LinkedHashMap<String,Object>(); version.put("version", target.version());
            if (target.worldSettingId() != null) version.put("worldSettingId", target.worldSettingId()); else version.put("provisionalSubjectKey", target.provisionalSubjectKey());
            versions.add(version);
        }
        assertThat(sql.queryForObject("select status from analysis_jobs where id=?", String.class, jobId)).isEqualTo("RUNNING");
        assertThat(sql.queryForObject("select lease_token from analysis_jobs where id=?", UUID.class, jobId).equals(lease)).isTrue();
        var completed = mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/world-setting-comparison-batches/{batch}/complete", jobId, batch.comparisonBatchId())
                .header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", leaseText)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("contextToken", context.contextToken(), "contextVersions", versions, "decisions", List.of(decision))))).andReturn();
        if (completed.getResponse().getStatus() != 200) throw new AssertionError("Worker comparison completion rejected", completed.getResolvedException());
        assertThat(worldWorker.claimNextWorldSettingComparisonBatch(jobId, lease)).isEmpty();
        tx().executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED)); return id;
    }
    private org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.Candidate worldInput(String name, String value) {
        return worldInput(null, name, value);
    }
    private org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.Candidate worldInput(String scope, String name, String value) {
        return new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.Candidate(
                org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.RACE, "엘프", scope, name, value,
                List.of(new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest.EvidenceSpan(value, null, null)),
                java.math.BigDecimal.ONE, Map.of("epistemic_status", "ASSERTED"), CharacterFactTemporalScope.PRESENT, null);
    }
    private JsonNode upload(String content) throws Exception {
        return upload(content, "WORK_START");
    }
    private JsonNode upload(String content, String referenceKind) throws Exception {
        return upload(content, referenceKind, null);
    }
    private JsonNode upload(String content, String referenceKind, Integer referenceEpisode) throws Exception {
        var request = multipart("/api/v1/works/{work}/setting-books", work.getId()).file(new MockMultipartFile("file", "원문.txt", MediaType.TEXT_PLAIN_VALUE, content.getBytes(StandardCharsets.UTF_8)))
                .param("referenceTimeKind", referenceKind).header("Authorization", bearer);
        if (referenceEpisode != null) request.param("referenceEpisodeNo", referenceEpisode.toString());
        return data(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode claim() throws Exception {
        // Isolated lease scaffolding: current Python cannot claim the unfinished C02 contract.
        // Keep source/input preparation real without advertising a capability the fixture does not consume.
        return tx().execute(s -> {
            UUID jobId = sql.queryForObject("select id from analysis_jobs where work_id=? and status='PENDING' order by created_at desc limit 1", UUID.class, work.getId());
            var job = jobs.findByIdForUpdate(jobId).orElseThrow(); assertThat(states.prepareInput(job)).isTrue();
            UUID lease = job.claim("deterministic-fixture", "validated comparison", java.time.LocalDateTime.now().plusMinutes(5));
            return json.createObjectNode().put("analysisJobId", jobId.toString()).put("leaseToken", lease.toString())
                    .put("checkpointStage", job.getCheckpointStage() == null ? null : job.getCheckpointStage().name());
        });
    }
    private void complete(UUID id, String lease, int statusCode) throws Exception {
        mvc.perform(post("/api/internal/v1/analysis-jobs/{id}/complete", id).header("X-Internal-Api-Key", "test-internal-api-key").header("X-Worker-Lease-Token", lease)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().is(statusCode));
    }
    private Set<UUID> candidateIds(UUID job) {
        var ids = new LinkedHashSet<>(sql.queryForList("select id from setting_candidates where analysis_job_id=? order by id", UUID.class, job));
        ids.addAll(sql.queryForList("select id from world_setting_candidates where analysis_job_id=? order by id", UUID.class, job)); return ids;
    }
    private Map<String,Integer> canonicalCounts() {
        return Map.of("characters", sql.queryForObject("select count(*) from characters where work_id=?", Integer.class, work.getId()),
                "facts", sql.queryForObject("select count(*) from character_facts f join characters c on c.id=f.character_id where c.work_id=?", Integer.class, work.getId()),
                "worlds", sql.queryForObject("select count(*) from world_settings where work_id=?", Integer.class, work.getId()));
    }
    private JsonNode data(String body) throws Exception { return json.readTree(body).path("data"); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    @FunctionalInterface private interface ThrowingAction { void run() throws Exception; }
}
