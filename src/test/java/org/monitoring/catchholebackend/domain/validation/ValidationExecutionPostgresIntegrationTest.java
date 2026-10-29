package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.service.EpisodeService;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeUploadType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.*;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationStartRequest;
import org.monitoring.catchholebackend.domain.validation.service.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.work.repository.WorkPurgeDataRepository;
import org.monitoring.catchholebackend.domain.work.service.WorkPurgeService;
import org.monitoring.catchholebackend.domain.work.dto.request.WorkPurgeCreateRequest;
import org.monitoring.catchholebackend.global.exception.AppException;
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

/** Destructive fixture cleanup is guarded to a new disposable loopback DB, never .env/shared DB. */
@SpringBootTest(properties={"spring.config.import=","spring.docker.compose.enabled=false"}) @ActiveProfiles("test") @DirtiesContext
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@EnabledIfEnvironmentVariable(named="GH310_EXECUTION_POSTGRES_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh310_execution_test")
@DisplayName("격리 PostgreSQL의 검수 동시성·중간 저장·파기")
class ValidationExecutionPostgresIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->System.getenv("GH310_EXECUTION_POSTGRES_JDBC_URL"));p.add("spring.datasource.username",()->"gh310");p.add("spring.datasource.password",()->"gh310-disposable-only");
        p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");p.add("spring.jpa.hibernate.ddl-auto",()->"validate");p.add("spring.flyway.enabled",()->"true");
    }
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EpisodeService episodes;
    @Autowired EpisodeValidationService admissions;
    @Autowired ValidationRecheckService rechecks;
    @Autowired ValidationWorkerService workers;
    @Autowired ValidationResultService results;
    @Autowired ValidationUsageService usage;
    @Autowired ValidationDataService data;
    @Autowired AiTokenService accounts;
    @Autowired WorkPurgeService purges;
    @Autowired WorkPurgeDataRepository physical;
    @Autowired ObjectStorageService hashes;
    @Autowired AnalysisStateJournal journal;
    @MockitoBean ObjectStorage storage;
    final Map<String,String> texts=new ConcurrentHashMap<>();
    TransactionTemplate tx;
    record Fixture(Long member,UUID work,UUID target,UUID report,String key,String hash,String version) {}
    @BeforeEach void initialize() {
        tx=new TransactionTemplate(transactions);jdbc.execute("TRUNCATE TABLE works,members CASCADE");texts.clear();
        when(storage.putBytes(anyString(),any(byte[].class),anyString())).thenAnswer(c->{texts.put(c.getArgument(0),new String(c.getArgument(1,byte[].class),StandardCharsets.UTF_8));return new StoredObject(c.getArgument(0),"synthetic-v1");});
        when(storage.putText(anyString(),anyString())).thenAnswer(c->{texts.put(c.getArgument(0),c.getArgument(1));return new StoredObject(c.getArgument(0),"synthetic-v1");});
        when(storage.getText(anyString())).thenAnswer(c->{var text=texts.get(c.getArgument(0));if(text==null)throw new IllegalStateException("synthetic source absent");return text;});
        when(storage.purgePrefixes(anyCollection())).thenReturn(new ObjectStoragePurgeResult(1,1,0));when(storage.purgePrefixesExcluding(anyCollection(),anyCollection())).thenReturn(new ObjectStoragePurgeResult(1,1,0));
    }
    Fixture fixture() {return fixture(null);}
    Fixture fixture(Long owner) {return tx.execute(s->{
        var member=owner==null?Member.register("execution-"+UUID.randomUUID()+"@example.invalid","synthetic-only","01012345678","합성 작가"):em.find(Member.class,owner);if(owner==null)em.persist(member);
        var work=Work.create(member,"격리 검수",WorkGenre.FANTASY,null);em.persist(work);String prior="앞선 합성 원고";texts.put("synthetic/prior/"+work.getId(),prior);
        em.persist(Episode.create(work,null,100,"앞 회차","synthetic/prior/"+work.getId(),"v1",hashes.contentHash(prior),prior.length()));work.updateLatestEpisodeNo(100);
        em.persist(WorkCharacter.create(work,"합성 인물",null,20,1,null,null,null,null,null,null));
        var batch=episodes.uploadEpisodes(member.getId(),work.getId(),new EpisodeUploadRequest(EpisodeUploadType.SINGLE_EPISODE,101,"합성",null),List.of(file("😀가😀가 합성 원고")));
        var e=em.createQuery("select e from Episode e where e.work=:w and e.episodeNo=101",Episode.class).setParameter("w",work).getSingleResult();
        var report=admissions.start(member.getId(),work.getId(),new ValidationStartRequest("initial",batch.batchId(),e.getId(),e.getContentHash(),e.getContentS3Key(),e.getContentS3Version()));
        return new Fixture(member.getId(),work.getId(),e.getId(),report.reportId(),e.getContentS3Key(),e.getContentHash(),e.getContentS3Version());
    });}
    static MockMultipartFile file(String text) {return new MockMultipartFile("episodeFiles","synthetic.txt","text/plain",text.getBytes(StandardCharsets.UTF_8));}
    Map<String,Object> claim() {return workers.claimValidationJob(new Claim(1,"synthetic-worker",Set.of("VALIDATION_EXECUTION_V1")));}
    UUID id(Map<String,Object> j) {return (UUID)j.get("jobId");} UUID lease(Map<String,Object> j) {return (UUID)j.get("leaseToken");} Scope scope(Map<String,Object> j) {return (Scope)j.get("scope");}
    Publish plan(Map<String,Object> j,ValidationStageKind kind,String ref,Map<String,Object> body) {return new Publish(scope(j),kind+":"+ref,ValidationBranch.SETTING,kind,ref,body,List.of(),null);}
    Map<String,Object> batch(List<?> questions) {return Map.of("questions",questions,"segments",List.of(Map.of("segment_ref","TSEG1","status",questions.isEmpty()?"NO_CHECK":"PLANNED","reason","합성 완료")),"rules",List.of());}
    Map<String,Object> planner(Map<String,Object> j) {return results.publishValidationPlan(id(j),lease(j),plan(j,ValidationStageKind.PLANNER,"TSEG1",batch(List.of())));}
    void expire(Map<String,Object> j) {jdbc.update("update validation_jobs set lease_expires_at=now()-interval '1 second' where id=?",id(j));}
    void noExtraction() {for(String table:List.of("analysis_jobs","setting_candidates","world_setting_candidates"))assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).as(table).isZero();}
    void zeroCopies(UUID report) {for(String table:List.of("validation_stages","validation_checks","validation_check_history","validation_findings","validation_evidence","validation_checkpoints","validation_publications"))assertThat(jdbc.queryForObject("select count(*) from "+table+" where report_id=?",Integer.class,report)).as(table).isZero();}
    static void await(CountDownLatch latch) {try {if(!latch.await(15,TimeUnit.SECONDS))throw new AssertionError("synthetic latch timed out");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);}}
    @Test @DisplayName("실제 Flyway·JPA schema에서 동시 claim은 한 소유자만 얻는다") void concurrentClaimsHaveOneRealOwnerAndFlywayJpaValidate() throws Exception {
        fixture();var go=new CountDownLatch(1);var ready=new CountDownLatch(2);List<Map<String,Object>> claims;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{ready.countDown();await(go);return claim();});var b=pool.submit(()->{ready.countDown();await(go);return claim();});await(ready);go.countDown();claims=Arrays.asList(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
        assertThat(claims.stream().filter(Objects::nonNull).count()).isEqualTo(1);assertThat(jdbc.queryForObject("select count(*) from validation_jobs where status='RUNNING'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='93' and success",Integer.class)).isEqualTo(1);noExtraction();
    }
    @Test @DisplayName("lease 회수는 저장 계획과 이전 실제 호출 정산 소유권을 보존한다") void leaseReclaimKeepsSavedPlanAndLateMeasuredOriginalCallOwnership() {
        var f=fixture();var old=claim();var receipt=planner(old);UUID call=UUID.randomUUID();usage.reserveValidationUsage(id(old),lease(old),new Reserve(scope(old),call,ValidationRole.PLANNER,"gpt-6.1-terra",100));
        expire(old);var next=claim();assertThat(scope(next).generation()).isEqualTo(scope(old).generation()+1);
        assertThat(jdbc.queryForObject("select status from ai_token_usages where request_id=?",String.class,call)).isEqualTo("UNKNOWN");assertThat(jdbc.queryForObject("select reserved_tokens from ai_token_accounts where member_id=?",java.math.BigDecimal.class,f.member())).isEqualByComparingTo("0");
        assertThatThrownBy(()->usage.reserveValidationUsage(id(old),lease(old),new Reserve(scope(old),UUID.randomUUID(),ValidationRole.PLANNER,"gpt-6.1-terra",100))).isInstanceOf(AppException.class);
        assertThat(planner(next)).isEqualTo(receipt);assertThat(jdbc.queryForObject("select count(*) from validation_stages",Integer.class)).isEqualTo(1);
        usage.reportValidationUsage(id(old),lease(old),call,new Usage(scope(old),UsageKind.SETTLE,25L,5L,7L,org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome.SUCCESS));
        assertThat(jdbc.queryForObject("select used_tokens from ai_token_accounts where member_id=?",java.math.BigDecimal.class,f.member())).isEqualByComparingTo("32");noExtraction();
    }
    @Test @DisplayName("동시 응답 유실 재전송은 하나의 저장 단계와 receipt를 만든다") void concurrentLostResponsePublicationHasOneDurableReceipt() throws Exception {
        fixture();var j=claim();var go=new CountDownLatch(1);var ready=new CountDownLatch(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{ready.countDown();await(go);return planner(j);});var b=pool.submit(()->{ready.countDown();await(go);return planner(j);});await(ready);go.countDown();assertThat(a.get(20,TimeUnit.SECONDS)).isEqualTo(b.get(20,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("select count(*) from validation_stages",Integer.class)).isEqualTo(1);assertThat(jdbc.queryForObject("select count(*) from validation_publications",Integer.class)).isEqualTo(1);noExtraction();
    }
    @Test @DisplayName("원문 읽기 I/O 동안 교체를 막지 않고 늦은 복사를 거절한다") void sourceReplacementCommitsWhileReaderIsBlockedAndLatePublishCannotRestoreCopies() throws Exception {
        var f=fixture();var j=claim();var reading=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean(true);
        when(storage.getText(f.key())).thenAnswer(c->{String frozen=texts.get(f.key());if(once.compareAndSet(true,false)){reading.countDown();await(release);}return frozen;});
        try(var pool=Executors.newFixedThreadPool(2)) {
            var publication=pool.submit(()->planner(j));await(reading);
            var replacement=pool.submit(()->episodes.replaceEpisodeFile(f.member(),f.work(),f.target(),file("바뀐 합성 원고")));
            try {replacement.get(5,TimeUnit.SECONDS);}finally {release.countDown();}
            assertThatThrownBy(()->publication.get(20,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(AppException.class);
        } finally {release.countDown();}
        zeroCopies(f.report());assertThat(jdbc.queryForObject("select status from validation_jobs where id=?",String.class,id(j))).isEqualTo("CANCELED");noExtraction();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true}) @DisplayName("진행·무효화 실행은 파기 drain과 계정 해제 뒤 FK 순서로 지운다") void workPurgeCancelsValidationDrainsAndReleasesBeforeForeignKeySafePhysicalDelete(boolean invalidatedFirst) {
        var f=fixture();var j=claim();planner(j);usage.reserveValidationUsage(id(j),lease(j),new Reserve(scope(j),UUID.randomUUID(),ValidationRole.PLANNER,"gpt-6.1-terra",100));
        if(invalidatedFirst)episodes.replaceEpisodeFile(f.member(),f.work(),f.target(),file("파기 전 변경 원고"));
        var request=purges.requestPurge(f.member(),f.work(),new WorkPurgeCreateRequest("영구 삭제"));
        assertThat(jdbc.queryForObject("select worker_drain_until is not null from work_purge_requests where id=?",Boolean.class,request.requestId())).isTrue();
        assertThat(jdbc.queryForObject("select reserved_tokens from ai_token_accounts where member_id=?",java.math.BigDecimal.class,f.member())).isEqualByComparingTo("0");zeroCopies(f.report());
        tx.executeWithoutResult(s->physical.purgeWorkData(f.work()));assertThat(jdbc.queryForObject("select count(*) from validation_jobs",Integer.class)).isZero();assertThat(jdbc.queryForObject("select count(*) from ai_token_usages",Integer.class)).isZero();noExtraction();
    }
    @Test @DisplayName("서로 다른 작품도 같은 실제 계정 잔액을 초과 예약하지 못한다") void sameMemberDifferentWorksCannotOverspendConcurrentAccountReservation() throws Exception {
        var f=fixture();var g=fixture(f.member());var a=claim();var b=claim();accounts.getUsage(f.member());jdbc.update("update ai_token_accounts set granted_tokens=100 where member_id=?",f.member());
        var go=new CountDownLatch(1);var ready=new CountDownLatch(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->reserveAfter(ready,go,a));var two=pool.submit(()->reserveAfter(ready,go,b));await(ready);go.countDown();assertThat(List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("select reserved_tokens from ai_token_accounts where member_id=?",java.math.BigDecimal.class,f.member())).isEqualByComparingTo("100");noExtraction();
    }
    boolean reserveAfter(CountDownLatch ready,CountDownLatch go,Map<String,Object> j) {ready.countDown();await(go);try{usage.reserveValidationUsage(id(j),lease(j),new Reserve(scope(j),UUID.randomUUID(),ValidationRole.PLANNER,"gpt-6.1-terra",100));return true;}catch(AppException ex){return false;}}
    @Test @DisplayName("원고 ABA는 새 report이고 이전 lease가 복사본을 복구하지 못한다") void sourceAbaCreatesFreshReportAndOldLeaseCannotReviveAnyCopiedResult() {
        var f=fixture();var old=claim();planner(old);String original=texts.get(f.key());
        episodes.replaceEpisodeFile(f.member(),f.work(),f.target(),file("중간 B 입력"));episodes.replaceEpisodeFile(f.member(),f.work(),f.target(),file(original));
        var previous=admissions.get(f.member(),f.work(),f.report());
        var request=tx.execute(s->{var e=em.find(Episode.class,f.target());assertThat(e.getContentHash()).isEqualTo(f.hash());return new org.monitoring.catchholebackend.domain.validation.dto.request.ValidationRecheckRequest("aba-new",previous.reportVersion(),e.getContentHash(),e.getContentS3Key(),e.getContentS3Version(),e.getContentRevision(),previous.baselineFingerprint(),null);});
        var next=rechecks.recheckValidation(f.member(),f.work(),f.report(),request);assertThat(next.reportId()).isNotEqualTo(f.report());assertThat(next.targetRevision()).isGreaterThan(previous.targetRevision());
        assertThatThrownBy(()->planner(old)).isInstanceOf(AppException.class);zeroCopies(f.report());var claimed=claim();assertThat(scope(claimed).reportId()).isEqualTo(next.reportId());noExtraction();
    }
    Map<String,Object> draft(String local,Map<String,Object> span,String property) {
        var d=new LinkedHashMap<String,Object>();d.put("local_ref",local);d.put("target_spans",List.of(span));d.put("subject_refs",List.of());d.put("subject_mentions",List.of("합성 신규 내용"));d.put("property_or_rule",property);d.put("time_context","현재");d.put("conditions",List.of());d.put("assertion_mode","NARRATION");d.put("check_intent","고정 비교");d.put("lookup_intents",List.of());d.put("reason","합성 계획");return d;
    }
    Map<String,Object> intent(String ref,String kind,String op) {var m=new LinkedHashMap<String,Object>();m.put("ref",ref);m.put("kind",kind);m.put("operation",op);m.put("purpose","실제 고정 문맥 확인");m.put("query",null);m.put("source_ref",null);m.put("start",null);m.put("end",null);m.put("evidence_refs",List.of());return m;}
    Map<String,Object> decision(String ref,Map<String,Object> draft,String text) {
        var b=new LinkedHashMap<String,Object>();b.put("question_ref",ref);b.put("check_revision",1);b.put("question",draft);b.put("target_context",List.of(Map.of("start",0,"text",text)));b.put("settings",List.of());b.put("evidence",List.of(Map.of("ref","E1","source_ref","R1","source_kind","EPISODE","start",0,"end",2,"quote","앞선","method","DIRECT","source_context",Map.of("episodeNo",100))));b.put("question_refs",List.of("Q1","Q2"));b.put("additional_target_context",List.of());b.put("source_catalogue",List.of(Map.of("source_ref","R1","source_kind","EPISODE","episodeNo",100)));b.put("input_digest","0".repeat(64));
        var direct=intent(ref+"-DIRECT","COMPARISON_CONTEXT","DIRECT");direct.put("source_ref","R1");direct.put("start",0);direct.put("end",2);
        b.put("receipts",List.of(Map.of("ref","L1","intent",intent(ref+"-BASELINE","COMPARISON_CONTEXT","BASELINE"),"status","SUCCEEDED","evidence_refs",List.of(),"limited",false),Map.of("ref","L2","intent",intent(ref+"-TARGET_CONTEXT","COMPARISON_CONTEXT","TARGET_CONTEXT"),"status","SUCCEEDED","evidence_refs",List.of("T1"),"limited",false),Map.of("ref","L3","intent",direct,"status","SUCCEEDED","evidence_refs",List.of("E1"),"limited",false)));
        var assessment=Map.of("reason","정상 완료 근거","evidence_refs",List.of("T1","E1"),"receipt_refs",List.of("L1","L2","L3"));var m=new LinkedHashMap<String,Object>();m.put("action","DECIDED");m.put("outcome","NO_CONFLICT");m.put("basis","COMPATIBLE");m.put("reason","같은 문맥에서 정상");m.put("impact",null);m.put("impact_reason",null);m.put("target_spans",draft.get("target_spans"));m.put("evidence_refs",List.of("T1","E1"));m.put("completion",Map.of("comparison_context",assessment,"changes_exceptions",assessment,"no_pending_clues",assessment,"required_reads",assessment));m.put("follow_ups",List.of());m.put("affected_question_refs",List.of());m.put("disagreements",List.of());m.put("evidence_bundle",b);return m;
    }
    @SuppressWarnings("unchecked") @Test @DisplayName("저장 계획·정상 질문을 복원하고 다중 후속 revision을 원자 변경한다") void savedMergedPlanAndTwoSuccessfulChecksSurviveCrashThenFollowUpRevisionsAreAtomic() {
        var f=fixture();var j=claim();var first=draft("one",Map.of("source_ref","T1","start",0,"end",1,"quote","😀"),"age");var second=draft("two",Map.of("source_ref","T1","start",2,"end",3,"quote","😀"),"level");
        var quotes=List.of(new Citation("T1",f.hash(),f.version(),0,1,"😀"),new Citation("T1",f.hash(),f.version(),2,3,"😀"));
        var command=plan(j,ValidationStageKind.PLANNER,"TSEG1",batch(List.of(first,second)));results.publishValidationPlan(id(j),lease(j),new Publish(command.scope(),command.idempotencyKey(),command.branch(),command.stage(),command.ref(),command.payload(),quotes,null));
        results.publishValidationPlan(id(j),lease(j),plan(j,ValidationStageKind.RELATED_SELECTION,"TSEG1:PAGE1",Map.of("entries",List.of(Map.of("entry_ref","B1","selected",false,"reason","합성 비선정")))));results.publishValidationPlan(id(j),lease(j),plan(j,ValidationStageKind.COVERAGE,"TSEG1",batch(List.of())));
        var merged=Map.<String,Object>of("questions",List.of(Map.of("localRef","Q1","originalRefs",List.of("PLANNER:TSEG1:one"),"state",Map.of("draft",first)),Map.of("localRef","Q2","originalRefs",List.of("PLANNER:TSEG1:two"),"state",Map.of("draft",second))));
        var receipt=results.publishValidationPlan(id(j),lease(j),new Publish(scope(j),"merged",ValidationBranch.SETTING,ValidationStageKind.MERGED_PLAN,"PLAN",merged,quotes,null));
        expire(j);var recovered=claim();var restored=(List<Map<String,Object>>)workers.loadValidationCheckpoint(id(recovered),lease(recovered)).get("checks");assertThat(restored).hasSize(2);assertThat(jdbc.queryForObject("select count(*) from validation_stages",Integer.class)).isEqualTo(4);
        String text=texts.get(f.key());var allQuotes=new ArrayList<>(quotes);allQuotes.add(new Citation("T1",f.hash(),f.version(),0,text.codePointCount(0,text.length()),text));allQuotes.add(new Citation("R1",hashes.contentHash("앞선 합성 원고"),"v1",0,2,"앞선"));
        for(var check:restored) {String ref=check.get("localRef").toString();results.publishValidationUnit(id(recovered),lease(recovered),new Unit(scope(recovered),"decision-"+ref,ValidationBranch.SETTING,ref,UUID.fromString(check.get("checkId").toString()),1L,check.get("checkInputDigest").toString(),"NO_CONFLICT",decision(ref,ref.equals("Q1")?first:second,text),allQuotes));}
        assertThat(results.getValidationProgress(f.member(),f.work(),f.report()).get("completedChecks")).isEqualTo(2L);
        var changes=new ArrayList<Change>();for(var check:restored)changes.add(new Change(UUID.fromString(check.get("checkId").toString()),1,check.get("checkInputDigest").toString(),Map.of("draft",check.get("localRef").equals("Q1")?first:second,"lookup_obligations",List.of(intent("NEW-1","NEW_CLUE","TARGET_CONTEXT")))));
        var invalid=new ArrayList<>(changes);var c=invalid.get(1);invalid.set(1,new Change(c.checkId(),2,c.expectedInputDigest(),c.state()));
        assertThatThrownBy(()->results.reviseValidationChecks(id(recovered),lease(recovered),new Revise(scope(recovered),"bad-all","NEW_CLUE",invalid,quotes))).isInstanceOf(AppException.class);
        assertThat(results.getValidationProgress(f.member(),f.work(),f.report()).get("completedChecks")).isEqualTo(2L);assertThat(jdbc.queryForObject("select count(*) from validation_check_history",Integer.class)).isZero();
        results.reviseValidationChecks(id(recovered),lease(recovered),new Revise(scope(recovered),"revise-all","NEW_CLUE",changes,quotes));
        assertThat(results.getValidationProgress(f.member(),f.work(),f.report()).get("completedChecks")).isEqualTo(0L);assertThat(jdbc.queryForObject("select count(*) from validation_check_history where outcome='NO_CONFLICT' and change_reason='NEW_CLUE'",Integer.class)).isEqualTo(2);
        var finish=results.completeValidationJob(id(recovered),lease(recovered),new Complete(scope(recovered),"pending-all","MODEL_CALL_FAILED"));assertThat(finish.get("status").toString()).isEqualTo("FAILED");
        episodes.replaceEpisodeFile(f.member(),f.work(),f.target(),file("인용 파기"));zeroCopies(f.report());noExtraction();
    }
}