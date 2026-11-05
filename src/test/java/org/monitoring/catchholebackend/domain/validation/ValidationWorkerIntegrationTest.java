package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** All numeric limits here are synthetic fixtures, not production defaults. */
@SpringBootTest(properties={"spring.config.import=","spring.jpa.properties.hibernate.generate_statistics=true"}) @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("검수 전용 API와 불변 입력·독립 분기·원장")
class ValidationWorkerIntegrationTest extends ValidationWorkerFixture {
    @org.springframework.beans.factory.annotation.Autowired org.monitoring.catchholebackend.domain.upload.service.SettingBookService settingBooks;
    @org.springframework.beans.factory.annotation.Autowired org.monitoring.catchholebackend.domain.validation.service.ValidationDataService data;
    @Test @DisplayName("실제 전용 Job과 서버 소유 scope를 선점하고 추출은 만들지 않는다") void claimCreatesDedicatedOwnedJobAndNoExtraction() throws Exception {
        var report=start();var job=claim();
        assertThat(job.path("jobId").isTextual()).isTrue();assertThat(job.path("scope").path("reportId")).isEqualTo(report.path("reportId"));
        assertThat(job.path("memberId").asLong()).isEqualTo(member.getId());assertThat(job.path("workId").asText()).isEqualTo(work.getId().toString());
        // #349 configuration v2: per-role model/effort/output cap from the explicit configuration; no retired roles; projection 2 without clues.
        assertThat(job.path("configurationVersion").asInt()).isEqualTo(2);assertThat(job.path("projectionVersion").asInt()).isEqualTo(2);assertThat(job.has("clueVersion")).isFalse();
        assertThat(job.path("models").path("JUDGE").path("model").asText()).isEqualTo("gpt-6-luna");assertThat(job.path("models").path("JUDGE").path("reasoningEffort").asText()).isEqualTo("high");
        assertThat(job.path("models").path("PLANNER").path("reasoningEffort").asText()).isEqualTo("medium");assertThat(job.path("models").path("TYPO").path("reasoningEffort").asText()).isEqualTo("low");
        assertThat(job.path("models").has("RELATED_SELECTION")).isFalse();assertThat(job.path("models").has("COVERAGE")).isFalse();
        assertThat(job.path("budgets").path("roles").path("JUDGE").path("outputTokens").asInt()).isEqualTo(100);assertThat(job.path("budgets").path("setting").has("outputTokens")).isFalse();
        assertThat(job.path("budgets").path("setting").has("cataloguePageSize")).isFalse();
        assertThat(claim().isNull()).isTrue();noExtraction();
    }
    @Test @DisplayName("#381 seed 없는 report도 저장 manifest 행의 hash로 queue되고 claim·완료된다")
    void seedlessReportQueuesClaimsAndCompletes() throws Exception {
        source();var report=stored(start());
        assertThat(report.getBaselineSeed()).isNull();assertThat(report.getBaselineEntries()).isNull();
        var queued=em.createQuery("select j from ValidationJob j where j.report.id=:r",org.monitoring.catchholebackend.domain.validation.entity.ValidationJob.class).setParameter("r",report.getId()).getSingleResult();
        assertThat(queued.getManifestHash()).isEqualTo(journal.hash(sourceManifests.manifests(report.getId())));
        var job=claim();
        assertThat(job.path("baselineHash").asText()).isEqualTo(report.getBaselineHash());assertThat(job.path("manifestHash").asText()).isEqualTo(queued.getManifestHash());
        var input=data.readValidationData(member.getId(),work.getId(),report.getId(),new org.monitoring.catchholebackend.domain.validation.dto.request.ValidationDataRequest(report.getBaselineHash(),report.getSourceContentHash(),"INPUT",0,100,null,null,null,null,null,null),
                UUID.fromString(job.path("jobId").asText()),UUID.fromString(job.path("leaseToken").asText()));
        assertThat(input.path("manifestHash").asText()).isEqualTo(queued.getManifestHash());assertThat(input.path("sources").get(0).path("sourceRef").asText()).isEqualTo("R1");
        assertThat(input.path("target").path("contentHash").asText()).isEqualTo(target.getContentHash());assertThat(input.path("entryCount").asInt()).isEqualTo(2);
        prePlan(job,List.of());savePlan(job,"MERGED_PLAN","PLAN",Map.of("questions",List.of()));
        internal(path(job,"/units"),job,unit(job,"TYPO","TSEG1","COMPLETED",typoEmpty()),200);
        var result=internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","seedless-complete"),200);
        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");assertThat(result.path("extractionJobId").asText()).isEqualTo(releaseOnly().toString());
        assertThat(stored(start()).getBaselineSeed()).isNull();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={0,30})
    @DisplayName("#381 실행이 켜진 접수(queue·기준 검증 포함)도 설정·후보·Fact·분석 Job을 읽지 않고 조회 수가 확정 설정 크기와 무관하다")
    void enabledIntakeWithQueueDoesNotDependOnSettingsSize(int subjects) throws Exception {
        var priorJob=org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.create(work,null,prior,org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION);em.persist(priorJob);
        for(int i=0;i<subjects;i++) {
            var owner=org.monitoring.catchholebackend.domain.character.entity.WorkCharacter.create(work,"인물"+i,null,20,1,null,null,null,null,null,null);em.persist(owner);
            var c=org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.create(work,prior,null,priorJob,org.monitoring.catchholebackend.domain.character.type.SettingEntityType.CHARACTER,owner.getName(),owner.getName(),owner.getId(),
                    org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.MATCHED,"직업","수리공"+i,org.monitoring.catchholebackend.domain.character.type.SettingValueType.STRING,null,
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode(),java.math.BigDecimal.ONE,null);em.persist(c);
            var fact=org.monitoring.catchholebackend.domain.character.entity.CharacterFact.create(owner,c,org.monitoring.catchholebackend.domain.character.type.CharacterFactType.PROFILE,"직업","수리공"+i,"수리공"+i,null,prior,null,priorJob,java.math.BigDecimal.ONE,12);em.persist(fact);
            em.persist(org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource.create(owner,org.monitoring.catchholebackend.domain.character.type.CharacterFactType.PROFILE,"직업",fact,0));
            em.persist(org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.create(work,org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory.LOCATION,"장소"+i,"위치","북부"));
        }
        em.flush();em.clear();
        var statistics=em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();statistics.clear();
        start();
        for(Class<?> forbidden:List.of(org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob.class,org.monitoring.catchholebackend.domain.character.entity.SettingCandidate.class,
                org.monitoring.catchholebackend.domain.character.entity.CharacterFact.class,org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource.class,
                org.monitoring.catchholebackend.domain.character.entity.WorkCharacter.class,org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.class))
            assertThat(statistics.getEntityStatistics(forbidden.getName()).getLoadCount()).as(forbidden.getSimpleName()).isZero();
        // 접수(SQL 29번 수준) + queue: 현재 입력 확인 2번(각각 fingerprint scalar 2 포함)·기준 검증(fingerprint 2·manifest 1·원고/출처 현재 상태 scalar)·manifest hash·실행 설정·Job insert.
        // 인물·세계관·후보 0개와 30개가 같은 53번, 엔티티 로드 6개(Member·Work·UploadBatch·UploadFile·Episode 2)다.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(QUEUED_INTAKE_STATEMENTS);
        assertThat(statistics.getEntityLoadCount()).isEqualTo(QUEUED_INTAKE_ENTITY_LOADS);
    }
    static final long QUEUED_INTAKE_STATEMENTS=53,QUEUED_INTAKE_ENTITY_LOADS=6;
    @Test @DisplayName("#381 실행 중 잠금을 우회해 확정 설정 version을 바꾸면 heartbeat·자료 읽기·게시가 409로 멈추고 대기 Job은 claim 때 취소된다")
    void bypassingSettingsWriteStopsTheRun() throws Exception {
        source();var report=stored(start());var job=claim();
        internal(path(job,"/heartbeat"),job,Map.of("scope",scope(job)),200);
        em.createNativeQuery("update world_settings set version = version + 1 where work_id = :w").setParameter("w",work.getId()).executeUpdate();
        assertThat(internal(path(job,"/heartbeat"),job,Map.of("scope",scope(job)),409).path("code").asText()).isEqualTo("VALIDATION_SOURCE_VERSION_CONFLICT");
        assertThatThrownBy(()->data.readValidationData(member.getId(),work.getId(),report.getId(),new org.monitoring.catchholebackend.domain.validation.dto.request.ValidationDataRequest(report.getBaselineHash(),report.getSourceContentHash(),"SUBJECTS",0,100,null,null,null,null,null,null),
                UUID.fromString(job.path("jobId").asText()),UUID.fromString(job.path("leaseToken").asText())))
                .isInstanceOf(org.monitoring.catchholebackend.global.exception.AppException.class).hasFieldOrPropertyWithValue("resultCode",org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode.VALIDATION_SOURCE_VERSION_CONFLICT);
        assertThat(internal(path(job,"/plans"),job,publication(job,"SETTING","PLANNER","TSEG1",batch("TSEG1",List.of())),409).path("code").asText()).isEqualTo("VALIDATION_SOURCE_VERSION_CONFLICT");
        noExtraction();
    }
    @Test @DisplayName("#381 legacy seed report는 저장 seed 검증으로 queue되고 manifest hash는 seed.sources와 같으며 저장 기준 항목으로 자료를 읽는다")
    void legacySeedReportIsStillServed() throws Exception {
        source();
        var book=org.monitoring.catchholebackend.domain.upload.entity.UploadFile.create(em.find(org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.class,batchId),org.monitoring.catchholebackend.domain.upload.type.UploadFileRole.SETTING_BOOK,"book.txt","text/plain","s3://synthetic/book",30);em.persist(book);
        for(int no=1;no<=2;no++)em.persist(org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(book,no,new org.monitoring.catchholebackend.global.storage.StoredTextObject("synthetic/book-"+no,"v1",String.valueOf(no).repeat(64),3),org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind.WORK_START,null));
        em.flush();
        org.mockito.Mockito.when(executionProperties.enabled()).thenReturn(false);var original=start();var report=toLegacySeed(stored(original));
        org.mockito.Mockito.when(executionProperties.enabled()).thenReturn(true);start();
        var queued=em.createQuery("select j from ValidationJob j where j.report.id=:r",org.monitoring.catchholebackend.domain.validation.entity.ValidationJob.class).setParameter("r",report.getId()).getSingleResult();
        assertThat(queued.getManifestHash()).isEqualTo(journal.hash(report.getBaselineSeed().path("sources")));
        var job=claim();assertThat(job.path("baselineHash").asText()).isEqualTo(journal.hash(report.getBaselineSeed()));
        var lease=UUID.fromString(job.path("leaseToken").asText());var jobId=UUID.fromString(job.path("jobId").asText());
        java.util.function.Function<String,JsonNode> read=operation->data.readValidationData(member.getId(),work.getId(),report.getId(),new org.monitoring.catchholebackend.domain.validation.dto.request.ValidationDataRequest(report.getBaselineHash(),report.getSourceContentHash(),operation,0,100,null,null,null,null,null,"SETTINGS".equals(operation)?List.of("S1"):null),jobId,lease);
        var input=read.apply("INPUT");
        assertThat(input.path("manifestHash").asText()).isEqualTo(queued.getManifestHash());
        var seedRefs=new ArrayList<String>();report.getBaselineSeed().path("sources").forEach(s->seedRefs.add(s.toString()));
        var inputRefs=new ArrayList<String>();input.path("sources").forEach(s->{var copy=s.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)copy).remove("sourceRef");inputRefs.add(copy.toString());});
        assertThat(inputRefs).isEqualTo(seedRefs).hasSize(3);
        assertThat(input.path("entryCount").asInt()).isEqualTo(report.getBaselineEntries().size());
        assertThat(read.apply("SUBJECTS").path("subjects").get(0).path("name").asText()).isEqualTo("합성 인물");
        assertThat(read.apply("SETTINGS").path("settings").get(0).path("state").path("name").asText()).isEqualTo("합성 인물");
    }
    @Test @DisplayName("전용 Worker 경로는 내부 key 인증을 요구한다") void workerRoutesRequireInternalKey() throws Exception {
        mvc.perform(post(WORKER+"/claim").contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isUnauthorized());noExtraction();
    }
    @Test @DisplayName("다른 lease와 report scope의 쓰기를 거절한다") void wrongLeaseAndImmutableScopeRejected() throws Exception {
        start();var job=claim();var wrong=job.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)wrong).put("leaseToken",UUID.randomUUID().toString());
        assertThat(internal(path(job,"/heartbeat"),wrong,Map.of("scope",scope(job)),409).path("code").asText()).isEqualTo("VALIDATION_LEASE_CONFLICT");
        var scope=scope(job);scope.put("reportId",UUID.randomUUID());
        internal(path(job,"/heartbeat"),job,Map.of("scope",scope),409);noExtraction();
    }
    @Test @DisplayName("실행 Job의 자료 읽기는 unleased 우회를 허용하지 않는다") void runningJobCannotUseUnleasedDataReader() throws Exception {
        var report=start();var job=claim();
        var request=Map.of("expectedBaselineHash",report.path("baselineHash").asText(),"expectedTargetHash",target.getContentHash(),"operation","INPUT","offset",0,"limit",100);
        mvc.perform(post("/api/internal/v1/works/"+work.getId()+"/validation-reports/"+report.path("reportId").asText()+"/data")
                .header("X-Internal-Api-Key",KEY).header("X-Validation-Member-Id",member.getId()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isConflict());noExtraction();
    }
    @Test @DisplayName("만료 lease는 연장이나 새 호출을 예약하지 못한다") void expiredLeaseCannotHeartbeatOrReserve() throws Exception {
        start();var job=claim();em.createNativeQuery("update validation_jobs set lease_expires_at=CURRENT_TIMESTAMP - interval '1' second").executeUpdate();em.clear();
        internal(path(job,"/heartbeat"),job,Map.of("scope",scope(job)),409);
        internal(path(job,"/usage/reserve"),job,Map.of("scope",scope(job),"requestId",UUID.randomUUID(),"role","PLANNER","model","gpt-6.1-terra","reservedTokens",100),409);noExtraction();
    }
    @Test @DisplayName("동일 단계의 재전송은 원 receipt와 의미 version을 유지한다") void savedStagesHaveExactReplayAndStableCheckpoint() throws Exception {
        source();start();var job=claim();var input=publication(job,"SETTING","PLANNER","TSEG1",Map.of("questions",List.of(),"segments",List.of(Map.of("segment_ref","TSEG1","status","NO_CHECK","reason","합성 완료"))));
        var first=internal(path(job,"/plans"),job,input,200);var replay=internal(path(job,"/plans"),job,input,200);assertThat(replay).isEqualTo(first);
        var before=get(UUID.fromString(job.path("scope").path("reportId").asText()));input.put("idempotencyKey","same-stage-new-receipt");internal(path(job,"/plans"),job,input,200);
        assertThat(get(UUID.fromString(job.path("scope").path("reportId").asText())).path("reportVersion")).isEqualTo(before.path("reportVersion"));
        input.put("payload",Map.of("changed",true));internal(path(job,"/plans"),job,input,409);
        var checkpoint=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(WORKER+path(job,"/checkpoint")).header("X-Internal-Api-Key",KEY).header("X-Worker-Lease-Token",job.path("leaseToken").asText())).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(checkpoint.getResponse().getContentAsString()).path("data").path("stages").size()).isEqualTo(1);noExtraction();
    }
    @Test @DisplayName("finding 0개만으로 처리 누락을 완료시키지 않는다") void emptyFindingsDoNotCompleteMissingCoverage() throws Exception {
        start();var job=claim();var done=internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","stop","failureCode","MODEL_CALL_FAILED"),200);
        assertThat(done.path("status").asText()).isEqualTo("FAILED");noExtraction();
    }
    @Test @DisplayName("같은 입력 재시도는 report를 보존하고 실행 세대만 바꾼다") void retrySameInputPreservesReportAndAdvancesGeneration() throws Exception {
        var report=start();var job=claim();internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","stop","failureCode","MODEL_CALL_FAILED"),200);
        var current=get(UUID.fromString(report.path("reportId").asText()));
        var retried=postOk("/"+report.path("reportId").asText()+"/retries",Map.of("expectedReportVersion",current.path("reportVersion").asLong(),"idempotencyKey","retry-same"));
        assertThat(retried.path("reportId")).isEqualTo(report.path("reportId"));var next=claim();assertThat(next.path("scope").path("generation").asLong()).isEqualTo(job.path("scope").path("generation").asLong()+1);
        internal(path(job,"/heartbeat"),job,Map.of("scope",scope(job)),409);noExtraction();
    }
    @Test @DisplayName("원문 변경은 checkpoint를 지우고 늦은 게시를 거절한다") void sourceInvalidationErasesCheckpointAndRejectsLatePublish() throws Exception {
        source();var report=start();var job=claim();
        var input=publication(job,"SETTING","PLANNER","TSEG1",Map.of("questions",List.of(),"segments",List.of(Map.of("segment_ref","TSEG1","status","NO_CHECK","reason","합성 완료"))));
        internal(path(job,"/plans"),job,input,200);replace("수정 원고");internal(path(job,"/plans"),job,input,409);
        assertThat(em.createNativeQuery("select count(*) from validation_stages").getSingleResult()).isEqualTo(0L);noExtraction();
    }
    @Test @DisplayName("양쪽 필수 처리가 저장되어야 정상 0건을 완료한다") void onlyBothDurableBranchesCompleteAndEmptyFindingsStayZero() throws Exception {
        source();start();var job=claim();prePlan(job,List.of());savePlan(job,"MERGED_PLAN","PLAN",Map.of("questions",List.of()));
        var settingOnly=internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","stop-setting"),200);assertThat(settingOnly.path("status").asText()).isEqualTo("PARTIAL");
        var report=get(UUID.fromString(job.path("scope").path("reportId").asText()));postOk("/"+report.path("reportId").asText()+"/retries",Map.of("expectedReportVersion",report.path("reportVersion").asLong(),"idempotencyKey","both-retry"));
        var next=claim();assertThat(next.path("checkpoint").path("stages").size()).isEqualTo(2);
        internal(path(next,"/units"),next,unit(next,"TYPO","TSEG1","COMPLETED",typoEmpty()),200);
        var finish=Map.of("scope",scope(next),"idempotencyKey","both-complete");var result=internal(path(next,"/complete"),next,finish,200);
        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");assertThat(result.path("completedChecks").asInt()).isZero();assertThat(result.path("findings").size()).isZero();
        // GH312 S2: 정상 0건 완료는 같은 트랜잭션에서 검토 종료 receipt와 release Job 하나를 만들고 재전송은 같은 receipt다.
        assertThat(result.path("extractionJobId").asText()).isEqualTo(releaseOnly().toString());
        assertThat(internal(path(next,"/complete"),next,finish,200)).isEqualTo(result);releaseOnly();
    }
    @Test @DisplayName("정상 질문도 안정 ID·완료 bundle·새 revision을 보존한다") void normalNoConflictDecisionHasStableServerCheckAndRevisionChangesAreAtomic() throws Exception {
        source();start();var job=claim();
        var span=Map.of("source_ref","T1","start",0,"end",2,"quote","처음");
        var citation=Map.of("sourceRef","T1","sourceHash",target.getContentHash(),"sourceVersion",target.getContentS3Version(),"start",0,"end",2,"quote","처음");
        var draft=draft("local-1",List.of(span));
        var planner=publication(job,"SETTING","PLANNER","TSEG1",batch("TSEG1",List.of(draft)));planner.put("citations",List.of(citation));internal(path(job,"/plans"),job,planner,200);
        var plan=publication(job,"SETTING","MERGED_PLAN","PLAN",Map.of("questions",List.of(Map.of("localRef","Q1","originalRefs",List.of("PLANNER:TSEG1:local-1"),"state",Map.of("draft",draft)))));plan.put("citations",List.of(citation));
        var saved=internal(path(job,"/plans"),job,plan,200);var check=saved.path("checks").path(0);assertThat(check.path("checkId").isTextual()).isTrue();
        var assessment=Map.of("reason","실제 확인","evidence_refs",List.of("T1"),"receipt_refs",List.of("L1","L2"));
        var decision=new LinkedHashMap<String,Object>(Map.of("action","DECIDED","outcome","NO_CONFLICT","reason","같은 문맥에서 정상","target_spans",List.of(span),"completion",Map.of("comparison_context",assessment,"changes_exceptions",assessment,"no_pending_clues",assessment,"required_reads",assessment),"follow_ups",List.of(),"affected_question_refs",List.of()));
        var missingBundle=unit(job,"SETTING","Q1","NO_CONFLICT",decision);missingBundle.put("idempotencyKey","missing-bundle");missingBundle.put("checkId",check.path("checkId").asText());missingBundle.put("expectedCheckRevision",1);missingBundle.put("checkInputDigest",check.path("checkInputDigest").asText());missingBundle.put("citations",List.of(citation));
        internal(path(job,"/units"),job,missingBundle,400);
        decision.put("basis","COMPATIBLE");decision.put("evidence_refs",List.of("T1"));decision.put("disagreements",List.of());decision.put("evidence_bundle",evidenceBundle(draft));
        var contextCitation=Map.of("sourceRef","T1","sourceHash",target.getContentHash(),"sourceVersion",target.getContentS3Version(),"start",0,"end",8,"quote","처음 합성 원고");
        var publish=unit(job,"SETTING","Q1","NO_CONFLICT",decision);publish.put("checkId",check.path("checkId").asText());publish.put("expectedCheckRevision",1);publish.put("checkInputDigest",check.path("checkInputDigest").asText());publish.put("citations",List.of(citation,contextCitation));
        internal(path(job,"/units"),job,publish,200);
        var progress=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base()+"/"+job.path("scope").path("reportId").asText()+"/results").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(progress).doesNotContain("evidence_bundle","lookup_intents","leaseToken","checkInputDigest","catalogue");noExtraction();
        internal(path(job,"/checkpoint"),job,Map.of("scope",scope(job),"idempotencyKey","before-revision-cp","branch","SETTING","expectedVersion",0,"payload",Map.of("phase","JUDGING","savedReceipt","authenticated-original"),"citations",List.of()),200);
        var changes=new ArrayList<Object>();changes.add(Map.of("checkId",check.path("checkId").asText(),"expectedRevision",1,"expectedInputDigest",check.path("checkInputDigest").asText(),"state",Map.of("draft",draft,"lookup_obligations",List.of(lookup("NEW-1","NEW_CLUE","TARGET_CONTEXT")),"new_clue","같은 속성의 새 단서")));
        var revised=internal(path(job,"/revisions"),job,Map.of("scope",scope(job),"idempotencyKey","follow-up","reason","NEW_CLUE","changes",changes,"citations",List.of(citation)),200);
        assertThat(revised.path("checks").path(0).path("revision").asLong()).isEqualTo(2);assertThat(revised.path("checks").path(0).path("outcome").isNull()).isTrue();
        var resumed=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(WORKER+path(job,"/checkpoint")).header("X-Internal-Api-Key",KEY).header("X-Worker-Lease-Token",job.path("leaseToken").asText())).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(resumed.getResponse().getContentAsString()).at("/data/branches/SETTING/payload/savedReceipt").asText()).isEqualTo("authenticated-original");
        internal(path(job,"/units"),job,publish,409);
        var old=new LinkedHashMap<>(publish);old.put("idempotencyKey","late-old-revision");internal(path(job,"/units"),job,old,409);
        assertThat(em.createNativeQuery("select count(*) from validation_check_history").getSingleResult()).isEqualTo(1L);
    }
    @Test @DisplayName("두 분기의 checkpoint CAS와 heartbeat의 공개 version을 분리한다") void independentBranchCheckpointsHaveSeparateCasAndNoSharedReportVersion() throws Exception {
        source();var report=start();var job=claim();
        for(String branch:List.of("SETTING","TYPO"))internal(path(job,"/checkpoint"),job,Map.of("scope",scope(job),"idempotencyKey","checkpoint-"+branch,"branch",branch,"expectedVersion",0,"payload",Map.of("validated_stage","saved"),"citations",List.of()),200);
        internal(path(job,"/checkpoint"),job,Map.of("scope",scope(job),"idempotencyKey","stale-cp","branch","SETTING","expectedVersion",0,"payload",Map.of(),"citations",List.of()),409);
        var before=get(UUID.fromString(report.path("reportId").asText()));internal(path(job,"/heartbeat"),job,Map.of("scope",scope(job)),200);
        assertThat(get(UUID.fromString(report.path("reportId").asText())).path("reportVersion")).isEqualTo(before.path("reportVersion"));noExtraction();
    }
    @Test @DisplayName("실제 검수 원장은 UNKNOWN·실패·늦은 사용량을 정확히 정산한다") void realValidationLedgerSettlesFailureUnknownAndLateLeaseUsageExactly() throws Exception {
        start();var job=claim();UUID request=UUID.randomUUID();
        var reserve=Map.of("scope",scope(job),"requestId",request,"role","PLANNER","model","gpt-6.1-terra","reservedTokens",100);
        var first=internal(path(job,"/usage/reserve"),job,reserve,200);assertThat(internal(path(job,"/usage/reserve"),job,reserve,200)).isEqualTo(first);
        var conflict=new LinkedHashMap<String,Object>(reserve);conflict.put("reservedTokens",101);internal(path(job,"/usage/reserve"),job,conflict,409);
        assertThat(em.createNativeQuery("select count(*) from ai_token_usages where validation_job_id is not null and analysis_job_id is null and chat_run_id is null").getSingleResult()).isEqualTo(1L);
        var unknown=Map.of("scope",scope(job),"kind","UNKNOWN");internal(path(job,"/usage/"+request),job,unknown,200);
        replace("수정 원고");
        var settlement=Map.of("scope",scope(job),"kind","SETTLE","inputTokens",25,"cachedInputTokens",5,"outputTokens",7,"outcome","FAILURE");
        assertThat(internal(path(job,"/usage/"+request),job,settlement,200).path("status").asText()).isEqualTo("SETTLED");internal(path(job,"/usage/"+request),job,settlement,200);
        assertThat(((java.math.BigDecimal)em.createNativeQuery("select used_tokens from ai_token_accounts where member_id=:m").setParameter("m",member.getId()).getSingleResult())).isEqualByComparingTo("32");
        var other=new LinkedHashMap<String,Object>(settlement);other.put("inputTokens",26);internal(path(job,"/usage/"+request),job,other,409);noExtraction();
    }
    @Test @DisplayName("실제 계정 잔액과 고정 모델을 검증하고 무효 실행의 새 예약을 막는다") void quotaAndModelLimitsAreRealAndNewReservationsStopAfterInvalidation() throws Exception {
        start();var job=claim();var request=new LinkedHashMap<String,Object>(Map.of("scope",scope(job),"requestId",UUID.randomUUID(),"role","PLANNER","model","gpt-6.1-terra","reservedTokens",100));
        request.put("model","gpt-6-luna");internal(path(job,"/usage/reserve"),job,request,400);request.put("model","gpt-6.1-terra");
        em.createNativeQuery("update ai_token_accounts set granted_tokens=0 where member_id=:m").setParameter("m",member.getId()).executeUpdate();em.clear();
        // Force creation, then the genuine account lock is authoritative for every call.
        internal(path(job,"/usage/reserve"),job,request,200);em.flush();em.createNativeQuery("update ai_token_accounts set granted_tokens=used_tokens+reserved_tokens where member_id=:m").setParameter("m",member.getId()).executeUpdate();em.clear();
        request.put("requestId",UUID.randomUUID());internal(path(job,"/usage/reserve"),job,request,409);replace("새 입력");internal(path(job,"/usage/reserve"),job,request,409);noExtraction();
    }
    @Test @DisplayName("질문을 낸 계획만 성공한 종료는 FAILED이며 저장 계획은 남긴다") void plannerOnlySuccessIsFailedAndItsSavedPlanRemains() throws Exception {
        source();start();var job=claim();
        var span=Map.of("source_ref","T1","start",0,"end",2,"quote","처음");
        var planner=publication(job,"SETTING","PLANNER","TSEG1",batch("TSEG1",List.of(draft("local-1",List.of(span)))));
        planner.put("citations",List.of(Map.of("sourceRef","T1","sourceHash",target.getContentHash(),"sourceVersion",target.getContentS3Version(),"start",0,"end",2,"quote","처음")));internal(path(job,"/plans"),job,planner,200);
        var finish=internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","planner-only","failureCode","MODEL_CALL_FAILED"),200);
        assertThat(finish.path("status").asText()).isEqualTo("FAILED");assertThat(finish.path("coverage").path("completedStages").asInt()).isEqualTo(1);noExtraction();
    }
    void targetText(String text) throws Exception {
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        target.updateContentStorage(target.getContentS3Key(),target.getContentS3Version(),hash);org.springframework.test.util.ReflectionTestUtils.setField(target,"validationUploadEligible",true);em.flush();
        when(storage.getText(anyString())).thenReturn(text);
    }
    @Test @DisplayName("grid v4의 질문 없는 NO_CHECK 계획 구간은 그 자체로 완료된 처리라 부분 성공으로 센다") void plannerNoCheckSegmentIsSuccessfulProcessing() throws Exception {
        source();start();var job=claim();savePlan(job,"PLANNER","TSEG1",batch("TSEG1",List.of()));
        var finish=internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","no-check-only","failureCode","MODEL_CALL_FAILED"),200);
        assertThat(finish.path("status").asText()).isEqualTo("PARTIAL");assertThat(finish.path("completedNoCheckSegments").asInt()).isEqualTo(1);noExtraction();
    }
    @Test @DisplayName("모든 필수 점검이 끝난 무질문 구간은 부분 성공으로 센다") void fullyCoveredNoCheckSegmentCountsBeforeAnotherSegmentFails() throws Exception {
        targetText("가".repeat(105));start();var job=claim();prePlan(job,List.of());
        var finish=internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","one-no-check","failureCode","MODEL_CALL_FAILED"),200);
        assertThat(finish.path("status").asText()).isEqualTo("PARTIAL");assertThat(finish.path("completedNoCheckSegments").asInt()).isEqualTo(1);noExtraction();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"T1","R1"})
    @DisplayName("실제 원문 인용이 맞아도 현재 회차만의 충돌은 거절하고 과거 출처 충돌은 저장한다")
    void conflictPublicationRequiresActualNonTargetEvidence(String sourceRef) throws Exception {
        source();String priorHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("합성 앞 원고".getBytes(java.nio.charset.StandardCharsets.UTF_8)));prior.updateContentStorage(prior.getContentS3Key(),prior.getContentS3Version(),priorHash);em.flush();start();var job=claim();
        var span=Map.of("source_ref","T1","start",0,"end",2,"quote","처음");var draft=draft("local-1",List.of(span));var citation=Map.of("sourceRef","T1","sourceHash",target.getContentHash(),"sourceVersion",target.getContentS3Version(),"start",0,"end",2,"quote","처음");
        var planner=publication(job,"SETTING","PLANNER","TSEG1",batch("TSEG1",List.of(draft)));planner.put("citations",List.of(citation));internal(path(job,"/plans"),job,planner,200);
        var plan=publication(job,"SETTING","MERGED_PLAN","PLAN",Map.of("questions",List.of(Map.of("localRef","Q1","originalRefs",List.of("PLANNER:TSEG1:local-1"),"state",Map.of("draft",draft)))));plan.put("citations",List.of(citation));var check=internal(path(job,"/plans"),job,plan,200).path("checks").path(0);
        var bundle=evidenceBundle(draft);String quote=sourceRef.equals("T1")?"처음":"합성";bundle.put("evidence",List.of(Map.of("ref","E1","source_ref",sourceRef,"source_kind","EPISODE","start",0,"end",2,"quote",quote)));
        var assessment=Map.of("reason","실제 고정 자료 확인","evidence_refs",List.of("T1","E1"),"receipt_refs",List.of("L1","L2"));
        var decision=new LinkedHashMap<String,Object>();decision.put("action","DECIDED");decision.put("outcome","CONFLICT");decision.put("basis","DIRECT_CONTRADICTION");decision.put("reason","합성 충돌 판정");decision.put("impact","CAUTION");decision.put("impact_reason","비교값 차이");decision.put("target_spans",List.of(span));decision.put("evidence_refs",List.of("T1","E1"));decision.put("completion",Map.of("comparison_context",assessment,"changes_exceptions",assessment,"no_pending_clues",assessment,"required_reads",assessment));decision.put("follow_ups",List.of());decision.put("affected_question_refs",List.of());decision.put("disagreements",List.of());decision.put("evidence_bundle",bundle);
        var citations=new ArrayList<Object>();citations.add(citation);citations.add(Map.of("sourceRef","T1","sourceHash",target.getContentHash(),"sourceVersion",target.getContentS3Version(),"start",0,"end",8,"quote","처음 합성 원고"));if(sourceRef.equals("R1"))citations.add(Map.of("sourceRef","R1","sourceHash",priorHash,"sourceVersion",prior.getContentS3Version(),"start",0,"end",2,"quote",quote));
        var command=unit(job,"SETTING","Q1","CONFLICT",decision);command.put("checkId",check.path("checkId").asText());command.put("expectedCheckRevision",1);command.put("checkInputDigest",check.path("checkInputDigest").asText());command.put("citations",citations);
        var before=get(UUID.fromString(job.path("scope").path("reportId").asText()));var result=internal(path(job,"/units"),job,command,sourceRef.equals("T1")?400:200);
        assertThat(em.createNativeQuery("select count(*) from validation_findings").getSingleResult()).isEqualTo(sourceRef.equals("T1")?0L:1L);
        if(sourceRef.equals("T1")) {assertThat(result.path("code").asText()).isEqualTo("VALIDATION_RESULT_INVALID");assertThat(get(UUID.fromString(job.path("scope").path("reportId").asText())).path("reportVersion")).isEqualTo(before.path("reportVersion"));}else assertThat(result.path("outcome").asText()).isEqualTo("CONFLICT");noExtraction();
    }
    @Test @DisplayName("반복 비BMP 인용은 정확한 code point 위치만 허용한다") void unicodeCodePointQuotesKeepRepeatedPositionsAndRejectUtf16Offsets() throws Exception {
        targetText("😀가😀가");start();var job=claim();
        var citation=Map.of("sourceRef","T1","sourceHash",target.getContentHash(),"sourceVersion",target.getContentS3Version(),"start",2,"end",3,"quote","😀");
        var body=new LinkedHashMap<String,Object>(Map.of("scope",scope(job),"idempotencyKey","unicode","branch","SETTING","expectedVersion",0,
                "payload",Map.of("evidence",List.of(Map.of("source_ref","T1","start",2,"end",3,"quote","😀"))),"citations",List.of(citation)));
        internal(path(job,"/checkpoint"),job,body,200);
        var bad=new LinkedHashMap<String,Object>(citation);bad.put("start",1);bad.put("end",2);
        body.put("idempotencyKey","utf16-offset");body.put("expectedVersion",1);body.put("citations",List.of(bad));internal(path(job,"/checkpoint"),job,body,400);noExtraction();
    }
    @Test @DisplayName("확실한 미호출 해제와 사용량 미상의 예산 의미를 구분한다") void knownNoCallReleaseReturnsRoleCapacityButUnknownDoesNot() throws Exception {
        start();var job=claim();UUID request=UUID.randomUUID();
        internal(path(job,"/usage/reserve"),job,Map.of("scope",scope(job),"requestId",request,"role","PLANNER","model","gpt-6.1-terra","reservedTokens",100),200);
        assertThat(internal(path(job,"/usage/"+request),job,Map.of("scope",scope(job),"kind","RELEASE"),200).path("status").asText()).isEqualTo("RELEASED");
        for(int i=0;i<20;i++) {
            UUID call=UUID.randomUUID();internal(path(job,"/usage/reserve"),job,Map.of("scope",scope(job),"requestId",call,"role","PLANNER","model","gpt-6.1-terra","reservedTokens",100),200);
            internal(path(job,"/usage/"+call),job,Map.of("scope",scope(job),"kind","UNKNOWN"),200);
        }
        internal(path(job,"/usage/reserve"),job,Map.of("scope",scope(job),"requestId",UUID.randomUUID(),"role","PLANNER","model","gpt-6.1-terra","reservedTokens",100),409);noExtraction();
    }
    @Test @DisplayName("역할 cap은 실행 한 번 기준이라 소진된 작가 재시도도 새 실행으로 처음부터 세고 명시 cap 변경은 새 budgetRevision이 된다")
    void authorRetryStartsAFreshRunAndAdoptsExplicitCaps() throws Exception {
        var report=start();var job=claim();
        for(int i=0;i<20;i++) {UUID call=UUID.randomUUID();internal(path(job,"/usage/reserve"),job,Map.of("scope",scope(job),"requestId",call,"role","PLANNER","model","gpt-6.1-terra","reservedTokens",100),200);internal(path(job,"/usage/"+call),job,Map.of("scope",scope(job),"kind","UNKNOWN"),200);}
        internal(path(job,"/usage/reserve"),job,Map.of("scope",scope(job),"requestId",UUID.randomUUID(),"role","PLANNER","model","gpt-6.1-terra","reservedTokens",100),409);
        internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","stop-capped","failureCode","VALIDATION_EXECUTION_LIMIT_EXCEEDED"),200);
        var raised=new java.util.EnumMap<org.monitoring.catchholebackend.domain.validation.type.ValidationRole,org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget>(executionProperties.roles());
        var planner=raised.get(org.monitoring.catchholebackend.domain.validation.type.ValidationRole.PLANNER);raised.put(org.monitoring.catchholebackend.domain.validation.type.ValidationRole.PLANNER,new org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget(21,22000,planner.outputTokens(),planner.model(),planner.reasoningEffort()));org.mockito.Mockito.when(executionProperties.roles()).thenReturn(raised);
        var current=get(UUID.fromString(report.path("reportId").asText()));
        var retry=Map.of("expectedReportVersion",current.path("reportVersion").asLong(),"idempotencyKey","fresh-run");var receipt=postOk("/"+report.path("reportId").asText()+"/retries",retry);var next=claim();
        assertThat(next.path("budgetRevision").asInt()).isEqualTo(2);assertThat(next.path("budgets").path("roles").path("PLANNER").path("maxCalls").asInt()).isEqualTo(21);
        assertThat(next.path("usage").size()).as("이전 실행의 원장 행은 보존된다").isEqualTo(20);
        assertThat(postOk("/"+report.path("reportId").asText()+"/retries",retry)).isEqualTo(receipt);
        for(int i=0;i<21;i++)internal(path(next,"/usage/reserve"),next,Map.of("scope",scope(next),"requestId",UUID.randomUUID(),"role","PLANNER","model","gpt-6.1-terra","reservedTokens",100),200);
        internal(path(next,"/usage/reserve"),next,Map.of("scope",scope(next),"requestId",UUID.randomUUID(),"role","PLANNER","model","gpt-6.1-terra","reservedTokens",100),409);noExtraction();
    }
    @Test @DisplayName("호출 한 번의 예약은 분기 입력 상한과 그 역할의 출력 상한의 합을 넘지 못한다")
    void perCallReservationUsesTheRolesOwnOutputCap() throws Exception {
        start();var job=claim();
        internal(path(job,"/usage/reserve"),job,Map.of("scope",scope(job),"requestId",UUID.randomUUID(),"role","JUDGE","model","gpt-6-luna","reservedTokens",1100),200);
        internal(path(job,"/usage/reserve"),job,Map.of("scope",scope(job),"requestId",UUID.randomUUID(),"role","JUDGE","model","gpt-6-luna","reservedTokens",1101),409);
        internal(path(job,"/usage/reserve"),job,Map.of("scope",scope(job),"requestId",UUID.randomUUID(),"role","JUDGE","model","gpt-6.1-terra","reservedTokens",100),400);
        internal(path(job,"/usage/reserve"),job,Map.of("scope",scope(job),"requestId",UUID.randomUUID(),"role","COVERAGE","model","gpt-6.1-terra","reservedTokens",100),400);noExtraction();
    }
    @Test @DisplayName("grid v4는 PLANNER·MERGED_PLAN만 받고 이전 단계(SUBJECT_RESOLUTION·COVERAGE·BOOK_*·RELATED_SELECTION)와 rules·clue_checks를 거절한다")
    void gridV4RejectsRetiredStagesAndFields() throws Exception {
        targetText("합성 인물이 웃었다");start();var job=claim();
        internal(path(job,"/plans"),job,publication(job,"SETTING","SUBJECT_RESOLUTION","TSEG1",Map.of("mentions",List.of())),400);
        internal(path(job,"/plans"),job,publication(job,"SETTING","COVERAGE","TSEG1",batch("TSEG1",List.of())),400);
        internal(path(job,"/plans"),job,publication(job,"SETTING","RELATED_SELECTION","TSEG1:PAGE1",Map.of("entries",List.of())),400);
        internal(path(job,"/plans"),job,publication(job,"SETTING","BOOK_SOURCE","R1",Map.of("source_read_status","READY","planned_checks",0)),400);
        var withRules=new LinkedHashMap<String,Object>(batch("TSEG1",List.of()));withRules.put("rules",List.of());internal(path(job,"/plans"),job,publication(job,"SETTING","PLANNER","TSEG1",withRules),400);
        var withClues=new LinkedHashMap<String,Object>(batch("TSEG1",List.of()));withClues.put("clue_checks",List.of());internal(path(job,"/plans"),job,publication(job,"SETTING","PLANNER","TSEG1",withClues),400);
        savePlan(job,"PLANNER","TSEG1",batch("TSEG1",List.of()));
        var stored=em.createQuery("select j from ValidationJob j",org.monitoring.catchholebackend.domain.validation.entity.ValidationJob.class).getSingleResult().getRequiredGrid().path("SETTING");
        assertThat(stored.path("gridVersion").asInt()).isEqualTo(4);assertThat(stored.has("segmentSubjects")).isFalse();assertThat(stored.has("modelClues")).isFalse();assertThat(stored.has("books")).isFalse();noExtraction();
    }
    @Test @DisplayName("새 공개 재시도만 최신 회차를 검사하고 원 receipt와 기존 lease는 보존한다") void publicRetryRequiresLatestEpisodeButOriginalReceiptAndLeaseRecoveryDoNot() throws Exception {
        var report=start();var job=claim();internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","old-stop","failureCode","MODEL_CALL_FAILED"),200);
        var later=org.monitoring.catchholebackend.domain.episode.entity.Episode.create(work,null,102,"후속","synthetic/102","v1","c".repeat(64),1);em.persist(later);em.flush();
        var current=get(UUID.fromString(report.path("reportId").asText()));var retry=Map.of("expectedReportVersion",current.path("reportVersion").asLong(),"idempotencyKey","latest-only");
        mvc.perform(post(base()+"/"+report.path("reportId").asText()+"/retries").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(retry)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_TARGET_INVALID"));
        assertThat(em.createNativeQuery("select generation from validation_jobs").getSingleResult()).isEqualTo(1L);assertThat(em.createNativeQuery("select status from validation_jobs").getSingleResult()).isEqualTo("FAILED");assertThat(get(UUID.fromString(report.path("reportId").asText())).path("reportVersion")).isEqualTo(current.path("reportVersion"));later.archive();em.flush();
        var receipt=postOk("/"+report.path("reportId").asText()+"/retries",retry);
        var nextLater=org.monitoring.catchholebackend.domain.episode.entity.Episode.create(work,null,102,"다시 후속","synthetic/102-new","v1","c".repeat(64),1);em.persist(nextLater);em.flush();
        assertThat(postOk("/"+report.path("reportId").asText()+"/retries",retry)).isEqualTo(receipt);assertThat(claim().path("scope").path("reportId")).isEqualTo(report.path("reportId"));noExtraction();
    }
    @Test @DisplayName("과거 대기는 명시 접수로만 queue하고 임베딩은 명시 예산으로만 추가한다") void disabledWaitingReportQueuesOnlyOnExplicitRecoveryAndEmbeddingNeedsExplicitRetryBudget() throws Exception {
        org.mockito.Mockito.when(executionProperties.enabled()).thenReturn(false);var report=start();assertThat(report.path("validationJobId").isNull()).isTrue();internal("/claim",null,Map.of("contractVersion",1,"workerId","synthetic","capabilities",List.of("VALIDATION_EXECUTION_V1")),409);
        org.mockito.Mockito.when(executionProperties.enabled()).thenReturn(true);assertThat(em.createNativeQuery("select count(*) from validation_jobs").getSingleResult()).isEqualTo(0L);
        var recovered=start();assertThat(recovered.path("reportId")).isEqualTo(report.path("reportId"));var job=claim();
        var request=Map.of("scope",scope(job),"requestId",UUID.randomUUID(),"role","QUERY_EMBEDDING","model","text-embedding-3-small","reservedTokens",20);
        internal(path(job,"/usage/reserve"),job,request,400);internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","optional-stop","failureCode","VECTOR_NOT_READY"),200);
        var roles=new java.util.EnumMap<org.monitoring.catchholebackend.domain.validation.type.ValidationRole,org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget>(executionProperties.roles());roles.put(org.monitoring.catchholebackend.domain.validation.type.ValidationRole.QUERY_EMBEDDING,new org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget(2,1000,null,null,null));
        org.mockito.Mockito.when(executionProperties.roles()).thenReturn(roles);org.mockito.Mockito.when(executionProperties.queryEmbeddingModel()).thenReturn("text-embedding-3-small");
        org.mockito.Mockito.when(executionProperties.setting()).thenReturn(new org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.Limits(21,101,61,1001,1,0,1));
        var current=get(UUID.fromString(report.path("reportId").asText()));postOk("/"+report.path("reportId").asText()+"/retries",Map.of("expectedReportVersion",current.path("reportVersion").asLong(),"idempotencyKey","explicit-embedding"));var next=claim();
        assertThat(next.path("models").path("QUERY_EMBEDDING").path("model").asText()).isEqualTo("text-embedding-3-small");assertThat(next.path("budgets").path("setting").path("segmentChars").asInt()).isEqualTo(100);assertThat(next.path("budgets").path("setting").path("searchLimit").asInt()).isEqualTo(10);assertThat(next.path("budgets").path("setting").path("modelCalls").asInt()).isEqualTo(21);
        var queryInput=Map.of("contractVersion",1,"inputDigest",next.at("/scope/inputDigest").asText(),"queryHash","a".repeat(64),"indexModel","text-embedding-3-small","indexVersion",next.at("/models/QUERY_EMBEDDING/version").asText(),"indexDimensions",1536,"manifestHash",next.path("manifestHash").asText());
        internal(path(next,"/usage/reserve"),next,Map.of("scope",scope(next),"requestId",UUID.randomUUID(),"role","QUERY_EMBEDDING","model","text-embedding-3-small","reservedTokens",20,"queryInput",queryInput),200);noExtraction();
    }
    @Test @DisplayName("다른 회원과 작품은 저장 결과·고정 원문을 읽지 못한다") void anotherMemberOrWorkCannotReadResultsOrScopedSources() throws Exception {
        var report=start();var job=claim();var other=org.monitoring.catchholebackend.domain.member.entity.Member.register("other-"+UUID.randomUUID()+"@example.invalid","synthetic","01098765432","다른 작가");em.persist(other);String otherToken=tokens.generateAccessToken(other);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base()+"/"+report.path("reportId").asText()+"/results").header("Authorization","Bearer "+otherToken)).andExpect(status().isNotFound());
        var otherWork=org.monitoring.catchholebackend.domain.work.entity.Work.create(member,"다른 작품",org.monitoring.catchholebackend.domain.work.type.WorkGenre.FANTASY,null);em.persist(otherWork);em.flush();
        mvc.perform(post("/api/internal/v1/works/"+otherWork.getId()+"/validation-reports/"+report.path("reportId").asText()+"/data").header("X-Internal-Api-Key",KEY).header("X-Validation-Member-Id",member.getId()).header("X-Validation-Job-Id",job.path("jobId").asText()).header("X-Worker-Lease-Token",job.path("leaseToken").asText()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("expectedBaselineHash",report.path("baselineHash").asText(),"expectedTargetHash",target.getContentHash(),"operation","INPUT","offset",0,"limit",100))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("VALIDATION_NOT_FOUND"));noExtraction();
    }
    @Test @DisplayName("legacy report의 유효 seed는 같은 report와 기준 revision에서 준비를 복구한다") void sameInputRetryMaterializesVerifiedSavedSeedWithoutNewBaseline() throws Exception {
        org.mockito.Mockito.when(executionProperties.enabled()).thenReturn(false);var original=start();var r=toLegacySeed(stored(original));String hash=r.getBaselineHash();r.hold("SYNTHETIC_PREPARATION_INTERRUPTED");em.flush();
        org.mockito.Mockito.when(executionProperties.enabled()).thenReturn(true);var held=get(r.getId());
        var retried=postOk("/"+r.getId()+"/retries",Map.of("expectedReportVersion",held.path("reportVersion").asLong(),"idempotencyKey","restore-valid-seed"));
        assertThat(retried.path("reportId")).isEqualTo(original.path("reportId"));assertThat(retried.path("baselineRevision").asLong()).isEqualTo(1);assertThat(retried.path("baselineHash").asText()).isEqualTo(hash);assertThat(retried.path("baselineStatus").asText()).isEqualTo("READY");assertThat(retried.path("validationJobId").isTextual()).isTrue();assertThat(claim().path("scope").path("reportId")).isEqualTo(original.path("reportId"));noExtraction();
    }
    @Test @DisplayName("seed 없음과 원문 파기는 같은 입력 재시도로 되살리지 않는다") void seedlessOrPurgedPreparationCannotBeRevivedBySameInputRetry() throws Exception {
        org.mockito.Mockito.when(executionProperties.enabled()).thenReturn(false);var original=start();var r=stored(original);r.hold("SYNTHETIC_PREPARATION_INTERRUPTED");
        org.springframework.test.util.ReflectionTestUtils.setField(r,"baselineSeed",null);org.springframework.test.util.ReflectionTestUtils.setField(r,"baselineHash",null);org.springframework.test.util.ReflectionTestUtils.setField(r,"baselineFingerprint",null);em.flush();
        org.mockito.Mockito.when(executionProperties.enabled()).thenReturn(true);var held=get(r.getId());conflict("/"+r.getId()+"/retries",Map.of("expectedReportVersion",held.path("reportVersion").asLong(),"idempotencyKey","no-seed"),"VALIDATION_SOURCE_VERSION_CONFLICT");
        replace("원문 파기");var purged=get(r.getId());conflict("/"+r.getId()+"/retries",Map.of("expectedReportVersion",purged.path("reportVersion").asLong(),"idempotencyKey","purged-seed"),"VALIDATION_SOURCE_VERSION_CONFLICT");assertThat(em.createNativeQuery("select count(*) from validation_jobs").getSingleResult()).isEqualTo(0L);assertThat(r.getBaselineSeed()).isNull();noExtraction();
    }
    @Test @DisplayName("grid v4는 설정집을 필수 단계로 두지 않고 원문도 읽지 않아 읽을 수 없는 설정집이 SETTING 완료를 막지 않는다")
    void settingBooksAreNotGridObligationsAndAreNeverRead() throws Exception {
        source();String bookKey="synthetic/unreadable-book",bookText="고정 설정집";String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bookText.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var book=org.monitoring.catchholebackend.domain.upload.entity.UploadFile.create(em.find(org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.class,batchId),org.monitoring.catchholebackend.domain.upload.type.UploadFileRole.SETTING_BOOK,"book.txt","text/plain","s3://synthetic/book",30);em.persist(book);
        var revision=org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(book,1,new org.monitoring.catchholebackend.global.storage.StoredTextObject(bookKey,"v1",hash,bookText.length()),org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind.WORK_START,null);em.persist(revision);book.linkCurrentRevision(revision);em.flush();org.mockito.Mockito.when(storage.getText(bookKey)).thenThrow(new IllegalStateException("synthetic unavailable"));
        start();var job=claim();mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(WORKER+path(job,"/checkpoint")).header("X-Internal-Api-Key",KEY).header("X-Worker-Lease-Token",job.path("leaseToken").asText())).andExpect(status().isOk()).andExpect(jsonPath("$.data.gridReady").value(false));
        internal(path(job,"/plans"),job,publication(job,"SETTING","PLANNER","TSEG1",batch("TSEG1",List.of())),200);
        internal(path(job,"/plans"),job,publication(job,"SETTING","BOOK_SOURCE","R2",Map.of("source_read_status","READY","planned_checks",1)),400);
        internal(path(job,"/plans"),job,publication(job,"SETTING","MERGED_PLAN","PLAN",Map.of("questions",List.of())),200);
        var finish=internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","no-book","failureCode","MODEL_CALL_FAILED"),200);
        assertThat(finish.path("status").asText()).isEqualTo("PARTIAL");assertThat(finish.path("settingStatus").asText()).isEqualTo("COMPLETED");assertThat(finish.path("typoStatus").asText()).isEqualTo("FAILED");
        org.mockito.Mockito.verify(storage,org.mockito.Mockito.never()).getText(bookKey);noExtraction();
    }
    @Test @DisplayName("TYPO의 최종 검증을 계획 endpoint로 우회할 수 없다") void typoCoverageCannotBypassUnitValidationThroughPlanRoute() throws Exception {
        source();var report=start();var job=claim();var before=get(UUID.fromString(report.path("reportId").asText()));
        internal(path(job,"/plans"),job,publication(job,"TYPO","TYPO_SEGMENT","TSEG1",Map.of("segments",List.of(Map.of("segment_ref","TSEG1","status","NO_CHECK","reason","제안 검증 우회 시도")))),400);
        assertThat(em.createNativeQuery("select count(*) from validation_stages").getSingleResult()).isEqualTo(0L);assertThat(em.createNativeQuery("select count(*) from validation_publications").getSingleResult()).isEqualTo(0L);assertThat(get(UUID.fromString(report.path("reportId").asText())).path("reportVersion")).isEqualTo(before.path("reportVersion"));noExtraction();
    }
    @Test @DisplayName("embedding 예약은 원 query identity와 실제 Job에 고정된다")
    void queryReservationIdentityCannotBeRebound() throws Exception {
        var roles=new java.util.EnumMap<org.monitoring.catchholebackend.domain.validation.type.ValidationRole,org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget>(executionProperties.roles());
        roles.put(org.monitoring.catchholebackend.domain.validation.type.ValidationRole.QUERY_EMBEDDING,new org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget(1,100,null,null,null));
        when(executionProperties.roles()).thenReturn(roles);when(executionProperties.queryEmbeddingModel()).thenReturn("text-embedding-3-small");
        start();var job=claim();UUID request=UUID.randomUUID();
        var proof=new LinkedHashMap<String,Object>(Map.of("contractVersion",1,"inputDigest",job.at("/scope/inputDigest").asText(),"queryHash","a".repeat(64),"indexModel","text-embedding-3-small","indexVersion","v1","indexDimensions",1536,"manifestHash",job.path("manifestHash").asText()));
        var body=new LinkedHashMap<String,Object>(Map.of("scope",scope(job),"requestId",request,"role","QUERY_EMBEDDING","model","text-embedding-3-small","reservedTokens",30,"queryInput",proof));
        internal(path(job,"/usage/reserve"),job,body,200);proof.put("queryHash","b".repeat(64));
        internal(path(job,"/usage/reserve"),job,body,409);noExtraction();
    }
    @Test @DisplayName("색인 채우기 예약은 이 실행의 고정 RAG 출처(앞 회차·현재 판 설정집) 하나에 묶이고 서비스 부담으로 작가 계정을 잡지 않는다")
    void indexFillReservationIsBoundToAFrozenRagSourceAndServicePaid() throws Exception {
        var roles=new java.util.EnumMap<org.monitoring.catchholebackend.domain.validation.type.ValidationRole,org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget>(executionProperties.roles());
        roles.put(org.monitoring.catchholebackend.domain.validation.type.ValidationRole.QUERY_EMBEDDING,new org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget(1,100,null,null,null));
        roles.put(org.monitoring.catchholebackend.domain.validation.type.ValidationRole.INDEX_EMBEDDING,new org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget(3,20000,null,null,null));
        when(executionProperties.roles()).thenReturn(roles);when(executionProperties.queryEmbeddingModel()).thenReturn("text-embedding-3-small");
        var book=org.monitoring.catchholebackend.domain.upload.entity.UploadFile.create(em.find(org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.class,batchId),org.monitoring.catchholebackend.domain.upload.type.UploadFileRole.SETTING_BOOK,"book.txt","text/plain","s3://synthetic/book",30);em.persist(book);
        org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision current=null;
        for(int no=1;no<=2;no++) {
            var revision=org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(book,no,new org.monitoring.catchholebackend.global.storage.StoredTextObject("synthetic/book-"+no,"v1",String.valueOf(no).repeat(64),3),org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind.WORK_START,null);
            em.persist(revision);current=revision;
        }
        book.linkCurrentRevision(current);em.flush();
        var report=start();var job=claim();
        assertThat(job.path("models").path("INDEX_EMBEDDING").path("model").asText()).isEqualTo("text-embedding-3-small");
        assertThat(job.path("budgets").path("roles").path("INDEX_EMBEDDING").path("maxCalls").asInt()).isEqualTo(3);
        // Manifest order: R1 = the prior episode, then the setting-book revisions; only the current one is a RAG source.
        var refs=new LinkedHashMap<String,JsonNode>();int index=1;for(var source:sourceManifests.manifests(stored(report).getId()))refs.put("R"+index++,source);
        String episodeRef=null,currentRef=null,oldRef=null;
        for(var entry:refs.entrySet()) {
            var source=entry.getValue();
            if("EPISODE".equals(source.path("sourceKind").asText()))episodeRef=entry.getKey();
            else if(source.path("currentRevision").asBoolean())currentRef=entry.getKey();else oldRef=entry.getKey();
        }
        assertThat(episodeRef).isNotNull();assertThat(currentRef).isNotNull();assertThat(oldRef).isNotNull();
        java.util.function.BiFunction<String,String,Map<String,Object>> input=(ref,hash)->{
            var value=new LinkedHashMap<String,Object>();value.put("contractVersion",1);value.put("inputDigest",job.at("/scope/inputDigest").asText());value.put("manifestHash",job.path("manifestHash").asText());
            value.put("sourceRef",ref);value.put("sourceHash",hash);value.put("chunkCount",2);value.put("chunkDigest","e".repeat(64));value.put("indexModel","text-embedding-3-small");value.put("indexVersion","v1");value.put("indexDimensions",1536);return value;};
        java.util.function.BiFunction<Object,Long,Map<String,Object>> reserve=(index_,tokens)->{
            var value=new LinkedHashMap<String,Object>();value.put("scope",scope(job));value.put("requestId",UUID.randomUUID());value.put("role","INDEX_EMBEDDING");value.put("model","text-embedding-3-small");value.put("reservedTokens",tokens);
            if(index_!=null)value.put("indexInput",index_);return value;};
        String episodeHash=refs.get(episodeRef).path("contentHash").asText(),currentHash=refs.get(currentRef).path("contentHash").asText();
        long accountBefore=((Number)em.createNativeQuery("select count(*) from ai_token_accounts where member_id=? and reserved_tokens>0").setParameter(1,member.getId()).getSingleResult()).longValue();
        // An embedding role is bounded by its run caps, not by the branch's per-call input cap (1,000 in this fixture).
        var episode=reserve.apply(input.apply(episodeRef,episodeHash),5000L);var saved=internal(path(job,"/usage/reserve"),job,episode,200);
        assertThat(saved.path("role").asText()).isEqualTo("INDEX_EMBEDDING");assertThat(saved.path("queryInput").path("sourceRef").asText()).isEqualTo(episodeRef);
        internal(path(job,"/usage/reserve"),job,reserve.apply(input.apply(currentRef,currentHash),100L),200);
        internal(path(job,"/usage/reserve"),job,reserve.apply(input.apply(oldRef,refs.get(oldRef).path("contentHash").asText()),100L),400);
        internal(path(job,"/usage/reserve"),job,reserve.apply(input.apply(episodeRef,"f".repeat(64)),100L),400);
        internal(path(job,"/usage/reserve"),job,reserve.apply(input.apply("R99",episodeHash),100L),400);
        internal(path(job,"/usage/reserve"),job,reserve.apply(null,100L),400);
        var query=new LinkedHashMap<>(reserve.apply(input.apply(episodeRef,episodeHash),100L));query.put("role","QUERY_EMBEDDING");
        internal(path(job,"/usage/reserve"),job,query,400);
        // A replay must carry the identical source identity.
        var replay=new LinkedHashMap<>(episode);replay.put("indexInput",input.apply(currentRef,currentHash));internal(path(job,"/usage/reserve"),job,replay,409);
        var row=(Object[])em.createNativeQuery("select charge_policy_version,reserved_charge from ai_token_usages where request_id=?").setParameter(1,episode.get("requestId")).getSingleResult();
        assertThat(row[0]).isEqualTo("SERVICE_PAID_V1");assertThat((java.math.BigDecimal)row[1]).isEqualByComparingTo("0");
        internal(path(job,"/usage/"+episode.get("requestId")),job,Map.of("scope",scope(job),"kind","SETTLE","inputTokens",1200,"cachedInputTokens",0,"outputTokens",0,"outcome","SUCCESS"),200);
        var settled=(Object[])em.createNativeQuery("select status,charged_tokens,input_tokens from ai_token_usages where request_id=?").setParameter(1,episode.get("requestId")).getSingleResult();
        assertThat(settled[0]).isEqualTo("SETTLED");assertThat((java.math.BigDecimal)settled[1]).isEqualByComparingTo("0");assertThat(((Number)settled[2]).longValue()).isEqualTo(1200);
        assertThat(((Number)em.createNativeQuery("select count(*) from ai_token_accounts where member_id=? and reserved_tokens>0").setParameter(1,member.getId()).getSingleResult()).longValue()).isEqualTo(accountBefore);
        // Run cap: the third call fits, a fourth does not (maxCalls 3; a rejected reservation is no call).
        internal(path(job,"/usage/reserve"),job,reserve.apply(input.apply(episodeRef,episodeHash),100L),200);
        assertThat(internal(path(job,"/usage/reserve"),job,reserve.apply(input.apply(episodeRef,episodeHash),100L),409).path("code").asText()).isEqualTo("VALIDATION_EXECUTION_LIMIT_EXCEEDED");
        noExtraction();
    }
    @Test @DisplayName("설정집 수정·보관은 그 설정집의 RAG 색인을 같은 트랜잭션에서 지운다")
    void settingBookChangeDeletesItsRetrievalIndex() {
        var book=org.monitoring.catchholebackend.domain.upload.entity.UploadFile.create(em.find(org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.class,batchId),org.monitoring.catchholebackend.domain.upload.type.UploadFileRole.SETTING_BOOK,"book.txt","text/plain","s3://synthetic/book",30);em.persist(book);
        var revision=org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(book,1,new org.monitoring.catchholebackend.global.storage.StoredTextObject("synthetic/book-1","v1","1".repeat(64),3),org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind.WORK_START,null);em.persist(revision);book.linkCurrentRevision(revision);em.flush();
        em.createNativeQuery("insert into retrieval_setting_book_sources(revision_id,setting_book_id,work_id,source_key,source_version,source_hash,chunk_policy,chunk_count,prepared_at) values (?,?,?,'synthetic/book-1','v1',?,'cl100k-800-100-boundary-v1',1,CURRENT_TIMESTAMP)")
                .setParameter(1,revision.getId()).setParameter(2,book.getId()).setParameter(3,work.getId()).setParameter(4,"1".repeat(64)).executeUpdate();
        em.createNativeQuery("insert into retrieval_setting_book_chunks(id,revision_id,chunk_index,chunk_text,start_offset,end_offset,chunk_hash,token_count,status,attempt_count) values (?,?,0,'설정',0,2,?,1,'MISSING',0)")
                .setParameter(1,UUID.randomUUID()).setParameter(2,revision.getId()).setParameter(3,"2".repeat(64)).executeUpdate();
        settingBooks.deleteSettingBook(member.getId(),work.getId(),book.getId());em.flush();
        assertThat(((Number)em.createNativeQuery("select count(*) from retrieval_setting_book_chunks").getSingleResult()).longValue()).isZero();
        assertThat(((Number)em.createNativeQuery("select count(*) from retrieval_setting_book_sources").getSingleResult()).longValue()).isZero();
    }

}
