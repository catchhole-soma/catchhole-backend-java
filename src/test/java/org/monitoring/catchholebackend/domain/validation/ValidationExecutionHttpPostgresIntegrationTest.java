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
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.config.import=","spring.docker.compose.enabled=false","internal.api-key=gh310-synthetic-internal","validation.execution.roles.QUERY_EMBEDDING.max-calls=1","validation.execution.roles.QUERY_EMBEDDING.max-reserved-tokens=1000","validation.execution.roles.INDEX_EMBEDDING.max-calls=10","validation.execution.roles.INDEX_EMBEDDING.max-reserved-tokens=100000","validation.execution.query-embedding-model=text-embedding-3-small"}) @ActiveProfiles("test") @DirtiesContext
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@EnabledIfEnvironmentVariable(named="GH310_EXECUTION_POSTGRES_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh310_execution_test")
@DisplayName("격리 PostgreSQL·실제 Spring HTTP·Python 검수 Worker")
class ValidationExecutionHttpPostgresIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->System.getenv("GH310_EXECUTION_POSTGRES_JDBC_URL"));p.add("spring.datasource.username",()->"gh310");p.add("spring.datasource.password",()->"gh310-disposable-only");
        p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");p.add("spring.jpa.hibernate.ddl-auto",()->"validate");p.add("spring.flyway.enabled",()->"true");
    }
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties executionProperties;
    @Autowired EntityManager em;
    @Autowired org.monitoring.catchholebackend.domain.validation.processor.ValidationSourceManifests sourceManifests;
    @Autowired org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider tokens;
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
    Fixture fixture(Long owner) {return fixture(owner,List.of());}
    Fixture fixture(Long owner,List<String> books) {return tx.execute(s->{
        var member=owner==null?Member.register("execution-"+UUID.randomUUID()+"@example.invalid","synthetic-only","01012345678","합성 작가"):em.find(Member.class,owner);if(owner==null)em.persist(member);
        var work=Work.create(member,"격리 검수",WorkGenre.FANTASY,null);em.persist(work);String prior="앞선 합성 원고";texts.put("synthetic/prior/"+work.getId(),prior);
        em.persist(Episode.create(work,null,100,"앞 회차","synthetic/prior/"+work.getId(),"v1",hashes.contentHash(prior),prior.length()));work.updateLatestEpisodeNo(100);
        em.persist(WorkCharacter.create(work,"합성 인물",null,20,1,null,null,null,null,null,null));
        var batch=episodes.uploadEpisodes(member.getId(),work.getId(),new EpisodeUploadRequest(EpisodeUploadType.SINGLE_EPISODE,101,"합성",null),List.of(file("😀가😀가 합성 원고")));
        for(int i=0;i<books.size();i++) {
            String text=books.get(i),key="synthetic/book/"+work.getId()+"/"+i;texts.put(key,text);
            var book=org.monitoring.catchholebackend.domain.upload.entity.UploadFile.create(em.find(org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.class,batch.batchId()),org.monitoring.catchholebackend.domain.upload.type.UploadFileRole.SETTING_BOOK,"book-"+i+".txt","text/plain","s3://synthetic/book-"+i,30);em.persist(book);
            var revision=org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(book,1,new StoredTextObject(key,"v1",hashes.contentHash(text),text.length()),org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind.WORK_START,null);em.persist(revision);book.linkCurrentRevision(revision);
        }em.flush();
        var e=em.createQuery("select e from Episode e where e.work=:w and e.episodeNo=101",Episode.class).setParameter("w",work).getSingleResult();
        var report=admissions.start(member.getId(),work.getId(),new ValidationStartRequest("initial",batch.batchId(),e.getId(),e.getContentHash(),e.getContentS3Key(),e.getContentS3Version()));
        return new Fixture(member.getId(),work.getId(),e.getId(),report.reportId(),e.getContentS3Key(),e.getContentHash(),e.getContentS3Version());
    });}
    static MockMultipartFile file(String text) {return new MockMultipartFile("episodeFiles","synthetic.txt","text/plain",text.getBytes(StandardCharsets.UTF_8));}
    Map<String,Object> claim() {return workers.claimValidationJob(new Claim(1,"synthetic-worker",Set.of("VALIDATION_EXECUTION_V1")));}
    UUID id(Map<String,Object> j) {return (UUID)j.get("jobId");} UUID lease(Map<String,Object> j) {return (UUID)j.get("leaseToken");} Scope scope(Map<String,Object> j) {return (Scope)j.get("scope");}
    Publish plan(Map<String,Object> j,ValidationStageKind kind,String ref,Map<String,Object> body) {return new Publish(scope(j),kind+":"+ref,ValidationBranch.SETTING,kind,ref,body,List.of(),null);}
    Map<String,Object> batch(List<?> questions) {return Map.of("questions",questions,"segments",List.of(Map.of("segment_ref","TSEG1","status",questions.isEmpty()?"NO_CHECK":"PLANNED","reason","합성 완료")));}
    Map<String,Object> planner(Map<String,Object> j) {return results.publishValidationPlan(id(j),lease(j),plan(j,ValidationStageKind.PLANNER,"TSEG1",batch(List.of())));}
    void expire(Map<String,Object> j) {jdbc.update("update validation_jobs set lease_expires_at=now()-interval '1 second' where id=?",id(j));}
    void noExtraction() {for(String table:List.of("analysis_jobs","setting_candidates","world_setting_candidates"))assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).as(table).isZero();}
    /** GH312 S2: 정상 0건 COMPLETED는 /complete가 검토 종료 receipt와 release Job 하나를 만들고 후보·확정은 아직 없다. */
    void releaseOnly(Fixture f) {
        assertThat(jdbc.queryForObject("select count(*) from validation_review_completions where report_id=? and trigger='AUTOMATIC'",Integer.class,f.report())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from analysis_jobs",Integer.class)).isEqualTo(1);
        for(String table:List.of("setting_candidates","world_setting_candidates"))assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).as(table).isZero();
    }
    void zeroCopies(UUID report) {for(String table:List.of("validation_stages","validation_checks","validation_check_history","validation_findings","validation_evidence","validation_checkpoints","validation_publications"))assertThat(jdbc.queryForObject("select count(*) from "+table+" where report_id=?",Integer.class,report)).as(table).isZero();}
    static void await(CountDownLatch latch) {try {if(!latch.await(15,TimeUnit.SECONDS))throw new AssertionError("synthetic latch timed out");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);}}

    com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
    ProcessBuilder pythonBuilder(String mode) {
        String checkout=System.getenv("GH310_AI_CHECKOUT"),python=System.getenv("GH310_PYTHON");
        assertThat(checkout).isNotBlank();assertThat(python).isNotBlank();
        var builder=new ProcessBuilder(python,checkout+"/tests/support/validation_execution_http_client.py","--base-url","http://127.0.0.1:"+port,"--mode",mode);
        if(mode.startsWith("vector")) {
            var arguments=new ArrayList<>(builder.command());arguments.add("--readonly-url");
            String url="postgresql+psycopg://gh310:gh310-disposable-only@127.0.0.1:"+System.getenv("GH310_EXECUTION_POSTGRES_JDBC_URL").split(":")[3].split("/")[0]+"/gh310_execution_test";
            arguments.add(url);
            // 접수 때 색인 채우기(2026-11-02): 같은 일회용 DB에 쓰기 연결을 주면 Worker가 빈 색인을 채운다.
            if(mode.startsWith("vector-fill")) {arguments.add("--index-url");arguments.add(url);}
            builder.command(arguments);
        }
        builder.directory(new java.io.File(checkout));builder.environment().put("PYTHONPATH",checkout);builder.redirectErrorStream(true);
        return builder;
    }
    com.fasterxml.jackson.databind.JsonNode python(String mode) throws Exception {
        var process=pythonBuilder(mode).start();assertThat(process.waitFor(40,TimeUnit.SECONDS)).as("synthetic worker timeout").isTrue();
        String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isZero();
        String line=output.lines().filter(x->x.startsWith("GH310_WORKER_RESULT ")).reduce((a,b)->b).orElseThrow(()->new AssertionError(output));
        return json.readTree(line.substring("GH310_WORKER_RESULT ".length()));
    }
    Map<String,Object> progress(Fixture f) {return results.getValidationProgress(f.member(),f.work(),f.report());}
    void reclaim(Fixture f) {jdbc.update("update validation_jobs set lease_expires_at=now()-interval '1 second' where report_id=?",f.report());}
    void retry(Fixture f,String key) {var p=progress(f);workers.retryValidation(f.member(),f.work(),f.report(),new Retry(((Number)p.get("reportVersion")).longValue(),key));}
    @Test @DisplayName("실제 Worker 양쪽 정상 0건은 서버 grid를 모두 완료한다")
    void actualWorkerCompletesBothZeroFindingBranches() throws Exception {
        var f=fixture();var response=python("zero");
        assertThat(response.at("/result/status").asText()).isEqualTo("COMPLETED");
        assertThat(response.at("/calls/PLANNER").asInt()).isEqualTo(1);assertThat(response.at("/calls/TYPO").asInt()).isEqualTo(1);
        assertThat(progress(f).get("completedChecks")).isEqualTo(0L);releaseOnly(f);
    }
    @Test @DisplayName("계획 저장 직후 crash·lease회수는 저장 planner를 재호출하지 않는다")
    void actualWorkerRestoresPlanAfterCrashWithoutPlanningCalls() throws Exception {
        var f=fixture();assertThat(python("crash-plan").at("/result/crashed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from validation_checks where report_id=?",Integer.class,f.report())).isEqualTo(1);
        reclaim(f);var resumed=python("inspect");assertThat(resumed.at("/result/status").asText()).as(resumed.toString()).isEqualTo("COMPLETED");
        assertThat(resumed.at("/calls/PLANNER").asInt()).isZero();
        assertThat(resumed.at("/calls/JUDGE").asInt()).isEqualTo(1);releaseOnly(f);
    }
    @Test @DisplayName("정상 NO_CONFLICT 저장 직후 crash는 JUDGE를 재호출하지 않는다")
    void actualWorkerKeepsSuccessfulNoConflictOnReclaim() throws Exception {
        var f=fixture();assertThat(python("crash-decision").at("/result/crashed").asBoolean()).isTrue();
        assertThat(progress(f).get("completedChecks")).isEqualTo(1L);reclaim(f);
        var resumed=python("inspect");assertThat(resumed.at("/result/status").asText()).as(resumed.toString()).isEqualTo("COMPLETED");
        assertThat(resumed.at("/calls/JUDGE").asInt()).isZero();releaseOnly(f);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"all-fail,FAILED","judge-fail,PARTIAL","typo-fail,PARTIAL"})
    @DisplayName("실제 실패와 일부 성공 0finding은 COMPLETED로 추측하지 않는다")
    void actualWorkerSeparatesFailedPartialAndEmptyFindings(String mode,String status) throws Exception {
        var f=fixture();var response=python(mode);assertThat(response.at("/result/status").asText()).isEqualTo(status);
        assertThat(json.valueToTree(progress(f)).path("findings").size()).isZero();noExtraction();
    }
    @Test @DisplayName("DB 현재값만으로 낸 충돌 판정을 finding으로 저장하고 과거 원문을 직접 읽지 않는다")
    void actualWorkerPublishesADbFirstConflict() throws Exception {
        var f=fixture();org.mockito.Mockito.clearInvocations(storage);var response=python("conflict");assertThat(response.at("/result/status").asText()).isEqualTo("COMPLETED");
        var p=json.valueToTree(progress(f));assertThat(p.path("findings").size()).isEqualTo(1);
        assertThat(p.path("completedChecks").asInt()).isEqualTo(1);assertThat(response.at("/calls/QUERY_EMBEDDING").asInt()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from validation_evidence where report_id=? and source_ref='R1'",Integer.class,f.report())).isZero();
        // 판정 작성 제목(2026-11-03): 실제 Worker가 게시한 headline이 검증을 지나 finding에 저장되고 작가 화면 값이 된다.
        var payload=json.readTree(jdbc.queryForObject("select payload::text from validation_findings where report_id=? and branch='SETTING'",String.class,f.report()));
        assertThat(payload.path("headline").asText()).isEqualTo("합성 대상의 확인 항목이 기존 설정과 달라요");
        assertThat(org.monitoring.catchholebackend.domain.validation.processor.ValidationFindingLabels.headline(payload)).isEqualTo("합성 대상의 확인 항목이 기존 설정과 달라요");
        // #381: live report의 finding은 실제 Worker가 옮긴 B 인용(현재 확정 설정 값 leaf)마다 주체·설정 이름표를 이름·key로만 남긴다.
        var settingQuotes=new ArrayList<String>();payload.path("basis_quotes").forEach(q->{if(q.path("ref").asText().matches("B[1-9][0-9]*"))settingQuotes.add(q.path("ref").asText());});
        assertThat(settingQuotes).as("the synthetic judge quotes one stored setting value").hasSize(1);
        var quoted=json.readTree(jdbc.queryForObject("select quoted_subjects::text from validation_findings where report_id=? and branch='SETTING'",String.class,f.report()));
        var character=jdbc.queryForObject("select id::text from characters where work_id=?",String.class,f.work());
        assertThat(quoted.size()).isEqualTo(1);var label=quoted.path(0);
        assertThat(label.path("ref").asText()).isEqualTo(settingQuotes.getFirst());assertThat(label.path("subjectType").asText()).isEqualTo("CHARACTER");
        assertThat(label.path("subjectId").asText()).isEqualTo(character);assertThat(label.path("subjectName").asText()).isEqualTo("합성 인물");
        assertThat(label.path("settingKey").asText()).matches("(AGE|LEVEL):[a-z]+");
        assertThat(java.util.stream.StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(label.fieldNames(),0),false).toList())
                .containsExactlyInAnyOrder("ref","subjectType","subjectId","subjectName","settingKey","settingName");
        org.mockito.Mockito.verify(storage,org.mockito.Mockito.never()).getText(org.mockito.ArgumentMatchers.startsWith("synthetic/prior/"));noExtraction();
    }
    @Test @DisplayName("판정 한 질문의 provider 실패는 그 질문만 INCOMPLETE로 저장하고 작가 재시도는 그 질문만 다시 판정한다(③)")
    void actualWorkerPublishesOneIncompleteQuestionAndRetryJudgesOnlyIt() throws Exception {
        var f=fixture();var first=python("judge-fail");assertThat(first.at("/result/status").asText()).isEqualTo("PARTIAL");
        var p=json.valueToTree(progress(f));
        assertThat(p.path("failureCode").asText()).isEqualTo("SETTING_QUESTIONS_INCOMPLETE");assertThat(p.path("incompleteChecks").asInt()).isEqualTo(1);assertThat(p.path("completedChecks").asInt()).isZero();
        assertThat(p.path("checks").path(0).path("outcome").asText()).isEqualTo("INCOMPLETE");assertThat(p.path("checks").path(0).path("failureCode").asText()).isEqualTo("MODEL_CALL_FAILED");
        assertThat(p.path("checks").path(0).path("targetSpans").path(0).path("quote").asText()).isNotBlank();assertThat(p.path("findings").size()).isZero();
        retry(f,"retry-incomplete-question");var resumed=python("inspect");
        assertThat(resumed.at("/result/status").asText()).as(resumed.toString()).isEqualTo("COMPLETED");
        assertThat(resumed.at("/calls/PLANNER").asInt()).isZero();assertThat(resumed.at("/calls/JUDGE").asInt()).isEqualTo(1);
        assertThat(progress(f).get("incompleteChecks")).isEqualTo(0L);releaseOnly(f);
    }
    @Test @DisplayName("설정집이 있어도 grid v4 Worker는 설정집을 읽지 않고 양쪽 분기를 완료한다")
    void actualWorkerNeverReadsSettingBooks() throws Exception {
        var f=fixture(null,List.of("첫 합성 설정집","둘째 합성 설정집"));
        var bookKeys=tx.execute(s->java.util.stream.StreamSupport.stream(sourceManifests.manifests(f.report()).spliterator(),false).filter(row->"SETTING_BOOK".equals(row.path("sourceKind").asText())).map(row->row.path("contentS3Key").asText()).toList());
        assertThat(bookKeys).hasSize(2);org.mockito.Mockito.clearInvocations(storage);
        var response=python("zero");assertThat(response.at("/result/status").asText()).as(response.toString()).isEqualTo("COMPLETED");
        for(var key:bookKeys)org.mockito.Mockito.verify(storage,org.mockito.Mockito.never()).getText(key);releaseOnly(f);
    }

    void readyIndex(Fixture f) {
        UUID prior=jdbc.queryForObject("select id from episodes where work_id=? and episode_no=100",UUID.class,f.work());String text="앞선 합성 원고";
        jdbc.update("insert into retrieval_sources(episode_id,work_id,source_key,source_version,source_hash,chunk_policy,chunk_count,prepared_at) select id,work_id,content_s3_key,content_s3_version,content_hash,'cl100k-800-100-boundary-v1',1,localtimestamp from episodes where id=?",prior);
        String vector="[1"+",0".repeat(1535)+"]";
        jdbc.update("insert into retrieval_chunks(id,episode_id,chunk_index,chunk_text,start_offset,end_offset,chunk_hash,token_count,embedding,embedding_model,embedding_version,embedded_at,status) values (?,?,0,?,0,?,?,5,CAST(? AS vector),'text-embedding-3-small','v1',localtimestamp,'READY')",UUID.randomUUID(),prior,text,text.length(),hashes.contentHash(text),vector);
    }
    @Test @DisplayName("DB로 판단할 수 없을 때만 판정이 요청한 의미 검색을 frozen 색인에서 한 번 실행하고 과거 회차 근거를 인용해 FINAL로 결정한다")
    void actualWorkerRunsOneJudgeRequestedVectorSearch() throws Exception {
        var f=fixture();readyIndex(f);var response=python("vector-inspect");
        assertThat(response.at("/result/status").asText()).as(response.toString()).isEqualTo("COMPLETED");
        assertThat(response.at("/calls/QUERY_EMBEDDING").asInt()).isEqualTo(1);assertThat(response.at("/calls/JUDGE").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from validation_evidence where report_id=? and source_ref='R1'",Integer.class,f.report())).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from ai_token_usages where purpose='VALIDATION_QUERY_EMBEDDING' and status='SETTLED'",Integer.class)).isEqualTo(1);releaseOnly(f);
    }
    @Test @DisplayName("검색 결정 저장 직후 crash·lease회수는 판정·임베딩을 다시 호출하지 않는다")
    void actualWorkerKeepsAPublishedVectorDecisionOnReclaim() throws Exception {
        var f=fixture();readyIndex(f);var first=python("vector-lookup-crash");
        assertThat(first.at("/calls/QUERY_EMBEDDING").asInt()).isEqualTo(1);assertThat(first.at("/result/crashed").asBoolean()).isTrue();
        assertThat(progress(f).get("completedChecks")).isEqualTo(1L);reclaim(f);
        var resumed=python("vector-inspect");assertThat(resumed.at("/result/status").asText()).as(resumed.toString()).isEqualTo("COMPLETED");
        assertThat(resumed.at("/calls/QUERY_EMBEDDING").asInt()).isZero();assertThat(resumed.at("/calls/JUDGE").asInt()).isZero();releaseOnly(f);
    }
    @Test @DisplayName("과거 회차 색인이 준비되지 않았으면 검색을 제안하지 않고 검색이 필요한 질문만 VECTOR_NOT_READY INCOMPLETE로 남긴다")
    void actualWorkerWithoutAReadyIndexLeavesTheSearchQuestionIncomplete() throws Exception {
        var f=fixture();var response=python("vector-inspect");
        assertThat(response.at("/result/status").asText()).as(response.toString()).isEqualTo("PARTIAL");assertThat(response.at("/calls/QUERY_EMBEDDING").asInt()).isZero();
        var p=json.valueToTree(progress(f));assertThat(p.path("incompleteChecks").asInt()).isEqualTo(1);assertThat(p.path("checks").path(0).path("failureCode").asText()).isEqualTo("VECTOR_NOT_READY");noExtraction();
    }
    int count(String sql,Object... args) {return jdbc.queryForObject(sql,Integer.class,args);}
    void filledIndex(Fixture f) {
        assertThat(count("select count(*) from retrieval_chunks c join retrieval_sources s on s.episode_id=c.episode_id where s.work_id=? and c.status='READY'",f.work())).isEqualTo(1);
        assertThat(count("select count(*) from retrieval_setting_book_chunks c join retrieval_setting_book_sources s on s.revision_id=c.revision_id where s.work_id=? and c.status='READY'",f.work())).isEqualTo(1);
    }
    @Test @DisplayName("접수 때 빈 색인(앞 회차·현재 설정집)을 채운 뒤 의미 검색이 설정집 조각도 근거로 돌려주고 서버가 설정집 원문으로 인용을 검증한다")
    void actualWorkerFillsAnEmptyIndexAtIntakeAndCitesTheSettingBook() throws Exception {
        var f=fixture(null,List.of("첫 합성 설정집"));
        assertThat(count("select count(*) from retrieval_chunks c join retrieval_sources s on s.episode_id=c.episode_id where s.work_id=?",f.work())).isZero();
        var manifest=tx.execute(s->sourceManifests.manifests(f.report()));
        var book=java.util.stream.StreamSupport.stream(manifest.spliterator(),false).filter(row->"SETTING_BOOK".equals(row.path("sourceKind").asText())).findFirst().orElseThrow();
        assertThat(book.path("currentRevision").asBoolean()).isTrue();
        var response=python("vector-fill-inspect");
        assertThat(response.at("/result/status").asText()).as(response.toString()).isEqualTo("COMPLETED");
        assertThat(response.at("/calls/INDEX_EMBEDDING").asInt()).isEqualTo(2);assertThat(response.at("/calls/QUERY_EMBEDDING").asInt()).isEqualTo(1);
        assertThat(response.at("/calls/JUDGE").asInt()).isEqualTo(2);filledIndex(f);
        // R1=앞 회차, R2=설정집 현재 판. 설정집 조각 인용은 서버가 hash 확인한 설정집 전문을 code point로 잘라 검증해 저장한다.
        assertThat(count("select count(*) from validation_evidence where report_id=? and source_ref='R1'",f.report())).isPositive();
        assertThat(count("select count(*) from validation_evidence where report_id=? and source_ref='R2' and quote='첫 합성 설정집'",f.report())).isPositive();
        assertThat(count("select count(*) from ai_token_usages where purpose='VALIDATION_INDEX_EMBEDDING' and status='SETTLED' and charge_policy_version='SERVICE_PAID_V1' and charged_tokens=0 and reserved_charge=0",new Object[0])).isEqualTo(2);
        assertThat(count("select count(*) from ai_token_usages where purpose='VALIDATION_INDEX_EMBEDDING' and validation_call_input->>'sourceRef' in ('R1','R2')",new Object[0])).isEqualTo(2);
        assertThat(jdbc.queryForObject("select reserved_tokens from ai_token_accounts where member_id=?",java.math.BigDecimal.class,f.member())).isEqualByComparingTo("0");
        releaseOnly(f);
    }
    @Test @DisplayName("색인 채우기의 provider 실패는 실행을 멈추지 않고 검색이 필요한 질문만 VECTOR_INDEX_FILL_FAILED로 남기며 작가 재시도가 빈 곳만 채운다")
    void actualWorkerKeepsJudgingAfterAFailedFillAndRetryFillsOnlyTheGaps() throws Exception {
        var f=fixture(null,List.of("첫 합성 설정집"));var first=python("vector-fill-fail");
        assertThat(first.at("/result/status").asText()).as(first.toString()).isEqualTo("PARTIAL");
        assertThat(first.at("/calls/QUERY_EMBEDDING").asInt()).isZero();
        var p=json.valueToTree(progress(f));assertThat(p.path("incompleteChecks").asInt()).isEqualTo(1);
        assertThat(p.path("checks").path(0).path("failureCode").asText()).isEqualTo("VECTOR_INDEX_FILL_FAILED");
        assertThat(p.path("checks").path(0).path("failureCause").asText()).isEqualTo("INDEX_FILL_PROVIDER_FAILED");
        assertThat(count("select count(*) from retrieval_chunks c join retrieval_sources s on s.episode_id=c.episode_id where s.work_id=? and c.status='READY'",f.work())).isZero();
        retry(f,"retry-after-fill-failure");var resumed=python("vector-fill-inspect");
        assertThat(resumed.at("/result/status").asText()).as(resumed.toString()).isEqualTo("COMPLETED");
        assertThat(resumed.at("/calls/INDEX_EMBEDDING").asInt()).isEqualTo(2);assertThat(resumed.at("/calls/PLANNER").asInt()).isZero();
        filledIndex(f);assertThat(progress(f).get("incompleteChecks")).isEqualTo(0L);releaseOnly(f);
    }
    @Test @DisplayName("색인을 채우고 검색 결정을 저장한 직후 crash·lease회수는 이미 READY인 조각을 다시 임베딩하지 않는다")
    void actualWorkerNeverReembedsAFilledIndexOnReclaim() throws Exception {
        var f=fixture(null,List.of("첫 합성 설정집"));var first=python("vector-fill-crash");
        assertThat(first.at("/result/crashed").asBoolean()).isTrue();assertThat(first.at("/calls/INDEX_EMBEDDING").asInt()).isEqualTo(2);
        filledIndex(f);var before=jdbc.queryForList("select id,embedded_at from retrieval_chunks c join retrieval_sources s on s.episode_id=c.episode_id where s.work_id=?",f.work());
        reclaim(f);var resumed=python("vector-fill-inspect");
        assertThat(resumed.at("/result/status").asText()).as(resumed.toString()).isEqualTo("COMPLETED");
        assertThat(resumed.at("/calls/INDEX_EMBEDDING").asInt()).isZero();assertThat(resumed.at("/calls/QUERY_EMBEDDING").asInt()).isZero();
        assertThat(jdbc.queryForList("select id,embedded_at from retrieval_chunks c join retrieval_sources s on s.episode_id=c.episode_id where s.work_id=?",f.work())).isEqualTo(before);
        releaseOnly(f);
    }
    @Test @DisplayName("대상 문맥 추가 읽기로 결정한 판정 저장 직후 crash는 게시된 질문을 다시 판정하지 않는다")
    void actualWorkerKeepsPublishedDecisionsAfterAFollowUpCrash() throws Exception {
        var f=fixture();assertThat(python("followup-crash").at("/result/crashed").asBoolean()).isTrue();
        int decided=jdbc.queryForObject("select count(*) from validation_checks where report_id=? and outcome='NO_CONFLICT'",Integer.class,f.report());
        assertThat(decided).isBetween(1,3);assertThat(jdbc.queryForObject("select count(*) from validation_checks where report_id=? and revision<>1",Integer.class,f.report())).as("grid v4는 revision을 올리지 않는다").isZero();
        assertThat(jdbc.queryForObject("select count(*) from validation_checks where report_id=? and local_ref='Q3' and outcome='NO_CONFLICT'",Integer.class,f.report())).isEqualTo(1);
        reclaim(f);org.mockito.Mockito.clearInvocations(storage);var resumed=python("followup-resume");
        assertThat(resumed.at("/result/status").asText()).as(resumed.toString()).isEqualTo("COMPLETED");
        assertThat(resumed.at("/judges/Q3").asInt()).isZero();assertThat(resumed.at("/calls/JUDGE").asInt()).isEqualTo(3-decided);
        assertThat(resumed.at("/calls/PLANNER").asInt()).isZero();releaseOnly(f);
    }

    org.monitoring.catchholebackend.domain.validation.entity.ValidationJob currentJob(Fixture f) {
        return tx.execute(s->em.createQuery("select j from ValidationJob j where j.report.id=:r",org.monitoring.catchholebackend.domain.validation.entity.ValidationJob.class).setParameter("r",f.report()).getSingleResult());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"replace","delete","expire","work-purge"})
    @DisplayName("실제 Worker의 provider 대기 중 입력 무효화·파기는 늦은 게시를 막고 실제 사용량만 정산한다")
    void actualWorkerRejectsLatePublicationButKeepsMeasuredUsage(String action) throws Exception {
        var f=fixture();var release=java.nio.file.Files.createTempDirectory("gh310-provider-release-").resolve("release");
        var builder=pythonBuilder("inspect");var arguments=new ArrayList<>(builder.command());arguments.add("--wait-file");arguments.add(release.toString());builder.command(arguments);var process=builder.start();
        var reader=new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
        try {
            var ready=CompletableFuture.supplyAsync(()->{try {String line;while((line=reader.readLine())!=null)if(line.equals("GH310_PROVIDER_READY"))return true;return false;}catch(java.io.IOException ex){throw new RuntimeException(ex);}});
            assertThat(ready.get(20,TimeUnit.SECONDS)).isTrue();
            String token=tx.execute(s->tokens.generateAccessToken(em.find(Member.class,f.member())));
            var before=progress(f);var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/v1/works/"+f.work()+"/episode-validations/"+f.report()+"/results")).header("Authorization","Bearer "+token).GET().build();
            var read=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(read.statusCode()).isEqualTo(200);assertThat(read.body()).doesNotContain("evidence_bundle","lookup_intents","leaseToken","checkInputDigest");assertThat(progress(f).get("reportVersion")).isEqualTo(before.get("reportVersion"));
            switch(action) {
                case "replace" -> episodes.replaceEpisodeFile(f.member(),f.work(),f.target(),file("다른 합성 원고"));
                case "delete" -> episodes.deleteEpisode(f.member(),f.work(),f.target());
                case "expire" -> reclaim(f);
                default -> purges.requestPurge(f.member(),f.work(),new WorkPurgeCreateRequest("합성 파기"));
            }
            java.nio.file.Files.writeString(release,"release");assertThat(process.waitFor(25,TimeUnit.SECONDS)).isTrue();
            String output=reader.lines().collect(java.util.stream.Collectors.joining("\n"));assertThat(process.exitValue()).as(output).isZero();assertThat(output).contains("stopped");
            assertThat(jdbc.queryForObject("select count(*) from validation_checks where report_id=? and outcome is not null",Integer.class,f.report())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from ai_token_usages where validation_job_id is not null and purpose='VALIDATION_JUDGE' and status='SETTLED'",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select reserved_tokens from ai_token_accounts where member_id=?",java.math.BigDecimal.class,f.member())).isEqualByComparingTo("0");
            if(!action.equals("expire"))zeroCopies(f.report());noExtraction();
        } finally {process.destroyForcibly();java.nio.file.Files.deleteIfExists(release);java.nio.file.Files.deleteIfExists(release.getParent());}
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"PLANNER","TYPO"})
    @DisplayName("한 분기의 실제 role cap409는 독립 정상 분기의 실행·정산을 취소하지 않는다")
    void actualWorkerKeepsOtherBranchAfterRoleCapRejection(String capped) throws Exception {
        var role=ValidationRole.valueOf(capped);var limits=new java.util.EnumMap<ValidationRole,org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget>(executionProperties.roles());
        var frozen=limits.get(role);limits.put(role,new org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget(1,22000,frozen.outputTokens(),frozen.model(),frozen.reasoningEffort()));when(executionProperties.roles()).thenReturn(limits);
        var f=fixture();var old=claim();UUID call=UUID.randomUUID();usage.reserveValidationUsage(id(old),lease(old),new Reserve(scope(old),call,role,"gpt-6.1-terra",100));
        usage.reportValidationUsage(id(old),lease(old),call,new Usage(scope(old),UsageKind.SETTLE,23L,0L,7L,org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome.SUCCESS));
        reclaim(f);var response=python("zero");assertThat(response.at("/result/status").asText()).as(response.toString()).isEqualTo("PARTIAL");
        assertThat(response.at("/calls/"+capped).asInt()).isZero();
        assertThat(response.at("/result/"+(capped.equals("PLANNER")?"typoStatus":"settingStatus")).asText()).isEqualTo("COMPLETED");noExtraction();
    }

}
