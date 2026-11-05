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

/** #381: 검수 실행이 확정 설정을 잠근 동안 같은 작품의 자동 확정 설정 writer는 claim되지 않는다. 다른 작품과 다른 Job 종류는 그대로다. */
@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("#381 검수 실행 중 자동 설정 작업의 claim 보류")
class ValidationSettingsLockClaimIntegrationTest extends ValidationWorkerFixture {
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
