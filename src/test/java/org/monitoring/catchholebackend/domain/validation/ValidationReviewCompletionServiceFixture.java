package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.service.EpisodeService;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeUploadType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.*;
import org.monitoring.catchholebackend.domain.validation.dto.request.*;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.monitoring.catchholebackend.domain.validation.service.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.storage.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GH312 S2 검토 종료를 실제 커밋 경계로 검증하는 service-level fixture. 테스트 트랜잭션을 쓰지 않으므로
 * /complete의 자동 release 보류(첫 트랜잭션 rollback 뒤 재실행)와 동시 요청을 그대로 관찰한다. 원고·모델 호출은 합성이다.
 */
abstract class ValidationReviewCompletionServiceFixture {
    static final String TEXT="처음 합성 원고";
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EpisodeService episodes;
    @Autowired EpisodeValidationService admissions;
    @Autowired ValidationWorkerService workers;
    @Autowired ValidationResultService results;
    @Autowired ValidationReviewService reviews;
    @Autowired ValidationReviewCompletionService completions;
    @Autowired AiTokenService accounts;
    @Autowired ObjectStorageService hashes;
    @MockitoBean ObjectStorage storage;
    final Map<String,String> texts=new ConcurrentHashMap<>();
    TransactionTemplate tx;
    record Fixture(Long member,UUID work,UUID target,UUID report,String targetHash) {}
    record Run(Map<String,Object> job,Map<String,Object> done) {
        Map<String,Object> replay(ValidationResultService results) {return results.completeValidationJob(id(job),lease(job),new Complete(scope(job),"done",null));}
        /** 저장 receipt는 JSON transport 값이므로 원 응답과 JSON으로 비교한다. */
        boolean sameReceipt(Map<String,Object> other) {
            try {var m=new com.fasterxml.jackson.databind.ObjectMapper();return m.readTree(m.writeValueAsString(done)).equals(m.readTree(m.writeValueAsString(other)));}
            catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException(e);}
        }
    }

    @BeforeEach void initializeStorage() {
        tx=new TransactionTemplate(transactions);texts.clear();
        when(storage.putBytes(anyString(),any(byte[].class),anyString())).thenAnswer(c->{texts.put(c.getArgument(0),new String(c.getArgument(1,byte[].class),StandardCharsets.UTF_8));return new StoredObject(c.getArgument(0),"synthetic-v1");});
        when(storage.putText(anyString(),anyString())).thenAnswer(c->{texts.put(c.getArgument(0),c.getArgument(1));return new StoredObject(c.getArgument(0),"synthetic-v1");});
        when(storage.getText(anyString())).thenAnswer(c->{var text=texts.get(c.getArgument(0));if(text==null)throw new IllegalStateException("synthetic source absent");return text;});
        when(storage.purgePrefixes(anyCollection())).thenReturn(new ObjectStoragePurgeResult(1,1,0));when(storage.purgePrefixesExcluding(anyCollection(),anyCollection())).thenReturn(new ObjectStoragePurgeResult(1,1,0));
    }
    Fixture fixture() {return tx.execute(s->{
        String phone="010"+String.format("%08d",new Random().nextInt(100_000_000));
        var member=Member.register("review-completion-"+UUID.randomUUID()+"@example.invalid","synthetic-only",phone,"합성 작가");em.persist(member);
        var work=Work.create(member,"검토 종료 합성",WorkGenre.FANTASY,null);em.persist(work);String prior="합성 앞 원고";texts.put("synthetic/prior/"+work.getId(),prior);
        em.persist(Episode.create(work,null,100,"앞 회차","synthetic/prior/"+work.getId(),"v1",hashes.contentHash(prior),prior.length()));work.updateLatestEpisodeNo(100);
        em.persist(WorkCharacter.create(work,"합성 인물",null,20,1,null,null,null,null,null,null));
        var batch=episodes.uploadEpisodes(member.getId(),work.getId(),new EpisodeUploadRequest(EpisodeUploadType.SINGLE_EPISODE,101,"합성",null),
                List.of(new MockMultipartFile("episodeFiles","101.txt","text/plain",TEXT.getBytes(StandardCharsets.UTF_8))));em.flush();
        var e=em.createQuery("select e from Episode e where e.work=:w and e.episodeNo=101",Episode.class).setParameter("w",work).getSingleResult();
        var report=admissions.start(member.getId(),work.getId(),new ValidationStartRequest("initial",batch.batchId(),e.getId(),e.getContentHash(),e.getContentS3Key(),e.getContentS3Version()));
        return new Fixture(member.getId(),work.getId(),e.getId(),report.reportId(),e.getContentHash());
    });}
    Map<String,Object> claim() {return workers.claimValidationJob(new Claim(1,"synthetic-worker",Set.of("VALIDATION_EXECUTION_V1")));}
    static UUID id(Map<String,Object> j) {return (UUID)j.get("jobId");}
    static UUID lease(Map<String,Object> j) {return (UUID)j.get("leaseToken");}
    static Scope scope(Map<String,Object> j) {return (Scope)j.get("scope");}
    static Map<String,Object> batch(List<?> questions) {return Map.of("questions",questions,"segments",List.of(Map.of("segment_ref","TSEG1","status",questions.isEmpty()?"NO_CHECK":"PLANNED","reason","합성 완료")),"rules",List.of());}
    void plan(Map<String,Object> j,ValidationStageKind kind,String ref,Map<String,Object> body) {results.publishValidationPlan(id(j),lease(j),new Publish(scope(j),kind+":"+ref,ValidationBranch.SETTING,kind,ref,body,List.of(),null));}
    /** 설정 질문 0건과 오탈자 구간 하나로 실행을 끝낸다. typoFinding이면 판단할 오탈자 제안 하나를 남긴다. */
    Run runToCompletion(Fixture f,boolean typoFinding) {
        var j=claim();assertThat(j).isNotNull();
        int entries=tx.execute(s->em.find(ValidationReport.class,f.report()).getBaselineEntries().size());var selected=new ArrayList<Object>();
        for(int i=1;i<=entries;i++)selected.add(Map.of("entry_ref","B"+i,"selected",false,"reason","합성 비선정"));
        plan(j,ValidationStageKind.PLANNER,"TSEG1",batch(List.of()));plan(j,ValidationStageKind.RELATED_SELECTION,"TSEG1:PAGE1",Map.of("entries",selected));
        plan(j,ValidationStageKind.COVERAGE,"TSEG1",batch(List.of()));plan(j,ValidationStageKind.MERGED_PLAN,"PLAN",Map.of("questions",List.of()));
        Map<String,Object> typo=typoFinding
                ?Map.of("segments",List.of(Map.of("segment_ref","TSEG1","status","PLANNED","reason","제안 있음")),"suggestions",List.of(Map.of("category","SPELLING","reason","맞춤법 제안","replacement","원교","span",Map.of("source_ref","T1","start",6,"end",8,"quote","원고"))))
                :Map.of("segments",List.of(Map.of("segment_ref","TSEG1","status","NO_CHECK","reason","오탈자 없음")),"suggestions",List.of());
        List<Citation> citations=typoFinding?List.of(new Citation("T1",f.targetHash(),"synthetic-v1",6,8,"원고")):List.of();
        results.publishValidationUnit(id(j),lease(j),new Unit(scope(j),"typo",ValidationBranch.TYPO,"TSEG1",null,null,null,"COMPLETED",typo,citations));
        var done=results.completeValidationJob(id(j),lease(j),new Complete(scope(j),"done",null));
        assertThat(done.get("status").toString()).isEqualTo("COMPLETED");return new Run(j,done);
    }
    void keepAllTypos(Fixture f) {
        for(var finding:reviews.getValidationFindings(f.member(),f.work(),f.report(),null,null,null,0,100).content())
            reviews.saveValidationJudgment(f.member(),f.work(),f.report(),finding.findingId(),new ValidationJudgmentRequest(ValidationJudgmentValue.KEEP_ORIGINAL,null,null));
    }
    long reportVersion(Fixture f) {return reviews.getValidationReview(f.member(),f.work(),f.report()).reportVersion();}
    /** 남은 사용량을 0으로 만들어 공개 생성 경로의 최소 예약 확인(AI_TOKEN_QUOTA_EXHAUSTED)이 막히게 한다. */
    void exhaustTokens(Fixture f) {accounts.ensureAnalysisCanStart(f.member());jdbc.update("update ai_token_accounts set used_tokens = granted_tokens where member_id = ?",f.member());}
    void refillTokens(Fixture f) {jdbc.update("update ai_token_accounts set used_tokens = 0 where member_id = ?",f.member());}
    long analysisJobs(Fixture f) {return jdbc.queryForObject("select count(*) from analysis_jobs where work_id = ?",Long.class,f.work());}
    long receipts(Fixture f) {return jdbc.queryForObject("select count(*) from validation_review_completions where work_id = ?",Long.class,f.work());}
}
