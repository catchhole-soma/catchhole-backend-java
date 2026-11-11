package org.monitoring.catchholebackend.domain.analysis.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.service.FirstAnalysisCompletionReportService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("계정별 첫 분석 완료 전환 보고")
class FirstAnalysisCompletionReportControllerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired WorkRepository works;
    @Autowired AnalysisJobRepository jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider jwt;
    @Autowired FirstAnalysisCompletionReportService reports;
    Member member;
    Work work;
    String token;
    final String endpoint = "/api/v1/first-analysis-completion-reports";

    @BeforeEach
    void prepare() {
        member = members.saveAndFlush(Member.register("first-done@example.com", "encoded", "01078790001", "작가"));
        work = works.saveAndFlush(Work.create(member, "첫 작품", WorkGenre.FANTASY, null));
        token = "Bearer " + jwt.generateAccessToken(member);
    }

    // 상태 전이를 거치지 않고 판정 조건에 쓰이는 컬럼만 직접 맞춘다.
    private UUID job(Work target, AnalysisJobType type, String status) {
        UUID id = jobs.saveAndFlush(AnalysisJob.create(target, null, null, type)).getId();
        jdbc.update("update analysis_jobs set status = ?, completed_at = ? where id = ?", status, LocalDateTime.now(), id);
        return id;
    }

    private void ordered(UUID id, String reviewMode, String journalStatus) {
        jdbc.update("update analysis_jobs set analysis_mode = 'ORDERED_PROVISIONAL', review_mode = ?, journal_status = ? where id = ?",
                reviewMode, journalStatus, id);
    }

    private boolean shouldReport() {
        return reports.getFirstAnalysisCompletionReport(member.getId()).shouldReport();
    }

    @Test @DisplayName("분석을 완료하지 않은 계정은 보고 대상이 아니며 선점도 기록하지 않는다")
    void notReportableWithoutCompletedAnalysis() throws Exception {
        mvc.perform(get(endpoint).header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shouldReport").value(false));
        job(work, AnalysisJobType.SETTING_EXTRACTION, "PENDING");
        job(work, AnalysisJobType.SETTING_EXTRACTION, "RUNNING");
        job(work, AnalysisJobType.SETTING_EXTRACTION, "FAILED");
        assertThat(shouldReport()).isFalse();
        mvc.perform(post(endpoint + "/claim").header("Authorization", token))
                .andExpect(jsonPath("$.data.shouldReport").value(false));
        assertThat(member.getFirstAnalysisCompletionReportedAt()).isNull();
    }

    @Test @DisplayName("단일 분석 성공부터 대상이며 조회만으로 보고 기록을 남기지 않는다")
    void readDoesNotConsumeReport() throws Exception {
        job(work, AnalysisJobType.SETTING_EXTRACTION, "SUCCEEDED");
        mvc.perform(get(endpoint).header("Authorization", token)).andExpect(jsonPath("$.data.shouldReport").value(true));
        mvc.perform(get(endpoint).header("Authorization", token)).andExpect(jsonPath("$.data.shouldReport").value(true));
        assertThat(member.getFirstAnalysisCompletionReportedAt()).isNull();
    }

    @Test @DisplayName("계정당 보고 선점은 한 번이며 다른 로그인과 다음 분석 완료에서도 반복하지 않는다")
    void claimOnlyOnce() throws Exception {
        job(work, AnalysisJobType.SETTING_EXTRACTION, "SUCCEEDED");
        mvc.perform(post(endpoint + "/claim").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shouldReport").value(true));
        var second = works.saveAndFlush(Work.create(member, "두 번째 작품", WorkGenre.SF, null));
        job(second, AnalysisJobType.SETTING_EXTRACTION, "SUCCEEDED");
        String otherSession = "Bearer " + jwt.generateAccessToken(member);
        mvc.perform(post(endpoint + "/claim").header("Authorization", otherSession))
                .andExpect(jsonPath("$.data.shouldReport").value(false));
        mvc.perform(get(endpoint).header("Authorization", otherSession))
                .andExpect(jsonPath("$.data.shouldReport").value(false));
        assertThat(member.getFirstAnalysisCompletionReportedAt()).isNotNull();
    }

    @Test @DisplayName("순차 분석은 봉인과 자동 반영 또는 검수 보류까지 끝나야 완료로 본다")
    void orderedAnalysisRequiresSealedAndApplied() {
        UUID id = job(work, AnalysisJobType.SETTING_EXTRACTION, "SUCCEEDED");
        ordered(id, "AUTOMATIC", "INCOMPLETE");
        assertThat(shouldReport()).as("재개 대기는 완료가 아니다").isFalse();
        ordered(id, "AUTOMATIC", "SEALED");
        assertThat(shouldReport()).as("자동 반영 전").isFalse();
        jdbc.update("update analysis_jobs set review_application_held_at = ? where id = ?", LocalDateTime.now(), id);
        assertThat(shouldReport()).as("검수 보류").isTrue();
        jdbc.update("update analysis_jobs set review_application_held_at = null, automatic_applied_at = ? where id = ?",
                LocalDateTime.now(), id);
        assertThat(shouldReport()).as("자동 반영 완료").isTrue();
        jdbc.update("update analysis_jobs set automatic_applied_at = null where id = ?", id);
        ordered(id, "MANUAL", "SEALED");
        assertThat(shouldReport()).as("직접 검토 순차 분석 봉인").isTrue();
    }

    @Test @DisplayName("오류 리포트 검수와 내부 비교 작업의 성공은 첫 분석 완료가 아니다")
    void onlySettingExtractionCounts() {
        job(work, AnalysisJobType.EPISODE_VALIDATION, "SUCCEEDED");
        UUID comparison = job(work, AnalysisJobType.SETTING_EXTRACTION, "SUCCEEDED");
        jdbc.update("update analysis_jobs set job_type = 'WORLD_SETTING_COMPARISON' where id = ?", comparison);
        assertThat(shouldReport()).isFalse();
    }

    @Test @DisplayName("다른 회원의 분석 완료는 이 계정의 보고 여부를 바꾸지 않는다")
    void accountsAreIndependent() {
        var other = members.saveAndFlush(Member.register("first-done-other@example.com", "encoded", "01078790002", "다른 작가"));
        var otherWork = works.saveAndFlush(Work.create(other, "다른 작품", WorkGenre.FANTASY, null));
        job(otherWork, AnalysisJobType.SETTING_EXTRACTION, "SUCCEEDED");
        assertThat(shouldReport()).isFalse();
        assertThat(reports.getFirstAnalysisCompletionReport(other.getId()).shouldReport()).isTrue();
    }

    @Test @DisplayName("비로그인 요청은 보고 여부를 읽거나 선점할 수 없다")
    void authenticationRequired() throws Exception {
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        mvc.perform(post(endpoint + "/claim")).andExpect(status().isUnauthorized());
    }
}
