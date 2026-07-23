package org.monitoring.catchholebackend.domain.analysis.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
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
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
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
class AnalysisJobControllerIntegrationTest {

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
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Member member;
    private Member otherMember;
    private Work work;
    private Work otherWork;
    private UploadBatch uploadBatch;
    private UploadBatch otherUploadBatch;
    private Episode firstEpisode;
    private Episode secondEpisode;
    private String accessToken;

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
        otherMember = memberRepository.save(Member.register(
                "other@example.com",
                "encoded-password",
                "01087654321",
                "다른 작가"
        ));
        work = workRepository.save(Work.create(member, "내 작품", WorkGenre.FANTASY, "내 설명"));
        otherWork = workRepository.save(Work.create(otherMember, "다른 작품", WorkGenre.MARTIAL_ARTS, "다른 설명"));
        uploadBatch = uploadBatchRepository.save(UploadBatch.create(
                work,
                member,
                UploadType.INITIAL_IMPORT,
                UploadSourceType.FILE
        ));
        uploadBatch.updateFileCount(2);
        uploadBatchRepository.save(uploadBatch);
        UploadFile firstUploadFile = uploadFileRepository.save(
                parsedEpisodeFile(uploadBatch, "episodes-1.txt", 1, 3, 3));
        UploadFile secondUploadFile = uploadFileRepository.save(
                parsedEpisodeFile(uploadBatch, "episodes-2.txt", 4, 5, 2));
        firstEpisode = episodeRepository.save(Episode.create(
                work, firstUploadFile.getId(), 1, "첫 회차", "episodes/1.txt", null, "hash-1", 10));
        secondEpisode = episodeRepository.save(Episode.create(
                work, secondUploadFile.getId(), 4, "넷째 회차", "episodes/4.txt", null, "hash-4", 10));

        otherUploadBatch = uploadBatchRepository.save(UploadBatch.create(
                otherWork,
                otherMember,
                UploadType.INITIAL_IMPORT,
                UploadSourceType.FILE
        ));
        accessToken = jwtTokenProvider.generateAccessToken(member);
    }

    @Test
    void createAnalysisJobCreatesPendingJobForAuthenticatedWork() throws Exception {
        mockMvc.perform(post("/api/v1/works/{workId}/analysis-jobs", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jobType": "EPISODE_VALIDATION",
                                  "batchId": "%s"
                                }
                                """.formatted(uploadBatch.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("분석 작업이 생성되었습니다."))
                .andExpect(jsonPath("$.data.id", notNullValue()))
                .andExpect(jsonPath("$.data.workId").value(work.getId().toString()))
                .andExpect(jsonPath("$.data.workTitle").value("내 작품"))
                .andExpect(jsonPath("$.data.batchId").value(uploadBatch.getId().toString()))
                .andExpect(jsonPath("$.data.target.batchId").value(uploadBatch.getId().toString()))
                .andExpect(jsonPath("$.data.target.uploadType").value("INITIAL_IMPORT"))
                .andExpect(jsonPath("$.data.target.status").value("PENDING"))
                .andExpect(jsonPath("$.data.target.fileCount").value(2))
                .andExpect(jsonPath("$.data.target.episodeStartNo").value(1))
                .andExpect(jsonPath("$.data.target.episodeEndNo").value(5))
                .andExpect(jsonPath("$.data.target.episodeCount").value(5))
                .andExpect(jsonPath("$.data.jobType").value("EPISODE_VALIDATION"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        assertThat(analysisJobRepository.count()).isEqualTo(1);
    }

    @Test
    void createAnalysisJobCreatesBatchTargetJob() throws Exception {
        mockMvc.perform(post("/api/v1/works/{workId}/analysis-jobs", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jobType": "SETTING_EXTRACTION",
                                  "batchId": "%s"
                                }
                                """.formatted(uploadBatch.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.workId").value(work.getId().toString()))
                .andExpect(jsonPath("$.data.workTitle").value("내 작품"))
                .andExpect(jsonPath("$.data.batchId").value(uploadBatch.getId().toString()))
                .andExpect(jsonPath("$.data.target.episodeStartNo").value(1))
                .andExpect(jsonPath("$.data.target.episodeEndNo").value(5))
                .andExpect(jsonPath("$.data.target.episodeCount").value(5))
                .andExpect(jsonPath("$.data.jobType").value("SETTING_EXTRACTION"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @ParameterizedTest
    @EnumSource(AnalysisJobType.class)
    @DisplayName("공개 생성 API는 모든 분석 작업 유형에서 선택 회차 범위를 허용한다")
    void createAnalysisJobTargetsOnlyRequestedEpisode(AnalysisJobType jobType) throws Exception {
        mockMvc.perform(post("/api/v1/works/{workId}/analysis-jobs", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jobType": "%s",
                                  "batchId": "%s",
                                  "episodeId": "%s"
                                }
                                """.formatted(jobType, uploadBatch.getId(), firstEpisode.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.episodeId").value(firstEpisode.getId().toString()))
                .andExpect(jsonPath("$.data.episodes", hasSize(1)))
                .andExpect(jsonPath("$.data.episodes[0].id").value(firstEpisode.getId().toString()))
                .andExpect(jsonPath("$.data.jobType").value(jobType.name()));

        AnalysisJob savedJob = analysisJobRepository.findAll().getFirst();
        assertThat(savedJob.getEpisode().getId()).isEqualTo(firstEpisode.getId());
    }

    @Test
    @DisplayName("완료된 배치 작업의 대상 회차는 원본 교체와 보관 후에도 유지된다")
    void completedBatchJobKeepsTargetSnapshotAfterEpisodeChanges() throws Exception {
        mockMvc.perform(post("/api/v1/works/{workId}/analysis-jobs", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jobType": "EPISODE_VALIDATION",
                                  "batchId": "%s"
                                }
                                """.formatted(uploadBatch.getId())))
                .andExpect(status().isOk());

        AnalysisJob analysisJob = analysisJobRepository.findAll().getFirst();
        analysisJob.succeed("{}", 0, 0);
        analysisJobRepository.save(analysisJob);

        UploadBatch replacementBatch = uploadBatchRepository.save(UploadBatch.create(
                work,
                member,
                UploadType.SINGLE_EPISODE,
                UploadSourceType.FILE
        ));
        UploadFile replacementFile = uploadFileRepository.save(
                parsedEpisodeFile(replacementBatch, "replacement.txt", 1, 1, 1));
        firstEpisode.replaceSourceFileAndContent(
                replacementFile.getId(),
                "episodes/replacement/1.txt",
                null,
                "replacement-hash",
                20
        );
        secondEpisode.archive();
        episodeRepository.saveAll(List.of(firstEpisode, secondEpisode));

        mockMvc.perform(get(
                                "/api/v1/works/{workId}/analysis-jobs/{analysisJobId}",
                                work.getId(),
                                analysisJob.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.episodes", hasSize(2)))
                .andExpect(jsonPath("$.data.episodes[0].id").value(firstEpisode.getId().toString()))
                .andExpect(jsonPath("$.data.episodes[1].id").value(secondEpisode.getId().toString()))
                .andExpect(jsonPath("$.data.episodes[1].status").value("ARCHIVED"));
    }

    @Test
    void createAnalysisJobAllowsDifferentEpisodeTargetsInSameBatch() throws Exception {
        analysisJobRepository.save(AnalysisJob.create(
                work, uploadBatch, firstEpisode, AnalysisJobType.EPISODE_VALIDATION));

        mockMvc.perform(post("/api/v1/works/{workId}/analysis-jobs", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jobType": "EPISODE_VALIDATION",
                                  "batchId": "%s",
                                  "episodeId": "%s"
                                }
                                """.formatted(uploadBatch.getId(), secondEpisode.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.episodeId").value(secondEpisode.getId().toString()));

        assertThat(analysisJobRepository.count()).isEqualTo(2);
    }

    @Test
    void createAnalysisJobRejectsDuplicateActiveEpisodeTarget() throws Exception {
        analysisJobRepository.save(AnalysisJob.create(
                work, uploadBatch, firstEpisode, AnalysisJobType.EPISODE_VALIDATION));

        mockMvc.perform(post("/api/v1/works/{workId}/analysis-jobs", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jobType": "EPISODE_VALIDATION",
                                  "batchId": "%s",
                                  "episodeId": "%s"
                                }
                                """.formatted(uploadBatch.getId(), firstEpisode.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_JOB_ALREADY_IN_PROGRESS"));
    }

    @Test
    @DisplayName("같은 배치의 전체 분석 작업이 활성 상태면 실패 작업 재시도를 거절한다")
    void retryFailedAnalysisJobRejectsActiveBatchJob() throws Exception {
        firstEpisode.markFailed();
        secondEpisode.markFailed();
        episodeRepository.saveAll(List.of(firstEpisode, secondEpisode));

        AnalysisJob failedJob = AnalysisJob.create(
                work, uploadBatch, null, AnalysisJobType.EPISODE_VALIDATION);
        failedJob.fail("분석 실패");
        analysisJobRepository.save(failedJob);
        analysisJobRepository.save(AnalysisJob.create(
                work, uploadBatch, null, AnalysisJobType.EPISODE_VALIDATION));

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/analysis-jobs/{analysisJobId}/retry",
                                work.getId(),
                                failedJob.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_JOB_ALREADY_IN_PROGRESS"));

        assertThat(analysisJobRepository.count()).isEqualTo(2);
    }

    @Test
    void getAnalysisJobsReturnsAuthenticatedWorkJobs() throws Exception {
        analysisJobRepository.save(AnalysisJob.create(work, uploadBatch, null, AnalysisJobType.EPISODE_VALIDATION));
        Thread.sleep(10);
        analysisJobRepository.save(AnalysisJob.create(work, uploadBatch, null, AnalysisJobType.SETTING_EXTRACTION));
        analysisJobRepository.save(
                AnalysisJob.create(otherWork, otherUploadBatch, null, AnalysisJobType.EPISODE_VALIDATION)
        );

        mockMvc.perform(get("/api/v1/works/{workId}/analysis-jobs", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].workTitle").value("내 작품"))
                .andExpect(jsonPath("$.data[0].target.episodeStartNo").value(1))
                .andExpect(jsonPath("$.data[0].target.episodeEndNo").value(5))
                .andExpect(jsonPath("$.data[0].target.episodeCount").value(5))
                .andExpect(jsonPath("$.data[0].jobType").value("SETTING_EXTRACTION"))
                .andExpect(jsonPath("$.data[1].jobType").value("EPISODE_VALIDATION"));
    }

    @Test
    void getAnalysisJobReturnsAuthenticatedWorkJob() throws Exception {
        AnalysisJob analysisJob = analysisJobRepository.save(
                AnalysisJob.create(work, uploadBatch, null, AnalysisJobType.EPISODE_VALIDATION)
        );

        mockMvc.perform(get("/api/v1/works/{workId}/analysis-jobs/{analysisJobId}", work.getId(), analysisJob.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(analysisJob.getId().toString()))
                .andExpect(jsonPath("$.data.workId").value(work.getId().toString()))
                .andExpect(jsonPath("$.data.workTitle").value("내 작품"))
                .andExpect(jsonPath("$.data.batchId").value(uploadBatch.getId().toString()))
                .andExpect(jsonPath("$.data.target.episodeStartNo").value(1))
                .andExpect(jsonPath("$.data.target.episodeEndNo").value(5))
                .andExpect(jsonPath("$.data.target.episodeCount").value(5))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void createAnalysisJobRejectsOtherMemberWork() throws Exception {
        mockMvc.perform(post("/api/v1/works/{workId}/analysis-jobs", otherWork.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jobType": "EPISODE_VALIDATION",
                                  "batchId": "%s"
                                }
                                """.formatted(otherUploadBatch.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("WORK_NOT_FOUND"));
    }

    @Test
    void createAnalysisJobRejectsBatchOutsideWork() throws Exception {
        mockMvc.perform(post("/api/v1/works/{workId}/analysis-jobs", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jobType": "EPISODE_VALIDATION",
                                  "batchId": "%s"
                                }
                                """.formatted(otherUploadBatch.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_JOB_TARGET_NOT_FOUND"));
    }

    @Test
    void getAnalysisJobsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/works/{workId}/analysis-jobs", work.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private UploadFile parsedEpisodeFile(
            UploadBatch batch,
            String filename,
            int startNo,
            int endNo,
            int episodeCount
    ) {
        UploadFile uploadFile = UploadFile.create(
                batch,
                UploadFileRole.EPISODE,
                filename,
                MediaType.TEXT_PLAIN_VALUE,
                "uploads/%s".formatted(filename),
                100L
        );
        uploadFile.markEpisodesParsed(startNo, endNo, episodeCount);
        return uploadFile;
    }
}
