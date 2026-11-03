package org.monitoring.catchholebackend.domain.analysis.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.*;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.aitoken.exception.AiTokenErrorCode;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.upload.entity.*;
import org.monitoring.catchholebackend.domain.upload.repository.*;
import org.monitoring.catchholebackend.domain.upload.type.*;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("분석 시작 확인의 인증·동의·원자적 종료")
class AnalysisStartCheckControllerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired WorkRepository works;
    @Autowired UploadBatchRepository batches;
    @Autowired UploadFileRepository files;
    @Autowired EpisodeRepository episodes;
    @Autowired AnalysisJobRepository jobs;
    @Autowired SettingCandidateRepository candidates;
    @Autowired AnalysisReviewContinuationRepository continuations;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean AiTokenService quota;
    private Member member;
    private Work work;
    private UploadBatch batch;
    private Episode next;
    private SettingCandidate candidate;
    private String token;

    @BeforeEach
    void setUp() {
        String id = UUID.randomUUID().toString();
        member = members.save(Member.register(id + "@example.com", "encoded", "010" + id.substring(0, 8), "작가"));
        token = "Bearer " + tokens.generateAccessToken(member);
        work = works.save(Work.create(member, "시작 확인", WorkGenre.FANTASY, "합성 테스트"));
        batch = batches.save(UploadBatch.create(work, member, UploadType.SINGLE_EPISODE, UploadSourceType.FILE));
        var file = UploadFile.create(batch, UploadFileRole.EPISODE, "2.txt", "text/plain", "test/2.txt", 10L);
        file.markEpisodesParsed(2, 2, 1);
        file = files.save(file);
        next = episodes.save(Episode.create(work, file.getId(), 2, "2화", "test/2.txt", "v1", "b".repeat(64), 10));
        var previous = episodes.save(Episode.create(work, null, 1, "1화", "test/1.txt", "v1", "a".repeat(64), 10));
        var job = AnalysisJob.create(work, batch, previous, AnalysisJobType.SETTING_EXTRACTION);
        job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        ReflectionTestUtils.setField(job, "status", AnalysisJobStatus.SUCCEEDED);
        ReflectionTestUtils.setField(job, "analysisMode", AnalysisMode.ORDERED_PROVISIONAL);
        ReflectionTestUtils.setField(job, "journalStatus", AnalysisJournalStatus.SEALED);
        ReflectionTestUtils.setField(job, "automaticAppliedAt", LocalDateTime.now());
        job = jobs.save(job);
        candidate = SettingCandidate.create(work, previous, null, job, SettingEntityType.CHARACTER,
                "아리아", "age", "17", SettingValueType.STRING, null,
                JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("quote", "열일곱")),
                new BigDecimal("0.8"), JsonNodeFactory.instance.objectNode().put("original", true));
        ReflectionTestUtils.setField(candidate, "comparisonStatus", CharacterFactComparisonStatus.COMPLETED);
        ReflectionTestUtils.setField(candidate, "suggestedOperation", CharacterFactOperation.REVIEW_REQUIRED);
        candidate = candidates.save(candidate);
    }

    @Test @DisplayName("확인 조회만으로 후보는 바뀌지 않고 동의 없이 새 분석은 거절한다")
    void requiresAcknowledgementWithoutMutatingOnPreview() throws Exception {
        preview(2).andExpect(status().isOk()).andExpect(jsonPath("$.data.ambiguousCharacterCount").value(1));
        create(null, false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_REVIEW_ACKNOWLEDGEMENT_REQUIRED"));
        assertPending();
    }

    @Test @DisplayName("동의한 후보만 종료하고 실제 새 작업 요청과 감사 기록을 연결한다")
    void closesWithNewJobsAndPreservesEvidence() throws Exception {
        String revision = revision(2);
        create(revision, true).andExpect(status().isOk());
        var closed = candidates.findById(candidate.getId()).orElseThrow();
        assertThat(closed.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CLOSED);
        assertThat(closed.getRawAiResultJson()).isEqualTo(candidate.getRawAiResultJson());
        assertThat(closed.getEvidenceSpans()).isEqualTo(candidate.getEvidenceSpans());
        assertThat(continuations.findById(closed.getClosureRequestId())).isPresent();
        assertThat(jobs.findAll()).anySatisfy(job -> {
            assertThat(job.getEpisode().getId()).isEqualTo(next.getId());
            assertThat(job.getMetricsRequestId()).isEqualTo(closed.getClosureRequestId());
        });
    }

    @Test @DisplayName("확인 이후 직접 수정한 후보가 생기면 오래된 동의로 종료할 수 없다")
    void authorEditAfterPreviewBlocksContinuation() throws Exception {
        String revision = revision(2);
        candidate.recordUserModification();
        candidates.saveAndFlush(candidate);
        create(revision, true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_MANUAL_REVIEW_REQUIRED"));
        assertPending();
    }

    @Test @DisplayName("동의는 분석 대상 회차에 결속되어 다른 범위에 재사용할 수 없다")
    void rejectsDifferentTargetRevision() throws Exception {
        create(revision(3), true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_REVIEW_GATE_CHANGED"));
        assertPending();
    }

    @Test @DisplayName("토큰 부족으로 새 분석 접수가 실패하면 후보 종료와 감사 기록도 되돌린다")
    void rollsBackClosureIfCreationFails() throws Exception {
        doThrow(new AppException(AiTokenErrorCode.AI_TOKEN_QUOTA_EXHAUSTED)).when(quota).ensureAnalysisCanStart(member.getId());
        create(revision(2), true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AI_TOKEN_QUOTA_EXHAUSTED"));
        assertPending();
        assertThat(continuations.findAll()).noneMatch(value -> value.getWorkId().equals(work.getId()));
        assertThat(jobs.findAll()).noneMatch(job -> job.getEpisode() != null && job.getEpisode().getId().equals(next.getId()));
    }

    @Test @DisplayName("다른 사용자 작품과 잘못된 회차 번호는 사전 확인할 수 없다")
    void validatesOwnershipAndNumbers() throws Exception {
        var stranger = members.save(Member.register(UUID.randomUUID() + "@example.com", "encoded", "01099998888", "다른 작가"));
        mvc.perform(post(path()).header("Authorization", "Bearer " + tokens.generateAccessToken(stranger))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"episodeNumbers\":[2]}"))
                .andExpect(status().isNotFound());
        preview(0).andExpect(status().isBadRequest());
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content("{\"episodeNumbers\":[2]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test @DisplayName("이전 직접 분석이 후보를 저장하기 전에도 다음 회차를 예약할 수 없다")
    void blocksEarlierRunningManualBeforePublication() throws Exception {
        var origin = manualOrigin(AnalysisJobStatus.RUNNING);
        candidates.deleteById(candidate.getId());
        preview(2).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeEarlierAnalysisCount").value(1))
                .andExpect(jsonPath("$.data.manualReviewCount").value(0));
        create(null, false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_PREVIOUS_ANALYSIS_IN_PROGRESS"));
    }

    @Test @DisplayName("앞 회차를 다회차 대상에 섞어도 남은 직접 검토를 우회할 수 없다")
    void doesNotBypassManualReviewByIncludingEarlierTarget() throws Exception {
        var origin = manualOrigin(AnalysisJobStatus.SUCCEEDED);
        var file = UploadFile.create(batch, UploadFileRole.EPISODE, "1.txt", "text/plain", "test/1.txt", 10L);
        file.markEpisodesParsed(1, 1, 1);
        file = files.save(file);
        var earlier = episodes.findById(origin.getEpisode().getId()).orElseThrow();
        ReflectionTestUtils.setField(earlier, "sourceFileId", file.getId());
        episodes.saveAndFlush(earlier);
        assertThat(jobs.findById(origin.getId()).orElseThrow().getReviewMode()).isEqualTo(AnalysisReviewMode.MANUAL);
        assertThat(candidates.findContinuationGateRows(work.getId(), 2)).hasSize(1);
        mvc.perform(post(path()).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"episodeNumbers\":[1,2]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.manualReviewCount").value(1));
        mvc.perform(post("/api/v1/works/" + work.getId() + "/analysis-jobs")
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"SETTING_EXTRACTION\",\"batchId\":\"" + batch.getId() + "\",\"reviewMode\":\"MANUAL\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ANALYSIS_MANUAL_REVIEW_REQUIRED"));
        assertPending();
    }

    @Test @DisplayName("같은 동의로 동시에 접수해도 후보 종료와 새 작업은 한 번만 생성된다")
    void concurrentContinuationCreatesOneJobAndReceipt() throws Exception {
        String revision = revision(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Integer> submit = () -> {
                start.await();
                return create(revision, true).andReturn().getResponse().getStatus();
            };
            var first = executor.submit(submit);
            var second = executor.submit(submit);
            start.countDown();
            assertThat(java.util.List.of(first.get(15, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(15, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(continuations.findAll().stream().filter(value -> value.getWorkId().equals(work.getId()))).hasSize(1);
        assertThat(jobs.findAll().stream().filter(job -> job.getEpisode() != null && job.getEpisode().getId().equals(next.getId()))).hasSize(1);
        assertThat(candidates.findById(candidate.getId()).orElseThrow().getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CLOSED);
    }

    private AnalysisJob manualOrigin(AnalysisJobStatus state) {
        var episode = candidate.getEpisode();
        candidates.deleteById(candidate.getId());
        var origin = AnalysisJob.create(work, batch, episode, AnalysisJobType.SETTING_EXTRACTION);
        origin.configureReviewMode(AnalysisReviewMode.MANUAL);
        ReflectionTestUtils.setField(origin, "status", state);
        origin = jobs.saveAndFlush(origin);
        candidate = candidates.save(SettingCandidate.create(work, episode, null, origin, SettingEntityType.CHARACTER,
                "아리아", "age", "17", SettingValueType.STRING, null, null, new BigDecimal("0.8"), null));
        return origin;
    }

    private String path() { return "/api/v1/works/" + work.getId() + "/analysis-start-checks"; }
    private ResultActions preview(int number) throws Exception {
        return mvc.perform(post(path()).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"episodeNumbers\":[" + number + "]}"));
    }
    private String revision(int number) throws Exception {
        return new ObjectMapper().readTree(preview(number).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("revision").asText();
    }
    private ResultActions create(String revision, boolean acknowledged) throws Exception {
        var body = JsonNodeFactory.instance.objectNode().put("jobType", "SETTING_EXTRACTION")
                .put("batchId", batch.getId().toString()).put("episodeId", next.getId().toString())
                .put("reviewMode", "MANUAL").put("closeAmbiguousCandidates", acknowledged);
        if (revision != null) body.put("reviewGateRevision", revision);
        return mvc.perform(post("/api/v1/works/" + work.getId() + "/analysis-jobs")
                .header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }
    private void assertPending() {
        var current = candidates.findById(candidate.getId()).orElseThrow();
        assertThat(current.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(current.getClosedAt()).isNull();
    }
}
