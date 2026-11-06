package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.analysis.service.*;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.service.SettingCandidatePromotionService;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.type.*;
import org.monitoring.catchholebackend.global.exception.AppException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.service.EpisodeService;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeUploadType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.monitoring.catchholebackend.global.storage.StoredObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"spring.config.import=", "spring.jpa.properties.hibernate.generate_statistics=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("오류 검사 우선 접수와 설정 추출 차단")
class ErrorFirstValidationIntegrationTest {
    @Autowired EntityManager em;
    @Autowired EpisodeService episodes;
    @Autowired org.monitoring.catchholebackend.global.storage.ObjectStorageService storedText;
    @Autowired JwtTokenProvider tokens;
    @Autowired MockMvc mvc;
    @Autowired AnalysisRunStateService states;
    @Autowired AnalysisJobWorkerService worker;
    @Autowired SettingCandidatePromotionService promotions;
    @Autowired List<AnalysisAutomaticApplicationContributor> contributors;
    @Autowired org.monitoring.catchholebackend.domain.validation.service.ValidationBaselineService baselines;
    @Autowired org.monitoring.catchholebackend.domain.work.repository.WorkPurgeDataRepository workPurge;
    @Autowired org.monitoring.catchholebackend.domain.validation.processor.ValidationLiveSettings liveSettings;
    @Autowired org.monitoring.catchholebackend.domain.validation.processor.ValidationSourceManifests sourceManifests;
    @MockitoBean ObjectStorage storage;
    final ObjectMapper json = new ObjectMapper();
    Member member;
    Work work;
    Episode target;
    String token;
    AnalysisJob job;
    SettingCandidate character;
    SettingCandidate fact;
    WorldSettingCandidate world;
    ValidationReport report;
    UUID lease;
    WorldSetting established;
    UUID batchId;

    void prepare() { prepare(true, 101); }

    void prepare(boolean prior, int targetNo) {
        em.createNativeQuery("create table if not exists episode_chunks (id uuid primary key, episode_id uuid)").executeUpdate();
        member = Member.register("validation@example.invalid", "test-only", "01012345678", "검수 작가");
        em.persist(member);
        work = Work.create(member, "검수 테스트", WorkGenre.FANTASY, "합성 자료");
        em.persist(work);
        if (prior) {
        em.persist(Episode.create(work, null, 70, "수리", "synthetic/70.txt", "v1", "7".repeat(64), 12));
        em.persist(Episode.create(work, null, 100, "이전", "synthetic/100.txt", "v1", "a".repeat(64), 12));
        work.updateLatestEpisodeNo(100);
        }
        when(storage.putBytes(anyString(), any(byte[].class), anyString()))
                .thenAnswer(call -> new StoredObject(call.getArgument(0), "synthetic-v1"));
        when(storage.putText(anyString(), anyString()))
                .thenAnswer(call -> new StoredObject(call.getArgument(0), "synthetic-v1"));
        var uploaded = episodes.uploadEpisodes(member.getId(), work.getId(),
                new EpisodeUploadRequest(EpisodeUploadType.SINGLE_EPISODE, targetNo, "신규", null),
                java.util.List.of(new MockMultipartFile("episodeFiles", "101.txt", "text/plain", "합성 신규 원고".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        batchId = uploaded.batchId();
        target = em.createQuery("select e from Episode e where e.work = :work and e.episodeNo = :no", Episode.class)
                .setParameter("work", work).setParameter("no", targetNo).getSingleResult();
        when(storage.purgePrefixes(anyCollection())).thenReturn(new org.monitoring.catchholebackend.global.storage.ObjectStoragePurgeResult(1, 1, 0));
        when(storage.purgePrefixesExcluding(anyCollection(), anyCollection())).thenReturn(new org.monitoring.catchholebackend.global.storage.ObjectStoragePurgeResult(1, 1, 0));
        token = tokens.generateAccessToken(member);
        em.flush();
    }

    Map<String, Object> request(String key) {
        return Map.of("idempotencyKey", key, "batchId", batchId, "episodeId", target.getId(),
                "expectedContentHash", target.getContentHash(), "expectedContentS3Key", target.getContentS3Key(),
                "expectedContentS3Version", target.getContentS3Version());
    }

    JsonNode start(String key) throws Exception {
        var result = mvc.perform(post(base()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request(key))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    String base() { return "/api/v1/works/" + work.getId() + "/episode-validations"; }

    @Test
    @DisplayName("검수 접수는 설정 추출 Job이나 후보를 한 건도 만들지 않는다")
    void admissionStoresBaselineButDoesNotCreateExtractionJobOrCandidates() throws Exception {
        prepare();
        var before = em.createQuery("select count(j) from AnalysisJob j", Long.class).getSingleResult();
        var receipt = start("error-first");
        assertThat(em.createQuery("select count(j) from AnalysisJob j", Long.class).getSingleResult()).isEqualTo(before);
        assertThat(em.createQuery("select count(c) from SettingCandidate c", Long.class).getSingleResult()).isZero();
        assertThat(em.createQuery("select count(c) from WorldSettingCandidate c", Long.class).getSingleResult()).isZero();
        assertThat(em.createQuery("select count(c) from CharacterFact c", Long.class).getSingleResult()).isZero();
        assertThat(receipt.path("extractionJobId").isNull()).isTrue();
        assertThat(receipt.path("candidateGeneration").isNull()).isTrue();
        assertThat(receipt.path("candidateDigest").isNull()).isTrue();
        assertThat(receipt.path("extractionStatus").asText()).isEqualTo("NOT_STARTED");
        assertThat(receipt.path("candidateStatus").asText()).isEqualTo("NOT_STARTED");
        assertThat(receipt.path("baselineStatus").asText()).isEqualTo("READY");
        assertThat(receipt.path("status").asText()).isEqualTo("WAITING_FOR_ERROR_REVIEW");
    }

    @Test
    @DisplayName("검수 접수는 확정 설정을 복사하지 않고 세 기준 hash에 같은 설정 버전 fingerprint를 둔다")
    void admissionIsReadyWithoutSeedAndThreeHashesAreTheSettingsFingerprint() throws Exception {
        prepare(); em.persist(WorldSetting.create(work, WorldSettingCategory.RACE, "인간", "서식지", "북부")); em.flush();
        var receipt = start("live-settings");
        var r = stored(receipt);
        assertThat(r.getBaselineStatus().name()).isEqualTo("READY"); assertThat(r.getStatus().name()).isEqualTo("WAITING_FOR_ERROR_REVIEW"); assertThat(r.getHoldReason()).isNull();
        assertThat(r.getBaselineSeed()).isNull(); assertThat(r.getBaselineEntries()).isNull();
        String fingerprint = liveSettings.fingerprint(work.getId());
        assertThat(r.getBaselineHash()).isEqualTo(fingerprint); assertThat(r.getBaselineFingerprint()).isEqualTo(fingerprint); assertThat(r.getConfirmedStateHash()).isEqualTo(fingerprint);
        assertThat(receipt.path("baselineHash").asText()).isEqualTo(fingerprint);
        assertThat(receipt.path("baselineFingerprint").asText()).isEqualTo(fingerprint);
        assertThat(receipt.path("confirmedStateHash").asText()).isEqualTo(fingerprint);
        assertThat(sourceManifests.manifests(r.getId()).findValuesAsText("episodeNo")).containsExactly("100", "70");
        assertThat(em.createNativeQuery("select count(*) from validation_reports where baseline_seed is null and baseline_entries is null and baseline_status = 'READY'").getSingleResult()).isEqualTo(1L);
    }

    @Test
    @DisplayName("검토 결과가 없는 접수는 Worker가 어떤 추출 작업도 claim하지 못한다")
    void workerCannotStartExtractionBeforeErrorReviewCompletes() throws Exception {
        prepare(); start("no-review");
        var request = new org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest(
                "synthetic-no-llm", "검사 전 차단", Set.of(AnalysisJobType.SETTING_EXTRACTION), Set.of(AnalysisMode.ORDERED_PROVISIONAL),
                true, Set.of(AnalysisSourceKind.EPISODE), true, true, true);
        assertThat(worker.claimAnalysisJob(request)).isEmpty();
        assertThat(em.createQuery("select count(j) from AnalysisJob j where j.status = 'RUNNING'", Long.class).getSingleResult()).isZero();
    }
    long count(String type) { return em.createQuery("select count(x) from " + type + " x", Long.class).getSingleResult(); }
    ValidationReport stored(JsonNode receipt) { return em.find(ValidationReport.class, UUID.fromString(receipt.path("reportId").asText())); }
    void assertNoExtraction() {
        assertThat(count("AnalysisJob")).isZero(); assertThat(count("SettingCandidate")).isZero();
        assertThat(count("WorldSettingCandidate")).isZero(); assertThat(count("CharacterFact")).isZero(); assertThat(count("CharacterSnapshotSource")).isZero();
    }

    @Test @DisplayName("중복 키와 새 키의 동일 입력은 기준을 다시 준비하거나 Job을 만들지 않는다")
    void duplicateAdmissionKeepsBaselineFingerprintAndIds() throws Exception {
        prepare(); var setting = WorldSetting.create(work, WorldSettingCategory.RACE, "인간", "서식지", "북부"); em.persist(setting);
        var first = start("one"); var r = stored(first); String hash = r.getBaselineHash(); long sources = count("ValidationBaselineSource"); long version = r.getVersion();
        // 이벤트 없이 확정 설정을 바꾸는 쓰기. 재전송은 기준을 다시 준비하지 않고, 설정 버전 fingerprint가 달라져 실행 전 검증이 알아본다(#381).
        setting.updateProperty("서식지", "서식지", "직접 DB 변경"); em.flush();
        var same = start("one"); var newKey = start("two");
        assertThat(same.path("reportId")).isEqualTo(first.path("reportId")); assertThat(newKey.path("baselineId")).isEqualTo(first.path("baselineId"));
        assertThat(r.getBaselineHash()).isEqualTo(hash); assertThat(r.getBaselineSeed()).isNull(); assertThat(r.getVersion()).isEqualTo(version);
        assertThat(count("ValidationBaselineSource")).isEqualTo(sources); assertThat(liveSettings.fingerprint(work.getId())).isNotEqualTo(hash);
        assertThat(count("ValidationReport")).isEqualTo(1); assertThat(count("ValidationRequestKey")).isEqualTo(2); assertNoExtraction();
    }

    @ParameterizedTest @ValueSource(strings = {"AUTOMATIC", "MANUAL", "VALIDATION"})
    @DisplayName("기준 READY여도 일반 분석과 재분석으로 오류 검토를 건너뛸 수 없다")
    void genericCreationRemainsBlockedWhenBaselineReady(String mode) throws Exception {
        prepare(); var receipt = start("gate"); assertThat(stored(receipt).getBaselineStatus().name()).isEqualTo("READY");
        mvc.perform(post("/api/v1/works/" + work.getId() + "/analysis-jobs").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("jobType", mode.equals("VALIDATION") ? "EPISODE_VALIDATION" : "SETTING_EXTRACTION", "batchId", batchId, "episodeId", target.getId(), "reviewMode", mode.equals("AUTOMATIC") ? "AUTOMATIC" : "MANUAL"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR_REVIEW_PENDING"));
        assertNoExtraction();
    }

    @Test @DisplayName("클라이언트의 가짜 검토 완료 플래그도 추출을 허용하지 않는다")
    void clientApprovalFlagCannotOpenGate() throws Exception {
        prepare(); var input = new java.util.HashMap<>(request("client-flags")); input.put("reviewComplete", true); input.put("findings", List.of()); input.put("approved", true);
        mvc.perform(post(base()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.errorReviewStatus").value("NOT_STARTED"));
        assertNoExtraction();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    @DisplayName("직접 회차와 target-only legacy Job 모두 claim과 재시도에서 차단한다")
    void existingJobsCannotClaimOrRetryThroughGate(boolean targetOnly) throws Exception {
        prepare(); start("legacy-bypass");
        var batch = em.find(org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.class, batchId);
        var legacy = AnalysisJob.create(work, batch, targetOnly ? null : target, AnalysisJobType.SETTING_EXTRACTION);
        if (targetOnly) legacy.addTargetEpisodes(List.of(target)); em.persist(legacy); em.flush();
        var claim = new org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest("synthetic", "검토 전", Set.of(AnalysisJobType.SETTING_EXTRACTION));
        assertThat(worker.claimAnalysisJob(claim)).isEmpty(); assertThat(legacy.getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
        legacy.fail("synthetic failure"); em.flush();
        mvc.perform(post("/api/v1/works/" + work.getId() + "/analysis-jobs/" + legacy.getId() + "/retry").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR_REVIEW_PENDING"));
        assertThat(legacy.getStatus()).isEqualTo(AnalysisJobStatus.FAILED); assertThat(count("AnalysisJob")).isEqualTo(1);
        assertThat(count("SettingCandidate")).isZero(); assertThat(count("WorldSettingCandidate")).isZero();
    }

    @Test @DisplayName("기존 target-only 분석 대상이면 검수 접수 자체를 거절한다")
    void rejectsPreviouslyStartedLegacyBatchTarget() throws Exception {
        prepare(); var batch = em.find(org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.class, batchId);
        var legacy = AnalysisJob.create(work, batch, null, AnalysisJobType.SETTING_EXTRACTION); legacy.addTargetEpisodes(List.of(target));
        legacy.claim("synthetic", "기존 실행", LocalDateTime.now().plusMinutes(5)); em.persist(legacy); em.flush();
        mvc.perform(post(base()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request("already-started"))))
                .andExpect(status().isBadRequest());
        assertThat(count("ValidationReport")).isZero();
    }

    @Test @DisplayName("Job 없는 오류 검수도 원고와 배치 목록에서 대기로 조회하며 Job 성공 수는 0이다")
    void noJobReportAppearsInEpisodeAndBatchLists() throws Exception {
        prepare(); var receipt = start("list");
        mvc.perform(get("/api/v1/works/" + work.getId() + "/episodes").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].analysisStatus").value("ERROR_REVIEW_PENDING"))
                .andExpect(jsonPath("$.data[0].latestAnalysisJobId").isEmpty()).andExpect(jsonPath("$.data[0].validationReportId").value(receipt.path("reportId").asText()));
        mvc.perform(get("/api/v1/works/" + work.getId() + "/analysis-jobs/batches").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].status").value("ERROR_REVIEW_PENDING"))
                .andExpect(jsonPath("$.data.content[0].jobGroups").isEmpty()).andExpect(jsonPath("$.data.content[0].validationReports[0].reportId").value(receipt.path("reportId").asText()));
        assertNoExtraction();
    }

    @Test @DisplayName("배치 목록 kind는 분석 batch와 검수 batch를 서버 페이지 단위로 나눈다")
    void batchListKindSeparatesAnalysisAndValidationBatches() throws Exception {
        prepare(); var receipt = start("kind");
        var analysisBatch = org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.create(work, member,
                org.monitoring.catchholebackend.domain.upload.type.UploadType.INITIAL_IMPORT,
                org.monitoring.catchholebackend.domain.upload.type.UploadSourceType.FILE);
        em.persist(analysisBatch);
        em.persist(AnalysisJob.create(work, analysisBatch, null, AnalysisJobType.SETTING_EXTRACTION)); em.flush();
        String url = "/api/v1/works/" + work.getId() + "/analysis-jobs/batches";
        mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).queryParam("kind", "ANALYSIS"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].batchId").value(analysisBatch.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].validationReports").isEmpty());
        mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).queryParam("kind", "VALIDATION"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].batchId").value(batchId.toString()))
                .andExpect(jsonPath("$.data.content[0].validationReports[0].reportId").value(receipt.path("reportId").asText()));
        mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).queryParam("kind", "REPORTS"))
                .andExpect(status().isBadRequest());
    }

    @Test @DisplayName("추출 전에 원고 파일을 교체할 수 있고 이전 기준과 결과는 무효화한다")
    void replacementBeforeExtractionInvalidatesCopiesAndKeepsOriginalBatch() throws Exception {
        prepare(); var r = stored(start("replace")); UUID originalBatch = batchId;
        episodes.replaceEpisodeFile(member.getId(), work.getId(), target.getId(), new MockMultipartFile("file", "replacement.txt", "text/plain", "새 합성 원고".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(r.getStatus().name()).isEqualTo("INVALIDATED"); assertThat(r.getBaselineSeed()).isNull();
        assertThat(r.getBatch().getId()).isEqualTo(originalBatch); assertNoExtraction();
        baselines.materialize(r.getId()); assertThat(r.getBaselineSeed()).isNull();
    }


    @Test @DisplayName("Job 없는 검수 배치의 최근 활동 시각은 접수 뒤 무효화 시각을 반영한다")
    void noJobBatchLastActivityIncludesReportInvalidation() throws Exception {
        prepare(); var r = stored(start("activity"));
        episodes.replaceEpisodeFile(member.getId(),work.getId(),target.getId(),new MockMultipartFile("file","new.txt","text/plain","합성 수정본".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        em.flush();
        var invalidatedAt = LocalDateTime.of(2026,10,28,12,34,56);
        em.createNativeQuery("update validation_reports set created_at=:created, updated_at=:updated where id=:id")
                .setParameter("created",invalidatedAt.minusDays(1)).setParameter("updated",invalidatedAt).setParameter("id",r.getId()).executeUpdate(); em.clear();
        var response=mvc.perform(get("/api/v1/works/"+work.getId()+"/analysis-jobs/batches").header(HttpHeaders.AUTHORIZATION,"Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].status").value("ERROR_REVIEW_INVALIDATED")).andReturn();
        var activity=json.readTree(response.getResponse().getContentAsString()).at("/data/content/0/lastActivityAt").asText();
        assertThat(LocalDateTime.parse(activity)).isEqualTo(invalidatedAt); assertNoExtraction();
    }

    @Test @DisplayName("기존 확정 설정 직접 수정은 허용하고 오류 기준은 재검사가 필요하도록 무효화한다")
    void canonicalEditAllowedButInvalidatesErrorBaseline() throws Exception {
        prepare(); var setting = WorldSetting.create(work, WorldSettingCategory.RACE, "기존", "서식지", "북부"); em.persist(setting);
        var r = stored(start("canonical-edit"));
        mvc.perform(patch("/api/v1/works/" + work.getId() + "/world-settings/" + setting.getId() + "/properties")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", setting.getVersion(), "currentSettingName", "서식지", "settingName", "서식지", "settingValue", "남부"))))
                .andExpect(status().isOk());
        assertThat(r.getStatus().name()).isEqualTo("INVALIDATED"); assertThat(r.getBaselineSeed()).isNull(); assertNoExtraction();
    }

    @Test @DisplayName("기준 파기와 늦은 정규화가 겹쳐도 삭제한 복사본을 복원하지 않는다")
    void priorPurgePreventsLateMaterialization() throws Exception {
        prepare(); var r = stored(start("purge"));
        var prior = em.createQuery("select e from Episode e where e.work = :work and e.episodeNo = 70", Episode.class).setParameter("work", work).getSingleResult();
        states.purgeSourceEvidenceForWorkForUpdate(work.getId(), prior.getId(), 70); em.flush(); baselines.materialize(r.getId());
        assertThat(r.getBaselineSeed()).isNull(); assertThat(count("ValidationBaselineSource")).isZero(); assertNoExtraction();
    }
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 30})
    @DisplayName("접수 준비는 설정·후보·Fact·분석 Job을 읽지 않고 조회 수와 엔티티 로드가 확정 설정 크기와 무관하다")
    void intakeQueryAndEntityLoadCountsDoNotDependOnSettingsSize(int subjects) throws Exception {
        prepare();
        var priorEpisode = em.createQuery("select e from Episode e where e.work = :work and e.episodeNo = 70", Episode.class).setParameter("work", work).getSingleResult();
        var priorJob = AnalysisJob.create(work, null, priorEpisode, AnalysisJobType.SETTING_EXTRACTION);
        // 운영의 큰 Job처럼 출처 확인과 무관한 입력 JSON을 채운다.
        ReflectionTestUtils.setField(priorJob, "automaticInputState", JsonNodeFactory.instance.objectNode().put("payload", "x".repeat(200_000)));
        em.persist(priorJob);
        for (int i = 0; i < subjects; i++) {
            var owner = org.monitoring.catchholebackend.domain.character.entity.WorkCharacter.create(work, "인물" + i, null, 20, 1, null, null, null, null, null, null);
            em.persist(owner);
            var c = SettingCandidate.create(work, priorEpisode, null, priorJob, SettingEntityType.CHARACTER, owner.getName(), owner.getName(), owner.getId(), SettingCandidateMatchStatus.MATCHED,
                    "직업", "수리공" + i, SettingValueType.STRING, null, JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("quote", "근거" + i)), BigDecimal.ONE, null);
            em.persist(c); ReflectionTestUtils.setField(c, "reviewStatus", SettingCandidateReviewStatus.CONFIRMED);
            var fact = CharacterFact.create(owner, c, CharacterFactType.PROFILE, "직업", "수리공" + i, "수리공" + i, null, priorEpisode, null, priorJob, BigDecimal.ONE, 12);
            em.persist(fact); em.persist(org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource.create(owner, CharacterFactType.PROFILE, "직업", fact, 0));
            var setting = WorldSetting.create(work, WorldSettingCategory.RACE, "종족" + i, "서식지", "남부"); em.persist(setting);
            var history = WorldSettingCandidate.create(work, priorEpisode, priorJob, WorldSettingCategory.RACE, "종족" + i, "서식지", "남부",
                    JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("quote", "남부에 산다")), BigDecimal.ONE, null);
            ReflectionTestUtils.setField(history, "targetWorldSetting", setting); ReflectionTestUtils.setField(history, "reviewStatus", WorldSettingReviewStatus.CONFIRMED);
            ReflectionTestUtils.setField(history, "finalCategory", WorldSettingCategory.RACE); ReflectionTestUtils.setField(history, "finalSubjectName", "종족" + i);
            ReflectionTestUtils.setField(history, "finalSettingName", "서식지"); ReflectionTestUtils.setField(history, "finalValue", "남부");
            ReflectionTestUtils.setField(history, "finalOperation", WorldSettingOperation.ADD);
            em.persist(history);
        }
        em.flush(); em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.clear();

        var receipt = start("intake-" + subjects);

        for (Class<?> forbidden : List.of(AnalysisJob.class, SettingCandidate.class, WorldSettingCandidate.class, CharacterFact.class,
                org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource.class,
                org.monitoring.catchholebackend.domain.character.entity.WorkCharacter.class, WorldSetting.class))
            assertThat(statistics.getEntityStatistics(forbidden.getName()).getLoadCount()).as(forbidden.getSimpleName()).isZero();
        // 접수 HTTP 요청 전체(인증·작품 잠금·자격 판정·report·출처 목록 2행·fingerprint scalar 2번·요청 키·응답): SQL 29번(조회 19·insert 4·update 3 포함),
        // 엔티티 로드 7개(Member·Work·UploadBatch·UploadFile·Episode 3). 인물·세계관·후보 0·1·30개 모두 같다. 앞 회차·설정집 수에만 비례한다(출처 행마다 insert와 파기 대기 확인).
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(INTAKE_STATEMENTS);
        assertThat(statistics.getEntityLoadCount()).isEqualTo(INTAKE_ENTITY_LOADS);
        var r = stored(receipt);
        assertThat(r.getBaselineSeed()).isNull(); assertThat(r.getBaselineEntries()).isNull();
        assertThat(sourceManifests.manifests(r.getId()).findValuesAsText("episodeNo")).containsExactly("100", "70");
    }
    static final long INTAKE_STATEMENTS = 29, INTAKE_ENTITY_LOADS = 7;
    @Test
    @DisplayName("기준 준비의 파기 대기 확인은 이력 수가 아니라 출처 수만큼만 조회한다")
    void baselineCaptureChecksPurgeOncePerSourceInsteadOfPerHistoryRow() throws Exception {
        prepare();
        var priorEpisode = em.createQuery("select e from Episode e where e.work = :work and e.episodeNo = 70", Episode.class).setParameter("work", work).getSingleResult();
        var priorJob = AnalysisJob.create(work, null, priorEpisode, AnalysisJobType.SETTING_EXTRACTION); em.persist(priorJob);
        var current = WorldSetting.create(work, WorldSettingCategory.RACE, "기존 종족", "서식지", "남부"); em.persist(current);
        for (String value : List.of("남부", "북부", "동부", "서부", "중부", "해안")) {
            var c = WorldSettingCandidate.create(work, priorEpisode, priorJob, WorldSettingCategory.RACE, "기존 종족", "서식지", value,
                    JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("quote", value)), BigDecimal.ONE, null);
            ReflectionTestUtils.setField(c, "targetWorldSetting", current); ReflectionTestUtils.setField(c, "reviewStatus", WorldSettingReviewStatus.CONFIRMED);
            ReflectionTestUtils.setField(c, "finalCategory", WorldSettingCategory.RACE); ReflectionTestUtils.setField(c, "finalSubjectName", "기존 종족");
            ReflectionTestUtils.setField(c, "finalSettingName", "서식지"); ReflectionTestUtils.setField(c, "finalValue", value);
            ReflectionTestUtils.setField(c, "finalOperation", WorldSettingOperation.UPDATE);
            em.persist(c);
        }
        em.flush(); em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.clear();

        var receipt = start("purge-check-once");

        // 앞 회차 원고 2개(70·100화)뿐이므로 이력 6행이 있어도 파기 대기 확인은 출처마다 한 번이다(#381부터 이력은 복사하지 않는다).
        long purgeChecks = java.util.Arrays.stream(statistics.getQueries())
                .filter(query -> query.contains("EpisodeSourcePurgeRequest") && query.contains("retainedContentKey"))
                .mapToLong(query -> statistics.getQueryStatistics(query).getExecutionCount()).sum();
        assertThat(purgeChecks).isEqualTo(2);
        var r = stored(receipt);
        assertThat(r.getBaselineSeed()).isNull();
        assertThat(sourceManifests.manifests(r.getId())).hasSize(2);
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("보관한 설정집의 실제 revision도 출처 목록에 고정하고 이후 설정 수정이 출처 목록을 바꾸지 않는다")
    void freezesActualBookRevisionsInManifestBeforeLaterSettingEdits(boolean archivedBook) throws Exception {
        prepare();
        var bookBatch = org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.create(work, member, org.monitoring.catchholebackend.domain.upload.type.UploadType.SETTING_BOOK, org.monitoring.catchholebackend.domain.upload.type.UploadSourceType.FILE);
        em.persist(bookBatch);
        var book = org.monitoring.catchholebackend.domain.upload.entity.UploadFile.create(bookBatch, org.monitoring.catchholebackend.domain.upload.type.UploadFileRole.SETTING_BOOK, "synthetic-book.txt", "text/plain", "synthetic-book", 2); em.persist(book);
        var r1 = org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(book, 1,
                new org.monitoring.catchholebackend.global.storage.StoredTextObject("synthetic/book-r1", "v1", "1".repeat(64), 2), org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind.EPISODE, 12);
        em.persist(r1); book.linkCurrentRevision(r1);
        var r2 = org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(book, 2,
                new org.monitoring.catchholebackend.global.storage.StoredTextObject("synthetic/book-r2", "v2", "2".repeat(64), 2));
        em.persist(r2); book.linkCurrentRevision(r2);
        if (archivedBook) book.archive();
        var current = WorldSetting.create(work, WorldSettingCategory.RACE, "기존 종족", "서식지", "남부"); em.persist(current);
        em.flush();
        var receipt = start("book-history");
        // 다음 회차가 등록되어도 과거 검사 자료에는 포함하지 않는다.
        em.persist(Episode.create(work, null, 102, "후속", "synthetic/102", "v1", "c".repeat(64), 2));
        var captured = em.find(ValidationReport.class, UUID.fromString(receipt.path("reportId").asText()));
        var manifests = sourceManifests.manifests(captured.getId());
        assertThat(manifests.findValuesAsText("revisionId")).containsExactly(r1.getId().toString(), r2.getId().toString());
        assertThat(manifests.findValuesAsText("episodeNo")).containsExactly("100", "70");
        // 접수 시점에 보관되지 않은 현재 판만 RAG 범위다.
        assertThat(manifests.get(3).path("currentRevision").asBoolean()).isEqualTo(!archivedBook);
        assertThat(manifests.get(2).path("currentRevision").asBoolean()).isFalse();
        var frozen = manifests.deepCopy();
        current.updateProperty("서식지", "서식지", "최신 수정"); em.flush();
        assertThat(sourceManifests.manifests(captured.getId())).isEqualTo(frozen);
        assertThat(captured.getBaselineSeed()).isNull();
    }
    @Test
    @DisplayName("저장 manifest 행은 저장 순서와 무관하게 접수 순서(앞 회차 번호 내림차순, 설정집 id·revision 순)로 다시 읽혀 legacy seed.sources와 같은 hash가 된다")
    void manifestRowsReadBackInIntakeOrderRegardlessOfInsertionOrder() throws Exception {
        prepare();
        for (int b = 0; b < 3; b++) {
            var bookBatch = org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.create(work, member, org.monitoring.catchholebackend.domain.upload.type.UploadType.SETTING_BOOK, org.monitoring.catchholebackend.domain.upload.type.UploadSourceType.FILE);
            em.persist(bookBatch);
            var book = org.monitoring.catchholebackend.domain.upload.entity.UploadFile.create(bookBatch, org.monitoring.catchholebackend.domain.upload.type.UploadFileRole.SETTING_BOOK, "book-" + b + ".txt", "text/plain", "synthetic-book-" + b, 2); em.persist(book);
            for (int no = 1; no <= 2; no++) {
                var revision = org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(book, no,
                        new org.monitoring.catchholebackend.global.storage.StoredTextObject("synthetic/book-" + b + "-" + no, "v1", String.valueOf(no).repeat(64), 2));
                em.persist(revision); book.linkCurrentRevision(revision);
            }
        }
        em.flush();
        var report = stored(start("manifest-order"));
        // 접수가 쓰던 순서: 회차 번호 내림차순, 그다음 접수의 설정집 revision 조회 순서(설정집 id, revisionNo).
        var expected = new java.util.ArrayList<String>(List.of("100", "70"));
        em.createQuery("select r.id from SettingBookRevision r where r.settingBook.batch.work.id = :id order by r.settingBook.id, r.revisionNo", UUID.class)
                .setParameter("id", work.getId()).getResultList().forEach(id -> expected.add(id.toString()));
        var seedSources = sourceManifests.manifests(report.getId());
        // 행을 지우고 역순으로 다시 저장해도(생성 시각·id가 바뀜) 같은 순서로 읽는다.
        var rows = em.createQuery("select s from ValidationBaselineSource s where s.report.id = :r", org.monitoring.catchholebackend.domain.validation.entity.ValidationBaselineSource.class)
                .setParameter("r", report.getId()).getResultList();
        var copies = rows.stream().map(row -> new Object[]{row.getEpisode(), row.getSettingBookRevision(), row.getSourceManifest().deepCopy()}).toList();
        rows.forEach(em::remove); em.flush();
        for (int i = copies.size() - 1; i >= 0; i--)
            em.persist(org.monitoring.catchholebackend.domain.validation.entity.ValidationBaselineSource.create(report, (Episode) copies.get(i)[0],
                    (org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision) copies.get(i)[1], (JsonNode) copies.get(i)[2]));
        em.flush(); em.clear();
        var manifests = sourceManifests.manifests(report.getId());
        var actual = new java.util.ArrayList<String>();
        manifests.forEach(m -> actual.add("EPISODE".equals(m.path("sourceKind").asText()) ? m.path("episodeNo").asText() : m.path("revisionId").asText()));
        assertThat(actual).isEqualTo(expected).hasSize(8);
        assertThat(sourceManifests.manifestHash(manifests)).isEqualTo(sourceManifests.manifestHash(seedSources));
    }
    @Test
    @DisplayName("파기 대기 중인 앞 회차는 출처 목록에 넣지 않고 그 원문 위치나 인용을 어디에도 복사하지 않는다")
    void baselineCreatedWhilePriorSourcePurgeIsPendingCannotCopyThatOriginal() throws Exception {
        prepare();
        var prior = em.createQuery("select e from Episode e where e.work = :work and e.episodeNo = 70", Episode.class).setParameter("work", work).getSingleResult();
        var historicalJob = AnalysisJob.create(work, null, prior, AnalysisJobType.SETTING_EXTRACTION); em.persist(historicalJob);
        var historical = WorldSettingCandidate.create(work, prior, historicalJob, WorldSettingCategory.RACE, "과거 종족", "특징", "유지한 확정값",
                JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("quote", "파기할 인용문")), BigDecimal.ONE, null);
        ReflectionTestUtils.setField(historical, "reviewStatus", WorldSettingReviewStatus.CONFIRMED);
        ReflectionTestUtils.setField(historical, "finalOperation", WorldSettingOperation.ADD);
        ReflectionTestUtils.setField(historical, "finalCategory", WorldSettingCategory.RACE);
        ReflectionTestUtils.setField(historical, "finalSubjectName", "과거 종족");
        ReflectionTestUtils.setField(historical, "finalSettingName", "특징");
        ReflectionTestUtils.setField(historical, "finalValue", "유지한 확정값");
        em.persist(historical);
        em.persist(org.monitoring.catchholebackend.domain.episode.entity.EpisodeSourcePurgeRequest.requestDeletion(prior, null)); prior.archive(); em.flush();
        var receipt = start("purge-pending");
        var captured = em.find(ValidationReport.class, UUID.fromString(receipt.path("reportId").asText()));
        assertThat(captured.getBaselineSeed()).isNull(); assertThat(captured.getBaselineEntries()).isNull();
        var manifests = sourceManifests.manifests(captured.getId());
        assertThat(manifests.findValuesAsText("episodeNo")).containsExactly("100");
        assertThat(manifests.toString()).doesNotContain("파기할 인용문", "synthetic/70.txt");
    }
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("첫 회차는 비교 자료가 있을 때만 접수한다")
    void firstEpisodeRequiresComparisonMaterial(boolean material) throws Exception {
        prepare(false, 1);
        if (material) em.persist(WorldSetting.create(work, WorldSettingCategory.RACE, "인간", "특징", "적응"));
        mvc.perform(post(base()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request("first"))))
                .andExpect(status().is(material ? 200 : 400));
        assertThat(count("ValidationReport")).isEqualTo(material ? 1 : 0); assertNoExtraction();
    }
    @ParameterizedTest
    @ValueSource(strings = {"existing-analysis", "middle", "stale-version", "other-owner", "multi"})
    @DisplayName("기존 분석 중간 삽입 다회차 버전 소유권 검증 실패는 접수하지 않는다")
    void rejectsIneligibleSourceWithoutCreatingReport(String reason) throws Exception {
        prepare();
        var input = new java.util.HashMap<>(request("invalid"));
        if (reason.equals("existing-analysis")) em.persist(AnalysisJob.create(work, em.find(org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.class, batchId), target, AnalysisJobType.SETTING_EXTRACTION));
        if (reason.equals("middle")) { em.persist(Episode.create(work, null, 102, "후속", "synthetic/102", "v1", "d".repeat(64), 2)); work.updateLatestEpisodeNo(102); }
        if (reason.equals("stale-version")) input.put("expectedContentHash", "d".repeat(64));
        if (reason.equals("multi")) ReflectionTestUtils.setField(em.find(org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.class, batchId), "uploadType", org.monitoring.catchholebackend.domain.upload.type.UploadType.MULTI_EPISODE_SINGLE_FILE);
        if (reason.equals("other-owner")) {
            var other = Member.register("other-validation@example.invalid", "test", "01098765432", "다른 작가"); em.persist(other); token = tokens.generateAccessToken(other);
        }
        em.flush();
        mvc.perform(post(base()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().is(reason.equals("other-owner") ? 404 : reason.equals("stale-version") ? 409 : 400));
        assertThat(count("ValidationReport")).isZero();
    }
    @Test
    @DisplayName("동일 요청 키의 다른 원고 버전은 중복 생성 없이 거절한다")
    void sameKeyWithDifferentSourceVersionIsRejectedWithoutNewRows() throws Exception {
        prepare(); start("stable-key");
        var changed = new java.util.HashMap<>(request("stable-key")); changed.put("expectedContentHash", "b".repeat(64));
        mvc.perform(post(base()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(changed)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VALIDATION_IDEMPOTENCY_CONFLICT"));
        assertThat(count("AnalysisJob")).isZero(); assertThat(count("ValidationReport")).isEqualTo(1);
    }
    @Test
    @DisplayName("같은 작가의 다른 작품에서도 원고 검수 접수를 재사용할 수 없다")
    void cannotReuseAnotherOwnedWorksEpisodeReceipt() throws Exception {
        prepare(); start("work-a");
        var otherWork = Work.create(member, "다른 작품", WorkGenre.FANTASY, "합성"); em.persist(otherWork); em.flush();
        mvc.perform(post("/api/v1/works/" + otherWork.getId() + "/episode-validations").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request("work-b"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_TARGET_INVALID"));
        assertThat(count("ValidationReport")).isEqualTo(1);
    }
    @ParameterizedTest
    @ValueSource(strings = {"SOURCE_UNAVAILABLE", "HASH_MISMATCH", "QUOTE_NOT_FOUND"})
    @DisplayName("과거 원문 저장소 장애나 인용 불일치와 무관하게 접수하고 접수 중 과거 원문을 읽지 않는다")
    void historicalSourceValidationPreservesReceiptAndExtractionGate(String outcome) throws Exception {
        prepare();
        var owner = org.monitoring.catchholebackend.domain.character.entity.WorkCharacter.create(work, "기존", null, null, null,
                JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(), null); em.persist(owner);
        var prior = em.createQuery("select e from Episode e where e.work = :work and e.episodeNo = 70", Episode.class).setParameter("work", work).getSingleResult();
        var previous = AnalysisJob.create(work, null, prior, AnalysisJobType.SETTING_EXTRACTION); em.persist(previous);
        var c = SettingCandidate.create(work, prior, null, previous, SettingEntityType.CHARACTER, "기존", "기존", owner.getId(), SettingCandidateMatchStatus.MATCHED,
                "직업", "수리공", SettingValueType.STRING, null, JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("quote", "합성 근거")), BigDecimal.ONE, null);
        em.persist(c); ReflectionTestUtils.setField(c, "reviewStatus", SettingCandidateReviewStatus.CONFIRMED);
        em.persist(CharacterFact.create(owner, c, CharacterFactType.PROFILE, "직업", "수리공", "수리공", null, prior, null, previous, BigDecimal.ONE, 12));
        if (outcome.equals("SOURCE_UNAVAILABLE")) {
            when(storage.getText(prior.getContentS3Key())).thenThrow(new IllegalStateException("synthetic storage failure"));
        } else {
            String content = "인용이 없는 합성 원문";
            when(storage.getText(prior.getContentS3Key())).thenReturn(content);
            if (outcome.equals("QUOTE_NOT_FOUND")) {
                ReflectionTestUtils.setField(previous, "sourceContentHash", storedText.contentHash(content));
                ReflectionTestUtils.setField(prior, "contentHash", storedText.contentHash(content));
            }
        }
        em.flush();
        long before = count("AnalysisJob"); var r = stored(start("source-unavailable"));
        assertThat(r.getStatus().name()).isEqualTo("WAITING_FOR_ERROR_REVIEW"); assertThat(r.getBaselineStatus().name()).isEqualTo("READY");
        assertThat(r.getBaselineSeed()).isNull();
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.never()).getText(prior.getContentS3Key());
        assertThat(r.getSourceContentHash()).isEqualTo(target.getContentHash()); assertThat(r.getSourceContentS3Key()).isEqualTo(target.getContentS3Key());
        assertThat(count("AnalysisJob")).isEqualTo(before); assertThat(count("ValidationReport")).isEqualTo(1);
        mvc.perform(get(base()+"/"+r.getId()).header(HttpHeaders.AUTHORIZATION,"Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.extractionStatus").value("NOT_STARTED"));
    }

}
