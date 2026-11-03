package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** #349 역할 cap은 실행(run) 단위다. 모든 수치는 합성 fixture(PLANNER cap 20회)다. */
@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("검수 역할 cap의 실행 단위 집계 (#349 run_start_generation)")
class ValidationUsageRunCapIntegrationTest extends ValidationWorkerFixture {
    UUID reserve(JsonNode job,int status) throws Exception {UUID call=UUID.randomUUID();internal(path(job,"/usage/reserve"),job,Map.of("scope",scope(job),"requestId",call,"role","PLANNER","model","gpt-6.1-terra","reservedTokens",100),status);return call;}
    void exhaust(JsonNode job) throws Exception {for(int i=0;i<20;i++)reserve(job,200);reserve(job,409);}
    long column(String name) {return ((Number)em.createNativeQuery("select "+name+" from validation_jobs").getSingleResult()).longValue();}
    void expireLease() {em.createNativeQuery("update validation_jobs set lease_expires_at=CURRENT_TIMESTAMP - interval '1' second").executeUpdate();em.clear();}
    void retry(JsonNode report,String key) throws Exception {var current=get(UUID.fromString(report.path("reportId").asText()));postOk("/"+report.path("reportId").asText()+"/retries",Map.of("expectedReportVersion",current.path("reportVersion").asLong(),"idempotencyKey",key));}

    @Test @DisplayName("Worker lease 회수는 같은 실행이라 이전 generation 사용량을 계속 센다")
    void leaseRecoveryKeepsCountingTheSameRun() throws Exception {
        start();var job=claim();exhaust(job);
        expireLease();var recovered=claim();
        assertThat(recovered.path("scope").path("generation").asLong()).isEqualTo(2);
        assertThat(column("run_start_generation")).isEqualTo(1);
        reserve(recovered,409);noExtraction();
    }
    @Test @DisplayName("작가 재시도는 새 실행을 시작해 cap을 처음부터 세고 이전 실행의 늦은 SETTLE은 새 실행 cap에 들어가지 않는다")
    void authorRetryStartsFreshAndLateOldSettlementsDoNotCount() throws Exception {
        var report=start();var job=claim();var calls=new ArrayList<UUID>();for(int i=0;i<20;i++)calls.add(reserve(job,200));reserve(job,409);
        internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","stop","failureCode","VALIDATION_EXECUTION_LIMIT_EXCEEDED"),200);
        retry(report,"fresh-run");var next=claim();
        assertThat(column("generation")).isEqualTo(2);assertThat(column("run_start_generation")).isEqualTo(2);
        // A provider answer from the old run arrives late with its original lease/generation.
        for(var call:calls.subList(0,5))internal(path(job,"/usage/"+call),job,Map.of("scope",scope(job),"kind","SETTLE","inputTokens",25,"cachedInputTokens",0,"outputTokens",7,"outcome","SUCCESS"),200);
        for(int i=0;i<20;i++)reserve(next,200);
        reserve(next,409);
        expireLease();var recovered=claim();assertThat(column("run_start_generation")).as("lease 회수는 실행 시작을 옮기지 않는다").isEqualTo(2);reserve(recovered,409);noExtraction();
    }
    @Test @DisplayName("호출하지 않았음이 확실한 RELEASE 행은 cap에 들어가지 않는다")
    void releasedRowsNeverCount() throws Exception {
        start();var job=claim();
        for(int i=0;i<25;i++) {var call=reserve(job,200);internal(path(job,"/usage/"+call),job,Map.of("scope",scope(job),"kind","RELEASE"),200);}
        for(int i=0;i<20;i++)reserve(job,200);
        reserve(job,409);noExtraction();
    }
}
