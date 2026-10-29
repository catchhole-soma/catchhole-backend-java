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
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.config.import=","spring.docker.compose.enabled=false","internal.api-key=gh310-synthetic-internal","validation.execution.roles.QUERY_EMBEDDING.max-calls=1","validation.execution.roles.QUERY_EMBEDDING.max-reserved-tokens=1000","validation.execution.query-embedding-model=text-embedding-3-small"}) @ActiveProfiles("test") @DirtiesContext
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
    Map<String,Object> batch(List<?> questions) {return Map.of("questions",questions,"segments",List.of(Map.of("segment_ref","TSEG1","status",questions.isEmpty()?"NO_CHECK":"PLANNED","reason","합성 완료")),"rules",List.of());}
    Map<String,Object> planner(Map<String,Object> j) {return results.publishValidationPlan(id(j),lease(j),plan(j,ValidationStageKind.PLANNER,"TSEG1",batch(List.of())));}
    void expire(Map<String,Object> j) {jdbc.update("update validation_jobs set lease_expires_at=now()-interval '1 second' where id=?",id(j));}
    void noExtraction() {for(String table:List.of("analysis_jobs","setting_candidates","world_setting_candidates"))assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).as(table).isZero();}
    void zeroCopies(UUID report) {for(String table:List.of("validation_stages","validation_checks","validation_check_history","validation_findings","validation_evidence","validation_checkpoints","validation_publications"))assertThat(jdbc.queryForObject("select count(*) from "+table+" where report_id=?",Integer.class,report)).as(table).isZero();}
    static void await(CountDownLatch latch) {try {if(!latch.await(15,TimeUnit.SECONDS))throw new AssertionError("synthetic latch timed out");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);}}

    com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
    ProcessBuilder pythonBuilder(String mode) {
        String checkout=System.getenv("GH310_AI_CHECKOUT"),python=System.getenv("GH310_PYTHON");
        assertThat(checkout).isNotBlank();assertThat(python).isNotBlank();
        var builder=new ProcessBuilder(python,checkout+"/tests/support/validation_execution_http_client.py","--base-url","http://127.0.0.1:"+port,"--mode",mode);
        if(mode.startsWith("vector")) {
            var arguments=new ArrayList<>(builder.command());arguments.add("--readonly-url");
            arguments.add("postgresql+psycopg://gh310:gh310-disposable-only@127.0.0.1:"+System.getenv("GH310_EXECUTION_POSTGRES_JDBC_URL").split(":")[3].split("/")[0]+"/gh310_execution_test");builder.command(arguments);
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
        assertThat(progress(f).get("completedChecks")).isEqualTo(0L);noExtraction();
    }
    @Test @DisplayName("계획 저장 직후 crash·lease회수는 저장 planner/coverage를 재호출하지 않는다")
    void actualWorkerRestoresPlanAfterCrashWithoutPlanningCalls() throws Exception {
        var f=fixture();assertThat(python("crash-plan").at("/result/crashed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from validation_checks where report_id=?",Integer.class,f.report())).isEqualTo(1);
        reclaim(f);var resumed=python("inspect");assertThat(resumed.at("/result/status").asText()).as(resumed.toString()).isEqualTo("COMPLETED");
        for(String role:List.of("PLANNER","RELATED_SELECTION","COVERAGE"))assertThat(resumed.at("/calls/"+role).asInt()).isZero();
        assertThat(resumed.at("/calls/JUDGE").asInt()).isEqualTo(1);noExtraction();
    }
    @Test @DisplayName("정상 NO_CONFLICT 저장 직후 crash는 JUDGE를 재호출하지 않는다")
    void actualWorkerKeepsSuccessfulNoConflictOnReclaim() throws Exception {
        var f=fixture();assertThat(python("crash-decision").at("/result/crashed").asBoolean()).isTrue();
        assertThat(progress(f).get("completedChecks")).isEqualTo(1L);reclaim(f);
        var resumed=python("inspect");assertThat(resumed.at("/result/status").asText()).as(resumed.toString()).isEqualTo("COMPLETED");
        assertThat(resumed.at("/calls/JUDGE").asInt()).isZero();noExtraction();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"all-fail,FAILED","judge-fail,PARTIAL","typo-fail,PARTIAL"})
    @DisplayName("실제 실패와 일부 성공 0finding은 COMPLETED로 추측하지 않는다")
    void actualWorkerSeparatesFailedPartialAndEmptyFindings(String mode,String status) throws Exception {
        var f=fixture();var response=python(mode);assertThat(response.at("/result/status").asText()).isEqualTo(status);
        assertThat(json.valueToTree(progress(f)).path("findings").size()).isZero();noExtraction();
    }
    @Test @DisplayName("실제 과거 원문 직접조회와 정확한 인용을 finding으로 저장한다")
    void actualWorkerPublishesPastSourceEvidence() throws Exception {
        var f=fixture();var response=python("conflict");assertThat(response.at("/result/status").asText()).isEqualTo("COMPLETED");
        var p=json.valueToTree(progress(f));assertThat(p.path("findings").size()).isEqualTo(1);
        assertThat(p.path("completedChecks").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from validation_evidence where report_id=? and source_ref='R1'",Integer.class,f.report())).isPositive();noExtraction();
    }
    void consumeEmbedding(Fixture f) {
        var j=tx.execute(s->em.createQuery("select j from ValidationJob j where j.report.id=:r",org.monitoring.catchholebackend.domain.validation.entity.ValidationJob.class).setParameter("r",f.report()).getSingleResult());
        var proof=new QueryInput(1,j.getInputDigest(),"a".repeat(64),"text-embedding-3-small","v1",1536,j.getManifestHash());UUID request=UUID.randomUUID();
        usage.reserveValidationUsage(j.getId(),j.getLeaseToken(),new Reserve(new Scope(1,f.report(),j.getGeneration(),j.getInputDigest()),request,ValidationRole.QUERY_EMBEDDING,"text-embedding-3-small",30,proof));
        usage.reportValidationUsage(j.getId(),j.getLeaseToken(),request,new Usage(new Scope(1,f.report(),j.getGeneration(),j.getInputDigest()),UsageKind.SETTLE,10L,0L,0L,org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome.SUCCESS));
    }
    @Test @DisplayName("실제 필요한 새 VECTOR의 cap이 소진되면 retry queue 전에 거절한다")
    void retryPreflightsActuallyMissingEmbeddingBeforeQueueing() throws Exception {
        var f=fixture();var response=python("vector-missing-crash");assertThat(response.at("/result/crashed").asBoolean()).isTrue();
        // No index was ready, so the failed read has no reusable receipt. Consume the synthetic role cap.
        consumeEmbedding(f);
        var job=tx.execute(s->em.createQuery("select j from ValidationJob j where j.report.id=:r",org.monitoring.catchholebackend.domain.validation.entity.ValidationJob.class).setParameter("r",f.report()).getSingleResult());
        results.completeValidationJob(job.getId(),job.getLeaseToken(),new Complete(new Scope(1,f.report(),job.getGeneration(),job.getInputDigest()),"synthetic-stop", "VECTOR_NOT_READY"));
        assertThatThrownBy(()->retry(f,"embedding-cap-missing")).isInstanceOf(AppException.class)
            .satisfies(ex->assertThat(((AppException)ex).getResultCode().getCode()).isEqualTo("VALIDATION_EXECUTION_LIMIT_EXCEEDED"));noExtraction();
    }

    @Test @DisplayName("성공 첫 책 처리와 target 계획은 다른 책 실패·crash 뒤 추가 호출 없이 복원한다")
    void actualWorkerKeepsFirstBookBeforeSecondBookReadFailure() throws Exception {
        var f=fixture(null,List.of("첫 합성 설정집","둘째 합성 설정집"));
        var bookKeys=tx.execute(s->java.util.stream.StreamSupport.stream(em.find(org.monitoring.catchholebackend.domain.validation.entity.ValidationReport.class,f.report()).getBaselineSeed().path("sources").spliterator(),false).filter(row->"SETTING_BOOK".equals(row.path("sourceKind").asText())).map(row->row.path("contentS3Key").asText()).toList());
        String first=bookKeys.get(0),second=bookKeys.get(1);
        String missing=texts.remove(second);assertThat(python("book-failure-crash").at("/result/crashed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from validation_stages where report_id=? and stage='BOOK_COVERAGE' and successful",Integer.class,f.report())).isEqualTo(1);
        texts.put(second,missing);reclaim(f);org.mockito.Mockito.clearInvocations(storage);
        var resumed=python("zero");assertThat(resumed.at("/result/status").asText()).as(resumed.toString()).isEqualTo("COMPLETED");
        assertThat(resumed.at("/calls/PLANNER").asInt()).isZero();assertThat(resumed.at("/calls/COVERAGE").asInt()).isEqualTo(1);
        org.mockito.Mockito.verify(storage,org.mockito.Mockito.never()).getText(first);noExtraction();
    }

    void readyIndex(Fixture f) {
        UUID prior=jdbc.queryForObject("select id from episodes where work_id=? and episode_no=100",UUID.class,f.work());String text="앞선 합성 원고";
        jdbc.update("insert into retrieval_sources(episode_id,work_id,source_key,source_version,source_hash,chunk_policy,chunk_count,prepared_at) select id,work_id,content_s3_key,content_s3_version,content_hash,'cl100k-800-100-boundary-v1',1,localtimestamp from episodes where id=?",prior);
        String vector="[1"+",0".repeat(1535)+"]";
        jdbc.update("insert into retrieval_chunks(id,episode_id,chunk_index,chunk_text,start_offset,end_offset,chunk_hash,token_count,embedding,embedding_model,embedding_version,embedded_at,status) values (?,?,0,?,0,?,?,5,CAST(? AS vector),'text-embedding-3-small','v1',localtimestamp,'READY')",UUID.randomUUID(),prior,text,text.length(),hashes.contentHash(text),vector);
    }
    @Test @DisplayName("실제 호환 VECTOR 영수증은 소진 cap에서도 JUDGE만 재개한다")
    void actualWorkerReusesSuccessfulVectorReceiptWithExhaustedEmbeddingCap() throws Exception {
        var f=fixture();readyIndex(f);var first=python("vector-judge-fail");
        assertThat(first.at("/calls/QUERY_EMBEDDING").asInt()).as(first.toString()).isEqualTo(1);assertThat(first.at("/result/status").asText()).isEqualTo("PARTIAL");
        // The successful original lookup is frozen; rebuilding the live index is not a new obligation.
        jdbc.update("update retrieval_chunks set status='FAILED',embedding=null");
        retry(f,"reuse-vector");var resumed=python("vector-inspect");
        assertThat(resumed.at("/result/status").asText()).as(resumed.toString()).isEqualTo("COMPLETED");assertThat(resumed.at("/calls/QUERY_EMBEDDING").asInt()).isZero();
        assertThat(resumed.at("/calls/JUDGE").asInt()).isEqualTo(1);noExtraction();
    }
    @Test @DisplayName("원자 FOLLOW_UP 직후 crash는 영향 질문만 다시 판단한다")
    void actualWorkerRestoresAtomicFollowupRevisionAfterCrash() throws Exception {
        var f=fixture();assertThat(python("followup-crash").at("/result/crashed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from validation_checks where report_id=? and outcome='NO_CONFLICT'",Integer.class,f.report())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from validation_checks where report_id=? and revision=2",Integer.class,f.report())).isEqualTo(2);
        reclaim(f);org.mockito.Mockito.clearInvocations(storage);var resumed=python("followup-resume");
        assertThat(resumed.at("/result/status").asText()).as(resumed.toString()).isEqualTo("COMPLETED");
        assertThat(resumed.at("/judges/Q1").asInt()).isEqualTo(1);assertThat(resumed.at("/judges/Q3").asInt()).isEqualTo(1);assertThat(resumed.at("/judges/Q2").asInt()).isZero();
        for(String role:List.of("PLANNER","RELATED_SELECTION","COVERAGE"))assertThat(resumed.at("/calls/"+role).asInt()).isZero();noExtraction();
    }

    org.monitoring.catchholebackend.domain.validation.entity.ValidationJob currentJob(Fixture f) {
        return tx.execute(s->em.createQuery("select j from ValidationJob j where j.report.id=:r",org.monitoring.catchholebackend.domain.validation.entity.ValidationJob.class).setParameter("r",f.report()).getSingleResult());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"queryHash","inputDigest","manifestHash","indexModel","indexVersion","indexDimensions","requestId","receiptFailure","vectorPartial","receiptNotReady"})
    @DisplayName("성공 usageID만으로 다른 질의·입력·색인 또는 실패 receipt를 재사용하지 않는다")
    void incompatibleVectorReceiptCannotBypassExhaustedRetry(String mismatch) throws Exception {
        var f=fixture();readyIndex(f);var first=python("vector-lookup-crash");assertThat(first.at("/calls/QUERY_EMBEDDING").asInt()).isEqualTo(1);assertThat(first.at("/result/crashed").asBoolean()).isTrue();
        var job=currentJob(f);var scope=new Scope(1,f.report(),job.getGeneration(),job.getInputDigest());
        var view=workers.loadValidationCheckpoint(job.getId(),job.getLeaseToken());var payload=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(view).at("/branches/SETTING/payload").deepCopy();
        var receipt=(com.fasterxml.jackson.databind.node.ObjectNode)java.util.stream.StreamSupport.stream(payload.at("/runtime/receipts").spliterator(),false).filter(r->"VECTOR".equals(r.at("/intent/operation").asText())).findFirst().orElseThrow();
        var proof=(com.fasterxml.jackson.databind.node.ObjectNode)receipt.at("/readiness/query_embedding");
        switch(mismatch) {
            case "indexDimensions" -> proof.put(mismatch,1024);
            case "indexVersion" -> proof.put(mismatch,"different-index");
            case "indexModel" -> proof.put(mismatch,"different-model");
            case "requestId" -> proof.put(mismatch,UUID.randomUUID().toString());
            case "receiptFailure" -> receipt.put("status","FAILED");
            case "vectorPartial" -> ((com.fasterxml.jackson.databind.node.ObjectNode)receipt.path("readiness")).put("vector_status","PARTIAL");
            case "receiptNotReady" -> ((com.fasterxml.jackson.databind.node.ObjectNode)receipt.path("readiness")).put("vector_status","NOT_READY");
            default -> proof.put(mismatch,"b".repeat(64));
        }
        var citations=jdbc.query("select distinct source_ref,source_hash,source_version,start_cp,end_cp,quote from validation_evidence where report_id=?",(rs,n)->new Citation(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getInt(5),rs.getString(6)),f.report());
        long version=((Number)((Map<?,?>)((Map<?,?>)view.get("branches")).get("SETTING")).get("version")).longValue();
        results.publishValidationCheckpoint(job.getId(),job.getLeaseToken(),new Checkpoint(scope,"synthetic-mismatch-"+mismatch,ValidationBranch.SETTING,version,json.convertValue(payload,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}),citations));
        results.completeValidationJob(job.getId(),job.getLeaseToken(),new Complete(scope,"stop-incompatible","MODEL_CALL_FAILED"));
        assertThatThrownBy(()->retry(f,"reject-incompatible-"+mismatch)).isInstanceOfSatisfying(AppException.class,e->assertThat(e.getResultCode().getCode()).isEqualTo("VALIDATION_EXECUTION_LIMIT_EXCEEDED"));noExtraction();
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
        limits.put(role,new org.monitoring.catchholebackend.global.config.validation.ValidationExecutionProperties.RoleBudget(1,22000));when(executionProperties.roles()).thenReturn(limits);
        var f=fixture();var old=claim();UUID call=UUID.randomUUID();usage.reserveValidationUsage(id(old),lease(old),new Reserve(scope(old),call,role,"gpt-6.1-terra",100));
        usage.reportValidationUsage(id(old),lease(old),call,new Usage(scope(old),UsageKind.SETTLE,23L,0L,7L,org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome.SUCCESS));
        reclaim(f);var response=python("zero");assertThat(response.at("/result/status").asText()).as(response.toString()).isEqualTo("PARTIAL");
        assertThat(response.at("/calls/"+capped).asInt()).isZero();
        assertThat(response.at("/result/"+(capped.equals("PLANNER")?"typoStatus":"settingStatus")).asText()).isEqualTo("COMPLETED");noExtraction();
    }

}
