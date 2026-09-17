package org.monitoring.catchholebackend.domain.analysis.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisGuideService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("계정별 첫 분석 방식 안내")
class AnalysisGuideControllerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired WorkRepository works;
    @Autowired AnalysisJobRepository jobs;
    @Autowired JwtTokenProvider jwt;
    @Autowired AnalysisGuideService guides;
    Member member;
    String token;
    final String endpoint = "/api/v1/analysis-mode-guide";

    @BeforeEach
    void prepare() {
        member = members.saveAndFlush(Member.register("guide@example.com", "encoded", "01078780001", "작가"));
        token = "Bearer " + jwt.generateAccessToken(member);
    }

    @Test @DisplayName("분석 0건만 대상이며 조회만으로 안내 이력을 기록하지 않는다")
    void readDoesNotConsumePrompt() throws Exception {
        mvc.perform(get(endpoint).header("Authorization", token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.shouldShow").value(true));
        assertThat(member.getAnalysisGuideShownAt()).isNull();
        mvc.perform(get(endpoint).header("Authorization", token)).andExpect(jsonPath("$.data.shouldShow").value(true));
    }

    @Test @DisplayName("계정당 안내 선점은 한 번이며 새 작품과 다른 로그인에서도 반복하지 않는다")
    void claimOnlyOnce() throws Exception {
        mvc.perform(post(endpoint+"/claim").header("Authorization", token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.shouldShow").value(true));
        works.saveAndFlush(Work.create(member, "새 작품", WorkGenre.FANTASY, null));
        String otherSession = "Bearer " + jwt.generateAccessToken(member);
        mvc.perform(post(endpoint+"/claim").header("Authorization", otherSession)).andExpect(jsonPath("$.data.shouldShow").value(false));
        mvc.perform(get(endpoint).header("Authorization", otherSession)).andExpect(jsonPath("$.data.shouldShow").value(false));
        assertThat(member.getAnalysisGuideShownAt()).isNotNull();
    }

    @Test @DisplayName("다른 작품의 분석도 포함하고 진행 중인 첫 분석부터 안내를 생략한다")
    void anyAnalysisInAccountSuppressesPrompt() throws Exception {
        var work = works.saveAndFlush(Work.create(member, "먼저 분석한 작품", WorkGenre.FANTASY, null));
        jobs.saveAndFlush(AnalysisJob.create(work, null, null, AnalysisJobType.SETTING_EXTRACTION));
        works.saveAndFlush(Work.create(member, "아직 분석 없는 작품", WorkGenre.SF, null));
        mvc.perform(get(endpoint).header("Authorization", token)).andExpect(jsonPath("$.data.shouldShow").value(false));
        mvc.perform(post(endpoint+"/claim").header("Authorization", token)).andExpect(jsonPath("$.data.shouldShow").value(false));
    }

    @Test @DisplayName("한 계정의 분석 이력이 다른 신규 계정의 안내를 막지 않는다")
    void accountsAreIndependent() {
        var other = members.saveAndFlush(Member.register("other-guide@example.com", "encoded", "01078780002", "다른 작가"));
        guides.markAnalysisStarted(member.getId());
        assertThat(guides.getGuide(member.getId()).shouldShow()).isFalse();
        assertThat(guides.getGuide(other.getId()).shouldShow()).isTrue();
    }

    @Test @DisplayName("첫 분석 시각을 보존해 작품과 분석 기록이 없어도 다시 표시하지 않는다")
    void firstAnalysisMarkerIsPermanentAndIdempotent() {
        guides.markAnalysisStarted(member.getId());
        var first = member.getFirstAnalysisStartedAt();
        guides.markAnalysisStarted(member.getId());
        assertThat(member.getFirstAnalysisStartedAt()).isEqualTo(first);
        assertThat(jobs.existsByWork_Member_Id(member.getId())).isFalse();
        assertThat(guides.claimGuide(member.getId()).shouldShow()).isFalse();
    }

    @Test @DisplayName("비로그인 요청은 안내 이력을 읽거나 변경할 수 없다")
    void authenticationRequired() throws Exception {
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        mvc.perform(post(endpoint+"/claim")).andExpect(status().isUnauthorized());
    }
}
