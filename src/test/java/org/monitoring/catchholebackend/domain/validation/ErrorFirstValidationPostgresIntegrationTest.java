package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.character.dto.request.*;
import org.monitoring.catchholebackend.domain.character.entity.*;
import org.monitoring.catchholebackend.domain.character.service.CharacterFactComparisonWorkerService;
import org.monitoring.catchholebackend.domain.character.type.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.monitoring.catchholebackend.domain.analysis.dto.request.*;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.service.*;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.service.EpisodeService;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeUploadType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationStartRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationStagingResponse;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationReportRepository;
import org.monitoring.catchholebackend.domain.validation.service.*;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.*;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.global.storage.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Only the newly provisioned disposable loopback gh303_test database may run destructive fixture cleanup. */
@SpringBootTest(properties = {"spring.config.import=", "spring.docker.compose.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext
@EnabledIfEnvironmentVariable(named = "GH303_ERROR_FIRST_POSTGRES_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh303_error_first_test")
@DisplayName("GH303 격리 PostgreSQL migration과 실제 트랜잭션 경합")
class ErrorFirstValidationPostgresIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> System.getenv("GH303_ERROR_FIRST_POSTGRES_JDBC_URL"));
        p.add("spring.datasource.username", () -> "gh303");
        p.add("spring.datasource.password", () -> "gh303-disposable-only");
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        p.add("spring.flyway.enabled", () -> "true");
    }
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WorkRepository works;
    @Autowired WorkPurgeDataRepository purge;
    @Autowired EpisodeService episodes;
    @Autowired EpisodeValidationService validations;
    @Autowired ValidationBaselineService baselines;
    @Autowired ValidationReportRepository reports;
    @Autowired AnalysisJobRepository jobs;
    @Autowired AnalysisJobWorkerService worker;
    @Autowired AnalysisRunStateService states;
    @Autowired CharacterFactComparisonWorkerService comparisons;
    @Autowired ObjectStorageService storedText;

    @MockitoBean ObjectStorage storage;
    TransactionTemplate tx;
    record Fixture(Long memberId, UUID workId, UUID episodeId, UUID priorId, UUID settingId, ValidationStartRequest request) {}

    @BeforeEach
    void isolatedFixture() {
        tx = new TransactionTemplate(transactions);
        jdbc.execute("TRUNCATE TABLE works, members CASCADE");
        when(storage.putBytes(anyString(), any(byte[].class), anyString())).thenAnswer(call -> new StoredObject(call.getArgument(0), "synthetic-v1"));
        when(storage.putText(anyString(), anyString())).thenAnswer(call -> new StoredObject(call.getArgument(0), "synthetic-v1"));
        when(storage.purgePrefixes(anyCollection())).thenReturn(new ObjectStoragePurgeResult(1, 1, 0));
        when(storage.purgePrefixesExcluding(anyCollection(), anyCollection())).thenReturn(new ObjectStoragePurgeResult(1, 1, 0));
    }
    Fixture fixture() {
        return tx.execute(status -> {
            var member = Member.register("gh303-postgres@example.invalid", "test-only", "01012345678", "합성 작가"); em.persist(member);
            var work = Work.create(member, "GH303 격리 PostgreSQL", WorkGenre.FANTASY, "synthetic-only"); em.persist(work);
            var prior = Episode.create(work, null, 100, "앞 회차", "synthetic/100", "v1", "a".repeat(64), 2); em.persist(prior); work.updateLatestEpisodeNo(100);
            var setting = WorldSetting.create(work, WorldSettingCategory.RACE, "인간", "서식지", "북부"); em.persist(setting);
            var uploaded = episodes.uploadEpisodes(member.getId(), work.getId(), new EpisodeUploadRequest(EpisodeUploadType.SINGLE_EPISODE, 101, "검수", null),
                    List.of(new MockMultipartFile("episodeFiles", "101.txt", "text/plain", "합성 원고".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            var target = em.createQuery("select e from Episode e where e.work = :work and e.episodeNo = 101", Episode.class).setParameter("work", work).getSingleResult();
            return new Fixture(member.getId(), work.getId(), target.getId(), prior.getId(), setting.getId(),
                    new ValidationStartRequest("pg-idempotent", uploaded.batchId(), target.getId(), target.getContentHash(), target.getContentS3Key(), target.getContentS3Version()));
        });
    }
    ValidationStagingResponse start(Fixture f) { return validations.start(f.memberId(), f.workId(), f.request()); }
    WorkerAnalysisJobClaimRequest claimRequest() {
        return new WorkerAnalysisJobClaimRequest("synthetic-no-llm", "합성 준비", Set.of(AnalysisJobType.SETTING_EXTRACTION),
                Set.of(AnalysisMode.ORDERED_PROVISIONAL), true, Set.of(AnalysisSourceKind.EPISODE), true, true, true);
    }
    static void await(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }


    void assertNoExtraction() {
        for (String table : List.of("analysis_jobs", "setting_candidates", "world_setting_candidates", "character_facts", "character_snapshot_sources")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).as(table).isZero();
        }
    }

    @Test @DisplayName("새 PostgreSQL 전체 migration은 추출 없는 원장과 승인 불가 제약을 검증한다")
    void freshMigrationValidatesNoExtractionSchemaAndClosedGate() {
        var f = fixture(); var r = start(f);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '89' and success", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name = 'validation_reports' and column_name in ('extraction_job_id','candidate_generation','candidate_digest')", Integer.class)).isZero();
        assertThat(r.extractionJobId()).isNull(); assertThat(r.candidateGeneration()).isNull(); assertThat(r.extractionStatus().name()).isEqualTo("NOT_STARTED");
        assertThat(r.baselineStatus().name()).isEqualTo("READY"); assertNoExtraction();
        assertThatThrownBy(() -> jdbc.update("update validation_reports set status = 'APPROVED' where id = ?", r.reportId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(worker.claimAnalysisJob(claimRequest())).isEmpty(); assertNoExtraction();
    }

    @Test @DisplayName("실제 동시 접수는 같은 원장과 기준만 만들고 Job은 만들지 않는다")
    void concurrentStartsHaveOneReportAndNoJobs() throws Exception {
        var f = fixture(); var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = java.util.stream.IntStream.range(0,2).mapToObj(i -> pool.submit(() -> { ready.countDown(); await(go); return start(f); })).toList();
            await(ready); go.countDown(); var one=futures.getFirst().get(20,TimeUnit.SECONDS); var two=futures.getLast().get(20,TimeUnit.SECONDS);
            assertThat(two).isEqualTo(one);
            assertThat(jdbc.queryForObject("select count(*) from validation_reports",Integer.class)).isEqualTo(1); assertNoExtraction();
        }
    }

    @Test @DisplayName("접수와 설정 수정은 같은 작품 잠금 아래 커밋된 기준을 캡처한다")
    void admissionAfterConcurrentSettingEditCapturesCommittedValue() throws Exception {
        var f=fixture(); var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var edit=pool.submit(() -> tx.executeWithoutResult(status -> { works.getOwnedWorkForUpdate(f.workId(),f.memberId());
                em.find(WorldSetting.class,f.settingId()).updateProperty("서식지","서식지","동부"); em.flush(); locked.countDown(); await(release); }));
            await(locked); var admission=pool.submit(() -> start(f)); release.countDown(); edit.get(20,TimeUnit.SECONDS); var r=admission.get(20,TimeUnit.SECONDS);
            assertThat(tx.<String>execute(status -> reports.findById(r.reportId()).orElseThrow().getBaselineSeed().path("worldSettings").toString())).contains("동부").doesNotContain("북부");
            assertNoExtraction();
        }
    }

    @Test @DisplayName("선행 no-job 검수 대상은 claim 선정 전에 제외하여 다른 정상 회차를 굶기지 않는다")
    void targetOnlyBlockedJobDoesNotStarveOrdinaryClaim() {
        var f=fixture(); start(f);
        UUID blocked=tx.execute(status -> {var work=em.find(Work.class,f.workId()); var legacy=AnalysisJob.create(work,null,null,AnalysisJobType.SETTING_EXTRACTION); legacy.addTargetEpisodes(List.of(em.find(Episode.class,f.episodeId()))); em.persist(legacy); return legacy.getId();});
        UUID ordinary=tx.execute(status -> {var work=em.find(Work.class,f.workId());var prior=em.find(Episode.class,f.priorId()); var job=AnalysisJob.create(work,null,prior,AnalysisJobType.SETTING_EXTRACTION);em.persist(job);return job.getId();});
        var request=new WorkerAnalysisJobClaimRequest("synthetic","정상 회차",Set.of(AnalysisJobType.SETTING_EXTRACTION));
        assertThat(worker.claimAnalysisJob(request).orElseThrow().analysisJobId()).isEqualTo(ordinary);
        assertThat(jdbc.queryForObject("select status from analysis_jobs where id = ?",String.class,blocked)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from setting_candidates",Integer.class)).isZero();
    }

    @Test @DisplayName("늦은 lease callback도 검토 전 진행 상태나 후보를 저장하지 못한다")
    void staleRunningJobCannotReportExtractionProgress() {
        var f=fixture(); start(f);
        UUID[] old=tx.execute(status -> {var job=AnalysisJob.create(em.find(Work.class,f.workId()),null,em.find(Episode.class,f.episodeId()),AnalysisJobType.SETTING_EXTRACTION);var lease=job.claim("synthetic","늦은 callback",LocalDateTime.now().plusMinutes(5));em.persist(job);return new UUID[]{job.getId(),lease};});
        assertThatThrownBy(() -> worker.heartbeatAnalysisJob(old[0],old[1])).isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class)
                .extracting(e -> ((org.monitoring.catchholebackend.global.exception.AppException)e).getResultCode().getCode()).isEqualTo("VALIDATION_ERROR_REVIEW_PENDING");
        assertThat(jdbc.queryForObject("select status from episodes where id=?",String.class,f.episodeId())).isEqualTo("UPLOADED");
        assertThat(jdbc.queryForObject("select count(*) from setting_candidates",Integer.class)).isZero();
    }

    @Test @DisplayName("파기 전에 seed를 읽었던 정규화도 파기 커밋 뒤 복사본을 복원하지 못한다")
    void purgeWinsAfterNormalizerReadsSeed() throws Exception {
        var f=fixture();var r=start(f);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var read=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var deletion=pool.submit(() -> tx.executeWithoutResult(status -> { works.getOwnedWorkForUpdate(f.workId(),f.memberId());states.purgeSourceEvidenceForWorkForUpdate(f.workId(),f.priorId(),100);em.find(Episode.class,f.priorId()).archive();em.flush();locked.countDown();await(release);}));
            await(locked);var normalize=pool.submit(() -> tx.executeWithoutResult(status -> {assertThat(reports.findById(r.reportId()).orElseThrow().getBaselineSeed()).isNotNull();read.countDown();baselines.materialize(r.reportId());}));
            await(read);release.countDown();deletion.get(20,TimeUnit.SECONDS);normalize.get(20,TimeUnit.SECONDS);
        }
        assertPurged(r.reportId());assertNoExtraction();
    }
    @Test @DisplayName("기준 준비가 먼저 완료되어도 뒤의 원문 파기와 설정 수정은 무효화한다")
    void readyBaselineIsInvalidatedBeforeAnyExtraction() {
        var f=fixture();var r=start(f);baselines.materialize(r.reportId());
        tx.executeWithoutResult(status -> { works.getOwnedWorkForUpdate(f.workId(),f.memberId());states.confirmedSettingsChanged(f.workId()); });
        baselines.materialize(r.reportId());assertPurged(r.reportId());assertNoExtraction();
    }
    void assertPurged(UUID id) {
        assertThat(jdbc.queryForObject("select baseline_seed is null and baseline_entries is null and status = 'INVALIDATED' from validation_reports where id=?",Boolean.class,id)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from validation_baseline_sources where report_id=?",Integer.class,id)).isZero();
    }

    @Test @DisplayName("추출 전에 실제 원고 파일을 바꾸면 이전 report와 원본 배치 연결을 무효화 상태로 보존한다")
    void replacementBeforeExtractionIsAllowedAndInvalidatesOldReport() {
        var f=fixture();var r=start(f);
        episodes.replaceEpisodeFile(f.memberId(),f.workId(),f.episodeId(),new MockMultipartFile("file","new.txt","text/plain","새 합성 원고".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(jdbc.queryForObject("select batch_id from validation_reports where id=?",UUID.class,r.reportId())).isEqualTo(f.request().batchId());
        assertPurged(r.reportId());assertNoExtraction();
        assertThat(validations.start(f.memberId(),f.workId(),f.request()).status().name()).isEqualTo("INVALIDATED");
    }

    @Test @DisplayName("DB 저장 실패는 정상 기준 보류로 삼키지 않고 접수 전체를 롤백한다")
    void databaseWriteFailureRollsBackAdmission() {
        var f=fixture();
        jdbc.execute("create function gh303_reject_sources() returns trigger language plpgsql as 'begin raise exception ''synthetic DB failure''; end'");
        jdbc.execute("create trigger gh303_reject before insert on validation_baseline_sources for each row execute function gh303_reject_sources()");
        try { assertThatThrownBy(() -> start(f)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("drop trigger gh303_reject on validation_baseline_sources");jdbc.execute("drop function gh303_reject_sources()"); }
        assertThat(jdbc.queryForObject("select count(*) from validation_reports",Integer.class)).isZero();assertNoExtraction();
    }


    record PriorComparison(UUID jobId, UUID lease, UUID candidateId, UUID characterId,
                           UUID comparisonBatchId, String contextToken) {}

    PriorComparison priorComparison(Fixture f, boolean batch) {
        var ids = tx.execute(status -> {
            String text = "합성 관측 시점";
            var episode = em.find(Episode.class, f.priorId());
            episode.updateContentStorage(episode.getContentS3Key(), "v1",
                    storedText.contentHash(text));
            when(storage.getText(episode.getContentS3Key())).thenReturn(text);
            var character = WorkCharacter.create(em.find(Work.class, f.workId()), "기존 인물", null, null, null,
                    null, null, null, null, null, null);
            character.replaceCurrentSnapshots(null, 1, null, null, null, null, null); em.persist(character);
            var job = AnalysisJob.create(em.find(Work.class, f.workId()), null, episode, AnalysisJobType.SETTING_EXTRACTION);
            job.updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_CANDIDATES_SAVED);
            var lease = job.claim("synthetic-no-llm", "기존 비교 중", LocalDateTime.now().plusMinutes(5)); em.persist(job);
            em.persist(CharacterSettingSchema.create(job.getWork(),"level",null,"레벨",CharacterFactType.LEVEL,
                    SettingValueType.NUMBER,CharacterSettingValueSemantics.BASE_VALUE,CharacterSettingMergePolicy.REPLACE,
                    JsonNodeFactory.instance.arrayNode(),CharacterSettingSchemaSource.DEV_SEED,true));
            var chunkId = UUID.randomUUID();
            jdbc.update("insert into episode_chunks(id,episode_id,chunk_index,chunk_text,start_offset,end_offset,paragraph_start_index,paragraph_end_index,created_at,updated_at) values (?,?,0,?,0,?,0,0,now(),now())",
                    chunkId, episode.getId(), text, text.codePointCount(0,text.length()));
            var candidate = SettingCandidate.create(job.getWork(), episode, chunkId, job, SettingEntityType.CHARACTER,
                    character.getName(), character.getName(), character.getId(), SettingCandidateMatchStatus.MATCHED,
                    "level", "1", SettingValueType.NUMBER, JsonNodeFactory.instance.objectNode().put("value",1),
                    JsonNodeFactory.instance.arrayNode(), BigDecimal.ONE, null); em.persist(candidate);
            var point = JsonNodeFactory.instance.objectNode().put("description", "관측한 새 시점");
            point.putArray("evidenceSpans").add(JsonNodeFactory.instance.objectNode().put("quote",text)
                    .put("startOffset",0).put("endOffset",text.codePointCount(0,text.length())).put("sourceChunkId",chunkId.toString()));
            var time = JsonNodeFactory.instance.objectNode().putNull("factTime"); time.set("referenceTime",point); candidate.recordNarrativeTime(time);
            return new UUID[]{job.getId(), lease, candidate.getId(), character.getId()};
        });
        if (batch) {
            var claimed = comparisons.claimNextCharacterFactComparisonBatch(ids[0], ids[1]).orElseThrow();
            var context = comparisons.getCharacterFactComparisonBatchContext(ids[0],claimed.comparisonBatchId(),ids[1]);
            return new PriorComparison(ids[0],ids[1],ids[2],ids[3],claimed.comparisonBatchId(),context.contextToken());
        }
        comparisons.claimNextCharacterFactComparison(ids[0],ids[1]).orElseThrow();
        var context = comparisons.getCharacterFactComparisonContext(ids[0],ids[2],ids[1]);
        return new PriorComparison(ids[0],ids[1],ids[2],ids[3],null,context.contextToken());
    }
    void exclude(PriorComparison c, boolean batch) {
        if (batch) {
            comparisons.completeCharacterFactComparisonBatch(c.jobId(),c.comparisonBatchId(),c.lease(),
                    new WorkerCharacterFactComparisonBatchCompleteRequest(c.contextToken(), List.of(
                            new WorkerCharacterFactComparisonBatchCompleteRequest.Decision("C1",CharacterFactOperation.EXCLUDE,
                                    "level",null,List.of(),List.of(),null,null,CharacterFactTemporalScope.PRESENT,"동일 설정의 새 관측",Map.of(),null,true)),List.of(),Map.of()));
        } else {
            comparisons.completeCharacterFactComparison(c.jobId(),c.candidateId(),c.lease(),
                    new WorkerCharacterFactComparisonCompleteRequest(CharacterFactOperation.EXCLUDE,null,null,null,null,List.of(),
                            CharacterFactTemporalScope.PRESENT,"동일 설정의 새 관측",c.contextToken(),Map.of(),null,true));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false,true})
    @DisplayName("이전 회차 singleton/batch EXCLUDE의 실제 시점 변경은 검수 접수와 직렬화되고 옛 기준을 파기한다")
    void priorExcludeTimeChangeWaitsForAdmissionAndInvalidatesBaseline(boolean batch) throws Exception {
        var f = fixture(); var c = priorComparison(f,batch);
        var captured = new CountDownLatch(1); var release = new CountDownLatch(1); var callbackStarted = new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var admission = pool.submit(() -> tx.execute(status -> {
                var r = start(f); assertThat(r.baselineStatus().name()).isEqualTo("READY");
                assertThat(reports.findById(r.reportId()).orElseThrow().getBaselineSeed().toString()).doesNotContain("관측한 새 시점");
                em.flush(); captured.countDown(); await(release); return r;
            }));
            await(captured);
            var completion = pool.submit(() -> { callbackStarted.countDown(); exclude(c,batch); });
            boolean waitedForWork;
            boolean jobUnlocked;
            try {
                await(callbackStarted);
                try { completion.get(350,TimeUnit.MILLISECONDS); waitedForWork = false; }
                catch (TimeoutException expected) { waitedForWork = true; }
                // The callback must wait on Work before taking Job; admission/canonical capture use that order.
                try {
                    tx.executeWithoutResult(status -> assertThat(em.createNativeQuery("select id from analysis_jobs where id = :id for update nowait")
                            .setParameter("id",c.jobId()).getSingleResult()).isEqualTo(c.jobId()));
                    jobUnlocked = true;
                } catch (jakarta.persistence.LockTimeoutException expected) { jobUnlocked = false; }
            } finally { release.countDown(); }
            var r=admission.get(20,TimeUnit.SECONDS); completion.get(20,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select current_narrative_time::text from characters where id=?",String.class,c.characterId())).contains("관측한 새 시점");
            final boolean serialized = waitedForWork;
            final boolean workFirst = jobUnlocked;
            org.junit.jupiter.api.Assertions.assertAll(
                    () -> assertThat(serialized).as("completion waits for Work").isTrue(),
                    () -> assertThat(workFirst).as("waiting completion has not locked Job").isTrue(),
                    () -> assertPurged(r.reportId()));
            assertThat(jdbc.queryForObject("select count(*) from analysis_jobs where episode_id=?",Integer.class,f.episodeId())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from setting_candidates where episode_id=?",Integer.class,f.episodeId())).isZero();
        }
    }

    @Test @DisplayName("작품과 회원 파기는 추출 Job 없는 새 원장을 FK 오류 없이 제거한다")
    void workAndMemberDeletionRemoveReportAndSources() {
        var f=fixture();start(f);
        tx.executeWithoutResult(status -> {works.getOwnedWorkForUpdate(f.workId(),f.memberId());purge.purgeWorkData(f.workId());em.clear();});
        tx.executeWithoutResult(status -> em.remove(em.find(Member.class,f.memberId())));
        for(String table:List.of("validation_reports","validation_request_keys","validation_baseline_sources","members","works"))assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).as(table).isZero();
    }
}
