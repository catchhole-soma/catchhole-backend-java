package org.monitoring.catchholebackend.domain.analysis.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.config.security.SecurityConstant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalysisJobWorkerControllerIntegrationTest {

    private static final String INTERNAL_API_KEY = "local-development-internal-api-key";
    private static final String CLAIM_URL = "/api/internal/v1/analysis-jobs/claim";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WorkRepository workRepository;

    @Autowired
    private UploadBatchRepository uploadBatchRepository;

    @Autowired
    private UploadFileRepository uploadFileRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Member member;
    private Work work;
    private UploadBatch uploadBatch;
    private UploadFile uploadFile;

    @BeforeEach
    void setUp() {
        analysisJobRepository.deleteAll();
        episodeRepository.deleteAll();
        uploadFileRepository.deleteAll();
        uploadBatchRepository.deleteAll();
        workRepository.deleteAll();
        memberRepository.deleteAll();

        member = memberRepository.save(Member.register(
                "writer@example.com",
                "encoded-password",
                "01012345678",
                "작가"
        ));
        work = workRepository.save(Work.create(member, "내 작품", "판타지", "내 설명"));
        uploadBatch = uploadBatchRepository.save(UploadBatch.create(
                work,
                member,
                UploadType.MULTI_EPISODE_SINGLE_FILE,
                UploadSourceType.FILE
        ));
        uploadFile = uploadFileRepository.save(parsedEpisodeFile(uploadBatch, "episodes.txt", 1, 2, 2));
        episodeRepository.save(episode(2, "두 번째 회차", "works/%s/episodes/2.txt".formatted(work.getId())));
        episodeRepository.save(episode(1, "첫 번째 회차", "works/%s/episodes/1.txt".formatted(work.getId())));
    }

    @Test
    void claimRequiresInternalApiKey() throws Exception {
        mockMvc.perform(post(CLAIM_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    void claimRejectsInvalidInternalApiKey() throws Exception {
        mockMvc.perform(post(CLAIM_URL)
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, "wrong-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    void claimRejectsMemberJwtWithoutInternalApiKey() throws Exception {
        String accessToken = jwtTokenProvider.generateAccessToken(member);

        mockMvc.perform(post(CLAIM_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    void claimReturnsNoContentWhenNoPendingJob() throws Exception {
        mockMvc.perform(post(CLAIM_URL)
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY))
                .andExpect(status().isNoContent());
    }

    @Test
    void claimOldestPendingJobAndReturnsEpisodeMetadata() throws Exception {
        AnalysisJob firstJob = analysisJobRepository.save(
                AnalysisJob.create(work, uploadBatch, null, AnalysisJobType.SETTING_EXTRACTION)
        );
        Thread.sleep(10);
        AnalysisJob secondJob = analysisJobRepository.save(
                AnalysisJob.create(work, uploadBatch, null, AnalysisJobType.EPISODE_VALIDATION)
        );

        mockMvc.perform(post(CLAIM_URL)
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "modelName": "gpt-4.1-mini",
                                  "currentStep": "원문 청킹"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.analysisJobId").value(firstJob.getId().toString()))
                .andExpect(jsonPath("$.data.jobType").value("SETTING_EXTRACTION"))
                .andExpect(jsonPath("$.data.workId").value(work.getId().toString()))
                .andExpect(jsonPath("$.data.workTitle").value("내 작품"))
                .andExpect(jsonPath("$.data.batchId").value(uploadBatch.getId().toString()))
                .andExpect(jsonPath("$.data.modelName").value("gpt-4.1-mini"))
                .andExpect(jsonPath("$.data.currentStep").value("원문 청킹"))
                .andExpect(jsonPath("$.data.episodes", hasSize(2)))
                .andExpect(jsonPath("$.data.episodes[0].episodeNo").value(1))
                .andExpect(jsonPath("$.data.episodes[0].title").value("첫 번째 회차"))
                .andExpect(jsonPath("$.data.episodes[0].contentS3Key").value("works/%s/episodes/1.txt".formatted(work.getId())))
                .andExpect(jsonPath("$.data.episodes[0].contentS3Version").value("v1"))
                .andExpect(jsonPath("$.data.episodes[0].contentHash").value("hash-1"))
                .andExpect(jsonPath("$.data.episodes[0].charCount").value(1001))
                .andExpect(jsonPath("$.data.episodes[1].episodeNo").value(2));

        AnalysisJob claimedJob = analysisJobRepository.findById(firstJob.getId()).orElseThrow();
        AnalysisJob pendingJob = analysisJobRepository.findById(secondJob.getId()).orElseThrow();
        assertThat(claimedJob.getStatus()).isEqualTo(AnalysisJobStatus.RUNNING);
        assertThat(claimedJob.getModelName()).isEqualTo("gpt-4.1-mini");
        assertThat(claimedJob.getCurrentStep()).isEqualTo("원문 청킹");
        assertThat(pendingJob.getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
    }

    @Test
    void claimMarksJobFailedWhenTargetEpisodesMissing() throws Exception {
        UploadBatch emptyBatch = uploadBatchRepository.save(UploadBatch.create(
                work,
                member,
                UploadType.SINGLE_EPISODE,
                UploadSourceType.FILE
        ));
        AnalysisJob analysisJob = analysisJobRepository.save(
                AnalysisJob.create(work, emptyBatch, null, AnalysisJobType.EPISODE_VALIDATION)
        );

        mockMvc.perform(post(CLAIM_URL)
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY))
                .andExpect(status().isNoContent());

        AnalysisJob failedJob = analysisJobRepository.findById(analysisJob.getId()).orElseThrow();
        assertThat(failedJob.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(failedJob.getErrorMessage()).contains("분석 대상 회차가 없습니다.");
    }

    @Test
    void updateProgressUpdatesRunningJobCurrentStep() throws Exception {
        AnalysisJob analysisJob = runningJob();

        mockMvc.perform(patch("/api/internal/v1/analysis-jobs/{analysisJobId}/progress", analysisJob.getId())
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentStep": "LLM 전처리"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        AnalysisJob updatedJob = analysisJobRepository.findById(analysisJob.getId()).orElseThrow();
        assertThat(updatedJob.getCurrentStep()).isEqualTo("LLM 전처리");
    }

    @Test
    void completeRunningJobRecordsResultMetadata() throws Exception {
        AnalysisJob analysisJob = runningJob();

        mockMvc.perform(post("/api/internal/v1/analysis-jobs/{analysisJobId}/complete", analysisJob.getId())
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "summaryJson": "{\\"status\\":\\"ok\\"}",
                                  "inputTokenCount": 1200,
                                  "outputTokenCount": 300
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        AnalysisJob completedJob = analysisJobRepository.findById(analysisJob.getId()).orElseThrow();
        assertThat(completedJob.getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
        assertThat(completedJob.getSummaryJson()).isEqualTo("{\"status\":\"ok\"}");
        assertThat(completedJob.getInputTokenCount()).isEqualTo(1200);
        assertThat(completedJob.getOutputTokenCount()).isEqualTo(300);
        assertThat(completedJob.getCompletedAt()).isNotNull();
    }

    @Test
    void failRunningJobRecordsErrorMessage() throws Exception {
        AnalysisJob analysisJob = runningJob();

        mockMvc.perform(post("/api/internal/v1/analysis-jobs/{analysisJobId}/fail", analysisJob.getId())
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "errorMessage": "LLM 응답 스키마 오류"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        AnalysisJob failedJob = analysisJobRepository.findById(analysisJob.getId()).orElseThrow();
        assertThat(failedJob.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(failedJob.getErrorMessage()).isEqualTo("LLM 응답 스키마 오류");
        assertThat(failedJob.getCompletedAt()).isNotNull();
    }

    @Test
    void statusUpdateRejectsNonRunningJob() throws Exception {
        AnalysisJob analysisJob = analysisJobRepository.save(
                AnalysisJob.create(work, uploadBatch, null, AnalysisJobType.EPISODE_VALIDATION)
        );

        mockMvc.perform(post("/api/internal/v1/analysis-jobs/{analysisJobId}/complete", analysisJob.getId())
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_JOB_STATUS_CONFLICT"));
    }

    @Test
    void statusUpdateReturnsNotFoundForUnknownJob() throws Exception {
        mockMvc.perform(patch("/api/internal/v1/analysis-jobs/{analysisJobId}/progress", java.util.UUID.randomUUID())
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentStep": "원문 청킹"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_JOB_NOT_FOUND"));
    }

    private AnalysisJob runningJob() {
        AnalysisJob analysisJob = AnalysisJob.create(work, uploadBatch, null, AnalysisJobType.EPISODE_VALIDATION);
        analysisJob.start("gpt-4.1-mini", "원문 청킹");
        return analysisJobRepository.save(analysisJob);
    }

    private Episode episode(int episodeNo, String title, String contentS3Key) {
        return Episode.create(
                work,
                uploadFile.getId(),
                episodeNo,
                title,
                contentS3Key,
                "v1",
                "hash-" + episodeNo,
                1000 + episodeNo
        );
    }

    private UploadFile parsedEpisodeFile(
            UploadBatch batch,
            String filename,
            int startNo,
            int endNo,
            int episodeCount
    ) {
        UploadFile file = UploadFile.create(
                batch,
                UploadFileRole.EPISODE,
                filename,
                MediaType.TEXT_PLAIN_VALUE,
                "uploads/%s".formatted(filename),
                100L
        );
        file.markParsed(startNo, endNo, episodeCount);
        return file;
    }
}
