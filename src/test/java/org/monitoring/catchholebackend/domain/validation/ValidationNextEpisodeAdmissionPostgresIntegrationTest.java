package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;

import jakarta.persistence.LockModeType;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeUploadResponse;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationStagingResponse;
import org.monitoring.catchholebackend.domain.validation.type.ValidationReviewStage;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;

/**
 * #314 B1 다음 회차 차단·접수 자격을 실제 PostgreSQL에서 다시 실행한다. 상위 H2 테스트 메서드를 그대로 상속하고(admission JPQL·자격 판정),
 * 다음 회차 업로드와 최신 회차 검수 접수가 같은 Work 행 잠금으로 직렬화되는지 실제 행 잠금 대기로 확인한다.
 * Destructive fixture cleanup is guarded to a new disposable loopback DB, never .env/shared DB.
 */
// chat.recovery-delay-ms: 이 클래스와 무관한 채팅 복구 scheduler가 fixture TRUNCATE와 겹쳐 읽지 않도록 Context 시작 1회만 돌게 한다.
@SpringBootTest(properties={"spring.config.import=","spring.docker.compose.enabled=false","chat.recovery-delay-ms=3600000"}) @ActiveProfiles("test")
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@EnabledIfEnvironmentVariable(named="GH310_EXECUTION_POSTGRES_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh310_execution_test")
@DisplayName("격리 PostgreSQL의 다음 회차 등록 차단과 검수 접수 직렬화")
class ValidationNextEpisodeAdmissionPostgresIntegrationTest extends ValidationNextEpisodeAdmissionIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->System.getenv("GH310_EXECUTION_POSTGRES_JDBC_URL"));p.add("spring.datasource.username",()->"gh310");p.add("spring.datasource.password",()->"gh310-disposable-only");
        p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");p.add("spring.jpa.hibernate.ddl-auto",()->"validate");p.add("spring.flyway.enabled",()->"true");
    }
    /** Worker claim은 DB 전체의 대기 Job을 고르므로 매 테스트 전에 합성 작품·회원 그래프를 모두 지운다. */
    @BeforeEach void clean() {org.monitoring.catchholebackend.support.PostgresFixtureCleanup.truncate(jdbc,"TRUNCATE TABLE works,members CASCADE");}

    static void await(CountDownLatch latch) {try {if(!latch.await(15,TimeUnit.SECONDS))throw new AssertionError("synthetic latch timed out");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);}}
    /** 이 DB에서 행 잠금을 기다리는 세션 수가 n이 될 때까지 기다린다. */
    void awaitLockWaiters(int n) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(count("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock'")<n) {
            if(System.nanoTime()>deadline)throw new AssertionError("requests did not queue on the Work row lock");
            try {Thread.sleep(20);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);}
        }
    }
    record Race(Object upload,Object start) {}
    /** Work 행을 잠근 채 두 요청을 정해진 순서로 같은 잠금에 줄 세운 뒤 풀어 결과를 모은다. */
    Race race(Owner o,EpisodeUploadResponse first,UUID target,String key,boolean uploadFirst) throws Exception {
        var request=startRequest(key,first.batchId(),target);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(3)) {
            var holder=pool.submit(()->tx.executeWithoutResult(s->{em.find(Work.class,o.work(),LockModeType.PESSIMISTIC_WRITE);locked.countDown();await(release);}));
            await(locked);
            Callable<Object> upload=()->{try {return upload(o.member(),o.work(),Mode.SINGLE,102);}catch(AppException e) {return e;}};
            Callable<Object> start=()->{try {return admissions.start(o.member(),o.work(),request);}catch(AppException e) {return e;}};
            var firstFuture=pool.submit(uploadFirst?upload:start);awaitLockWaiters(1);
            var secondFuture=pool.submit(uploadFirst?start:upload);awaitLockWaiters(2);
            release.countDown();holder.get(30,TimeUnit.SECONDS);
            var a=firstFuture.get(30,TimeUnit.SECONDS);var b=secondFuture.get(30,TimeUnit.SECONDS);
            return uploadFirst?new Race(a,b):new Race(b,a);
        }
    }
    static String code(Object outcome) {return outcome instanceof AppException e?e.getResultCode().getCode():"OK";}

    @Test @DisplayName("다음 회차 업로드와 최신 회차 검수 접수는 Work 행 잠금으로 직렬화되어 먼저 잠근 쪽만 성공하고 교착·부분 쓰기가 없다")
    void nextEpisodeUploadAndReviewStartSerialize() throws Exception {
        // 업로드가 먼저 잠그면 101은 최신 회차가 아니게 되어 접수가 거절된다.
        var o=owner(true,true);var first=upload(o.member(),o.work(),Mode.SINGLE,101);var target=first.createdEpisodes().getFirst().id();var before=writes(o.work());
        var uploadWins=race(o,first,target,"upload-first",true);
        assertThat(uploadWins.upload()).isInstanceOf(EpisodeUploadResponse.class);assertThat(code(uploadWins.start())).isEqualTo("VALIDATION_TARGET_INVALID");
        var after=writes(o.work());
        assertThat(after.reports()).isZero();assertThat(after.keys()).isZero();assertThat(after.validationJobs()).isZero();
        assertThat(after.batches()).isEqualTo(before.batches()+1);assertThat(after.episodes()).isEqualTo(before.episodes()+1);
        assertThat(eligibility(o.member(),o.work(),target).path("reasonCode").asText()).isEqualTo("NOT_LATEST_EPISODE");

        // 접수가 먼저 잠그면 새 report가 다음 회차 등록을 막고 업로드는 아무것도 쓰지 않는다.
        var p=owner(true,true);var pFirst=upload(p.member(),p.work(),Mode.SINGLE,101);var pTarget=pFirst.createdEpisodes().getFirst().id();var pBefore=writes(p.work());
        var startWins=race(p,pFirst,pTarget,"start-first",false);
        assertThat(startWins.start()).isInstanceOf(ValidationStagingResponse.class);assertThat(code(startWins.upload())).isEqualTo(BLOCKED);
        var report=((ValidationStagingResponse)startWins.start()).reportId();
        assertThat(((AppException)startWins.upload()).getErrorContext()).containsEntry("reportId",report.toString()).containsEntry("episodeNo",101);
        var pAfter=writes(p.work());
        assertThat(pAfter.batches()).isEqualTo(pBefore.batches());assertThat(pAfter.files()).isEqualTo(pBefore.files());assertThat(pAfter.episodes()).isEqualTo(pBefore.episodes());
        assertThat(pAfter.storedObjects()).as("the losing upload stored no object").isEqualTo(pBefore.storedObjects());
        assertThat(pAfter.reports()).isEqualTo(1);assertThat(pAfter.keys()).isEqualTo(1);
    }

    @Test @DisplayName("커밋된 검수 접수 뒤 다음 회차 업로드는 실제 PostgreSQL에서도 세 방식 모두 409·쓰기 0이다")
    void nextEpisodeBlockedAfterStartCommitted() throws Exception {
        var o=owner(true,true);var first=upload(o.member(),o.work(),Mode.SINGLE,101);var target=first.createdEpisodes().getFirst().id();
        var started=admissions.start(o.member(),o.work(),startRequest("committed",first.batchId(),target));
        assertThat(started.reviewStage()).isIn(ValidationReviewStage.PREPARING_BASELINE,ValidationReviewStage.VALIDATING);
        var before=writes(o.work());
        for(var mode:Mode.values())
            assertThatThrownBy(()->upload(o.member(),o.work(),mode,102)).as(mode.name()).isInstanceOfSatisfying(AppException.class,e->{
                assertThat(e.getResultCode().getCode()).isEqualTo(BLOCKED);
                assertThat(e.getErrorContext()).containsEntry("reportId",started.reportId().toString()).containsEntry("episodeId",target.toString())
                        .containsEntry("episodeNo",101).containsEntry("reviewStage",started.reviewStage().name());
            });
        assertThat(writes(o.work())).isEqualTo(before);
        var admission=eligibility(o.member(),o.work(),null).path("nextEpisodeAdmission");
        assertThat(admission.path("allowed").asBoolean()).isFalse();assertThat(admission.path("blockingReportId").asText()).isEqualTo(started.reportId().toString());
    }
}
