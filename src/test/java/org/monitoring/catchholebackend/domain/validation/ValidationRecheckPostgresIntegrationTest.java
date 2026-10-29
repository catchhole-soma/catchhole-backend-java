package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.character.dto.request.CharacterUpdateRequest;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.service.EpisodeService;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeUploadType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.validation.dto.request.*;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationStagingResponse;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.monitoring.catchholebackend.domain.validation.service.*;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.*;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingPropertyUpdateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingService;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
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

/** Destructive fixture cleanup is restricted to the disposable loopback gh310_recheck_test DB. */
@SpringBootTest(properties={"spring.config.import=","spring.docker.compose.enabled=false"}) @ActiveProfiles("test") @DirtiesContext
@EnabledIfEnvironmentVariable(named="GH310_RECHECK_POSTGRES_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh310_recheck_test")
class ValidationRecheckPostgresIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->System.getenv("GH310_RECHECK_POSTGRES_JDBC_URL"));
        p.add("spring.datasource.username",()->"gh310"); p.add("spring.datasource.password",()->"gh310-disposable-only");
        p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        p.add("spring.jpa.hibernate.ddl-auto",()->"validate"); p.add("spring.flyway.enabled",()->"true");
    }
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WorkRepository works;
    @Autowired WorkPurgeDataRepository workPurge;
    @Autowired EpisodeService episodes;
    @Autowired EpisodeValidationService admissions;
    @Autowired ValidationRecheckService rechecks;
    @Autowired ValidationCorrectionService corrections;
    @Autowired ValidationBaselineService baselines;
    @Autowired ValidationDataService data;
    @Autowired AnalysisRunStateService states;
    @Autowired AnalysisStateJournal journal;
    @Autowired WorldSettingService worldEdits;
    @MockitoBean ObjectStorage storage;
    TransactionTemplate tx;
    record Fixture(Long member,UUID work,UUID target,UUID prior,UUID world,UUID character,ValidationStartRequest request) {}
    @BeforeEach void initialize() {
        tx=new TransactionTemplate(transactions); jdbc.execute("TRUNCATE TABLE works,members CASCADE");
        when(storage.putBytes(anyString(),any(byte[].class),anyString())).thenAnswer(c->new StoredObject(c.getArgument(0),"synthetic-v1"));
        when(storage.putText(anyString(),anyString())).thenAnswer(c->new StoredObject(c.getArgument(0),"synthetic-v1"));
        when(storage.purgePrefixes(anyCollection())).thenReturn(new ObjectStoragePurgeResult(1,1,0));
        when(storage.purgePrefixesExcluding(anyCollection(),anyCollection())).thenReturn(new ObjectStoragePurgeResult(1,1,0));
    }
    Fixture fixture() {
        return tx.execute(s->{
            var member=Member.register("gh310@example.invalid","test-only","01012345678","합성 작가");em.persist(member);
            var work=Work.create(member,"격리 재검수",WorkGenre.FANTASY,null);em.persist(work);
            var prior=Episode.create(work,null,100,"이전","synthetic/100","v1","a".repeat(64),1);em.persist(prior);work.updateLatestEpisodeNo(100);
            var world=WorldSetting.create(work,WorldSettingCategory.RACE,"합성 종족","서식지","북부");em.persist(world);
            var character=WorkCharacter.create(work,"합성 인물",null,20,1,null,null,null,null,null,null);em.persist(character);
            var upload=episodes.uploadEpisodes(member.getId(),work.getId(),new EpisodeUploadRequest(EpisodeUploadType.SINGLE_EPISODE,101,"신규",null),
                    List.of(file("처음 합성 원고")));
            var target=em.createQuery("select e from Episode e where e.work=:work and e.episodeNo=101",Episode.class).setParameter("work",work).getSingleResult();
            return new Fixture(member.getId(),work.getId(),target.getId(),prior.getId(),world.getId(),character.getId(),
                    new ValidationStartRequest("initial",upload.batchId(),target.getId(),target.getContentHash(),target.getContentS3Key(),target.getContentS3Version()));
        });
    }
    static MockMultipartFile file(String text) {return new MockMultipartFile("episodeFiles","synthetic.txt","text/plain",text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    ValidationStagingResponse start(Fixture f) {return admissions.start(f.member(),f.work(),f.request());}
    ValidationRecheckRequest request(Fixture f,ValidationStagingResponse previous,String key) {
        return tx.execute(s->{var episode=em.find(Episode.class,f.target());var report=em.find(ValidationReport.class,previous.reportId());
            return new ValidationRecheckRequest(key,report.getVersion(),episode.getContentHash(),episode.getContentS3Key(),episode.getContentS3Version(),episode.getContentRevision(),previous.baselineHash(),null);});
    }
    ValidationStagingResponse recheck(Fixture f,ValidationStagingResponse previous,ValidationRecheckRequest input) {return rechecks.recheckValidation(f.member(),f.work(),previous.reportId(),input);}
    ValidationCorrectionRequest correction(Fixture f,ValidationStagingResponse report,boolean character) {
        return tx.execute(s->{
            var r=em.find(ValidationReport.class,report.reportId());int index=0;
            for(var entry:r.getBaselineEntries()) {
                index++;if(!entry.path("domain").asText().equals(character?"characters":"worldSettings"))continue;
                return new ValidationCorrectionRequest("correction",r.getVersion(),r.getId(),r.getBaselineRevision(),r.getBaselineHash(),"B"+index,journal.hash(entry),
                        character?new ValidationCorrectionRequest.CharacterCorrection(f.character(),0L,CharacterFactType.AGE,"age",
                                new CharacterUpdateRequest("합성 인물",null,21,1,null,List.of(),List.of(),List.of(),List.of(),List.of())):null,
                        character?null:new ValidationCorrectionRequest.WorldSettingCorrection(f.world(),0L,new WorldSettingPropertyUpdateRequest("서식지","서식지","남부",0L)));
            }throw new AssertionError("missing synthetic entry");
        });
    }
    static void await(CountDownLatch latch) {try{assertThat(latch.await(15,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    void noExtraction() {for(String table:List.of("analysis_jobs","setting_candidates","world_setting_candidates"))assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).as(table).isZero();}
    void purged(UUID report) {
        assertThat(jdbc.queryForObject("select status='INVALIDATED' and baseline_seed is null and baseline_entries is null from validation_reports where id=?",Boolean.class,report)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from validation_baseline_sources where report_id=?",Integer.class,report)).isZero();
    }
    @Test void concurrentRechecksHaveOneCurrentAndOneHistory() throws Exception {
        var f=fixture();var previous=start(f);episodes.replaceEpisodeFile(f.member(),f.work(),f.target(),file("새 원고"));var input=request(f,previous,"concurrent");
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{ready.countDown();await(go);return recheck(f,previous,input);});
            var second=pool.submit(()->{ready.countDown();await(go);return recheck(f,previous,input);});await(ready);go.countDown();
            assertThat(first.get(20,TimeUnit.SECONDS)).isEqualTo(second.get(20,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("select count(*) from validation_reports",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from validation_reports where is_current",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='92' and success",Integer.class)).isEqualTo(1);purged(previous.reportId());noExtraction();
    }
    @Test void delayedMaterializationCannotRestorePurgedText() throws Exception {
        var f=fixture();var report=start(f);tx.executeWithoutResult(s->em.find(ValidationReport.class,report.reportId()).hold("SYNTHETIC_PENDING_MATERIALIZATION"));
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var read=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var deletion=pool.submit(()->tx.executeWithoutResult(s->{works.getOwnedWorkForUpdate(f.work(),f.member());
                states.purgeSourceEvidenceForWorkForUpdate(f.work(),f.prior(),100);em.find(Episode.class,f.prior()).archive();em.flush();locked.countDown();await(release);}));
            await(locked);var normalization=pool.submit(()->tx.executeWithoutResult(s->{assertThat(em.find(ValidationReport.class,report.reportId()).getBaselineSeed()).isNotNull();read.countDown();baselines.materialize(report.reportId());}));
            await(read);release.countDown();deletion.get(20,TimeUnit.SECONDS);normalization.get(20,TimeUnit.SECONDS);
        }purged(report.reportId());noExtraction();
    }
    @Test void replacementABARejectsOldReport() {
        var f=fixture();var old=start(f);episodes.replaceEpisodeFile(f.member(),f.work(),f.target(),file("B 원고"));
        tx.executeWithoutResult(s->{works.getOwnedWorkForUpdate(f.work(),f.member());em.find(Episode.class,f.target()).updateContentStorage(f.request().expectedContentS3Key(),f.request().expectedContentS3Version(),f.request().expectedContentHash());});
        assertThat(jdbc.queryForObject("select content_revision from episodes where id=?",Long.class,f.target())).isEqualTo(2);
        assertThatThrownBy(()->data.readValidationData(f.member(),f.work(),old.reportId(),new ValidationDataRequest(old.baselineHash(),f.request().expectedContentHash(),"INPUT",0,20,null,null,null,null,null)))
                .isInstanceOf(AppException.class);purged(old.reportId());noExtraction();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void receiptInsertFailureRollsBackAllActualEdits(boolean character) {
        var f=fixture();var report=start(f);var input=correction(f,report,character);
        jdbc.execute("create function gh310_reject_correction() returns trigger language plpgsql as 'begin raise exception ''synthetic receipt failure''; end'");
        jdbc.execute("create trigger gh310_reject before insert on validation_corrections for each row execute function gh310_reject_correction()");
        try{assertThatThrownBy(()->corrections.correctSettingAndLinkBaseline(f.member(),f.work(),report.reportId(),input)).isInstanceOf(RuntimeException.class);}
        finally{jdbc.execute("drop trigger gh310_reject on validation_corrections");jdbc.execute("drop function gh310_reject_correction()");}
        assertThat(jdbc.queryForObject("select current_age from characters where id=?",Integer.class,f.character())).isEqualTo(20);
        assertThat(jdbc.queryForObject("select properties_json->>'서식지' from world_settings where id=?",String.class,f.world())).isEqualTo("북부");
        assertThat(jdbc.queryForObject("select baseline_seed is not null and status='WAITING_FOR_ERROR_REVIEW' from validation_reports where id=?",Boolean.class,report.reportId())).isTrue();
        for(String table:List.of("validation_corrections","character_facts","character_snapshot_sources"))assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).as(table).isZero();noExtraction();
    }
    @Test void staleCorrectionRollsBackAllEdits() throws Exception {
        var f=fixture();var report=start(f);var input=correction(f,report,false);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var edit=pool.submit(()->tx.executeWithoutResult(s->{works.getOwnedWorkForUpdate(f.work(),f.member());worldEdits.updateWorldSettingProperty(f.member(),f.work(),f.world(),new WorldSettingPropertyUpdateRequest("서식지","서식지","동부",0L));locked.countDown();await(release);}));
            await(locked);var correction=pool.submit(()->corrections.correctSettingAndLinkBaseline(f.member(),f.work(),report.reportId(),input));release.countDown();edit.get(20,TimeUnit.SECONDS);
            assertThatThrownBy(()->correction.get(20,TimeUnit.SECONDS)).hasCauseInstanceOf(AppException.class);
        }
        assertThat(jdbc.queryForObject("select properties_json->>'서식지' from world_settings where id=?",String.class,f.world())).isEqualTo("동부");
        assertThat(jdbc.queryForObject("select count(*) from validation_corrections",Integer.class)).isZero();purged(report.reportId());noExtraction();
    }
    @Test void workAndMemberPurgeRemoveHistoryAndCorrectionCopies() {
        var f=fixture();var first=start(f);var receipt=corrections.correctSettingAndLinkBaseline(f.member(),f.work(),first.reportId(),correction(f,first,false));
        var current=admissions.get(f.member(),f.work(),first.reportId());
        recheck(f,first,new ValidationRecheckRequest("after-correction",current.reportVersion(),f.request().expectedContentHash(),f.request().expectedContentS3Key(),f.request().expectedContentS3Version(),0L,null,receipt.correctionId()));
        tx.executeWithoutResult(s->{works.getOwnedWorkForUpdate(f.work(),f.member());workPurge.purgeWorkData(f.work());em.clear();em.remove(em.find(Member.class,f.member()));});
        for(String table:List.of("validation_corrections","validation_reports","validation_request_keys","validation_baseline_sources","works","members"))assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).as(table).isZero();
    }
}
