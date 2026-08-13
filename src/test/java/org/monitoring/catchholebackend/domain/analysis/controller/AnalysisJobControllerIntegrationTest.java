package org.monitoring.catchholebackend.domain.analysis.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenReserveRequest;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenAccountRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenGrantRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenUsageRepository;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageStatus;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
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
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
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
    private AiTokenUsageRepository aiTokenUsageRepository;

    @Autowired
    private AiTokenGrantRepository aiTokenGrantRepository;

    @Autowired
    private AiTokenAccountRepository aiTokenAccountRepository;

    @Autowired
    private AiTokenService aiTokenService;

    @Autowired
    private SettingCandidateRepository settingCandidateRepository;

    @Autowired
    private WorldSettingCandidateRepository worldSettingCandidateRepository;

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
        aiTokenUsageRepository.deleteAll();
        aiTokenGrantRepository.deleteAll();
        aiTokenAccountRepository.deleteAll();
        List<AnalysisJob> existingJobs = analysisJobRepository.findAll();
        existingJobs.forEach(job -> {
            job.unlinkWorldSettingCandidate();
            job.unlinkSettingCandidate();
        });
        analysisJobRepository.saveAllAndFlush(existingJobs);
        settingCandidateRepository.deleteAll();
        worldSettingCandidateRepository.deleteAll();
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
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id", notNullValue()))
                .andExpect(jsonPath("$.data[0].workId").value(work.getId().toString()))
                .andExpect(jsonPath("$.data[0].workTitle").value("내 작품"))
                .andExpect(jsonPath("$.data[0].batchId").value(uploadBatch.getId().toString()))
                .andExpect(jsonPath("$.data[0].target.batchId").value(uploadBatch.getId().toString()))
                .andExpect(jsonPath("$.data[0].target.uploadType").value("INITIAL_IMPORT"))
                .andExpect(jsonPath("$.data[0].target.status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].target.fileCount").value(2))
                .andExpect(jsonPath("$.data[0].target.episodeStartNo").value(1))
                .andExpect(jsonPath("$.data[0].target.episodeEndNo").value(5))
                .andExpect(jsonPath("$.data[0].target.episodeCount").value(5))
                .andExpect(jsonPath("$.data[0].episodeId").value(firstEpisode.getId().toString()))
                .andExpect(jsonPath("$.data[0].episodes", hasSize(1)))
                .andExpect(jsonPath("$.data[0].jobType").value("EPISODE_VALIDATION"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[1].episodeId").value(secondEpisode.getId().toString()));

        assertThat(analysisJobRepository.count()).isEqualTo(2);
    }

    @Test
    void createAnalysisJobCreatesOneJobPerBatchEpisode() throws Exception {
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
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].workId").value(work.getId().toString()))
                .andExpect(jsonPath("$.data[0].workTitle").value("내 작품"))
                .andExpect(jsonPath("$.data[0].batchId").value(uploadBatch.getId().toString()))
                .andExpect(jsonPath("$.data[0].target.episodeStartNo").value(1))
                .andExpect(jsonPath("$.data[0].target.episodeEndNo").value(5))
                .andExpect(jsonPath("$.data[0].target.episodeCount").value(5))
                .andExpect(jsonPath("$.data[0].episodeId").value(firstEpisode.getId().toString()))
                .andExpect(jsonPath("$.data[0].jobType").value("SETTING_EXTRACTION"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[1].episodeId").value(secondEpisode.getId().toString()));
    }

    @Test
    @DisplayName("현재 대상 회차가 없는 배치에는 분석 작업을 생성하지 않는다")
    void createAnalysisJobRejectsBatchWithoutTargetEpisodes() throws Exception {
        UploadBatch settingBookBatch = uploadBatchRepository.save(UploadBatch.create(
                work,
                member,
                UploadType.SETTING_BOOK,
                UploadSourceType.FILE
        ));
        UploadFile settingBook = UploadFile.create(
                settingBookBatch,
                UploadFileRole.SETTING_BOOK,
                "setting-book.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "uploads/setting-book.txt",
                100L
        );
        settingBook.markParsed();
        uploadFileRepository.save(settingBook);

        mockMvc.perform(post("/api/v1/works/{workId}/analysis-jobs", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jobType": "SETTING_EXTRACTION",
                                  "batchId": "%s"
                                }
                                """.formatted(settingBookBatch.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_JOB_TARGET_NOT_FOUND"));

        assertThat(analysisJobRepository.count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(
            value = AnalysisJobType.class,
            names = {"SETTING_EXTRACTION", "EPISODE_VALIDATION"}
    )
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
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].episodeId").value(firstEpisode.getId().toString()))
                .andExpect(jsonPath("$.data[0].episodes", hasSize(1)))
                .andExpect(jsonPath("$.data[0].episodes[0].id").value(firstEpisode.getId().toString()))
                .andExpect(jsonPath("$.data[0].jobType").value(jobType.name()));

        AnalysisJob savedJob = analysisJobRepository.findAll().getFirst();
        assertThat(savedJob.getEpisode().getId()).isEqualTo(firstEpisode.getId());
    }

    @Test
    @DisplayName("완료된 배치 작업의 대상 회차는 원본 교체와 보관 후에도 유지된다")
    void completedBatchJobKeepsTargetSnapshotAfterEpisodeChanges() throws Exception {
        AnalysisJob analysisJob = AnalysisJob.create(
                work, uploadBatch, null, AnalysisJobType.EPISODE_VALIDATION);
        analysisJob.addTargetEpisodes(List.of(firstEpisode, secondEpisode));
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
                .andExpect(jsonPath("$.data[0].episodeId").value(secondEpisode.getId().toString()));

        assertThat(analysisJobRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("회차 없는 캐릭터 비교 hidden Job은 같은 배치의 공개 분석 생성을 막지 않는다")
    void createAnalysisJobIgnoresEpisodeLessCharacterComparisonJob() throws Exception {
        AnalysisJob sourceJob = AnalysisJob.create(
                work,
                uploadBatch,
                null,
                AnalysisJobType.SETTING_EXTRACTION
        );
        sourceJob.succeed("{}", 0, 0);
        sourceJob = analysisJobRepository.save(sourceJob);
        SettingCandidate legacyCandidate = settingCandidateRepository.save(candidate(
                sourceJob,
                null,
                "stats.strength"
        ));
        AnalysisJob hiddenJob = analysisJobRepository.save(
                AnalysisJob.createCharacterFactComparison(legacyCandidate)
        );

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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].episodeId").value(firstEpisode.getId().toString()));

        assertThat(analysisJobRepository.findById(hiddenJob.getId()))
                .get()
                .extracting(AnalysisJob::getStatus)
                .isEqualTo(AnalysisJobStatus.PENDING);
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
    @DisplayName("배치 생성 대상 중 활성 회차가 하나라도 있으면 새 작업을 하나도 만들지 않는다")
    void createAnalysisJobsRejectsBatchAtomicallyWhenAnyEpisodeIsActive() throws Exception {
        analysisJobRepository.save(AnalysisJob.create(
                work, uploadBatch, firstEpisode, AnalysisJobType.EPISODE_VALIDATION));

        mockMvc.perform(post("/api/v1/works/{workId}/analysis-jobs", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jobType": "EPISODE_VALIDATION",
                                  "batchId": "%s"
                                }
                                """.formatted(uploadBatch.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_JOB_ALREADY_IN_PROGRESS"));

        assertThat(analysisJobRepository.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(
            value = AnalysisJobType.class,
            names = {"SETTING_EXTRACTION", "EPISODE_VALIDATION"}
    )
    @DisplayName("재분석은 같은 회차와 작업 유형의 이전 미검토 후보만 제거한다")
    void createAnalysisJobDeletesOnlySupersededPendingCandidates(AnalysisJobType jobType) throws Exception {
        AnalysisJobType otherJobType = jobType == AnalysisJobType.SETTING_EXTRACTION
                ? AnalysisJobType.EPISODE_VALIDATION
                : AnalysisJobType.SETTING_EXTRACTION;
        AnalysisJob targetJob = succeededJob(
                firstEpisode,
                jobType
        );
        AnalysisJob otherEpisodeJob = succeededJob(
                secondEpisode,
                jobType
        );
        AnalysisJob otherTypeJob = succeededJob(
                firstEpisode,
                otherJobType
        );

        SettingCandidate pendingTarget = candidate(
                targetJob,
                firstEpisode,
                "profile.pending-target"
        );
        SettingCandidate confirmedTarget = candidate(
                targetJob,
                firstEpisode,
                "profile.confirmed-target"
        );
        confirmedTarget.confirm();
        SettingCandidate dismissedTarget = candidate(
                targetJob,
                firstEpisode,
                "profile.dismissed-target"
        );
        dismissedTarget.dismiss();
        SettingCandidate pendingOtherEpisode = candidate(
                otherEpisodeJob,
                secondEpisode,
                "profile.other-episode"
        );
        SettingCandidate pendingOtherType = candidate(
                otherTypeJob,
                firstEpisode,
                "profile.other-type"
        );
        settingCandidateRepository.saveAll(List.of(
                pendingTarget,
                confirmedTarget,
                dismissedTarget,
                pendingOtherEpisode,
                pendingOtherType
        ));

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
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].jobType").value(jobType.name()));

        assertThat(settingCandidateRepository.existsById(pendingTarget.getId())).isFalse();
        assertThat(settingCandidateRepository.findById(confirmedTarget.getId()))
                .get()
                .extracting(SettingCandidate::getReviewStatus)
                .isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
        assertThat(settingCandidateRepository.findById(dismissedTarget.getId()))
                .get()
                .extracting(SettingCandidate::getReviewStatus)
                .isEqualTo(SettingCandidateReviewStatus.DISMISSED);
        assertThat(settingCandidateRepository.existsById(pendingOtherEpisode.getId())).isTrue();
        assertThat(settingCandidateRepository.existsById(pendingOtherType.getId())).isTrue();
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
    @DisplayName("실패 분석 재시도는 대체 Job의 전체 예약을 해제한 뒤 새 작업을 만든다")
    void retryFailedAnalysisJobDeletesSupersededPendingCandidates() throws Exception {
        firstEpisode.markFailed();
        episodeRepository.save(firstEpisode);
        AnalysisJob failedJob = AnalysisJob.create(
                work,
                uploadBatch,
                firstEpisode,
                AnalysisJobType.SETTING_EXTRACTION
        );
        failedJob.fail("완료 보고 실패");
        failedJob = analysisJobRepository.save(failedJob);

        SettingCandidate pendingCandidate = candidate(
                failedJob,
                firstEpisode,
                "profile.pending"
        );
        SettingCandidate confirmedCandidate = candidate(
                failedJob,
                firstEpisode,
                "profile.confirmed"
        );
        confirmedCandidate.confirm();
        settingCandidateRepository.saveAll(List.of(pendingCandidate, confirmedCandidate));

        AnalysisJob characterComparisonJob = AnalysisJob.createCharacterFactComparison(pendingCandidate);
        UUID characterComparisonLeaseToken = characterComparisonJob.claim(
                "gpt-5.6-terra",
                "CHARACTER_FACT_COMPARISON",
                LocalDateTime.now().plusMinutes(5)
        );
        characterComparisonJob = analysisJobRepository.save(characterComparisonJob);
        UUID characterComparisonUsageRequestId = UUID.randomUUID();
        aiTokenService.reserve(new AiTokenReserveRequest(
                characterComparisonUsageRequestId,
                characterComparisonJob.getId(),
                AiTokenPurpose.CHARACTER_FACT_COMPARISON,
                1,
                "gpt-5.6-terra",
                1_000_000L
        ), characterComparisonLeaseToken);

        WorldSettingCandidate pendingWorldSettingCandidate = worldSettingCandidateRepository.save(
                WorldSettingCandidate.create(
                        work,
                        firstEpisode,
                        failedJob,
                        WorldSettingCategory.RACE,
                        "바바리안",
                        "서식지",
                        "혹한 지역",
                        JsonNodeFactory.instance.arrayNode().add(
                                JsonNodeFactory.instance.objectNode()
                                        .put("quote", "바바리안은 혹한 지역에 산다.")
                        ),
                        new BigDecimal("0.95"),
                        JsonNodeFactory.instance.objectNode()
                )
        );
        AnalysisJob worldComparisonJob = AnalysisJob.createWorldSettingComparison(
                pendingWorldSettingCandidate
        );
        UUID comparisonLeaseToken = worldComparisonJob.claim(
                "gpt-5.6-terra",
                "WORLD_SETTING_COMPARISON",
                LocalDateTime.now().plusMinutes(5)
        );
        worldComparisonJob = analysisJobRepository.save(worldComparisonJob);
        UUID comparisonUsageRequestId = UUID.randomUUID();
        aiTokenService.reserve(new AiTokenReserveRequest(
                comparisonUsageRequestId,
                worldComparisonJob.getId(),
                AiTokenPurpose.WORLD_SETTING_COMPARISON,
                1,
                "gpt-5.6-terra",
                1_000_000L
        ), comparisonLeaseToken);

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/analysis-jobs/{analysisJobId}/retry",
                                work.getId(),
                                failedJob.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].jobType").value("SETTING_EXTRACTION"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"));

        assertThat(settingCandidateRepository.existsById(pendingCandidate.getId())).isFalse();
        assertThat(settingCandidateRepository.findById(confirmedCandidate.getId()))
                .get()
                .extracting(SettingCandidate::getReviewStatus)
                .isEqualTo(SettingCandidateReviewStatus.CONFIRMED);
        AnalysisJob supersededCharacterComparisonJob = analysisJobRepository
                .findById(characterComparisonJob.getId())
                .orElseThrow();
        assertThat(supersededCharacterComparisonJob.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(supersededCharacterComparisonJob.getSettingCandidate()).isNull();
        assertThat(aiTokenUsageRepository.findById(characterComparisonUsageRequestId))
                .get()
                .satisfies(usage -> {
                    assertThat(usage.getStatus()).isEqualTo(AiTokenUsageStatus.RELEASED);
                    assertThat(usage.getOutcome()).isEqualTo(AiTokenUsageOutcome.USAGE_UNAVAILABLE);
                });
        assertThat(worldSettingCandidateRepository.existsById(pendingWorldSettingCandidate.getId()))
                .isFalse();
        AnalysisJob supersededComparisonJob = analysisJobRepository
                .findById(worldComparisonJob.getId())
                .orElseThrow();
        assertThat(supersededComparisonJob.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(supersededComparisonJob.getWorldSettingCandidate()).isNull();
        assertThat(aiTokenUsageRepository.findById(comparisonUsageRequestId))
                .get()
                .satisfies(usage -> {
                    assertThat(usage.getStatus()).isEqualTo(AiTokenUsageStatus.RELEASED);
                    assertThat(usage.getOutcome()).isEqualTo(AiTokenUsageOutcome.USAGE_UNAVAILABLE);
                });
        assertThat(analysisJobRepository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("실패 작업과 다른 유형의 회차별 작업이 활성 상태면 재시도를 거절한다")
    void retryFailedAnalysisJobRejectsActiveJobOfDifferentType() throws Exception {
        firstEpisode.markFailed();
        episodeRepository.save(firstEpisode);

        AnalysisJob failedJob = AnalysisJob.create(
                work, uploadBatch, firstEpisode, AnalysisJobType.SETTING_EXTRACTION);
        failedJob.fail("설정 추출 실패");
        analysisJobRepository.save(failedJob);
        analysisJobRepository.save(AnalysisJob.create(
                work, uploadBatch, firstEpisode, AnalysisJobType.EPISODE_VALIDATION));

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
    @DisplayName("실패 작업과 같은 유형의 회차별 작업이 활성 상태면 기존 작업을 멱등 반환한다")
    void retryFailedAnalysisJobReturnsActiveJobOfSameType() throws Exception {
        firstEpisode.markFailed();
        episodeRepository.save(firstEpisode);

        AnalysisJob failedJob = AnalysisJob.create(
                work, uploadBatch, firstEpisode, AnalysisJobType.SETTING_EXTRACTION);
        failedJob.fail("설정 추출 실패");
        analysisJobRepository.save(failedJob);
        AnalysisJob activeJob = analysisJobRepository.save(AnalysisJob.create(
                work, uploadBatch, firstEpisode, AnalysisJobType.SETTING_EXTRACTION));

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/analysis-jobs/{analysisJobId}/retry",
                                work.getId(),
                                failedJob.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(activeJob.getId().toString()))
                .andExpect(jsonPath("$.data[0].jobType").value("SETTING_EXTRACTION"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"));

        assertThat(analysisJobRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 유형의 활성 작업을 멱등 반환할 때 남은 사용량을 다시 검사하지 않는다")
    void retryFailedAnalysisJobReturnsActiveJobWhenQuotaIsReserved() throws Exception {
        firstEpisode.markFailed();
        episodeRepository.save(firstEpisode);

        AnalysisJob failedJob = AnalysisJob.create(
                work, uploadBatch, firstEpisode, AnalysisJobType.SETTING_EXTRACTION);
        failedJob.fail("설정 추출 실패");
        analysisJobRepository.save(failedJob);
        AnalysisJob activeJob = AnalysisJob.create(
                work, uploadBatch, firstEpisode, AnalysisJobType.SETTING_EXTRACTION);
        UUID leaseToken = activeJob.claim(
                "gpt-5.6-terra",
                "설정 추출 중",
                LocalDateTime.now().plusMinutes(5)
        );
        activeJob = analysisJobRepository.save(activeJob);
        aiTokenService.reserve(new AiTokenReserveRequest(
                UUID.randomUUID(),
                activeJob.getId(),
                AiTokenPurpose.SETTING_EXTRACTION,
                1,
                "gpt-5.6-terra",
                2_000_000L
        ), leaseToken);

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/analysis-jobs/{analysisJobId}/retry",
                                work.getId(),
                                failedJob.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(activeJob.getId().toString()))
                .andExpect(jsonPath("$.data[0].status").value("RUNNING"));

        assertThat(analysisJobRepository.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = EpisodeStatus.class, names = {"UPLOADED", "ANALYZED"})
    @DisplayName("회차의 현재 상태가 실패가 아니어도 회차별 실패 작업은 같은 유형으로 재시도한다")
    void retryFailedPerEpisodeJobIgnoresMutableEpisodeStatus(EpisodeStatus episodeStatus) throws Exception {
        firstEpisode.updateStatus(episodeStatus);
        episodeRepository.save(firstEpisode);

        AnalysisJob failedJob = AnalysisJob.create(
                work, uploadBatch, firstEpisode, AnalysisJobType.SETTING_EXTRACTION);
        failedJob.fail("설정 추출 실패");
        analysisJobRepository.save(failedJob);

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/analysis-jobs/{analysisJobId}/retry",
                                work.getId(),
                                failedJob.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].jobType").value("SETTING_EXTRACTION"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"));

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
    @DisplayName("분석 배치 목록은 최신 유효 작업과 설정 후보 검토 현황을 집계한다")
    void getAnalysisBatchesAggregatesCurrentJobsAndCandidateCounts() throws Exception {
        AnalysisJob succeededJob = AnalysisJob.create(
                work, uploadBatch, firstEpisode, AnalysisJobType.SETTING_EXTRACTION);
        succeededJob.succeed("{}", 10, 5);
        succeededJob = analysisJobRepository.save(succeededJob);
        analysisJobRepository.save(AnalysisJob.create(
                work, uploadBatch, secondEpisode, AnalysisJobType.SETTING_EXTRACTION));
        settingCandidateRepository.save(SettingCandidate.create(
                work,
                firstEpisode,
                UUID.randomUUID(),
                succeededJob,
                SettingEntityType.CHARACTER,
                "아리아",
                "profile.gender",
                "여성",
                SettingValueType.STRING,
                null,
                null,
                new BigDecimal("0.9000"),
                null
        ));
        worldSettingCandidateRepository.save(worldSettingCandidate(
                succeededJob,
                firstEpisode,
                "바바리안"
        ));

        mockMvc.perform(get("/api/v1/works/{workId}/analysis-jobs/batches", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("page", "0")
                        .queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].batchId").value(uploadBatch.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.content[0].episodeStartNo").value(1))
                .andExpect(jsonPath("$.data.content[0].episodeEndNo").value(4))
                .andExpect(jsonPath("$.data.content[0].episodeCount").value(2))
                .andExpect(jsonPath("$.data.content[0].totalCandidateCount").value(1))
                .andExpect(jsonPath("$.data.content[0].reviewedCandidateCount").value(0))
                .andExpect(jsonPath("$.data.content[0].pendingCandidateCount").value(1))
                .andExpect(jsonPath("$.data.content[0].worldSettingTotalCandidateCount").value(1))
                .andExpect(jsonPath("$.data.content[0].worldSettingReviewedCandidateCount").value(0))
                .andExpect(jsonPath("$.data.content[0].worldSettingPendingCandidateCount").value(1))
                .andExpect(jsonPath("$.data.content[0].jobGroups", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].jobGroups[0].jobType")
                        .value("SETTING_EXTRACTION"))
                .andExpect(jsonPath("$.data.content[0].jobGroups[0].totalJobCount").value(2))
                .andExpect(jsonPath("$.data.content[0].jobGroups[0].pendingJobCount").value(1))
                .andExpect(jsonPath("$.data.content[0].jobGroups[0].succeededJobCount").value(1));
    }

    @Test
    @DisplayName("분석 배치 목록은 세계관 후보만 검토 대기여도 검토 필요로 표시한다")
    void getAnalysisBatchesRequiresReviewForPendingWorldSettingCandidates() throws Exception {
        AnalysisJob succeededJob = succeededJob(firstEpisode, AnalysisJobType.SETTING_EXTRACTION);
        worldSettingCandidateRepository.save(worldSettingCandidate(
                succeededJob,
                firstEpisode,
                "미궁 1층"
        ));

        mockMvc.perform(get("/api/v1/works/{workId}/analysis-jobs/batches", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.data.content[0].totalCandidateCount").value(0))
                .andExpect(jsonPath("$.data.content[0].pendingCandidateCount").value(0))
                .andExpect(jsonPath("$.data.content[0].worldSettingTotalCandidateCount").value(1))
                .andExpect(jsonPath("$.data.content[0].worldSettingReviewedCandidateCount").value(0))
                .andExpect(jsonPath("$.data.content[0].worldSettingPendingCandidateCount").value(1));
    }

    @Test
    @DisplayName("분석 배치 목록은 재시도 전 실패 작업 대신 같은 회차의 최신 작업을 사용한다")
    void getAnalysisBatchesUsesLatestJobPerEpisode() throws Exception {
        AnalysisJob failedJob = AnalysisJob.create(
                work, uploadBatch, firstEpisode, AnalysisJobType.EPISODE_VALIDATION);
        failedJob.fail("이전 실패");
        analysisJobRepository.save(failedJob);
        Thread.sleep(10);
        AnalysisJob retryJob = analysisJobRepository.save(AnalysisJob.create(
                work, uploadBatch, firstEpisode, AnalysisJobType.EPISODE_VALIDATION));

        mockMvc.perform(get("/api/v1/works/{workId}/analysis-jobs/batches", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.content[0].jobGroups[0].totalJobCount").value(1))
                .andExpect(jsonPath("$.data.content[0].jobGroups[0].failedJobCount").value(0))
                .andExpect(jsonPath("$.data.content[0].jobGroups[0].currentAnalysisJobIds", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].jobGroups[0].currentAnalysisJobIds[0]")
                        .value(retryJob.getId().toString()));
    }

    @Test
    @DisplayName("분석 배치 목록은 과거 다회차 작업도 회차별 현재 상태로 집계한다")
    void getAnalysisBatchesCountsLegacyBatchWideJobPerEpisode() throws Exception {
        AnalysisJob legacyJob = AnalysisJob.create(
                work, uploadBatch, null, AnalysisJobType.EPISODE_VALIDATION);
        legacyJob.addTargetEpisodes(List.of(firstEpisode, secondEpisode));
        legacyJob.succeed("{}", 10, 5);
        legacyJob = analysisJobRepository.save(legacyJob);
        Thread.sleep(10);
        AnalysisJob retryJob = analysisJobRepository.save(AnalysisJob.create(
                work, uploadBatch, firstEpisode, AnalysisJobType.EPISODE_VALIDATION));

        mockMvc.perform(get("/api/v1/works/{workId}/analysis-jobs/batches", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.content[0].jobGroups[0].totalJobCount").value(2))
                .andExpect(jsonPath("$.data.content[0].jobGroups[0].pendingJobCount").value(1))
                .andExpect(jsonPath("$.data.content[0].jobGroups[0].succeededJobCount").value(1))
                .andExpect(jsonPath("$.data.content[0].jobGroups[0].currentAnalysisJobIds", hasSize(2)))
                .andExpect(jsonPath("$.data.content[0].jobGroups[0].currentAnalysisJobIds",
                        containsInAnyOrder(legacyJob.getId().toString(), retryJob.getId().toString())));
    }

    @Test
    @DisplayName("분석 배치 목록은 업로드 배치를 서버에서 10개씩 페이지 조회한다")
    void getAnalysisBatchesPaginatesUploadBatches() throws Exception {
        for (int index = 0; index < 11; index++) {
            UploadBatch batch = uploadBatchRepository.save(UploadBatch.create(
                    work,
                    member,
                    UploadType.INITIAL_IMPORT,
                    UploadSourceType.FILE
            ));
            analysisJobRepository.save(AnalysisJob.create(
                    work, batch, null, AnalysisJobType.SETTING_EXTRACTION));
        }

        mockMvc.perform(get("/api/v1/works/{workId}/analysis-jobs/batches", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("page", "0")
                        .queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(10)))
                .andExpect(jsonPath("$.data.totalElements").value(11))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true));

        mockMvc.perform(get("/api/v1/works/{workId}/analysis-jobs/batches", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("page", "1")
                        .queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
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

    private AnalysisJob succeededJob(Episode episode, AnalysisJobType jobType) {
        AnalysisJob analysisJob = AnalysisJob.create(work, uploadBatch, episode, jobType);
        analysisJob.succeed("{}", 0, 0);
        return analysisJobRepository.save(analysisJob);
    }

    private SettingCandidate candidate(
            AnalysisJob analysisJob,
            Episode episode,
            String attributeName
    ) {
        return SettingCandidate.create(
                work,
                episode,
                UUID.randomUUID(),
                analysisJob,
                SettingEntityType.CHARACTER,
                "아리아",
                attributeName,
                "value",
                SettingValueType.STRING,
                null,
                null,
                new BigDecimal("0.9000"),
                null
        );
    }

    private WorldSettingCandidate worldSettingCandidate(
            AnalysisJob analysisJob,
            Episode episode,
            String subjectName
    ) {
        return WorldSettingCandidate.create(
                work,
                episode,
                analysisJob,
                WorldSettingCategory.LOCATION,
                subjectName,
                "지형 구조",
                "복잡한 통로 구조",
                JsonNodeFactory.instance.arrayNode().add(
                        JsonNodeFactory.instance.objectNode()
                                .put("quote", "%s은 복잡한 통로 구조다.".formatted(subjectName))
                ),
                new BigDecimal("0.9000"),
                JsonNodeFactory.instance.objectNode()
        );
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
