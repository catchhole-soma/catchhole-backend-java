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

/** GH312 S4 회차 검수 이력 API. 과거 report는 요약만 반환하며 인용·근거·판정 payload를 복제하지 않는다. */
@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("GH312 S4 회차 검수 이력 조회")
class ValidationHistoryIntegrationTest extends ValidationReviewFixture {
    JsonNode history(String query,String bearer,int status) throws Exception {return read("/history?episodeId="+target.getId()+query,bearer,status);}

    @Test @DisplayName("재검수 뒤 이력은 최신 revision부터 요약만 반환하고 퇴역 report는 무효화 시각·0건·INVALIDATED 단계다")
    void historySummarizesCurrentAndRetiredReports() throws Exception {
        var report=reportId(completedWithFindings(1));judge(report,only(report,"SETTING"),"NOT_CONFLICT");
        var first=history("",token,200);
        assertThat(first.path("totalElements").asLong()).isEqualTo(1);var row=first.path("content").path(0);
        assertThat(row.path("reportId").asText()).isEqualTo(report.toString());assertThat(row.path("revision").asLong()).isEqualTo(1);assertThat(row.path("current").asBoolean()).isTrue();
        assertThat(row.path("executionStatus").asText()).isEqualTo("COMPLETED");assertThat(row.path("mode").asText()).isEqualTo("BEFORE_CONFIRMATION");
        assertThat(row.path("counts").path("settingFindings").asLong()).isEqualTo(1);assertThat(row.path("counts").path("typoFindings").asLong()).isEqualTo(2);
        assertThat(row.path("reviewStage").asText()).isEqualTo("AWAITING_REVIEW");assertThat(row.path("requestedAt").isTextual()).isTrue();
        for(String nullable:List.of("completion","application","releaseHold","invalidatedAt","previousReportId"))assertThat(row.path(nullable).isNull()).as(nullable).isTrue();
        assertThat(row.toString()).as("summary only").doesNotContain("quote","evidence","payload","spans","처음");
        assertThat(get(report).path("reviewStage").asText()).isEqualTo("AWAITING_REVIEW");assertThat(review(report).path("reviewStage").asText()).isEqualTo("AWAITING_REVIEW");

        var original=get(report);replace("수정한 합성 원고");
        org.mockito.Mockito.when(storage.getText(org.mockito.ArgumentMatchers.anyString())).thenAnswer(c->c.getArgument(0).equals(target.getContentS3Key())?"수정한 합성 원고":"합성 앞 원고");
        assertThat(get(report).path("reviewStage").asText()).isEqualTo("INVALIDATED");
        var next=UUID.fromString(postOk(recheckPath(original),recheck(original,"after-fix")).path("reportId").asText());
        var both=history("",token,200).path("content");
        assertThat(both.size()).isEqualTo(2);assertThat(both.path(0).path("reportId").asText()).isEqualTo(next.toString());
        assertThat(both.path(0).path("revision").asLong()).isEqualTo(2);assertThat(both.path(0).path("previousReportId").asText()).isEqualTo(report.toString());
        assertThat(both.path(0).path("current").asBoolean()).isTrue();assertThat(both.path(0).path("reviewStage").asText()).isEqualTo("VALIDATING");
        var retired=both.path(1);
        assertThat(retired.path("reportId").asText()).isEqualTo(report.toString());assertThat(retired.path("current").asBoolean()).isFalse();
        assertThat(retired.path("status").asText()).isEqualTo("INVALIDATED");assertThat(retired.path("reviewStage").asText()).isEqualTo("INVALIDATED");
        assertThat(retired.path("invalidatedAt").isTextual()).as("first invalidation time is recorded").isTrue();
        assertThat(retired.path("counts").path("settingFindings").asLong()+retired.path("counts").path("typoFindings").asLong()).as("purged findings count as 0").isZero();
        assertThat(both.toString()).doesNotContain("quote","evidence","payload");

        var page=history("&page=0&size=1",token,200);
        assertThat(page.path("content").size()).isEqualTo(1);assertThat(page.path("totalElements").asLong()).isEqualTo(2);assertThat(page.path("hasNext").asBoolean()).isTrue();
        noExtraction();
    }

    @Test @DisplayName("이력은 작품 소유권·페이지 범위를 검증하고 다른 회차 ID는 빈 페이지다")
    void historyChecksOwnershipAndPaging() throws Exception {
        reportId(completedWithFindings(1));
        assertThat(code(history("&size=0",token,400))).isEqualTo("REQUEST_VALIDATION_FAILED");
        assertThat(code(history("&size=101",token,400))).isEqualTo("REQUEST_VALIDATION_FAILED");
        assertThat(code(history("&page=-1",token,400))).isEqualTo("REQUEST_VALIDATION_FAILED");
        var other=org.monitoring.catchholebackend.domain.member.entity.Member.register("other-"+UUID.randomUUID()+"@example.invalid","synthetic","01098765431","다른 작가");em.persist(other);
        assertThat(code(history("",tokens.generateAccessToken(other),404))).isEqualTo("WORK_NOT_FOUND");
        var empty=read("/history?episodeId="+UUID.randomUUID(),token,200);
        assertThat(empty.path("content").size()).isZero();assertThat(empty.path("totalElements").asLong()).isZero();
        read("/history",token,400);
    }
}
