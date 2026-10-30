package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.dto.request.AnalysisJobCreateRequest;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.episode.dto.request.*;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeUploadResponse;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.entity.EpisodeSourcePurgeRequest;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeUploadType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.service.SettingBookService;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationStartRequest;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationIntakeEligibility;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * #314 B1: 확정 전 검수(BEFORE_CONFIRMATION)가 검토·설정 저장을 마치지 않은 동안 모든 업로드 방식의 다음 회차 등록을 쓰기 0으로 막고,
 * 접수 전 자격 조회가 접수(start)와 같은 판정을 읽기만 하는지 확인한다. release Job은 실제 claim·완료 경계로 진행하며(모델 호출 없음)
 * Worker claim이 DB 전체의 대기 Job을 고르므로 테스트 트랜잭션 없이 메서드마다 새 Context를 쓴다.
 */
@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("#314 B1 접수 자격 조회와 미완료 검수 동안 다음 회차 등록 차단")
class ValidationNextEpisodeAdmissionIntegrationTest extends ValidationSettingsApplicationFixture {
    static final String BLOCKED="VALIDATION_NEXT_EPISODE_BLOCKED";
    static final String BLOCKED_MESSAGE="이 회차의 검토와 설정 저장을 마친 뒤 다음 회차를 등록할 수 있어요.";
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @Autowired AnalysisJobService analysisJobsApi;
    @Autowired SettingBookService settingBooks;
    /** 실제 판정을 그대로 호출한다. B1 이전에 등록된 회차를 재현하는 한 사례만 특정 작품의 업로드 판정을 건너뛴다. */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean org.monitoring.catchholebackend.domain.validation.processor.ValidationNextEpisodeAdmission admission;
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach void chunks() {jdbc.execute("create table if not exists episode_chunks (id uuid primary key, episode_id uuid)");}

    // ---- 세 업로드 방식 ----
    enum Mode {SINGLE,MULTI_SINGLE_FILE,MULTI_MULTI_FILE}
    static MockMultipartFile text(String name,String body) {return new MockMultipartFile("episodeFiles",name,"text/plain",body.getBytes(StandardCharsets.UTF_8));}
    static List<EpisodeUploadConfirmationRequest> confirmations(int no) {
        return List.of(new EpisodeUploadConfirmationRequest(0,no,"합성 "+no),new EpisodeUploadConfirmationRequest(1,no+1,"합성 "+(no+1)));
    }
    EpisodeUploadResponse upload(Long member,UUID work,Mode mode,int no) {
        return switch(mode) {
            case SINGLE -> episodes.uploadEpisodes(member,work,new EpisodeUploadRequest(EpisodeUploadType.SINGLE_EPISODE,no,"합성 "+no,null),List.of(text(no+".txt","합성 "+no+"화 원고")));
            case MULTI_SINGLE_FILE -> episodes.uploadEpisodes(member,work,new EpisodeUploadRequest(EpisodeUploadType.MULTI_EPISODE_SINGLE_FILE,null,null,confirmations(no)),
                    List.of(text("multi.txt","제 "+no+"화 합성\n본문 하나\n\n제 "+(no+1)+"화 합성\n본문 둘\n")));
            case MULTI_MULTI_FILE -> episodes.uploadEpisodes(member,work,new EpisodeUploadRequest(EpisodeUploadType.MULTI_EPISODE_MULTI_FILE,null,null,confirmations(no)),
                    List.of(text(no+".txt","제 "+no+"화 합성\n본문 하나\n"),text((no+1)+".txt","제 "+(no+1)+"화 합성\n본문 둘\n")));
        };
    }

    // ---- 쓰기 0 확인 ----
    record Writes(long batches,long files,long episodes,long reports,long keys,long validationJobs,long analysisJobs,int storedObjects) {}
    long count(String sql,Object... args) {return jdbc.queryForObject(sql,Long.class,args);}
    Writes writes(UUID work) {
        return new Writes(count("select count(*) from upload_batches where work_id=?",work),
                count("select count(*) from upload_files f join upload_batches b on b.id=f.batch_id where b.work_id=?",work),
                count("select count(*) from episodes where work_id=?",work),
                count("select count(*) from validation_reports where work_id=?",work),
                count("select count(*) from validation_request_keys where work_id=?",work),
                count("select count(*) from validation_jobs j join validation_reports r on r.id=j.report_id where r.work_id=?",work),
                count("select count(*) from analysis_jobs where work_id=?",work),texts.size());
    }
    ValidationReviewStage stage(Fixture f) {return admissions.get(f.member(),f.work(),f.report()).reviewStage();}
    /** 같은 report가 세 방식 모두를 막고, 409 context로 이동할 report를 알려 주며, 아무것도 쓰지 않는다. */
    void assertBlockedEverywhere(Fixture f,ValidationReviewStage stage) {
        var before=writes(f.work());
        for(var mode:Mode.values())
            assertThatThrownBy(()->upload(f.member(),f.work(),mode,102)).as(mode.name()).isInstanceOfSatisfying(AppException.class,e->{
                assertThat(e.getResultCode().getCode()).isEqualTo(BLOCKED);assertThat(e.getMessage()).isEqualTo(BLOCKED_MESSAGE);
                assertThat(e.getErrorContext()).containsExactlyInAnyOrderEntriesOf(Map.of("reportId",f.report().toString(),"episodeId",f.target().toString(),"episodeNo",101,"reviewStage",stage.name()));
            });
        assertThat(writes(f.work())).as("blocked uploads write no batch/file/episode/report/job/object").isEqualTo(before);
    }

    // ---- HTTP ----
    String token(Long member) {return tokens.generateAccessToken(tx.execute(s->em.find(Member.class,member)));}
    JsonNode eligibility(Long member,UUID work,UUID episode) throws Exception {
        var result=mvc.perform(get("/api/v1/works/"+work+"/episode-validations/eligibility"+(episode==null?"":"?episodeId="+episode))
                .header(HttpHeaders.AUTHORIZATION,"Bearer "+token(member))).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }
    record Http(int status,JsonNode body) {}
    Http startHttp(Long member,UUID work,Map<String,Object> body) throws Exception {
        var result=mvc.perform(post("/api/v1/works/"+work+"/episode-validations").header(HttpHeaders.AUTHORIZATION,"Bearer "+token(member))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn();
        return new Http(result.getResponse().getStatus(),json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)));
    }
    Map<String,Object> startBody(String key,UUID batch,UUID episode) {
        var row=jdbc.queryForList("select content_hash,content_s3_key,content_s3_version from episodes where id=?",episode);
        var body=new LinkedHashMap<String,Object>();body.put("idempotencyKey",key);body.put("batchId",batch);body.put("episodeId",episode);
        body.put("expectedContentHash",row.isEmpty()?"0".repeat(64):row.getFirst().get("content_hash"));
        body.put("expectedContentS3Key",row.isEmpty()?"synthetic/missing":row.getFirst().get("content_s3_key"));
        body.put("expectedContentS3Version",row.isEmpty()?null:row.getFirst().get("content_s3_version"));return body;
    }
    ValidationStartRequest startRequest(String key,UUID batch,UUID episode) {
        var b=startBody(key,batch,episode);
        return new ValidationStartRequest(key,batch,episode,(String)b.get("expectedContentHash"),(String)b.get("expectedContentS3Key"),(String)b.get("expectedContentS3Version"));
    }

    /** 작품 하나. prior면 고정 원문이 있는 100화, material이면 비교 자료(활성 인물)를 둔다. */
    record Owner(Long member,UUID work) {}
    Owner owner(boolean prior,boolean material) {return tx.execute(s->{
        String phone="010"+String.format("%08d",new Random().nextInt(100_000_000));
        var member=Member.register("admission-"+UUID.randomUUID()+"@example.invalid","synthetic-only",phone,"합성 작가");em.persist(member);
        var work=Work.create(member,"다음 회차 차단 합성",WorkGenre.FANTASY,null);em.persist(work);
        if(prior) {String text="합성 앞 원고";texts.put("synthetic/prior/"+work.getId(),text);
            em.persist(Episode.create(work,null,100,"앞 회차","synthetic/prior/"+work.getId(),"v1",hashes.contentHash(text),text.length()));work.updateLatestEpisodeNo(100);}
        if(material)em.persist(WorkCharacter.create(work,"합성 인물",null,20,1,null,null,null,null,null,null));
        return new Owner(member.getId(),work.getId());
    });}
    boolean uploadEligible(UUID episode) {return Boolean.TRUE.equals(jdbc.queryForObject("select validation_upload_eligible from episodes where id=?",Boolean.class,episode));}

    @Test @DisplayName("확정 전 검수가 검토 대기·확정 보류인 동안 단일·다회차 두 방식 모두 409이고 배치·파일·회차·저장 객체를 하나도 만들지 않는다")
    void pendingValidationBlocksNextEpisodeAcrossUploadModes() throws Exception {
        var awaiting=fixture();runToCompletion(awaiting,true);
        assertThat(stage(awaiting)).isEqualTo(ValidationReviewStage.AWAITING_REVIEW);
        assertBlockedEverywhere(awaiting,ValidationReviewStage.AWAITING_REVIEW);
        var before=writes(awaiting.work());
        var metadata=new MockMultipartFile("metadata","",MediaType.APPLICATION_JSON_VALUE,"{\"uploadType\":\"SINGLE_EPISODE\",\"singleEpisodeNo\":102}".getBytes(StandardCharsets.UTF_8));
        var result=mvc.perform(multipart("/api/v1/works/{workId}/episodes",awaiting.work()).file(metadata).file(text("102.txt","합성 102화 원고"))
                .header(HttpHeaders.AUTHORIZATION,"Bearer "+token(awaiting.member()))).andExpect(status().isConflict()).andReturn();
        var body=json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("message").asText()).isEqualTo(BLOCKED_MESSAGE);assertThat(body.path("error").path("code").asText()).isEqualTo(BLOCKED);
        var context=body.path("error").path("context");
        assertThat(context.path("reportId").asText()).isEqualTo(awaiting.report().toString());assertThat(context.path("episodeId").asText()).isEqualTo(awaiting.target().toString());
        assertThat(context.path("episodeNo").asInt()).isEqualTo(101);assertThat(context.path("reviewStage").asText()).isEqualTo("AWAITING_REVIEW");
        assertThat(writes(awaiting.work())).as("HTTP multipart path writes nothing either").isEqualTo(before);
        var admission=eligibility(awaiting.member(),awaiting.work(),null).path("nextEpisodeAdmission");
        assertThat(admission.path("allowed").asBoolean()).isFalse();assertThat(admission.path("blockingReportId").asText()).isEqualTo(awaiting.report().toString());
        assertThat(admission.path("blockingEpisodeNo").asInt()).isEqualTo(101);assertThat(admission.path("blockingReviewStage").asText()).isEqualTo("AWAITING_REVIEW");
        assertThat(admission.path("message").asText()).isEqualTo(BLOCKED_MESSAGE);

        // 회차 전체 확정 보류(APPLICATION_HELD)도 설정 저장 전이므로 막는다.
        var r=release();var held=r.f();
        inJob(r.job(),job->{var d=discovery(job,"에르웬");return stat(job,d.getProvisionalSubjectKey(),"에르웬",35,CharacterFactOperation.REVIEW_REQUIRED);});
        completeExtraction(r);assertThat(applicationRow(held).get("status")).isEqualTo("HELD_REVIEW");
        assertThat(stage(held)).isEqualTo(ValidationReviewStage.APPLICATION_HELD);
        assertBlockedEverywhere(held,ValidationReviewStage.APPLICATION_HELD);
    }

    @Test @DisplayName("회차 전체 확정(APPLIED) 뒤에는 같은 업로드가 성공하고 새 회차가 검수 자격을 받아 다음 검수가 다시 다음 회차를 막는다")
    void appliedValidationAllowsNextEpisode() throws Exception {
        var r=release();var f=r.f();applicable(r);completeExtraction(r);
        assertThat(applicationRow(f).get("status")).isEqualTo("APPLIED");assertThat(stage(f)).isEqualTo(ValidationReviewStage.APPLIED);
        assertThat(eligibility(f.member(),f.work(),null).path("nextEpisodeAdmission").path("allowed").asBoolean()).isTrue();
        var uploaded=upload(f.member(),f.work(),Mode.SINGLE,102);var next=uploaded.createdEpisodes().getFirst().id();
        assertThat(uploadEligible(next)).as("single append after APPLIED is a validation target").isTrue();
        var eligibility=eligibility(f.member(),f.work(),next);
        assertThat(eligibility.path("reasonCode").asText()).isEqualTo("ELIGIBLE");assertThat(eligibility.path("eligible").asBoolean()).isTrue();
        assertThat(eligibility.path("latestEpisode").asBoolean()).isTrue();assertThat(eligibility.path("currentReport").isNull()).isTrue();
        var started=admissions.start(f.member(),f.work(),startRequest("next-episode",uploaded.batchId(),next));
        var before=writes(f.work());
        assertThatThrownBy(()->upload(f.member(),f.work(),Mode.MULTI_SINGLE_FILE,103)).isInstanceOfSatisfying(AppException.class,e->{
            assertThat(e.getResultCode().getCode()).isEqualTo(BLOCKED);assertThat(e.getErrorContext()).containsEntry("reportId",started.reportId().toString()).containsEntry("episodeNo",102);
        });
        assertThat(writes(f.work())).isEqualTo(before);
    }

    @Test @DisplayName("파일 교체로 무효화된 report는 재검수가 퇴역시키기 전까지 current로 남아 다음 회차를 막고, 재검수 report가 APPLIED가 되면 허용한다")
    void invalidatedReportBlocksUntilRecheckApplies() throws Exception {
        var f=fixture();runToCompletion(f,true);String replaced="수정한 합성 원고";replace(f,replaced);
        assertThat(stage(f)).isEqualTo(ValidationReviewStage.INVALIDATED);
        assertThat(jdbc.queryForObject("select is_current from validation_reports where id=?",Boolean.class,f.report())).as("only a recheck retires it").isTrue();
        // 받아 주면 101이 최신 회차가 아니게 되어 재검수가 거절되고 101을 추출할 길이 없어진다.
        assertBlockedEverywhere(f,ValidationReviewStage.INVALIDATED);
        assertThat(eligibility(f.member(),f.work(),null).path("nextEpisodeAdmission").path("blockingReviewStage").asText()).isEqualTo("INVALIDATED");

        var next=recheck(f,"after-fix");
        assertThat(jdbc.queryForObject("select is_current from validation_reports where id=?",Boolean.class,f.report())).as("retired report is no longer consulted").isFalse();
        assertThat(admissions.get(next.member(),next.work(),next.report()).mode()).isEqualTo(ValidationReportMode.BEFORE_CONFIRMATION);
        assertThatThrownBy(()->upload(f.member(),f.work(),Mode.SINGLE,102)).as("the recheck report blocks until applied")
                .isInstanceOfSatisfying(AppException.class,e->assertThat(e.getErrorContext()).containsEntry("reportId",next.report().toString()));
        var run=runToCompletion(next,false);var release=(UUID)run.done().get("extractionJobId");assertThat(release).as(String.valueOf(run.done())).isNotNull();
        sourceText=replaced;var payload=analysisWorker.claimAnalysisJob(claimRequest()).orElseThrow();assertThat(payload.analysisJobId()).isEqualTo(release);
        inJob(release,job->world(job,"엘프","서식지","남부"));completeExtraction(release,payload.leaseToken());
        assertThat(applicationRow(next).get("status")).isEqualTo("APPLIED");assertThat(stage(next)).isEqualTo(ValidationReviewStage.APPLIED);

        var uploaded=upload(f.member(),f.work(),Mode.MULTI_SINGLE_FILE,102);
        assertThat(uploaded.createdEpisodes()).extracting(e->e.episodeNo()).containsExactly(102,103);
        assertThat(uploaded.createdEpisodes()).allSatisfy(e->assertThat(uploadEligible(e.id())).as("multi upload is never a validation target").isFalse());
    }

    @Test @DisplayName("회차 전체 확정(APPLIED) 뒤 다음 회차를 받고 앞 회차 원고를 교체해 report가 무효가 되어도 이후 회차 등록을 막지 않는다")
    void appliedThenReplacedEpisodeDoesNotBlockNextEpisode() throws Exception {
        var r=release();var f=r.f();applicable(r);completeExtraction(r);
        assertThat(applicationRow(f).get("status")).isEqualTo("APPLIED");
        upload(f.member(),f.work(),Mode.SINGLE,102);
        replace(f,"수정한 합성 원고");
        // 원고 교체 정리(EpisodeSourceChanged)는 APPLIED report도 current인 채 INVALIDATED로 만들고 회차 전체 확정 receipt는 이력으로 남긴다.
        var row=jdbc.queryForMap("select is_current,status,baseline_status from validation_reports where id=?",f.report());
        assertThat(row.get("is_current")).isEqualTo(true);assertThat(row.get("status")).isEqualTo("INVALIDATED");assertThat(row.get("baseline_status")).isEqualTo("INVALIDATED");
        assertThat(applicationRow(f).get("status")).as("the APPLIED receipt survives the invalidation").isEqualTo("APPLIED");
        assertThat(stage(f)).as("history semantics stay INVALIDATED").isEqualTo(ValidationReviewStage.INVALIDATED);

        var admission=eligibility(f.member(),f.work(),null).path("nextEpisodeAdmission");
        assertThat(admission.path("allowed").asBoolean()).isTrue();assertThat(admission.path("blockingReportId").isNull()).isTrue();
        var next=upload(f.member(),f.work(),Mode.SINGLE,103).createdEpisodes().getFirst().id();
        assertThat(uploadEligible(next)).isTrue();
        assertThat(eligibility(f.member(),f.work(),next).path("reasonCode").asText()).as("intake reads the same admission rule").isEqualTo("ELIGIBLE");

        // 101은 최신 회차가 아니므로 진단 재검수는 기존 규칙대로 거절된다. 현재 report는 그대로 101의 이력이다.
        var view=eligibility(f.member(),f.work(),f.target());
        assertThat(view.path("reasonCode").asText()).isEqualTo("REPORT_EXISTS");assertThat(view.path("latestEpisode").asBoolean()).isFalse();
        assertThatThrownBy(()->recheck(f,"diagnostic-after-replacement")).isInstanceOfSatisfying(AppException.class,
                e->assertThat(e.getResultCode().getCode()).isEqualTo("VALIDATION_TARGET_INVALID"));
    }

    @Test @DisplayName("삭제(ARCHIVED)한 회차의 report는 퇴역하지 않아도 다음 회차 등록을 막지 않는다")
    void deletedEpisodeReportDoesNotBlock() throws Exception {
        var f=fixture();runToCompletion(f,true);
        assertBlockedEverywhere(f,ValidationReviewStage.AWAITING_REVIEW);
        episodes.deleteEpisode(f.member(),f.work(),f.target());
        assertThat(jdbc.queryForObject("select status from episodes where id=?",String.class,f.target())).isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject("select is_current from validation_reports where id=?",Boolean.class,f.report())).as("deletion does not retire the report").isTrue();
        var admission=eligibility(f.member(),f.work(),null).path("nextEpisodeAdmission");
        assertThat(admission.path("allowed").asBoolean()).isTrue();assertThat(admission.path("blockingReportId").isNull()).isTrue();
        // 커밋 뒤 원문 파기가 끝나 비워진 101화 번호로 새 원고를 단일 append 업로드한다.
        var uploaded=upload(f.member(),f.work(),Mode.SINGLE,101);var next=uploaded.createdEpisodes().getFirst().id();
        assertThat(uploadEligible(next)).isTrue();
        assertThat(eligibility(f.member(),f.work(),next).path("reasonCode").asText()).as("eligibility reads the same admission query")
                .isEqualTo(ValidationEligibilityReason.ELIGIBLE.name());
    }

    @Test @DisplayName("확정 뒤 진단 재검수(DIAGNOSTIC_ONLY)는 검토 대기에서 멈춰도 다음 회차 등록을 막지 않는다")
    void diagnosticRecheckDoesNotBlockNextEpisode() throws Exception {
        var r=release();var f=r.f();applicable(r);completeExtraction(r);
        var diagnostic=recheck(f,"diagnostic");
        assertThat(admissions.get(diagnostic.member(),diagnostic.work(),diagnostic.report()).mode()).isEqualTo(ValidationReportMode.DIAGNOSTIC_ONLY);
        runToCompletion(diagnostic,false);
        assertThat(stage(diagnostic)).as("diagnostic stops before review completion by design").isEqualTo(ValidationReviewStage.AWAITING_REVIEW);
        var admission=eligibility(f.member(),f.work(),null).path("nextEpisodeAdmission");
        assertThat(admission.path("allowed").asBoolean()).isTrue();assertThat(admission.path("blockingReportId").isNull()).isTrue();
        var uploaded=upload(f.member(),f.work(),Mode.MULTI_MULTI_FILE,102);
        assertThat(uploaded.createdEpisodes()).extracting(e->e.episodeNo()).containsExactlyInAnyOrder(102,103);
    }

    @Test @DisplayName("미완료 검수 동안에도 사전 감지·제목 수정·설정집 업로드·원고 파일 교체는 막지 않는다")
    void replaceAndDetectAreNotBlocked() throws Exception {
        var f=fixture();runToCompletion(f,true);
        var before=writes(f.work());
        var detected=episodes.detectEpisodes(f.member(),f.work(),new EpisodeDetectionRequest(EpisodeUploadType.SINGLE_EPISODE,102,"합성 102"),List.of(text("102.txt","합성 102화 원고")));
        assertThat(detected.detectedEpisodes()).hasSize(1);assertThat(writes(f.work())).as("detection writes nothing").isEqualTo(before);
        assertThat(episodes.updateEpisodeTitle(f.member(),f.work(),f.target(),new EpisodeTitleUpdateRequest("고친 제목")).title()).isEqualTo("고친 제목");
        settingBooks.uploadSettingBook(f.member(),f.work(),new MockMultipartFile("file","setting.txt","text/plain","합성 설정집 본문".getBytes(StandardCharsets.UTF_8)));
        assertThat(count("select count(*) from upload_batches where work_id=? and upload_type='SETTING_BOOK'",f.work())).isEqualTo(1);
        assertThat(stage(f)).isEqualTo(ValidationReviewStage.AWAITING_REVIEW);
        assertThatThrownBy(()->upload(f.member(),f.work(),Mode.SINGLE,102)).as("the block still applies to new episodes")
                .isInstanceOfSatisfying(AppException.class,e->assertThat(e.getResultCode().getCode()).isEqualTo(BLOCKED));
        replace(f,"수정한 합성 원고");
        assertThat(jdbc.queryForObject("select content_hash from episodes where id=?",String.class,f.target())).isEqualTo(hashes.contentHash("수정한 합성 원고"));
        assertThat(stage(f)).as("the fix path invalidates the report").isEqualTo(ValidationReviewStage.INVALIDATED);
    }

    /** 자격 조회 사유와 같은 입력의 접수 결과(새 report·기존 report·오류 코드)가 일치한다. */
    void assertStartMatches(Owner o,UUID episode,Map<String,Object> body,ValidationEligibilityReason expected,Set<ValidationEligibilityReason> seen) throws Exception {
        var view=eligibility(o.member(),o.work(),episode);
        assertThat(view.path("reasonCode").asText()).as("eligibility of "+expected).isEqualTo(expected.name());
        assertThat(view.path("eligible").asBoolean()).isEqualTo(expected==ValidationEligibilityReason.ELIGIBLE);
        long reports=count("select count(*) from validation_reports where work_id=?",o.work());
        var outcome=startHttp(o.member(),o.work(),body);
        switch(expected) {
            case ELIGIBLE -> {assertThat(outcome.status()).isEqualTo(200);assertThat(count("select count(*) from validation_reports where work_id=?",o.work())).isEqualTo(reports+1);}
            case REPORT_EXISTS -> {
                assertThat(outcome.status()).isEqualTo(200);assertThat(outcome.body().path("data").path("reportId").asText()).isEqualTo(view.path("currentReport").path("reportId").asText());
                assertThat(count("select count(*) from validation_reports where work_id=?",o.work())).isEqualTo(reports);
            }
            default -> {
                var code=ValidationIntakeEligibility.errorCode(expected);
                assertThat(outcome.status()).as(expected.name()).isEqualTo(code.getStatus().value());
                assertThat(outcome.body().path("error").path("code").asText()).as(expected.name()).isEqualTo(code.name());
                assertThat(outcome.body().path("message").asText()).as("eligibility uses the start error wording").isEqualTo(view.path("message").asText());
                assertThat(count("select count(*) from validation_reports where work_id=?",o.work())).isEqualTo(reports);
            }
        }
        seen.add(expected);
    }

    @Test @DisplayName("자격 조회의 모든 사유가 같은 입력의 접수 결과와 일치한다")
    void eligibilityMatchesStartOutcome() throws Exception {
        var seen=EnumSet.noneOf(ValidationEligibilityReason.class);
        // ELIGIBLE → 접수, 이후 REPORT_EXISTS → 새 키도 같은 report
        var o=owner(true,true);var first=upload(o.member(),o.work(),Mode.SINGLE,101);var target=first.createdEpisodes().getFirst().id();
        var fresh=eligibility(o.member(),o.work(),target);
        assertThat(fresh.path("latestEpisode").asBoolean()).isTrue();assertThat(fresh.path("currentReport").isNull()).isTrue();assertThat(fresh.path("nextEpisodeAdmission").path("allowed").asBoolean()).isTrue();
        assertStartMatches(o,target,startBody("first",first.batchId(),target),ValidationEligibilityReason.ELIGIBLE,seen);
        var existing=eligibility(o.member(),o.work(),target);
        assertThat(existing.path("currentReport").path("revision").asLong()).isEqualTo(1);assertThat(existing.path("currentReport").path("mode").asText()).isEqualTo("BEFORE_CONFIRMATION");
        assertThat(existing.path("currentReport").path("reviewStage").asText()).isEqualTo(existing.path("nextEpisodeAdmission").path("blockingReviewStage").asText());
        assertThat(existing.path("nextEpisodeAdmission").path("blockingReportId").asText()).isEqualTo(existing.path("currentReport").path("reportId").asText());
        assertStartMatches(o,target,startBody("second",first.batchId(),target),ValidationEligibilityReason.REPORT_EXISTS,seen);

        // 다른 작품의 회차와 없는 회차는 존재를 드러내지 않는다
        var stranger=owner(true,true);
        assertStartMatches(stranger,target,startBody("foreign",first.batchId(),target),ValidationEligibilityReason.EPISODE_NOT_FOUND,seen);
        assertThat(eligibility(stranger.member(),stranger.work(),target).path("currentReport").isNull()).as("another work's report is not exposed").isTrue();
        var missing=UUID.randomUUID();assertStartMatches(stranger,missing,startBody("missing",UUID.randomUUID(),missing),ValidationEligibilityReason.EPISODE_NOT_FOUND,seen);

        // 마지막 뒤가 아닌 단일 업로드, 다회차 업로드
        var gap=owner(true,true);var skipped=upload(gap.member(),gap.work(),Mode.SINGLE,105);var skippedId=skipped.createdEpisodes().getFirst().id();
        assertStartMatches(gap,skippedId,startBody("gap",skipped.batchId(),skippedId),ValidationEligibilityReason.NOT_SINGLE_APPEND_UPLOAD,seen);
        var multi=owner(true,true);var both=upload(multi.member(),multi.work(),Mode.MULTI_SINGLE_FILE,101);var lastOfMulti=both.createdEpisodes().stream().filter(e->e.episodeNo()==102).findFirst().orElseThrow().id();
        assertStartMatches(multi,lastOfMulti,startBody("multi",both.batchId(),lastOfMulti),ValidationEligibilityReason.NOT_SINGLE_APPEND_UPLOAD,seen);

        // 최신 회차가 아님
        var older=owner(true,true);var a=upload(older.member(),older.work(),Mode.SINGLE,101);upload(older.member(),older.work(),Mode.SINGLE,102);var aId=a.createdEpisodes().getFirst().id();
        assertThat(eligibility(older.member(),older.work(),aId).path("latestEpisode").asBoolean()).isFalse();
        assertStartMatches(older,aId,startBody("older",a.batchId(),aId),ValidationEligibilityReason.NOT_LATEST_EPISODE,seen);

        // 원문 파기 대기
        var purging=owner(true,true);var p=upload(purging.member(),purging.work(),Mode.SINGLE,101);var pId=p.createdEpisodes().getFirst().id();
        tx.executeWithoutResult(s->{var e=em.find(Episode.class,pId);em.persist(EpisodeSourcePurgeRequest.requestReplacement(e,null,e.getContentS3Key()));});
        assertStartMatches(purging,pId,startBody("purging",p.batchId(),pId),ValidationEligibilityReason.SOURCE_PURGE_PENDING,seen);

        // 일반 분석을 먼저 시작함
        var analyzed=owner(true,true);var an=upload(analyzed.member(),analyzed.work(),Mode.SINGLE,101);var anId=an.createdEpisodes().getFirst().id();
        analysisJobsApi.createAnalysisJobs(analyzed.member(),analyzed.work(),new AnalysisJobCreateRequest(AnalysisJobType.SETTING_EXTRACTION,an.batchId(),anId));
        assertStartMatches(analyzed,anId,startBody("analyzed",an.batchId(),anId),ValidationEligibilityReason.ANALYSIS_ALREADY_STARTED,seen);

        // 다른 회차의 미완료 검수: 차단을 우회하는 공개 경로는 없으므로 B1 이전에 등록된 회차를 업로드 판정 하나만 건너뛰어 재현한다.
        var legacy=owner(true,true);var l1=upload(legacy.member(),legacy.work(),Mode.SINGLE,101);var l1Id=l1.createdEpisodes().getFirst().id();
        var pending=admissions.start(legacy.member(),legacy.work(),startRequest("legacy-101",l1.batchId(),l1Id));
        org.mockito.Mockito.doNothing().when(admission).assertNextEpisodeAdmitted(legacy.work());
        var l2=upload(legacy.member(),legacy.work(),Mode.SINGLE,102);var l2Id=l2.createdEpisodes().getFirst().id();
        org.mockito.Mockito.reset(admission);
        assertThatThrownBy(()->upload(legacy.member(),legacy.work(),Mode.SINGLE,103)).as("the real check is back")
                .isInstanceOfSatisfying(AppException.class,e->assertThat(e.getResultCode().getCode()).isEqualTo(BLOCKED));
        var blockedView=eligibility(legacy.member(),legacy.work(),l2Id);
        assertThat(blockedView.path("nextEpisodeAdmission").path("blockingReportId").asText()).isEqualTo(pending.reportId().toString());
        assertThat(blockedView.path("latestEpisode").asBoolean()).isTrue();
        assertStartMatches(legacy,l2Id,startBody("legacy-102",l2.batchId(),l2Id),ValidationEligibilityReason.NEXT_EPISODE_BLOCKED,seen);
        assertThat(startHttp(legacy.member(),legacy.work(),startBody("legacy-102b",l2.batchId(),l2Id)).body().path("error").path("context").path("reportId").asText())
                .as("start carries the same blocking report").isEqualTo(pending.reportId().toString());

        // 첫 회차인데 비교 자료가 없음
        var empty=owner(false,false);var e1=upload(empty.member(),empty.work(),Mode.SINGLE,1);var e1Id=e1.createdEpisodes().getFirst().id();
        assertThat(uploadEligible(e1Id)).isTrue();
        assertStartMatches(empty,e1Id,startBody("empty",e1.batchId(),e1Id),ValidationEligibilityReason.COMPARISON_MATERIAL_REQUIRED,seen);

        assertThat(seen).as("every reason branch is exercised").containsExactlyInAnyOrder(ValidationEligibilityReason.values());
    }

    @Test @DisplayName("자격 조회는 report·요청 키·Job·report version을 바꾸지 않는다")
    void eligibilityIsReadOnly() throws Exception {
        var f=fixture();runToCompletion(f,true);
        var o=owner(true,true);var up=upload(o.member(),o.work(),Mode.SINGLE,101);var fresh=up.createdEpisodes().getFirst().id();
        var beforeReport=jdbc.queryForMap("select version,updated_at,status,baseline_status from validation_reports where id=?",f.report());
        var beforeF=writes(f.work());var beforeO=writes(o.work());
        long judgments=count("select count(*) from validation_judgments"),completionsRows=count("select count(*) from validation_review_completions");
        for(int i=0;i<2;i++) {
            eligibility(f.member(),f.work(),null);eligibility(f.member(),f.work(),f.target());eligibility(f.member(),f.work(),UUID.randomUUID());
            assertThat(eligibility(o.member(),o.work(),fresh).path("reasonCode").asText()).isEqualTo("ELIGIBLE");eligibility(o.member(),o.work(),null);
        }
        assertThat(writes(f.work())).isEqualTo(beforeF);assertThat(writes(o.work())).as("an eligible episode is not started by the lookup").isEqualTo(beforeO);
        assertThat(jdbc.queryForMap("select version,updated_at,status,baseline_status from validation_reports where id=?",f.report())).isEqualTo(beforeReport);
        assertThat(count("select count(*) from validation_judgments")).isEqualTo(judgments);assertThat(count("select count(*) from validation_review_completions")).isEqualTo(completionsRows);
        mvc.perform(get("/api/v1/works/"+f.work()+"/episode-validations/eligibility").header(HttpHeaders.AUTHORIZATION,"Bearer "+token(o.member()))).andExpect(status().isNotFound());
    }
}
