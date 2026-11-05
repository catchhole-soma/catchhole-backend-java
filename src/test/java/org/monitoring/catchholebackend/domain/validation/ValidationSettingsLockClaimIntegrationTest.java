package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobClaimRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** #381: 검수가 접수부터 검토 종료까지 확정 설정을 잠근 동안 같은 작품의 자동 확정 설정 writer는 claim되지 않는다. 다른 작품과 다른 Job 종류, 검토 종료가 만든 release는 그대로다. */
@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("#381 검수 접수부터 검토 종료까지 자동 설정 작업의 claim 보류")
class ValidationSettingsLockClaimIntegrationTest extends ValidationReviewFixture {
    @Autowired AnalysisJobClaimRepository claims;

    @ParameterizedTest(name="{0}")
    @EnumSource(value=AnalysisJobType.class,names={"SETTING_EXTRACTION","CHARACTER_FACT_COMPARISON","WORLD_SETTING_COMPARISON"})
    @DisplayName("검수가 대기·실행 중인 작품의 추출·비교 Job은 claim되지 않고 다른 작품은 claim되며 검수가 끝나면 claim된다")
    void settingWritersWaitWhileTheirWorkIsLocked(AnalysisJobType type) throws Exception {
        var report=start();UUID jobId=UUID.fromString(report.path("validationJobId").asText());
        UUID own=pending(type,em.find(Work.class,work.getId()),em.find(Episode.class,prior.getId()));
        var otherWork=otherWork();UUID other=pending(type,otherWork,otherEpisode(otherWork));
        assertThat(claimable(type)).as("대기 중인 검수가 있는 작품은 건너뛰고 다른 작품을 고른다").contains(other);
        cancel(other);
        assertThat(claimable(type)).as("QUEUED").isEmpty();
        var job=claim();
        assertThat(claimable(type)).as("RUNNING").isEmpty();
        internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","stop","failureCode","MODEL_CALL_FAILED"),200);
        assertThat(claimable(type)).as("검수 실행 종료 뒤").contains(own);
        assertThat(em.createNativeQuery("select status from validation_jobs where id=?1").setParameter(1,jobId).getSingleResult()).isEqualTo("FAILED");
    }

    @ParameterizedTest(name="{0}")
    @EnumSource(value=AnalysisJobType.class,names={"SETTING_EXTRACTION","CHARACTER_FACT_COMPARISON","WORLD_SETTING_COMPARISON"})
    @DisplayName("실행이 끝나도 검토 종료 전에는 같은 작품의 추출·비교 Job을 claim하지 않고 검토를 마치면 claim한다")
    void settingWritersWaitUntilReviewCompletion(AnalysisJobType type) throws Exception {
        UUID report=UUID.fromString(typoReviewPending().path("reportId").asText());
        UUID own=pending(type,em.find(Work.class,work.getId()),em.find(Episode.class,prior.getId()));
        assertThat(claimable(type)).as("검토 단계").isEmpty();
        completeReviewKeepingTypos(report);
        assertThat(claimable(type)).as("검토 종료 뒤").contains(own);
    }

    @Test @DisplayName("작가의 검토 종료가 같은 트랜잭션에서 만든 release 추출 Job은 잠금에 막히지 않고 claim된다")
    void releaseJobCreatedAtExplicitReviewCompletionIsClaimable() throws Exception {
        UUID report=UUID.fromString(typoReviewPending().path("reportId").asText());
        assertThat(claimableRelease()).as("검토 종료 전에는 release Job이 없다").isEmpty();
        UUID release=completeReviewKeepingTypos(report);
        assertThat(claimableRelease()).contains(release);
    }

    @Test @DisplayName("판단할 항목이 없어 /complete가 자동으로 검토를 종료하면 그 release 추출 Job도 claim된다")
    void releaseJobCreatedAtAutomaticReviewCompletionIsClaimable() throws Exception {
        UUID report=zeroFindingCompletion();
        UUID release=(UUID)em.createQuery("select c.extractionJob.id from ValidationReviewCompletion c where c.report.id=:r").setParameter("r",report).getSingleResult();
        assertThat(claimableRelease()).contains(release);
    }

    @Test @DisplayName("대기 잠금 시간이 지난 QUEUED 검수는 추출을 막지 않는다")
    void staleQueuedValidationDoesNotDeferClaims() throws Exception {
        var report=start();UUID jobId=UUID.fromString(report.path("validationJobId").asText());
        UUID own=pending(AnalysisJobType.SETTING_EXTRACTION,em.find(Work.class,work.getId()),em.find(Episode.class,prior.getId()));
        assertThat(claimable(AnalysisJobType.SETTING_EXTRACTION)).isEmpty();
        em.flush();em.createNativeQuery("update validation_jobs set updated_at=?1 where id=?2").setParameter(1,LocalDateTime.now().minusMinutes(11)).setParameter(2,jobId).executeUpdate();em.clear();
        assertThat(claimable(AnalysisJobType.SETTING_EXTRACTION)).contains(own);
    }

    @Test @DisplayName("확정 설정을 쓰지 않는 다른 Job 종류는 검수가 실행 중이어도 claim 조건이 바뀌지 않는다")
    void otherJobTypesAreUnaffected() throws Exception {
        start();claim();
        UUID own=pending(AnalysisJobType.EPISODE_VALIDATION,em.find(Work.class,work.getId()),em.find(Episode.class,prior.getId()));
        assertThat(claimable(AnalysisJobType.EPISODE_VALIDATION)).contains(own);
    }

    Optional<UUID> claimable(AnalysisJobType type) {
        em.flush();
        return claims.findClaimableJob(new WorkerAnalysisJobClaimRequest("synthetic-no-llm",null,Set.of(type),null,null,null,null,null,null,null,null)).map(AnalysisJob::getId);
    }
    /** 검토 종료 release는 순차 자동 분석이라 그 모드를 지원하는 claim만 가져간다(ValidationReviewCompletionIntegrationTest와 같은 요청). */
    Optional<UUID> claimableRelease() {
        em.flush();
        return claims.findClaimableJob(new WorkerAnalysisJobClaimRequest("synthetic-no-llm","검토 종료 release",Set.of(AnalysisJobType.SETTING_EXTRACTION),
                Set.of(org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode.CONFIRMED_ONLY,org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode.ORDERED_PROVISIONAL),
                true,Set.of(org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE),true,true,true)).map(AnalysisJob::getId);
    }
    UUID pending(AnalysisJobType type,Work owner,Episode episode) {
        var batch=em.createQuery("select b from UploadBatch b where b.work.id=:w order by b.createdAt",UploadBatch.class).setParameter("w",owner.getId()).setMaxResults(1).getResultList();
        var job=AnalysisJob.create(owner,batch.isEmpty()?null:batch.getFirst(),episode,type);em.persist(job);em.flush();return job.getId();
    }
    void cancel(UUID analysisJob) {
        em.flush();em.createNativeQuery("update analysis_jobs set status='CANCELED' where id=?1").setParameter(1,analysisJob).executeUpdate();em.clear();
    }
    Work otherWork() {var other=Work.create(em.find(org.monitoring.catchholebackend.domain.member.entity.Member.class,member.getId()),"다른 합성 작품",WorkGenre.FANTASY,null);em.persist(other);return other;}
    Episode otherEpisode(Work owner) {
        var episode=Episode.create(owner,null,1,"다른 회차","synthetic/other/"+owner.getId(),"v1","b".repeat(64),3);em.persist(episode);owner.updateLatestEpisodeNo(1);em.flush();return episode;
    }
}
