package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** 배포 전(grid v1) SETTING grid를 가진 Job의 공개 retry. 모든 원고·수치는 합성 fixture다. */
@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("v1 SETTING grid Job의 공개 retry 전환 (#349)")
class ValidationLegacyGridRetryIntegrationTest extends ValidationWorkerFixture {
    ValidationJob job() {return em.createQuery("select j from ValidationJob j",ValidationJob.class).getSingleResult();}
    long count(String sql) {return ((Number)em.createNativeQuery(sql).getSingleResult()).longValue();}
    UUID reportId(JsonNode job) {return UUID.fromString(job.path("scope").path("reportId").asText());}
    /** 저장 SETTING grid를 배포 전 빌더 모양(gridVersion 1, page 선별 단위, segmentSubjects 없음)으로 되돌리고 그때 저장된 page stage를 남긴다. */
    void storeLegacySettingGrid() {
        var j=job();var legacy=(ObjectNode)j.getRequiredGrid().path("SETTING").deepCopy();legacy.put("gridVersion",1);legacy.remove("segmentSubjects");
        var units=legacy.putArray("units");
        for(String[] unit:new String[][]{{"PLANNER","TSEG1"},{"COVERAGE","TSEG1"},{"RELATED_SELECTION","TSEG1:PAGE1"},{"MERGED_PLAN","PLAN"}})units.addObject().put("stage",unit[0]).put("ref",unit[1]);
        j.initializeGrid(ValidationBranch.SETTING,legacy);
        var page=json.createObjectNode();page.putArray("entries").addObject().put("entry_ref","B1").put("selected",false).put("reason","합성 비선정");
        em.persist(ValidationStage.create(j.getReport(),ValidationBranch.SETTING,ValidationStageKind.RELATED_SELECTION,"TSEG1:PAGE1","a".repeat(64),page,true));em.flush();
    }
    void checkpoint(JsonNode job,String branch) throws Exception {
        internal(path(job,"/checkpoint"),job,Map.of("scope",scope(job),"idempotencyKey","checkpoint-"+branch,"branch",branch,"expectedVersion",0,"payload",Map.of("phase","PLANNING"),"citations",List.of()),200);
    }
    String finish(JsonNode job) throws Exception {
        return internal(path(job,"/complete"),job,Map.of("scope",scope(job),"idempotencyKey","stop-"+UUID.randomUUID(),"failureCode","MODEL_CALL_FAILED"),200).path("status").asText();
    }
    void retry(JsonNode job,String key) throws Exception {
        postOk("/"+reportId(job)+"/retries",Map.of("expectedReportVersion",get(reportId(job)).path("reportVersion").asLong(),"idempotencyKey",key));
    }
    Map<String,Object> settle(JsonNode job,String role) throws Exception {
        UUID call=UUID.randomUUID();internal(path(job,"/usage/reserve"),job,Map.of("scope",scope(job),"requestId",call,"role",role,"model","gpt-6.1-terra","reservedTokens",100),200);
        internal(path(job,"/usage/"+call),job,Map.of("scope",scope(job),"kind","SETTLE","inputTokens",25,"cachedInputTokens",0,"outputTokens",7,"outcome","SUCCESS"),200);
        return Map.of("requestId",call);
    }

    @Test @DisplayName("부분 완료 v1 Job의 retry는 SETTING 사본만 버리고 다음 실행이 v2 grid를 만들며 TYPO와 사용량 누계는 남는다")
    void partialLegacyJobRetryDiscardsSettingCopiesAndRebuildsV2Grid() throws Exception {
        source();start();var job=claim();
        internal(path(job,"/units"),job,unit(job,"TYPO","TSEG1","COMPLETED",typoEmpty()),200);checkpoint(job,"TYPO");checkpoint(job,"SETTING");
        var planner=batch("TSEG1",List.of());savePlan(job,"PLANNER","TSEG1",planner);settle(job,"RELATED_SELECTION");
        storeLegacySettingGrid();
        assertThat(internal(path(job,"/plans"),job,publication(job,"SETTING","SUBJECT_RESOLUTION","TSEG1",Map.of("mentions",List.of())),409).path("code").asText())
                .as("배포 전 grid는 새 grid와 base가 달라 SETTING 게시가 충돌한다").isEqualTo("VALIDATION_SOURCE_VERSION_CONFLICT");
        assertThat(finish(job)).isEqualTo("PARTIAL");
        long usages=count("select count(*) from ai_token_usages"),used=count("select used_tokens from ai_token_accounts where member_id="+member.getId());

        retry(job,"upgrade-partial");
        assertThat(count("select count(*) from validation_stages where branch='SETTING'")).isZero();
        assertThat(count("select count(*) from validation_stages where branch='TYPO'")).isEqualTo(1);
        assertThat(count("select count(*) from validation_checkpoints where branch='SETTING'")).isZero();
        assertThat(count("select count(*) from validation_checkpoints where branch='TYPO'")).isEqualTo(1);
        assertThat(count("select count(*) from validation_publications where request_key in ('PLANNER:TSEG1','checkpoint-SETTING')")).isZero();
        assertThat(count("select count(*) from validation_publications where request_key in ('unit-TYPO-TSEG1','checkpoint-TYPO')")).isEqualTo(2);
        var j=job();assertThat(j.getStatus()).isEqualTo(ValidationExecutionStatus.QUEUED);assertThat(j.isGridReady()).isFalse();
        assertThat(j.getRequiredGrid().has("SETTING")).isFalse();assertThat(j.getRequiredGrid().has("TYPO")).isTrue();
        assertThat(count("select count(*) from ai_token_usages")).as("원장 행은 retry 규칙대로 보존한다").isEqualTo(usages);
        assertThat(count("select used_tokens from ai_token_accounts where member_id="+member.getId())).isEqualTo(used);
        assertThat(count("select count(*) from ai_token_usages where status='SETTLED'")).isEqualTo(1);

        var next=claim();assertThat(next.path("usage").size()).as("역할 cap 누계는 Job 전체로 이어진다").isEqualTo(1);
        savePlan(next,"PLANNER","TSEG1",planner);
        assertThat(count("select count(*) from validation_stages where branch='SETTING' and stage='PLANNER'")).as("같은 key의 같은 본문도 옛 receipt가 아니라 새 stage로 저장된다").isEqualTo(1);
        var grid=job().getRequiredGrid().path("SETTING");
        assertThat(grid.path("gridVersion").asInt()).isEqualTo(2);assertThat(grid.has("segmentSubjects")).isTrue();
        var units=new ArrayList<String>();grid.path("units").forEach(u->units.add(u.path("stage").asText()+":"+u.path("ref").asText()));
        assertThat(units).contains("SUBJECT_RESOLUTION:TSEG1").noneMatch(u->u.startsWith("RELATED_SELECTION"));
        savePlan(next,"SUBJECT_RESOLUTION","TSEG1",Map.of("mentions",List.of()));savePlan(next,"COVERAGE","TSEG1",batch("TSEG1",List.of()));noExtraction();
    }
    @Test @DisplayName("실패 상태의 v1 Job도 retry에서 SETTING 사본을 버리고 grid를 비운다")
    void failedLegacyJobRetryAlsoDiscardsSettingCopies() throws Exception {
        source();start();var job=claim();savePlan(job,"PLANNER","TSEG1",batch("TSEG1",List.of()));storeLegacySettingGrid();
        assertThat(finish(job)).isEqualTo("FAILED");
        retry(job,"upgrade-failed");
        assertThat(count("select count(*) from validation_stages")).isZero();
        var j=job();assertThat(j.getStatus()).isEqualTo(ValidationExecutionStatus.QUEUED);assertThat(j.getRequiredGrid()).isNull();assertThat(j.isGridReady()).isFalse();
        var next=claim();savePlan(next,"PLANNER","TSEG1",batch("TSEG1",List.of()));
        assertThat(job().getRequiredGrid().path("SETTING").path("gridVersion").asInt()).isEqualTo(2);noExtraction();
    }
    @Test @DisplayName("현재 grid v2 Job의 retry는 기존처럼 SETTING stage·checkpoint·grid를 보존한다")
    void currentGridRetryKeepsSettingStages() throws Exception {
        source();start();var job=claim();
        internal(path(job,"/units"),job,unit(job,"TYPO","TSEG1","COMPLETED",typoEmpty()),200);checkpoint(job,"SETTING");
        savePlan(job,"PLANNER","TSEG1",batch("TSEG1",List.of()));savePlan(job,"SUBJECT_RESOLUTION","TSEG1",Map.of("mentions",List.of()));
        assertThat(finish(job)).isEqualTo("PARTIAL");var before=job().getRequiredGrid().path("SETTING").deepCopy();
        retry(job,"keep-v2");
        assertThat(count("select count(*) from validation_stages where branch='SETTING'")).isEqualTo(2);
        assertThat(count("select count(*) from validation_checkpoints where branch='SETTING'")).isEqualTo(1);
        assertThat(count("select count(*) from validation_publications where request_key='PLANNER:TSEG1'")).isEqualTo(1);
        assertThat(job().getRequiredGrid().path("SETTING")).isEqualTo(before);assertThat(job().getStatus()).isEqualTo(ValidationExecutionStatus.QUEUED);noExtraction();
    }
    @Test @DisplayName("SETTING finding이 있는 v1 Job은 판정과 그 check를 지우지 않으므로 retry가 SETTING 사본을 그대로 둔다")
    void legacyJobWithSettingFindingKeepsSettingCopies() throws Exception {
        source();start();var job=claim();
        var span=Map.of("source_ref","T1","start",0,"end",2,"quote","처음");
        var citation=Map.of("sourceRef","T1","sourceHash",target.getContentHash(),"sourceVersion",target.getContentS3Version(),"start",0,"end",2,"quote","처음");
        var draft=draft("local-1",List.of(span));var planner=publication(job,"SETTING","PLANNER","TSEG1",batch("TSEG1",List.of(draft)));planner.put("citations",List.of(citation));internal(path(job,"/plans"),job,planner,200);
        savePlan(job,"SUBJECT_RESOLUTION","TSEG1",Map.of("mentions",List.of()));savePlan(job,"COVERAGE","TSEG1",batch("TSEG1",List.of()));
        var plan=publication(job,"SETTING","MERGED_PLAN","PLAN",Map.of("questions",List.of(Map.of("localRef","Q1","originalRefs",List.of("PLANNER:TSEG1:local-1"),"state",Map.of("draft",draft)))));plan.put("citations",List.of(citation));
        var check=internal(path(job,"/plans"),job,plan,200).path("checks").path(0);
        em.persist(ValidationFinding.create(job().getReport(),ValidationBranch.SETTING,"Q1",UUID.fromString(check.path("checkId").asText()),1L,"CONFLICT",json.createObjectNode().put("reason","합성 충돌")));
        storeLegacySettingGrid();long stages=count("select count(*) from validation_stages where branch='SETTING'");
        finish(job);retry(job,"keep-finding");
        assertThat(count("select count(*) from validation_stages where branch='SETTING'")).isEqualTo(stages);
        assertThat(count("select count(*) from validation_checks")).isEqualTo(1);assertThat(count("select count(*) from validation_findings")).isEqualTo(1);
        assertThat(job().getRequiredGrid().path("SETTING").path("gridVersion").asInt()).isEqualTo(1);noExtraction();
    }
}
