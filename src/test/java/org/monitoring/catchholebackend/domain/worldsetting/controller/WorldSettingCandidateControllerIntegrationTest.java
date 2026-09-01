package org.monitoring.catchholebackend.domain.worldsetting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenAccount;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenAccountRepository;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateGroupConfirmRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateGroupDismissRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateConfirmRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDismissRequest;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonBatch;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecision;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecisionSource;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonBatchRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonDecisionRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonDecisionSourceRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonReviewReason;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingConsolidationStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType;
import org.monitoring.catchholebackend.global.config.security.SecurityConstant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("세계관 설정 후보 API 통합 테스트")
class WorldSettingCandidateControllerIntegrationTest {

    private static final String INTERNAL_API_KEY = "local-development-internal-api-key";

    private final ObjectMapper objectMapper = new ObjectMapper();

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
    private AiTokenAccountRepository aiTokenAccountRepository;

    @Autowired
    private WorldSettingRepository worldSettingRepository;

    @Autowired
    private WorldSettingCandidateRepository candidateRepository;

    @Autowired
    private WorldSettingComparisonBatchRepository comparisonBatchRepository;

    @Autowired
    private WorldSettingComparisonDecisionRepository comparisonDecisionRepository;

    @Autowired
    private WorldSettingComparisonDecisionSourceRepository comparisonSourceRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Member member;
    private Work work;
    private UploadBatch uploadBatch;
    private Episode episode;
    private AnalysisJob analysisJob;
    private String accessToken;

    @BeforeEach
    void setUp() {
        clearData();
        member = memberRepository.save(Member.register(
                "world-candidate-writer@example.com",
                "encoded-password",
                "01055556666",
                "후보 작가"
        ));
        work = workRepository.save(Work.create(member, "설원 전기", WorkGenre.FANTASY, "세계관 후보 테스트"));
        uploadBatch = uploadBatchRepository.save(UploadBatch.create(
                work,
                member,
                UploadType.SINGLE_EPISODE,
                UploadSourceType.FILE
        ));
        episode = episodeRepository.save(Episode.create(
                work,
                null,
                3,
                "3화",
                "works/%s/episodes/3.txt".formatted(work.getId()),
                "version-3",
                "hash-3",
                300
        ));
        analysisJob = analysisJobRepository.save(AnalysisJob.create(
                work,
                uploadBatch,
                episode,
                AnalysisJobType.SETTING_EXTRACTION
        ));
        accessToken = jwtTokenProvider.generateAccessToken(member);
    }

    @AfterEach
    void tearDown() {
        clearData();
    }

    @Test
    @DisplayName("작가가 수정안을 저장하면 재비교 없이 후보를 새 분류·대상 그룹으로 즉시 이동한다")
    void updateDecisionMovesPendingCandidateToEditedGroupWithoutRecomparison() throws Exception {
        WorldSettingCandidate movedCandidate = completedAddCandidate(
                WorldSettingCategory.MONSTER,
                "구울",
                "이동 방식",
                "사람과 비슷한 체형으로 4족 보행한다"
        );
        WorldSettingCandidate remainingCandidate = completedAddCandidate(
                WorldSettingCategory.MONSTER,
                "구울",
                "약점",
                "햇빛에 약하다"
        );
        candidateRepository.saveAllAndFlush(List.of(movedCandidate, remainingCandidate));

        mockMvc.perform(patch("/api/v1/works/{workId}/world-setting-candidates/decisions", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "batchId", uploadBatch.getId(),
                                "candidates", List.of(java.util.Map.of(
                                        "candidateId", movedCandidate.getId(),
                                        "operation", "ADD",
                                        "category", "LOCATION",
                                        "subjectName", "미궁",
                                        "settingName", "이동 방식",
                                        "value", "사람과 비슷한 체형으로 4족 보행한다"
                                ))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groupKey").value("LOCATION|미궁"))
                .andExpect(jsonPath("$.data.candidates[0].reviewStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.candidates[0].comparisonStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.candidates[0].userModified").value(true))
                .andExpect(jsonPath("$.data.candidates[0].finalCategory").value("LOCATION"))
                .andExpect(jsonPath("$.data.candidates[0].finalSubjectName").value("미궁"))
                .andExpect(jsonPath("$.data.candidates[0].reviewedAt").doesNotExist());

        mockMvc.perform(get("/api/v1/works/{workId}/world-setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups.content.length()").value(2))
                .andExpect(jsonPath("$.data.groups.content[*].subjectName")
                        .value(containsInAnyOrder("구울", "미궁")))
                .andExpect(jsonPath("$.data.groups.content[?(@.subjectName == '미궁')].category")
                        .value("LOCATION"))
                .andExpect(jsonPath("$.data.groups.content[?(@.subjectName == '미궁')].changeCount")
                        .value(1))
                .andExpect(jsonPath("$.data.groups.content[?(@.subjectName == '구울')].changeCount")
                        .value(1));

        WorldSettingCandidate persisted = candidateRepository.findById(movedCandidate.getId()).orElseThrow();
        assertThat(persisted.getFinalCategory()).isEqualTo(WorldSettingCategory.LOCATION);
        assertThat(persisted.getFinalSubjectName()).isEqualTo("미궁");
        assertThat(persisted.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.PENDING_REVIEW);
        assertThat(persisted.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(persisted.getReviewedAt()).isNull();
        assertThat(analysisJobRepository.findAll())
                .filteredOn(job -> job.getJobType() == AnalysisJobType.WORLD_SETTING_COMPARISON)
                .isEmpty();
    }

    @Test
    @DisplayName("동명 과거 그룹이 모두 확정되어도 개별 수정한 후보는 새 대기 그룹으로 다시 나타난다")
    void updateDecisionCreatesPendingGroupWhenSameNameHistoricalGroupWasConfirmed() throws Exception {
        WorldSettingCandidate historical = completedAddCandidate(
                WorldSettingCategory.LOCATION,
                "미궁",
                "위치",
                "북부"
        );
        candidateRepository.saveAndFlush(historical);
        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-confirm", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorldSettingCandidateGroupConfirmRequest(
                                uploadBatch.getId(),
                                List.of(new WorldSettingCandidateGroupConfirmRequest.Decision(
                                        historical.getId(),
                                        WorldSettingOperation.ADD,
                                        WorldSettingCategory.LOCATION,
                                        "미궁",
                                        null,
                                        "위치",
                                        "북부",
                                        false,
                                        null
                                ))
                        ))))
                .andExpect(status().isOk());

        WorldSettingCandidate pending = completedAddCandidate(
                WorldSettingCategory.MONSTER,
                "구울",
                "약점",
                "햇빛"
        );
        candidateRepository.saveAndFlush(pending);
        mockMvc.perform(patch("/api/v1/works/{workId}/world-setting-candidates/decisions", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "batchId", uploadBatch.getId(),
                                "candidates", List.of(java.util.Map.of(
                                        "candidateId", pending.getId(),
                                        "operation", "ADD",
                                        "category", "LOCATION",
                                        "subjectName", "미궁",
                                        "settingName", "약점",
                                        "value", "햇빛"
                                ))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groupKey").value("LOCATION|미궁"));

        mockMvc.perform(get("/api/v1/works/{workId}/world-setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString())
                        .queryParam("reviewStatus", "PENDING_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups.totalElements").value(1))
                .andExpect(jsonPath("$.data.groups.content[0].subjectName").value("미궁"))
                .andExpect(jsonPath("$.data.groups.content[0].changeCount").value(1));
    }

    @Test
    @DisplayName("재비교 요청은 전용 내부 Job을 멱등 생성하고 공개 분석 이력에서는 숨긴다")
    void recompareCreatesHiddenComparisonJobIdempotently() throws Exception {
        WorldSettingCandidate candidate = candidate("바바리안", "서식지", "혹한 지역");
        candidate.startComparison();
        candidate.failComparison("비교 응답 오류");
        candidateRepository.save(candidate);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post(
                                    "/api/v1/works/{workId}/world-setting-candidates/{candidateId}/recompare",
                                    work.getId(),
                                    candidate.getId()
                            )
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.comparisonStatus").value("PENDING"));
        }

        assertThat(analysisJobRepository.findAll())
                .filteredOn(job -> job.getJobType() == AnalysisJobType.WORLD_SETTING_COMPARISON)
                .hasSize(1);
        mockMvc.perform(get("/api/v1/works/{workId}/analysis-jobs", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].jobType").value("SETTING_EXTRACTION"));

        MvcResult claimResult = mockMvc.perform(post("/api/internal/v1/analysis-jobs/claim")
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allowedJobTypes":["WORLD_SETTING_COMPARISON"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.worldSettingCandidateId").value(candidate.getId().toString()))
                .andReturn();
        JsonNode claimBody = objectMapper.readTree(claimResult.getResponse().getContentAsString());
        UUID comparisonJobId = UUID.fromString(claimBody.at("/data/analysisJobId").asText());
        UUID leaseToken = UUID.fromString(claimBody.at("/data/leaseToken").asText());

        mockMvc.perform(put(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-subject-resolutions",
                                comparisonJobId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(SecurityConstant.WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "resolutions": [{
                                    "candidateId": "%s",
                                    "targetWorldSettingIds": []
                                  }]
                                }
                                """.formatted(candidate.getId())))
                .andExpect(status().isOk());

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/claim-next",
                                comparisonJobId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(SecurityConstant.WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates.length()").value(1))
                .andExpect(jsonPath("$.data.candidates[0].candidateRef").value("C1"))
                .andExpect(jsonPath("$.data.candidates[0].candidateId")
                        .value(candidate.getId().toString()));
    }

    @Test
    @DisplayName("토큰 중단 후보는 단건 재비교를 거절하고 배치 API로만 재개한다")
    void recompareRejectsTokenInterruptedCandidateUntilBatchResume() throws Exception {
        analysisJob.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
        analysisJob.fail(AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED, "내부 quota 오류");
        analysisJobRepository.saveAndFlush(analysisJob);

        WorldSettingCandidate candidate = candidate("바바리안", "서식지", "혹한 지역");
        candidate.interruptComparisonForTokenQuota("내부 quota 오류");
        candidateRepository.saveAndFlush(candidate);

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/world-setting-candidates/{candidateId}/recompare",
                                work.getId(),
                                candidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT"));

        assertThat(candidateRepository.findById(candidate.getId()))
                .get()
                .satisfies(savedCandidate -> {
                    assertThat(savedCandidate.getComparisonStatus())
                            .isEqualTo(WorldSettingComparisonStatus.FAILED);
                    assertThat(savedCandidate.getComparisonFailureCode())
                            .isEqualTo(AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED);
                });
        assertThat(analysisJobRepository.findAll())
                .filteredOn(job -> job.getJobType() == AnalysisJobType.WORLD_SETTING_COMPARISON)
                .isEmpty();

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/world-setting-candidates/batches/{batchId}"
                                        + "/resume-token-interrupted",
                                work.getId(),
                                uploadBatch.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumedCandidateCount").value(1))
                .andExpect(jsonPath("$.data.remainingInterruptedCandidateCount").value(0));

        assertThat(candidateRepository.findById(candidate.getId()))
                .get()
                .satisfies(savedCandidate -> {
                    assertThat(savedCandidate.getComparisonStatus())
                            .isEqualTo(WorldSettingComparisonStatus.PENDING);
                    assertThat(savedCandidate.getComparisonFailureCode()).isNull();
                });
        assertThat(analysisJobRepository.findAll())
                .filteredOn(job -> job.getJobType() == AnalysisJobType.WORLD_SETTING_COMPARISON)
                .hasSize(1);
    }

    @Test
    @DisplayName("토큰 중단 후보 일괄 재개는 해당 후보만 재사용하고 반복 호출에도 Job을 중복 생성하지 않는다")
    void resumeTokenInterruptedComparisonsIsSelectiveAndIdempotent() throws Exception {
        analysisJob.claim("gpt-5.6-terra", "세계관 비교", LocalDateTime.now().plusMinutes(5));
        analysisJob.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
        analysisJob.fail(
                AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED,
                "Client error 409 for url https://internal.example/token/reserve"
        );
        analysisJobRepository.saveAndFlush(analysisJob);

        WorldSettingCandidate pendingInterrupted = candidate("바바리안", "서식지", "혹한 지역");
        pendingInterrupted.interruptComparisonForTokenQuota("내부 quota URL");
        WorldSettingCandidate processingInterrupted = candidate("마탑", "위치", "황도 중앙");
        processingInterrupted.startComparison();
        processingInterrupted.interruptComparisonForTokenQuota("내부 stack trace");
        WorldSettingCandidate completed = completedAddCandidate("왕국", "수도", "아르덴");
        WorldSettingCandidate ordinaryFailure = candidate("성검", "소유자", "용사");
        ordinaryFailure.startComparison();
        ordinaryFailure.failComparison(AnalysisFailureCode.LLM_PROVIDER_ERROR, "provider raw URL");
        candidateRepository.saveAllAndFlush(List.of(
                pendingInterrupted,
                processingInterrupted,
                completed,
                ordinaryFailure
        ));

        mockMvc.perform(get("/api/v1/works/{workId}/world-setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenInterruptedComparisonCount").value(2))
                .andExpect(jsonPath("$.data.activeComparisonJobCount").value(0))
                .andExpect(jsonPath("$.data.canResumeTokenInterruptedComparisons").value(true));

        mockMvc.perform(get("/api/v1/works/{workId}/world-setting-candidates/{candidateId}",
                                work.getId(), pendingInterrupted.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.comparisonFailureCode").value("AI_TOKEN_QUOTA_EXHAUSTED"))
                .andExpect(jsonPath("$.data.comparisonErrorMessage")
                        .value("AI 토큰이 부족해 분석이 중단되었습니다."));

        mockMvc.perform(get("/api/v1/works/{workId}/analysis-jobs/batches", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].status").value("PARTIALLY_FAILED"))
                .andExpect(jsonPath("$.data.content[0].worldSettingTokenInterruptedCandidateCount").value(2))
                .andExpect(jsonPath("$.data.content[0].canResumeTokenInterruptedWorldSettingComparisons")
                        .value(true));

        String resumeUrl = "/api/v1/works/{workId}/world-setting-candidates/batches/{batchId}"
                + "/resume-token-interrupted";
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post(resumeUrl, work.getId(), uploadBatch.getId())
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resumedCandidateCount").value(attempt == 0 ? 2 : 0))
                    .andExpect(jsonPath("$.data.activeCandidateCount").value(2))
                    .andExpect(jsonPath("$.data.remainingInterruptedCandidateCount").value(0));
        }

        assertThat(candidateRepository.findAllById(List.of(
                        pendingInterrupted.getId(),
                        processingInterrupted.getId()
                )))
                .allSatisfy(candidate -> {
                    assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.PENDING);
                    assertThat(candidate.getComparisonFailureCode()).isNull();
                });
        assertThat(candidateRepository.findById(completed.getId()).orElseThrow().getComparisonStatus())
                .isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(candidateRepository.findById(ordinaryFailure.getId()).orElseThrow().getComparisonFailureCode())
                .isEqualTo(AnalysisFailureCode.LLM_PROVIDER_ERROR);
        assertThat(analysisJobRepository.findAll())
                .filteredOn(job -> job.getJobType() == AnalysisJobType.WORLD_SETTING_COMPARISON)
                .hasSize(2);

        mockMvc.perform(get("/api/v1/works/{workId}/world-setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeComparisonJobCount").value(2));

        mockMvc.perform(get("/api/v1/works/{workId}/analysis-jobs/batches", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.content[0].worldSettingTokenInterruptedCandidateCount").value(0))
                .andExpect(jsonPath("$.data.content[0].canResumeTokenInterruptedWorldSettingComparisons")
                        .value(false));
    }

    @Test
    @DisplayName("첫 비교 최소 예약량보다 부족하면 토큰 중단 일괄 재개를 상태 변경 없이 409로 거절한다")
    void resumeTokenInterruptedComparisonsRejectsInsufficientMinimumReservation() throws Exception {
        WorldSettingCandidate interrupted = candidate("바바리안", "서식지", "혹한 지역");
        interrupted.interruptComparisonForTokenQuota("quota");
        candidateRepository.saveAndFlush(interrupted);
        aiTokenAccountRepository.saveAndFlush(AiTokenAccount.create(member, 2255L));

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/world-setting-candidates/batches/{batchId}"
                                        + "/resume-token-interrupted",
                                work.getId(),
                                uploadBatch.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AI_TOKEN_QUOTA_EXHAUSTED"));

        WorldSettingCandidate unchanged = candidateRepository.findById(interrupted.getId()).orElseThrow();
        assertThat(unchanged.isTokenInterruptedComparison()).isTrue();
        assertThat(analysisJobRepository.findAll())
                .noneMatch(job -> job.getJobType() == AnalysisJobType.WORLD_SETTING_COMPARISON);
    }

    @Test
    @DisplayName("묶음 전체 집계와 세계관 분류·제안 작업 필터를 분리해 조회한다")
    void getCandidatesReturnsBatchCountsAndFilteredPage() throws Exception {
        WorldSettingCandidate completed = candidate("바바리안", "서식지", "혹한 지역");
        completed.startComparison();
        completed.completeComparison(
                null,
                WorldSettingOperation.ADD,
                "서식지",
                null,
                "혹한 지역",
                "새 대상",
                objectMapper.createObjectNode().put("operation", "ADD"),
                LocalDateTime.now()
        );
        candidateRepository.save(completed);
        candidateRepository.save(candidate("마탑", "위치", "황도 중앙"));

        mockMvc.perform(get("/api/v1/works/{workId}/world-setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString())
                        .queryParam("operation", "ADD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.episodeStartNo").value(3))
                .andExpect(jsonPath("$.data.episodeEndNo").value(3))
                .andExpect(jsonPath("$.data.episodeCount").value(1))
                .andExpect(jsonPath("$.data.totalCandidateCount").value(2))
                .andExpect(jsonPath("$.data.pendingCandidateCount").value(2))
                .andExpect(jsonPath("$.data.pendingComparisonCount").value(1))
                .andExpect(jsonPath("$.data.activeComparisonJobCount").value(0))
                .andExpect(jsonPath("$.data.groups.totalElements").value(1))
                .andExpect(jsonPath("$.data.groups.content[0].changeCount").value(1))
                .andExpect(jsonPath("$.data.groups.content[0].candidates[0].suggestedOperation").value("ADD"));
    }

    @Test
    @DisplayName("같은 분류·대상의 후보를 근거 회차와 key row가 있는 한 그룹으로 조회한다")
    void getCandidatesGroupsRowsBySubject() throws Exception {
        WorldSettingCandidate habitat = completedAddCandidate("바바리안", "서식지", "혹한 지역");
        WorldSettingCandidate society = completedAddCandidate("바바리안", "사회 구조", "부족 단위로 생활");
        candidateRepository.saveAll(List.of(habitat, society));

        mockMvc.perform(get("/api/v1/works/{workId}/world-setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString())
                        .queryParam("reviewStatus", "PENDING_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups.totalElements").value(1))
                .andExpect(jsonPath("$.data.groups.content[0].groupKey").value("RACE|바바리안"))
                .andExpect(jsonPath("$.data.groups.content[0].subjectName").value("바바리안"))
                .andExpect(jsonPath("$.data.groups.content[0].changeCount").value(2))
                .andExpect(jsonPath("$.data.groups.content[0].addCount").value(2))
                .andExpect(jsonPath("$.data.groups.content[0].evidenceEpisodeNos[0]").value(3))
                .andExpect(jsonPath("$.data.groups.content[0].status").value("READY"))
                .andExpect(jsonPath("$.data.groups.content[0].candidates.length()").value(2))
                .andExpect(jsonPath("$.data.groups.content[0].candidates[0].evidenceSpans[0].quote")
                        .value("바바리안은 혹한 지역다."))
                .andExpect(jsonPath("$.data.groups.content[0].candidates[0].evidenceSpans[0].startOffset")
                        .value(10))
                .andExpect(jsonPath("$.data.groups.content[0].candidates[0].evidenceSpans[0].endOffset")
                        .value(30));
    }

    @Test
    @DisplayName("신규 대상의 여러 ADD key를 한 번에 확정하고 같은 적용 버전을 기록한다")
    void confirmGroupCreatesTargetOnceWithOneVersion() throws Exception {
        WorldSettingCandidate habitat = completedAddCandidate("바바리안", "서식지", "혹한 지역");
        WorldSettingCandidate trait = completedAddCandidate("바바리안", "특징", "강인한 신체");
        WorldSettingCandidate society = completedAddCandidate("바바리안", "사회 구조", "부족 단위로 생활");
        candidateRepository.saveAllAndFlush(List.of(habitat, trait, society));

        WorldSettingCandidateGroupConfirmRequest request = groupConfirmRequest(
                decision(habitat, WorldSettingOperation.ADD, "서식지", "혹한 지역"),
                decision(trait, WorldSettingOperation.ADD, "특징", "강인한 신체"),
                decision(society, WorldSettingOperation.ADD, "사회 구조", "부족 단위로 생활")
        );

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-confirm", work.getId())
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.appliedWorldSettingVersion").value(0))
                    .andExpect(jsonPath("$.data.candidates.length()").value(3))
                    .andExpect(jsonPath("$.data.candidates[0].reviewStatus").value("CONFIRMED"))
                    .andExpect(jsonPath("$.data.candidates[1].reviewStatus").value("CONFIRMED"))
                    .andExpect(jsonPath("$.data.candidates[2].reviewStatus").value("CONFIRMED"));
        }

        assertThat(worldSettingRepository.countByWorkId(work.getId())).isEqualTo(1);
        WorldSetting applied = worldSettingRepository
                .findByWorkIdAndCategoryAndNormalizedSubjectName(
                        work.getId(), WorldSettingCategory.RACE, "바바리안"
                ).orElseThrow();
        assertThat(applied.getVersion()).isZero();
        assertThat(applied.getPropertyValue("서식지")).isEqualTo("혹한 지역");
        assertThat(applied.getPropertyValue("특징")).isEqualTo("강인한 신체");
        assertThat(applied.getPropertyValue("사회 구조")).isEqualTo("부족 단위로 생활");
        assertThat(candidateRepository.findAll())
                .extracting(WorldSettingCandidate::getAppliedWorldSettingVersion)
                .containsOnly(0L);
        assertThat(candidateRepository.findAll())
                .extracting(WorldSettingCandidate::getComparisonStatus)
                .containsOnly(WorldSettingComparisonStatus.COMPLETED);
    }

    @Test
    @DisplayName("같은 설정안의 source 전체를 선택하면 한 후보는 적용하고 다른 후보는 제외할 수 있다")
    void confirmGroupAllowsMixedApplyAndExcludeForSharedDecision() throws Exception {
        WorldSetting existingTarget = worldSettingRepository.saveAndFlush(WorldSetting.create(
                work,
                WorldSettingCategory.MONSTER,
                "고블린",
                "서식지",
                "미궁 1층"
        ));
        WorldSettingCandidate trap = WorldSettingCandidate.create(
                work,
                episode,
                analysisJob,
                WorldSettingCategory.MONSTER,
                "고블린 떼",
                "함정 사용",
                "사냥감의 이동 경로에 함정을 설치한다.",
                objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                        .put("quote", "고블린들은 길목마다 함정을 파 두었다.")
                        .put("startOffset", 10)
                        .put("endOffset", 31)),
                new BigDecimal("0.9500"),
                null
        );
        WorldSettingCandidate ambush = WorldSettingCandidate.create(
                work,
                episode,
                analysisJob,
                WorldSettingCategory.MONSTER,
                "고블린 무리",
                "매복 습성",
                "숨어서 사냥감이 가까워지기를 기다린다.",
                objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                        .put("quote", "수풀에 숨은 고블린들이 일제히 튀어나왔다.")
                        .put("startOffset", 40)
                        .put("endOffset", 63)),
                new BigDecimal("0.9500"),
                null
        );
        candidateRepository.saveAllAndFlush(List.of(trap, ambush));
        JsonNode resolvedTargetIds = objectMapper.createArrayNode()
                .add(existingTarget.getId().toString());
        trap.resolveSubject(
                WorldSettingSubjectResolutionType.EXISTING,
                "TARGET:" + existingTarget.getId(),
                existingTarget.getSubjectName(),
                resolvedTargetIds
        );
        ambush.resolveSubject(
                WorldSettingSubjectResolutionType.EXISTING,
                "TARGET:" + existingTarget.getId(),
                existingTarget.getSubjectName(),
                resolvedTargetIds
        );

        WorldSettingComparisonBatch comparisonBatch = comparisonBatchRepository.saveAndFlush(
                WorldSettingComparisonBatch.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.MONSTER,
                        null,
                        WorldSettingSubjectResolutionType.EXISTING,
                        "TARGET:" + existingTarget.getId(),
                        existingTarget.getSubjectName(),
                        resolvedTargetIds,
                        2
                )
        );
        trap.startComparison(comparisonBatch, "C1");
        ambush.startComparison(comparisonBatch, "C2");
        candidateRepository.saveAllAndFlush(List.of(trap, ambush));
        WorldSettingComparisonDecision sharedDecision = comparisonDecisionRepository.saveAndFlush(
                WorldSettingComparisonDecision.create(
                        comparisonBatch,
                        "D1",
                        "고블린",
                        existingTarget,
                        null,
                        null,
                        WorldSettingConsolidationStatus.MERGED,
                        WorldSettingSuggestedOperation.ADD,
                        null,
                        "전투 특성",
                        "사냥 전술",
                        null,
                        "이동 경로에 함정을 설치한 뒤 숨어서 매복한다.",
                        "두 근거가 고블린의 한 가지 사냥 전술을 보완한다.",
                        objectMapper.createObjectNode().put("decisionRef", "D1")
                )
        );
        LocalDateTime comparedAt = LocalDateTime.now();
        trap.completeComparison(sharedDecision, comparedAt);
        ambush.completeComparison(sharedDecision, comparedAt);
        candidateRepository.saveAllAndFlush(List.of(trap, ambush));
        comparisonSourceRepository.saveAllAndFlush(List.of(
                WorldSettingComparisonDecisionSource.create(
                        comparisonBatch,
                        sharedDecision,
                        trap,
                        "C1",
                        0
                ),
                WorldSettingComparisonDecisionSource.create(
                        comparisonBatch,
                        sharedDecision,
                        ambush,
                        "C2",
                        1
                )
        ));

        WorldSettingCandidateGroupConfirmRequest.Decision trapDecision =
                new WorldSettingCandidateGroupConfirmRequest.Decision(
                        trap.getId(),
                        WorldSettingOperation.ADD,
                        WorldSettingCategory.MONSTER,
                        "고블린",
                        "전투 특성",
                        "사냥 전술",
                        "이동 경로에 함정을 설치한 뒤 숨어서 매복한다.",
                        false,
                        null
                );
        WorldSettingCandidateGroupConfirmRequest.Decision ambushDecision =
                new WorldSettingCandidateGroupConfirmRequest.Decision(
                        ambush.getId(),
                        WorldSettingOperation.EXCLUDE,
                        WorldSettingCategory.MONSTER,
                        "고블린",
                        "전투 특성",
                        "사냥 전술",
                        "이동 경로에 함정을 설치한 뒤 숨어서 매복한다.",
                        false,
                        "매복 근거는 최종 설정에서 제외"
                );

        WorldSettingCandidateConfirmRequest singleConfirmRequest =
                new WorldSettingCandidateConfirmRequest(
                        WorldSettingOperation.ADD,
                        WorldSettingCategory.MONSTER,
                        "고블린",
                        "전투 특성",
                        "사냥 전술",
                        "이동 경로에 함정을 설치한 뒤 숨어서 매복한다.",
                        false,
                        null
                );
        mockMvc.perform(post(
                                "/api/v1/works/{workId}/world-setting-candidates/{candidateId}/confirm",
                                work.getId(),
                                trap.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(singleConfirmRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_SELECTION_INVALID"));

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/world-setting-candidates/{candidateId}/dismiss",
                                work.getId(),
                                trap.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WorldSettingCandidateDismissRequest("한 후보만 제외")
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_SELECTION_INVALID"));

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/world-setting-candidates/group-confirm",
                                work.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupConfirmRequest(trapDecision))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_SELECTION_INVALID"));

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/world-setting-candidates/group-dismiss",
                                work.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WorldSettingCandidateGroupDismissRequest(
                                        uploadBatch.getId(),
                                        List.of(trap.getId()),
                                        "한 후보만 제외"
                                )
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_SELECTION_INVALID"));

        assertThat(candidateRepository.findAll())
                .extracting(WorldSettingCandidate::getReviewStatus)
                .containsOnly(WorldSettingReviewStatus.PENDING_REVIEW);

        WorldSettingCandidateGroupConfirmRequest request = groupConfirmRequest(
                trapDecision,
                ambushDecision
        );

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post(
                                    "/api/v1/works/{workId}/world-setting-candidates/group-confirm",
                                    work.getId()
                            )
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.candidates.length()").value(2))
                    .andExpect(jsonPath("$.data.candidates[*].reviewStatus")
                            .value(containsInAnyOrder("CONFIRMED", "DISMISSED")));
        }

        WorldSetting applied = worldSettingRepository
                .findByWorkIdAndCategoryAndNormalizedSubjectName(
                        work.getId(),
                        WorldSettingCategory.MONSTER,
                        "고블린"
                )
                .orElseThrow();
        assertThat(applied.getVersion()).isEqualTo(1L);
        assertThat(applied.getPropertyCount()).isEqualTo(2);
        assertThat(applied.getPropertyValue("서식지")).isEqualTo("미궁 1층");
        assertThat(applied.getPropertyValue("전투 특성", "사냥 전술"))
                .isEqualTo("이동 경로에 함정을 설치한 뒤 숨어서 매복한다.");
        assertThat(candidateRepository.findById(trap.getId()).orElseThrow()
                .getAppliedWorldSettingVersion()).isEqualTo(1L);
        assertThat(candidateRepository.findById(ambush.getId()).orElseThrow()
                .getAppliedWorldSettingVersion()).isNull();
        assertThat(comparisonDecisionRepository.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("변경하지 않은 묶음 ADD를 확정하면 기존 root와 새 설정을 공통 범위에 원자 반영한다")
    void groupConfirmAppliesRootMoveAndPreservesRootEvidence() throws Exception {
        RootMoveFixture fixture = rootMoveFixture();
        WorldSettingCandidate candidate = fixture.candidate();

        mockMvc.perform(get(
                                "/api/v1/works/{workId}/world-setting-candidates/{candidateId}",
                                work.getId(),
                                candidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.existingRootPropertyNamesToMove[0]")
                        .value("생명력"));

        WorldSettingCandidateConfirmRequest singleConfirm =
                new WorldSettingCandidateConfirmRequest(
                        WorldSettingOperation.ADD,
                        WorldSettingCategory.RACE,
                        "바바리안",
                        "신체",
                        "근력 기댓값",
                        "높다",
                        false,
                        null
                );
        mockMvc.perform(post(
                                "/api/v1/works/{workId}/world-setting-candidates/{candidateId}/confirm",
                                work.getId(),
                                candidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(singleConfirm)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_SELECTION_INVALID"));
        mockMvc.perform(post(
                                "/api/v1/works/{workId}/world-setting-candidates/{candidateId}/dismiss",
                                work.getId(),
                                candidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WorldSettingCandidateDismissRequest("단건 제외 시도")
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_SELECTION_INVALID"));

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/world-setting-candidates/group-confirm",
                                work.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupConfirmRequest(
                                decision(
                                        candidate,
                                        WorldSettingOperation.ADD,
                                        "신체",
                                        "근력 기댓값",
                                        "높다"
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appliedWorldSettingVersion").value(1));

        WorldSetting applied = worldSettingRepository.findById(fixture.target().getId()).orElseThrow();
        assertThat(applied.getPropertyValue("생명력")).isNull();
        assertThat(applied.getPropertyValue("신체", "생명력"))
                .isEqualTo("선택 가능한 종족 중 가장 높다");
        assertThat(applied.getPropertyValue("신체", "근력 기댓값")).isEqualTo("높다");
        assertThat(applied.getVersion()).isEqualTo(1L);
        assertThat(comparisonDecisionRepository.findById(
                        candidate.getComparisonDecision().getId()
                ).orElseThrow().getRootPropertyMovesAppliedWorldSettingVersion())
                .isEqualTo(1L);

        mockMvc.perform(get(
                                "/api/v1/works/{workId}/world-settings/{settingId}",
                                work.getId(),
                                applied.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.propertyEvidence[0].scopeName").value("신체"))
                .andExpect(jsonPath("$.data.propertyEvidence[0].settingName").value("생명력"))
                .andExpect(jsonPath("$.data.propertyEvidence[0].latestEvidence.value")
                        .value("선택 가능한 종족 중 가장 높다"))
                .andExpect(jsonPath("$.data.propertyEvidence[0].history[0].evidenceSpans[0].quote")
                        .value("바바리안은 선택 가능한 종족 중 생명력이 가장 높다."));

        String recreatedRootValue = "후속 회차에서 별도 root로 다시 기록됐다";
        applied.addProperty("생명력", recreatedRootValue);
        worldSettingRepository.saveAndFlush(applied);
        WorldSettingCandidate recreatedRootEvidence = candidate(
                "바바리안",
                "생명력",
                recreatedRootValue
        );
        recreatedRootEvidence.startComparison();
        recreatedRootEvidence.completeComparison(
                applied,
                WorldSettingOperation.UPDATE,
                "생명력",
                recreatedRootValue,
                recreatedRootValue,
                "이동 뒤 별도로 재생성된 root 설정의 근거",
                objectMapper.createObjectNode().put("operation", "UPDATE"),
                LocalDateTime.now().plusMinutes(1)
        );
        recreatedRootEvidence.confirm(
                WorldSettingOperation.UPDATE,
                WorldSettingCategory.RACE,
                "바바리안",
                "생명력",
                recreatedRootValue,
                null,
                member,
                applied
        );
        candidateRepository.saveAndFlush(recreatedRootEvidence);

        MvcResult detailResult = mockMvc.perform(get(
                                "/api/v1/works/{workId}/world-settings/{settingId}",
                                work.getId(),
                                applied.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detail = objectMapper.readTree(detailResult.getResponse().getContentAsString());
        JsonNode movedLifeEvidence = propertyEvidence(detail, "신체", "생명력");
        assertThat(movedLifeEvidence.path("history")).hasSize(1);
        assertThat(movedLifeEvidence.path("history").toString())
                .doesNotContain("이동 뒤 별도로 재생성된 root 설정의 근거")
                .doesNotContain(recreatedRootValue);
    }

    @Test
    @DisplayName("확정 전에 이동할 root 값이 바뀌면 새 설정을 부분 적용하지 않고 ROW 재비교로 보낸다")
    void groupConfirmRecomparesWhenRootMoveSnapshotIsStale() throws Exception {
        RootMoveFixture fixture = rootMoveFixture();
        fixture.target().updateProperty(
                null,
                "생명력",
                null,
                "생명력",
                "최근 회차에서 하향되었다"
        );
        worldSettingRepository.saveAndFlush(fixture.target());

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/world-setting-candidates/group-confirm",
                                work.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupConfirmRequest(
                                decision(
                                        fixture.candidate(),
                                        WorldSettingOperation.ADD,
                                        "신체",
                                        "근력 기댓값",
                                        "높다"
                                )
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.context.scope").value("ROW"))
                .andExpect(jsonPath("$.error.context.reason").value("PROPERTY_CHANGED"))
                .andExpect(jsonPath("$.error.context.affectedCandidateIds[0]")
                        .value(fixture.candidate().getId().toString()));

        WorldSetting unchanged = worldSettingRepository.findById(fixture.target().getId()).orElseThrow();
        assertThat(unchanged.getPropertyValue("생명력")).isEqualTo("최근 회차에서 하향되었다");
        assertThat(unchanged.getPropertyValue("신체", "생명력")).isNull();
        assertThat(unchanged.getPropertyValue("신체", "근력 기댓값")).isNull();
        assertThat(candidateRepository.findById(fixture.candidate().getId()).orElseThrow()
                .getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.RECOMPARISON_REQUIRED);
    }

    @Test
    @DisplayName("작가가 AI 설정안을 편집하면 새 설정만 반영하고 기존 root는 이동하지 않는다")
    void groupConfirmSkipsRootMoveForAuthorEditedDecision() throws Exception {
        RootMoveFixture fixture = rootMoveFixture();

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/world-setting-candidates/group-confirm",
                                work.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupConfirmRequest(
                                decision(
                                        fixture.candidate(),
                                        WorldSettingOperation.ADD,
                                        "신체",
                                        "근력 기댓값",
                                        "매우 높다"
                                )
                        ))))
                .andExpect(status().isOk());

        WorldSetting applied = worldSettingRepository.findById(fixture.target().getId()).orElseThrow();
        assertThat(applied.getPropertyValue("생명력"))
                .isEqualTo("선택 가능한 종족 중 가장 높다");
        assertThat(applied.getPropertyValue("신체", "생명력")).isNull();
        assertThat(applied.getPropertyValue("신체", "근력 기댓값")).isEqualTo("매우 높다");
        assertThat(applied.getVersion()).isEqualTo(1L);
        assertThat(comparisonDecisionRepository.findById(
                        fixture.candidate().getComparisonDecision().getId()
                ).orElseThrow().isRootPropertyMovesDisabled())
                .isTrue();
    }

    @Test
    @DisplayName("shared 설정안의 source 하나라도 제외하면 해당 설정안의 root 이동 전체를 적용하지 않는다")
    void groupConfirmSkipsSharedDecisionRootMoveWhenOneSourceIsExcluded() throws Exception {
        RootMoveFixture fixture = rootMoveFixture();
        WorldSettingCandidate sibling = addRootMoveDecisionSibling(fixture);

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/world-setting-candidates/group-confirm",
                                work.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupConfirmRequest(
                                decision(
                                        fixture.candidate(),
                                        WorldSettingOperation.ADD,
                                        "신체",
                                        "근력 기댓값",
                                        "높다"
                                ),
                                decision(
                                        sibling,
                                        WorldSettingOperation.EXCLUDE,
                                        "신체",
                                        "근력 기댓값",
                                        "높다"
                                )
                        ))))
                .andExpect(status().isOk());

        WorldSetting applied = worldSettingRepository.findById(fixture.target().getId()).orElseThrow();
        assertThat(applied.getPropertyValue("생명력"))
                .isEqualTo("선택 가능한 종족 중 가장 높다");
        assertThat(applied.getPropertyValue("신체", "생명력")).isNull();
        assertThat(applied.getPropertyValue("신체", "근력 기댓값")).isEqualTo("높다");
        assertThat(candidateRepository.findById(fixture.candidate().getId()).orElseThrow()
                .getReviewStatus()).isEqualTo(WorldSettingReviewStatus.CONFIRMED);
        assertThat(candidateRepository.findById(sibling.getId()).orElseThrow()
                .getReviewStatus()).isEqualTo(WorldSettingReviewStatus.DISMISSED);
        assertThat(comparisonDecisionRepository.findById(
                        fixture.candidate().getComparisonDecision().getId()
                ).orElseThrow().getRootPropertyMovesAppliedWorldSettingVersion())
                .isNull();
        assertThat(comparisonDecisionRepository.findById(
                        fixture.candidate().getComparisonDecision().getId()
                ).orElseThrow().isRootPropertyMovesDisabled())
                .isTrue();

        applied.addProperty("신체", "생명력", "별도로 생성된 범위 값");
        worldSettingRepository.saveAndFlush(applied);
        MvcResult detailResult = mockMvc.perform(get(
                                "/api/v1/works/{workId}/world-settings/{settingId}",
                                work.getId(),
                                applied.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detail = objectMapper.readTree(detailResult.getResponse().getContentAsString());
        assertThat(propertyEvidence(detail, "신체", "생명력").path("history"))
                .isEmpty();
    }

    @Test
    @DisplayName("shared 설정안의 source 하나를 편집하면 모든 응답과 확정에서 root 이동을 비활성화한다")
    void updateDecisionDisablesSharedRootMoveForEverySource() throws Exception {
        RootMoveFixture fixture = rootMoveFixture();
        WorldSettingCandidate sibling = addRootMoveDecisionSibling(fixture);

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/world-setting-candidates/decisions",
                                work.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "batchId", uploadBatch.getId(),
                                "candidates", List.of(java.util.Map.of(
                                        "candidateId", fixture.candidate().getId(),
                                        "operation", "ADD",
                                        "category", "RACE",
                                        "subjectName", "바바리안",
                                        "scopeName", "신체",
                                        "settingName", "근력 기댓값",
                                        "value", "매우 높다"
                                ))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.data.candidates[0].existingRootPropertyNamesToMove.length()"
                ).value(0));

        mockMvc.perform(get(
                                "/api/v1/works/{workId}/world-setting-candidates/{candidateId}",
                                work.getId(),
                                sibling.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.existingRootPropertyNamesToMove.length()").value(0));
        assertThat(comparisonDecisionRepository.findById(
                        fixture.candidate().getComparisonDecision().getId()
                ).orElseThrow().isRootPropertyMovesDisabled())
                .isTrue();

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/world-setting-candidates/group-confirm",
                                work.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupConfirmRequest(
                                decision(
                                        fixture.candidate(),
                                        WorldSettingOperation.ADD,
                                        "신체",
                                        "근력 기댓값",
                                        "높다"
                                ),
                                decision(
                                        sibling,
                                        WorldSettingOperation.ADD,
                                        "신체",
                                        "근력 기댓값",
                                        "높다"
                                )
                        ))))
                .andExpect(status().isOk());

        WorldSetting applied = worldSettingRepository.findById(fixture.target().getId()).orElseThrow();
        assertThat(applied.getPropertyValue("생명력"))
                .isEqualTo("선택 가능한 종족 중 가장 높다");
        assertThat(applied.getPropertyValue("신체", "생명력")).isNull();
        assertThat(applied.getPropertyValue("신체", "근력 기댓값")).isEqualTo("높다");
        assertThat(comparisonDecisionRepository.findById(
                        fixture.candidate().getComparisonDecision().getId()
                ).orElseThrow().getRootPropertyMovesAppliedWorldSettingVersion())
                .isNull();
    }

    @Test
    @DisplayName("같은 대상에서 설정명이 중복되면 합치거나 중복 후보를 제외하라는 안내를 반환한다")
    void confirmGroupExplainsDuplicateSettingNames() throws Exception {
        WorldSettingCandidate first = completedAddCandidate("바바리안", "기능", "서로 대화할 수 있다");
        WorldSettingCandidate second = completedAddCandidate("바바리안", "기능", "신호를 보낼 수 있다");
        candidateRepository.saveAllAndFlush(List.of(first, second));

        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-confirm", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupConfirmRequest(
                                decision(first, WorldSettingOperation.ADD, "기능", "서로 대화할 수 있다"),
                                decision(second, WorldSettingOperation.ADD, " 기능 ", "신호를 보낼 수 있다")
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_SETTING_NAME_DUPLICATED"))
                .andExpect(jsonPath("$.message")
                        .value("같은 범위와 설정명이 여러 번 포함되어 있습니다. 내용을 하나로 합치거나 중복 후보를 제외해 주세요."));

        assertThat(worldSettingRepository.countByWorkId(work.getId())).isZero();
        assertThat(candidateRepository.findAll())
                .extracting(WorldSettingCandidate::getReviewStatus)
                .containsOnly(WorldSettingReviewStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("서로 다른 범위의 동일 설정명 후보는 한 그룹에서 각각 확정한다")
    void confirmGroupAllowsSameSettingNameInDifferentScopes() throws Exception {
        WorldSettingCandidate firstFloor = completedAddCandidate(
                "미궁",
                "1층",
                "방향별 몬스터 출몰 규칙",
                "동쪽에서 고블린이 출몰한다."
        );
        WorldSettingCandidate secondFloor = completedAddCandidate(
                "미궁",
                "2층",
                "방향별 몬스터 출몰 규칙",
                "중앙부에서 언데드가 출몰한다."
        );
        candidateRepository.saveAllAndFlush(List.of(firstFloor, secondFloor));

        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-confirm", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupConfirmRequest(
                                decision(
                                        firstFloor,
                                        WorldSettingOperation.ADD,
                                        "1층",
                                        "방향별 몬스터 출몰 규칙",
                                        "동쪽에서 고블린이 출몰한다."
                                ),
                                decision(
                                        secondFloor,
                                        WorldSettingOperation.ADD,
                                        "2층",
                                        "방향별 몬스터 출몰 규칙",
                                        "중앙부에서 언데드가 출몰한다."
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates[*].finalScopeName")
                        .value(containsInAnyOrder("1층", "2층")));

        WorldSetting applied = worldSettingRepository
                .findByWorkIdAndCategoryAndNormalizedSubjectName(
                        work.getId(), WorldSettingCategory.RACE, "미궁"
                ).orElseThrow();
        assertThat(applied.getVersion()).isZero();
        assertThat(applied.getPropertyValue("1층", "방향별 몬스터 출몰 규칙"))
                .isEqualTo("동쪽에서 고블린이 출몰한다.");
        assertThat(applied.getPropertyValue("2층", "방향별 몬스터 출몰 규칙"))
                .isEqualTo("중앙부에서 언데드가 출몰한다.");
    }

    @Test
    @DisplayName("같은 대상 그룹의 row별 분류·대상 수정안을 최종 대상별로 나누어 확정한다")
    void confirmGroupSplitsAuthorEditedRowsByFinalTarget() throws Exception {
        WorldSettingCandidate first = completedAddCandidate("구울", "출몰 규칙", "밤에 배회한다.");
        WorldSettingCandidate second = completedAddCandidate("구울", "출몰 규칙", "습지에서 배회한다.");
        candidateRepository.saveAllAndFlush(List.of(first, second));
        WorldSettingCandidateGroupConfirmRequest.Decision firstDecision =
                new WorldSettingCandidateGroupConfirmRequest.Decision(
                        first.getId(),
                        WorldSettingOperation.ADD,
                        WorldSettingCategory.RACE,
                        "구울",
                        "출몰 규칙",
                        "밤에 배회한다.",
                        false,
                        null
                );
        WorldSettingCandidateGroupConfirmRequest.Decision secondDecision =
                new WorldSettingCandidateGroupConfirmRequest.Decision(
                        second.getId(),
                        WorldSettingOperation.ADD,
                        WorldSettingCategory.MONSTER,
                        "변종 구울",
                        "출몰 규칙",
                        "습지에서 배회한다.",
                        false,
                        null
                );

        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-confirm", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupConfirmRequest(firstDecision, secondDecision))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.worldSettingId").doesNotExist())
                .andExpect(jsonPath("$.data.appliedWorldSettingVersion").doesNotExist())
                .andExpect(jsonPath("$.data.candidates[*].finalCategory")
                        .value(containsInAnyOrder("RACE", "MONSTER")))
                .andExpect(jsonPath("$.data.candidates[*].finalSubjectName")
                        .value(containsInAnyOrder("구울", "변종 구울")));

        assertThat(worldSettingRepository.countByWorkId(work.getId())).isEqualTo(2);
        assertThat(worldSettingRepository.findByWorkIdAndCategoryAndNormalizedSubjectName(
                work.getId(), WorldSettingCategory.RACE, "구울"
        ).orElseThrow().getPropertyValue("출몰 규칙")).isEqualTo("밤에 배회한다.");
        assertThat(worldSettingRepository.findByWorkIdAndCategoryAndNormalizedSubjectName(
                work.getId(), WorldSettingCategory.MONSTER, "변종 구울"
        ).orElseThrow().getPropertyValue("출몰 규칙")).isEqualTo("습지에서 배회한다.");
    }

    @Test
    @DisplayName("같은 기존 대상의 여러 설정안을 한 번에 확정하면 버전을 한 번만 증가시킨다")
    void confirmMultipleDecisionsForExistingTargetIncrementsVersionOnce() throws Exception {
        WorldSetting target = worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "바바리안",
                "특징",
                "전투에 특화된 종족"
        ));
        WorldSettingCandidate habitat = candidate("바바리안", "서식지", "혹한 지역");
        WorldSettingCandidate trait = candidate("바바리안", "특징", "강인한 신체");
        candidateRepository.saveAllAndFlush(List.of(habitat, trait));
        JsonNode resolvedTargetIds = objectMapper.createArrayNode().add(target.getId().toString());
        habitat.resolveSubject(
                WorldSettingSubjectResolutionType.EXISTING,
                "TARGET:" + target.getId(),
                target.getSubjectName(),
                resolvedTargetIds
        );
        trait.resolveSubject(
                WorldSettingSubjectResolutionType.EXISTING,
                "TARGET:" + target.getId(),
                target.getSubjectName(),
                resolvedTargetIds
        );
        WorldSettingComparisonBatch comparisonBatch = comparisonBatchRepository.saveAndFlush(
                WorldSettingComparisonBatch.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.RACE,
                        null,
                        WorldSettingSubjectResolutionType.EXISTING,
                        "TARGET:" + target.getId(),
                        target.getSubjectName(),
                        resolvedTargetIds,
                        2
                )
        );
        habitat.startComparison(comparisonBatch, "C1");
        trait.startComparison(comparisonBatch, "C2");
        candidateRepository.saveAllAndFlush(List.of(habitat, trait));
        WorldSettingComparisonDecision habitatDecision = WorldSettingComparisonDecision.create(
                comparisonBatch,
                "D1",
                target.getSubjectName(),
                target,
                null,
                null,
                WorldSettingConsolidationStatus.SINGLE,
                WorldSettingSuggestedOperation.ADD,
                null,
                null,
                "서식지",
                null,
                "혹한 지역",
                "새 설정 추가",
                objectMapper.createObjectNode().put("decisionRef", "D1")
        );
        WorldSettingComparisonDecision traitDecision = WorldSettingComparisonDecision.create(
                comparisonBatch,
                "D2",
                target.getSubjectName(),
                target,
                null,
                "특징",
                WorldSettingConsolidationStatus.SINGLE,
                WorldSettingSuggestedOperation.MERGE,
                null,
                null,
                "특징",
                "전투에 특화된 종족",
                "강인한 신체를 가진 전투 종족",
                "기존 특징 병합",
                objectMapper.createObjectNode().put("decisionRef", "D2")
        );
        comparisonDecisionRepository.saveAllAndFlush(List.of(habitatDecision, traitDecision));
        LocalDateTime comparedAt = LocalDateTime.now();
        habitat.completeComparison(habitatDecision, comparedAt);
        trait.completeComparison(traitDecision, comparedAt);
        candidateRepository.saveAllAndFlush(List.of(habitat, trait));
        comparisonSourceRepository.saveAllAndFlush(List.of(
                WorldSettingComparisonDecisionSource.create(
                        comparisonBatch,
                        habitatDecision,
                        habitat,
                        "C1",
                        0
                ),
                WorldSettingComparisonDecisionSource.create(
                        comparisonBatch,
                        traitDecision,
                        trait,
                        "C2",
                        0
                )
        ));

        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-confirm", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupConfirmRequest(
                                decision(habitat, WorldSettingOperation.ADD, "서식지", "혹한 지역"),
                                decision(
                                        trait,
                                        WorldSettingOperation.MERGE,
                                        "특징",
                                        "강인한 신체를 가진 전투 종족"
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.worldSettingId").value(target.getId().toString()))
                .andExpect(jsonPath("$.data.appliedWorldSettingVersion").value(1))
                .andExpect(jsonPath("$.data.candidates.length()").value(2));

        WorldSetting applied = worldSettingRepository.findById(target.getId()).orElseThrow();
        assertThat(applied.getVersion()).isEqualTo(1L);
        assertThat(applied.getPropertyValue("서식지")).isEqualTo("혹한 지역");
        assertThat(applied.getPropertyValue("특징")).isEqualTo("강인한 신체를 가진 전투 종족");
        assertThat(candidateRepository.findAll())
                .extracting(WorldSettingCandidate::getAppliedWorldSettingVersion)
                .containsOnly(1L);
        assertThat(comparisonDecisionRepository.count()).isEqualTo(2L);
        assertThat(habitatDecision.getId()).isNotEqualTo(traitDecision.getId());
    }

    @Test
    @DisplayName("외부에서 한 key가 바뀌면 그룹을 부분 적용하지 않고 ROW 재비교 문맥을 반환한다")
    void groupConfirmReturnsRowConflictWithoutPartialApply() throws Exception {
        WorldSetting target = worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "바바리안",
                "특징",
                "전투 종족"
        ));
        WorldSettingCandidate trait = candidate("바바리안", "특징", "강인한 신체");
        trait.startComparison();
        trait.completeComparison(
                target,
                WorldSettingOperation.MERGE,
                "특징",
                "전투 종족",
                "강인한 신체를 가진 전투 종족",
                "기존 특징 병합",
                objectMapper.createObjectNode(),
                LocalDateTime.now()
        );
        WorldSettingCandidate habitat = candidate("바바리안", "서식지", "혹한 지역");
        habitat.startComparison();
        habitat.completeComparison(
                target,
                WorldSettingOperation.ADD,
                "서식지",
                null,
                "혹한 지역",
                "새 설정 추가",
                objectMapper.createObjectNode(),
                LocalDateTime.now()
        );
        candidateRepository.saveAllAndFlush(List.of(trait, habitat));
        target.updateProperty("특징", "특징", "민첩한 전투 종족");
        worldSettingRepository.saveAndFlush(target);

        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-confirm", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupConfirmRequest(
                                decision(trait, WorldSettingOperation.MERGE, "특징", "강인한 신체를 가진 전투 종족"),
                                decision(habitat, WorldSettingOperation.ADD, "서식지", "혹한 지역")
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_RECOMPARISON_REQUIRED"))
                .andExpect(jsonPath("$.error.context.scope").value("ROW"))
                .andExpect(jsonPath("$.error.context.reason").value("PROPERTY_CHANGED"))
                .andExpect(jsonPath("$.error.context.affectedCandidateIds[0]")
                        .value(trait.getId().toString()));

        WorldSetting unchanged = worldSettingRepository.findById(target.getId()).orElseThrow();
        assertThat(unchanged.hasProperty("서식지")).isFalse();
        assertThat(candidateRepository.findById(habitat.getId()).orElseThrow().getReviewStatus())
                .isEqualTo(WorldSettingReviewStatus.PENDING_REVIEW);
        assertThat(candidateRepository.findById(trait.getId()).orElseThrow().getComparisonStatus())
                .isEqualTo(WorldSettingComparisonStatus.RECOMPARISON_REQUIRED);

        mockMvc.perform(get("/api/v1/works/{workId}/world-setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString())
                        .queryParam("reviewStatus", "PENDING_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups.content[0].recomparisonScope").value("ROW"));
    }

    @Test
    @DisplayName("신규 대상 비교 뒤 같은 대상이 먼저 생성되면 전체 row를 GROUP 재비교로 전환한다")
    void groupConfirmReturnsGroupConflictWhenTargetWasCreatedExternally() throws Exception {
        WorldSettingCandidate habitat = completedAddCandidate("바바리안", "서식지", "혹한 지역");
        WorldSettingCandidate society = completedAddCandidate("바바리안", "사회 구조", "부족 단위로 생활");
        candidateRepository.saveAllAndFlush(List.of(habitat, society));
        worldSettingRepository.saveAndFlush(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "바바리안",
                "특징",
                "전투 종족"
        ));

        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-confirm", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupConfirmRequest(
                                decision(habitat, WorldSettingOperation.ADD, "서식지", "혹한 지역")
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.context.scope").value("GROUP"))
                .andExpect(jsonPath("$.error.context.reason").value("TARGET_CREATED"))
                .andExpect(jsonPath("$.error.context.affectedCandidateIds.length()").value(2));

        assertThat(candidateRepository.findAll())
                .extracting(WorldSettingCandidate::getComparisonStatus)
                .containsOnly(WorldSettingComparisonStatus.RECOMPARISON_REQUIRED);

        mockMvc.perform(get("/api/v1/works/{workId}/world-setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString())
                        .queryParam("reviewStatus", "PENDING_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups.content[0].recomparisonScope").value("GROUP"));
    }

    @Test
    @DisplayName("같은 대상 그룹의 선택 후보를 한 요청으로 제외한다")
    void dismissCandidateGroup() throws Exception {
        WorldSettingCandidate first = completedAddCandidate("바바리안", "서식지", "혹한 지역");
        WorldSettingCandidate second = completedAddCandidate("바바리안", "특징", "강인한 신체");
        candidateRepository.saveAllAndFlush(List.of(first, second));

        WorldSettingCandidateGroupDismissRequest request = new WorldSettingCandidateGroupDismissRequest(
                uploadBatch.getId(),
                List.of(first.getId(), second.getId()),
                "이번 묶음에서는 제외"
        );
        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-dismiss", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates[0].reviewStatus").value("DISMISSED"))
                .andExpect(jsonPath("$.data.candidates[1].reviewStatus").value("DISMISSED"));

        assertThat(worldSettingRepository.countByWorkId(work.getId())).isZero();
    }

    @Test
    @DisplayName("신규 대상 ADD 후보를 확정하고 같은 요청을 중복 반영하지 않는다")
    void confirmNewSubjectIsIdempotent() throws Exception {
        WorldSettingCandidate candidate = candidate("바바리안", "서식지", "혹한 지역");
        candidate.startComparison();
        candidate.completeComparison(
                null,
                WorldSettingOperation.ADD,
                "서식지",
                null,
                "혹한 지역",
                "새 대상",
                objectMapper.createObjectNode().put("operation", "ADD"),
                LocalDateTime.now()
        );
        candidateRepository.save(candidate);

        WorldSettingCandidateConfirmRequest request = confirmRequest(
                WorldSettingOperation.ADD,
                "바바리안",
                "서식지",
                "혹한 지역"
        );

        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{candidateId}/confirm",
                        work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.appliedWorldSettingVersion").value(0));

        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{candidateId}/confirm",
                        work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.appliedWorldSettingVersion").value(0));

        assertThat(worldSettingRepository.countByWorkId(work.getId())).isEqualTo(1);
        WorldSetting applied = worldSettingRepository
                .findByWorkIdAndCategoryAndNormalizedSubjectName(
                        work.getId(), WorldSettingCategory.RACE, "바바리안"
                ).orElseThrow();
        assertThat(applied.getPropertyValue("서식지")).isEqualTo("혹한 지역");
        assertThat(applied.getVersion()).isZero();
    }

    @Test
    @DisplayName("2차 비교가 연결한 기존 대상의 정식 대상명으로 속성을 확정한다")
    void confirmUsesComparedTargetSubjectName() throws Exception {
        WorldSetting target = worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "바바리안",
                "특징",
                "전투 종족"
        ));
        WorldSettingCandidate candidate = candidate("야만인", "서식지", "혹한 지역");
        candidate.startComparison();
        candidate.completeComparison(
                target,
                WorldSettingOperation.ADD,
                "서식지",
                null,
                "혹한 지역",
                "야만인은 기존 바바리안 종족과 같은 대상",
                objectMapper.createObjectNode().put("operation", "ADD"),
                LocalDateTime.now()
        );
        candidateRepository.save(candidate);
        WorldSettingCandidateConfirmRequest request = confirmRequest(
                WorldSettingOperation.ADD,
                "바바리안",
                "서식지",
                "혹한 지역"
        );

        mockMvc.perform(get("/api/v1/works/{workId}/world-setting-candidates/{candidateId}",
                        work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subjectName").value("야만인"))
                .andExpect(jsonPath("$.data.targetSubjectName").value("바바리안"));

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{candidateId}/confirm",
                            work.getId(), candidate.getId())
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reviewStatus").value("CONFIRMED"))
                    .andExpect(jsonPath("$.data.finalSubjectName").value("바바리안"))
                    .andExpect(jsonPath("$.data.userModified").value(false));
        }

        assertThat(worldSettingRepository.countByWorkId(work.getId())).isEqualTo(1);
        WorldSetting applied = worldSettingRepository.findById(target.getId()).orElseThrow();
        assertThat(applied.getPropertyValue("서식지")).isEqualTo("혹한 지역");
    }

    @Test
    @DisplayName("같은 행의 다른 설정만 바뀌면 버전이 달라도 후보 설정을 확정한다")
    void confirmIgnoresVersionChangeFromDifferentProperty() throws Exception {
        WorldSetting target = worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "바바리안",
                "서식지",
                "온대 지역"
        ));
        WorldSettingCandidate candidate = candidate("바바리안", "서식지", "혹한 지역");
        candidate.startComparison();
        candidate.completeComparison(
                target,
                WorldSettingOperation.UPDATE,
                "서식지",
                "온대 지역",
                "혹한 지역",
                "기존 설정 수정",
                objectMapper.createObjectNode().put("operation", "UPDATE"),
                LocalDateTime.now()
        );
        candidateRepository.save(candidate);
        target.addProperty("특징", "전투 종족");
        worldSettingRepository.saveAndFlush(target);

        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{candidateId}/confirm",
                        work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest(
                                WorldSettingOperation.UPDATE,
                                "바바리안",
                                "서식지",
                                "혹한 지역"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.appliedWorldSettingVersion").value(2));

        WorldSetting applied = worldSettingRepository.findById(target.getId()).orElseThrow();
        assertThat(applied.getPropertyValue("서식지")).isEqualTo("혹한 지역");
        assertThat(applied.getPropertyValue("특징")).isEqualTo("전투 종족");
        assertThat(applied.getVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 설정이 제3의 값으로 바뀌면 409와 재비교 필요 상태를 함께 남긴다")
    void confirmMarksRecomparisonRequiredOnSamePropertyConflict() throws Exception {
        WorldSetting target = worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "바바리안",
                "서식지",
                "온대 지역"
        ));
        WorldSettingCandidate candidate = candidate("바바리안", "서식지", "혹한 지역");
        candidate.startComparison();
        candidate.completeComparison(
                target,
                WorldSettingOperation.UPDATE,
                "서식지",
                "온대 지역",
                "혹한 지역",
                "기존 설정 수정",
                objectMapper.createObjectNode().put("operation", "UPDATE"),
                LocalDateTime.now()
        );
        candidateRepository.save(candidate);
        target.updateProperty("서식지", "서식지", "사막 지역");
        worldSettingRepository.saveAndFlush(target);

        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{candidateId}/confirm",
                        work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest(
                                WorldSettingOperation.UPDATE,
                                "바바리안",
                                "서식지",
                                "혹한 지역"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_RECOMPARISON_REQUIRED"));

        WorldSettingCandidate conflicted = candidateRepository.findById(candidate.getId()).orElseThrow();
        assertThat(conflicted.getComparisonStatus())
                .isEqualTo(WorldSettingComparisonStatus.RECOMPARISON_REQUIRED);
        assertThat(worldSettingRepository.findById(target.getId()).orElseThrow().getPropertyValue("서식지"))
                .isEqualTo("사막 지역");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = WorldSettingOperation.class, names = {"UPDATE", "MERGE"})
    @DisplayName("UPDATE와 MERGE는 현재 존재하는 설정만 확정할 수 있다")
    void confirmUpdateOrMergeRequiresExistingProperty(WorldSettingOperation operation) throws Exception {
        WorldSetting target = worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "바바리안",
                "특징",
                "전투 종족"
        ));
        WorldSettingCandidate candidate = candidate("바바리안", "서식지", "혹한 지역");
        candidate.startComparison();
        candidate.completeComparison(
                target,
                operation,
                "서식지",
                null,
                "혹한 지역",
                "기존 대상의 설정 수정",
                objectMapper.createObjectNode().put("operation", operation.name()),
                LocalDateTime.now()
        );
        candidateRepository.save(candidate);

        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{candidateId}/confirm",
                        work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest(
                                operation,
                                "바바리안",
                                "서식지",
                                "혹한 지역"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_RECOMPARISON_REQUIRED"));

        assertThat(worldSettingRepository.findById(target.getId()).orElseThrow().hasProperty("서식지"))
                .isFalse();
        assertThat(candidateRepository.findById(candidate.getId()).orElseThrow().getComparisonStatus())
                .isEqualTo(WorldSettingComparisonStatus.RECOMPARISON_REQUIRED);
    }

    @Test
    @DisplayName("ADD는 현재 존재하지 않는 설정만 확정할 수 있다")
    void confirmAddRequiresMissingProperty() throws Exception {
        WorldSetting target = worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "바바리안",
                "서식지",
                "온대 지역"
        ));
        WorldSettingCandidate candidate = candidate("바바리안", "서식지", "혹한 지역");
        candidate.startComparison();
        candidate.completeComparison(
                target,
                WorldSettingOperation.ADD,
                "서식지",
                "온대 지역",
                "혹한 지역",
                "기존 대상에 설정 추가",
                objectMapper.createObjectNode().put("operation", "ADD"),
                LocalDateTime.now()
        );
        candidateRepository.save(candidate);

        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{candidateId}/confirm",
                        work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest(
                                WorldSettingOperation.ADD,
                                "바바리안",
                                "서식지",
                                "혹한 지역"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_RECOMPARISON_REQUIRED"));

        assertThat(worldSettingRepository.findById(target.getId()).orElseThrow().getPropertyValue("서식지"))
                .isEqualTo("온대 지역");
        assertThat(candidateRepository.findById(candidate.getId()).orElseThrow().getComparisonStatus())
                .isEqualTo(WorldSettingComparisonStatus.RECOMPARISON_REQUIRED);
    }

    @Test
    @DisplayName("수동으로 과거 값에 되돌리면 현재 근거는 직접 입력으로 표시하고 후보 이력은 유지한다")
    void manualRollbackDoesNotReuseOlderCandidateAsLatestEvidence() throws Exception {
        WorldSetting target = worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "바바리안",
                "서식지",
                "초원"
        ));
        WorldSettingCandidate firstCandidate = candidate("바바리안", "서식지", "설원");
        firstCandidate.startComparison();
        firstCandidate.completeComparison(
                target,
                WorldSettingOperation.UPDATE,
                "서식지",
                "초원",
                "설원",
                "첫 번째 후보",
                objectMapper.createObjectNode().put("operation", "UPDATE"),
                LocalDateTime.now()
        );
        candidateRepository.save(firstCandidate);
        confirm(firstCandidate, WorldSettingOperation.UPDATE, "설원");

        WorldSettingCandidate secondCandidate = candidate("바바리안", "서식지", "사막");
        secondCandidate.startComparison();
        secondCandidate.completeComparison(
                target,
                WorldSettingOperation.UPDATE,
                "서식지",
                "설원",
                "사막",
                "두 번째 후보",
                objectMapper.createObjectNode().put("operation", "UPDATE"),
                LocalDateTime.now().plusSeconds(1)
        );
        candidateRepository.save(secondCandidate);
        confirm(secondCandidate, WorldSettingOperation.UPDATE, "사막");

        WorldSetting manuallyRolledBack = worldSettingRepository.findById(target.getId()).orElseThrow();
        manuallyRolledBack.updateProperty("서식지", "서식지", "설원");
        worldSettingRepository.saveAndFlush(manuallyRolledBack);

        mockMvc.perform(get("/api/v1/works/{workId}/world-settings/{settingId}",
                        work.getId(), target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.properties[0].settingName").value("서식지"))
                .andExpect(jsonPath("$.data.properties[0].value").value("설원"))
                .andExpect(jsonPath("$.data.propertyEvidence[0].latestEvidence").doesNotExist())
                .andExpect(jsonPath("$.data.propertyEvidence[0].history.length()").value(2))
                .andExpect(jsonPath("$.data.propertyEvidence[0].history[0].value").value("사막"))
                .andExpect(jsonPath("$.data.propertyEvidence[0].history[1].value").value("설원"));
    }

    @Test
    @DisplayName("CONFLICT 비교 완료는 반영하지 않고 사용자가 정리한 값만 확정한다")
    void conflictRequiresUserResolutionBeforeConfirm() throws Exception {
        WorldSettingCandidate candidate = candidate(
                "메시지 스톤",
                "통신 반경",
                "약 300m\n약 3km"
        );
        candidate.startComparison();
        candidate.completeComparison(
                null,
                WorldSettingConsolidationStatus.CONFLICT,
                WorldSettingOperation.ADD,
                "통신 반경",
                null,
                "약 300m\n약 3km",
                "원문마다 통신 반경이 달라 최종값 확인이 필요하다.",
                objectMapper.createObjectNode().put("consolidationStatus", "CONFLICT"),
                LocalDateTime.now()
        );
        candidateRepository.save(candidate);

        assertThat(worldSettingRepository.count()).isZero();
        assertThat(candidateRepository.findById(candidate.getId()).orElseThrow())
                .extracting(
                        WorldSettingCandidate::getComparisonStatus,
                        WorldSettingCandidate::getReviewStatus
                )
                .containsExactly(
                        WorldSettingComparisonStatus.COMPLETED,
                        WorldSettingReviewStatus.PENDING_REVIEW
                );

        mockMvc.perform(get("/api/v1/works/{workId}/world-setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conflictCandidateCount").value(1));

        WorldSettingCandidateGroupConfirmRequest.Decision unresolved =
                new WorldSettingCandidateGroupConfirmRequest.Decision(
                        candidate.getId(),
                        WorldSettingOperation.ADD,
                        WorldSettingCategory.RACE,
                        "메시지 스톤",
                        "통신 반경",
                        "약 300m",
                        false,
                        null
                );
        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-confirm", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupConfirmRequest(unresolved))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_CONFLICT_UNRESOLVED"));

        assertThat(worldSettingRepository.count()).isZero();
        assertThat(candidateRepository.findById(candidate.getId()).orElseThrow().getReviewStatus())
                .isEqualTo(WorldSettingReviewStatus.PENDING_REVIEW);

        WorldSettingCandidateGroupConfirmRequest.Decision resolved =
                new WorldSettingCandidateGroupConfirmRequest.Decision(
                        candidate.getId(),
                        WorldSettingOperation.ADD,
                        WorldSettingCategory.RACE,
                        "메시지 스톤",
                        "통신 반경",
                        "약 300m",
                        true,
                        null
                );
        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-confirm", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupConfirmRequest(resolved))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates[0].consolidationStatus").value("CONFLICT"))
                .andExpect(jsonPath("$.data.candidates[0].finalValue").value("약 300m"));

        WorldSetting applied = worldSettingRepository
                .findByWorkIdAndCategoryAndNormalizedSubjectName(
                        work.getId(),
                        WorldSettingCategory.RACE,
                        "메시지 스톤"
                )
                .orElseThrow();
        assertThat(applied.getPropertyValue("통신 반경")).isEqualTo("약 300m");
        assertThat(candidateRepository.findById(candidate.getId()).orElseThrow().getReviewStatus())
                .isEqualTo(WorldSettingReviewStatus.CONFIRMED);

        mockMvc.perform(get("/api/v1/works/{workId}/world-setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conflictCandidateCount").value(0));
    }

    @Test
    @DisplayName("작가가 수정한 ADD 경로가 이미 있으면 재비교 없이 명확한 충돌을 반환한다")
    void authorEditedAddRejectsExistingPathWithoutRecomparison() throws Exception {
        WorldSetting target = worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "미궁",
                "폐쇄 시점",
                "100년 전"
        ));
        WorldSettingCandidate candidate = candidate("미궁", "출몰 규칙", "동쪽에서 고블린이 출몰한다.");
        candidate.startComparison();
        candidate.completeComparison(
                target,
                WorldSettingOperation.ADD,
                "출몰 규칙",
                null,
                "동쪽에서 고블린이 출몰한다.",
                "새 설정 추가",
                objectMapper.createObjectNode().put("operation", "ADD"),
                LocalDateTime.now()
        );
        candidateRepository.saveAndFlush(candidate);

        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-confirm", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupConfirmRequest(
                                decision(candidate, WorldSettingOperation.ADD, "폐쇄 시점", "90년 전")
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_ADD_PATH_DUPLICATED"))
                .andExpect(jsonPath("$.message")
                        .value("추가하려는 범위와 설정명이 이미 존재합니다. 설정명을 바꾸거나 수정·병합 방식을 선택해 주세요."));

        assertThat(analysisJobRepository.findAll())
                .filteredOn(job -> job.getJobType() == AnalysisJobType.WORLD_SETTING_COMPARISON)
                .isEmpty();
        assertThat(candidateRepository.findById(candidate.getId()).orElseThrow().getComparisonStatus())
                .isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(worldSettingRepository.findById(target.getId()).orElseThrow().getPropertyValue("폐쇄 시점"))
                .isEqualTo("100년 전");
    }

    @Test
    @DisplayName("작가가 수정한 범위 경로는 LLM 재비교 없이 직접 병합한다")
    void authorEditedScopePathAppliesWithoutRecomparison() throws Exception {
        WorldSetting target = worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "미궁",
                "폐쇄 시점",
                "100년 전"
        ));
        WorldSettingCandidate candidate = candidate("미궁", "출몰 규칙", "동쪽에서 고블린이 출몰한다.");
        candidate.startComparison();
        candidate.completeComparison(
                target,
                WorldSettingOperation.ADD,
                "출몰 규칙",
                null,
                "동쪽에서 고블린이 출몰한다.",
                "새 설정 추가",
                objectMapper.createObjectNode().put("operation", "ADD"),
                LocalDateTime.now()
        );
        candidateRepository.saveAndFlush(candidate);

        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-confirm", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupConfirmRequest(
                                decision(
                                        candidate,
                                        WorldSettingOperation.ADD,
                                        "1층",
                                        "출몰 규칙",
                                        "동쪽에서 고블린이 출몰한다."
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates[0].comparisonStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.candidates[0].finalScopeName").value("1층"));

        assertThat(analysisJobRepository.findAll())
                .filteredOn(job -> job.getJobType() == AnalysisJobType.WORLD_SETTING_COMPARISON)
                .isEmpty();
        assertThat(worldSettingRepository.findById(target.getId()).orElseThrow()
                .getPropertyValue("1층", "출몰 규칙"))
                .isEqualTo("동쪽에서 고블린이 출몰한다.");
    }

    @Test
    @DisplayName("제외는 멱등이고 이후 확정 전환은 충돌한다")
    void dismissIsIdempotentAndCannotBeConfirmed() throws Exception {
        WorldSettingCandidate candidate = candidate("바바리안", "등장", "골목에 나타남");
        candidateRepository.save(candidate);
        WorldSettingCandidateDismissRequest dismissRequest = new WorldSettingCandidateDismissRequest("단발 사건");

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{candidateId}/dismiss",
                            work.getId(), candidate.getId())
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dismissRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reviewStatus").value("DISMISSED"))
                    .andExpect(jsonPath("$.data.finalOperation").value("EXCLUDE"));
        }

        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{candidateId}/confirm",
                        work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest(
                                WorldSettingOperation.ADD,
                                "바바리안",
                                "등장",
                                "골목에 나타남"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT"));

        assertThat(candidateRepository.findById(candidate.getId()).orElseThrow().getReviewStatus())
                .isEqualTo(WorldSettingReviewStatus.DISMISSED);
    }

    @Test
    @DisplayName("범위 확인 필요 후보는 기존 경로를 보여주고 작가가 경로를 선택한 뒤에만 반영한다")
    void scopeReviewRequiresAuthorDecisionBeforeApplyingExistingPath() throws Exception {
        WorldSetting target = worldSettingRepository.saveAndFlush(WorldSetting.create(
                work,
                WorldSettingCategory.LOCATION,
                "미궁",
                "1층",
                "광원",
                "벽에 붙은 수정들이 광원 역할을 한다."
        ));
        long initialVersion = target.getVersion();
        WorldSettingCandidate candidate = WorldSettingCandidate.create(
                work,
                episode,
                analysisJob,
                WorldSettingCategory.LOCATION,
                "미궁",
                null,
                "광원",
                "벽과 천장의 수정들이 주변을 밝힌다.",
                objectMapper.createArrayNode().add(
                        objectMapper.createObjectNode().put("quote", "수정들이 주변을 환하게 밝혔다.")
                ),
                new BigDecimal("0.9500"),
                objectMapper.createObjectNode().put("settingName", "광원")
        );
        candidate.startComparison();
        candidate.completeComparison(
                target,
                WorldSettingConsolidationStatus.SINGLE,
                WorldSettingSuggestedOperation.REVIEW_REQUIRED,
                "1층",
                "광원",
                WorldSettingComparisonReviewReason.SCOPE_UNRESOLVED,
                null,
                "광원",
                "벽에 붙은 수정들이 광원 역할을 한다.",
                "벽과 천장의 수정들이 주변을 밝힌다.",
                "후보의 적용 범위 확인이 필요합니다.",
                objectMapper.createObjectNode().put("operation", "REVIEW_REQUIRED"),
                LocalDateTime.now()
        );
        candidateRepository.saveAndFlush(candidate);

        mockMvc.perform(get("/api/v1/works/{workId}/world-setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString())
                        .queryParam("operation", "REVIEW_REQUIRED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingCandidateCount").value(1))
                .andExpect(jsonPath("$.data.groups.content[0].reviewRequiredCount").value(1))
                .andExpect(jsonPath("$.data.groups.content[0].candidates[0].comparisonStatus")
                        .value("COMPLETED"))
                .andExpect(jsonPath("$.data.groups.content[0].candidates[0].reviewStatus")
                        .value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.groups.content[0].candidates[0].suggestedOperation")
                        .value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.data.groups.content[0].candidates[0].comparisonReviewReason")
                        .value("SCOPE_UNRESOLVED"))
                .andExpect(jsonPath("$.data.groups.content[0].candidates[0].matchedScopeName")
                        .value("1층"))
                .andExpect(jsonPath("$.data.groups.content[0].candidates[0].matchedPropertyName")
                        .value("광원"))
                .andExpect(jsonPath("$.data.groups.content[0].candidates[0].proposedScopeName")
                        .doesNotExist());

        assertThat(worldSettingRepository.findById(target.getId()).orElseThrow().getVersion())
                .isEqualTo(initialVersion);

        String unresolvedRootAddJson = """
                {
                  "batchId": "%s",
                  "candidates": [{
                    "candidateId": "%s",
                    "operation": "ADD",
                    "category": "LOCATION",
                    "subjectName": "미궁",
                    "settingName": "광원",
                    "value": "벽과 천장의 수정들이 주변을 밝힌다."
                  }]
                }
                """.formatted(uploadBatch.getId(), candidate.getId());
        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-confirm", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unresolvedRootAddJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_OPERATION_INVALID"));

        WorldSettingCandidate unresolved = candidateRepository.findById(candidate.getId()).orElseThrow();
        assertThat(unresolved.getFinalOperation()).isNull();
        assertThat(unresolved.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.PENDING_REVIEW);
        assertThat(worldSettingRepository.findById(target.getId()).orElseThrow().getVersion())
                .isEqualTo(initialVersion);

        String decisionJson = """
                {
                  "batchId": "%s",
                  "candidates": [{
                    "candidateId": "%s",
                    "operation": "UPDATE",
                    "category": "LOCATION",
                    "subjectName": "미궁",
                    "scopeName": "1층",
                    "settingName": "광원",
                    "value": "벽과 천장의 수정들이 주변을 밝힌다."
                  }]
                }
                """.formatted(uploadBatch.getId(), candidate.getId());
        mockMvc.perform(patch("/api/v1/works/{workId}/world-setting-candidates/decisions", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates[0].comparisonStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.candidates[0].reviewStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.candidates[0].finalOperation").value("UPDATE"))
                .andExpect(jsonPath("$.data.candidates[0].finalScopeName").value("1층"));

        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-confirm", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unresolvedRootAddJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_OPERATION_INVALID"));

        WorldSettingCandidate unresolvedMismatch =
                candidateRepository.findById(candidate.getId()).orElseThrow();
        assertThat(unresolvedMismatch.getFinalOperation()).isEqualTo(WorldSettingOperation.UPDATE);
        assertThat(unresolvedMismatch.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.PENDING_REVIEW);
        assertThat(worldSettingRepository.findById(target.getId()).orElseThrow().getVersion())
                .isEqualTo(initialVersion);

        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/group-confirm", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates[0].reviewStatus").value("CONFIRMED"));

        WorldSetting applied = worldSettingRepository.findById(target.getId()).orElseThrow();
        assertThat(applied.getVersion()).isEqualTo(initialVersion + 1);
        assertThat(applied.getPropertyValue("1층", "광원"))
                .isEqualTo("벽과 천장의 수정들이 주변을 밝힌다.");
        assertThat(applied.getPropertyValue(null, "광원")).isNull();
    }

    private WorldSettingCandidate candidate(String subjectName, String settingName, String value) {
        return candidate(subjectName, null, settingName, value);
    }

    private WorldSettingCandidate candidate(
            String subjectName,
            String scopeName,
            String settingName,
            String value
    ) {
        return WorldSettingCandidate.create(
                work,
                episode,
                analysisJob,
                WorldSettingCategory.RACE,
                subjectName,
                scopeName,
                settingName,
                value,
                objectMapper.createArrayNode().add(
                        objectMapper.createObjectNode()
                                .put("quote", "%s은 %s다.".formatted(subjectName, value))
                                .put("startOffset", 10)
                                .put("endOffset", 30)
                ),
                new BigDecimal("0.9500"),
                objectMapper.createObjectNode().put("subjectName", subjectName)
        );
    }

    private WorldSettingCandidate completedAddCandidate(
            String subjectName,
            String settingName,
            String value
    ) {
        return completedAddCandidate(subjectName, null, settingName, value);
    }

    private WorldSettingCandidate completedAddCandidate(
            String subjectName,
            String scopeName,
            String settingName,
            String value
    ) {
        WorldSettingCandidate candidate = candidate(subjectName, scopeName, settingName, value);
        candidate.startComparison();
        candidate.completeComparison(
                null,
                WorldSettingConsolidationStatus.SINGLE,
                WorldSettingOperation.ADD,
                scopeName,
                settingName,
                null,
                value,
                "새 대상 또는 설정 추가",
                objectMapper.createObjectNode().put("operation", "ADD"),
                LocalDateTime.now()
        );
        return candidate;
    }

    private WorldSettingCandidate completedAddCandidate(
            WorldSettingCategory category,
            String subjectName,
            String settingName,
            String value
    ) {
        WorldSettingCandidate candidate = WorldSettingCandidate.create(
                work,
                episode,
                analysisJob,
                category,
                subjectName,
                settingName,
                value,
                objectMapper.createArrayNode().add(
                        objectMapper.createObjectNode().put("quote", "%s은 %s다.".formatted(subjectName, value))
                ),
                new BigDecimal("0.9500"),
                objectMapper.createObjectNode().put("subjectName", subjectName)
        );
        candidate.startComparison();
        candidate.completeComparison(
                null,
                WorldSettingConsolidationStatus.SINGLE,
                WorldSettingOperation.ADD,
                null,
                settingName,
                null,
                value,
                "새 대상 또는 설정 추가",
                objectMapper.createObjectNode().put("operation", "ADD"),
                LocalDateTime.now()
        );
        return candidate;
    }

    private RootMoveFixture rootMoveFixture() {
        String lifeValue = "선택 가능한 종족 중 가장 높다";
        WorldSetting target = worldSettingRepository.saveAndFlush(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "바바리안",
                "생명력",
                lifeValue
        ));
        WorldSettingCandidate historicalLife = WorldSettingCandidate.create(
                work,
                episode,
                analysisJob,
                WorldSettingCategory.RACE,
                "바바리안",
                "생명력",
                lifeValue,
                objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                        .put("quote", "바바리안은 선택 가능한 종족 중 생명력이 가장 높다.")),
                new BigDecimal("0.9500"),
                null
        );
        historicalLife.startComparison();
        historicalLife.completeComparison(
                target,
                WorldSettingOperation.UPDATE,
                "생명력",
                lifeValue,
                lifeValue,
                "기존 root 설정의 원문 근거",
                objectMapper.createObjectNode().put("operation", "UPDATE"),
                LocalDateTime.now().minusMinutes(1)
        );
        historicalLife.confirm(
                WorldSettingOperation.UPDATE,
                WorldSettingCategory.RACE,
                "바바리안",
                "생명력",
                lifeValue,
                null,
                member,
                target
        );
        candidateRepository.saveAndFlush(historicalLife);

        WorldSettingCandidate candidate = candidate(
                "바바리안",
                "근력 기댓값",
                "높다"
        );
        candidateRepository.saveAndFlush(candidate);
        JsonNode resolvedTargetIds = objectMapper.createArrayNode()
                .add(target.getId().toString());
        candidate.resolveSubject(
                WorldSettingSubjectResolutionType.EXISTING,
                "TARGET:" + target.getId(),
                target.getSubjectName(),
                resolvedTargetIds
        );
        WorldSettingComparisonBatch comparisonBatch = comparisonBatchRepository.saveAndFlush(
                WorldSettingComparisonBatch.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.RACE,
                        null,
                        WorldSettingSubjectResolutionType.EXISTING,
                        "TARGET:" + target.getId(),
                        target.getSubjectName(),
                        resolvedTargetIds,
                        1
                )
        );
        candidate.startComparison(comparisonBatch, "C1");
        candidateRepository.saveAndFlush(candidate);
        WorldSettingComparisonDecision comparisonDecision = comparisonDecisionRepository.saveAndFlush(
                WorldSettingComparisonDecision.create(
                        comparisonBatch,
                        "D1",
                        "바바리안",
                        target,
                        null,
                        null,
                        WorldSettingConsolidationStatus.SINGLE,
                        WorldSettingSuggestedOperation.ADD,
                        null,
                        "신체",
                        "근력 기댓값",
                        null,
                        "높다",
                        "두 신체 관련 설정을 공통 범위로 정리한다.",
                        List.of(new WorldSettingComparisonDecision.ExistingRootPropertyMoveSnapshot(
                                "생명력",
                                lifeValue
                        )),
                        objectMapper.createObjectNode().put("decisionRef", "D1")
                )
        );
        candidate.completeComparison(comparisonDecision, LocalDateTime.now());
        candidateRepository.saveAndFlush(candidate);
        comparisonSourceRepository.saveAndFlush(WorldSettingComparisonDecisionSource.create(
                comparisonBatch,
                comparisonDecision,
                candidate,
                "C1",
                0
        ));
        return new RootMoveFixture(target, candidate);
    }

    private WorldSettingCandidate addRootMoveDecisionSibling(RootMoveFixture fixture) {
        WorldSettingCandidate sibling = candidate(
                "바바리안",
                "근력 보조 근거",
                "전사 평균보다 높다"
        );
        candidateRepository.saveAndFlush(sibling);
        JsonNode resolvedTargetIds = objectMapper.createArrayNode()
                .add(fixture.target().getId().toString());
        sibling.resolveSubject(
                WorldSettingSubjectResolutionType.EXISTING,
                "TARGET:" + fixture.target().getId(),
                fixture.target().getSubjectName(),
                resolvedTargetIds
        );
        sibling.startComparison(fixture.candidate().getComparisonBatch(), "C2");
        sibling.completeComparison(
                fixture.candidate().getComparisonDecision(),
                LocalDateTime.now()
        );
        candidateRepository.saveAndFlush(sibling);
        comparisonSourceRepository.saveAndFlush(WorldSettingComparisonDecisionSource.create(
                fixture.candidate().getComparisonBatch(),
                fixture.candidate().getComparisonDecision(),
                sibling,
                "C2",
                1
        ));
        return sibling;
    }

    private JsonNode propertyEvidence(
            JsonNode response,
            String scopeName,
            String settingName
    ) {
        for (JsonNode evidence : response.at("/data/propertyEvidence")) {
            if (scopeName.equals(evidence.path("scopeName").asText())
                    && settingName.equals(evidence.path("settingName").asText())) {
                return evidence;
            }
        }
        throw new AssertionError("Property evidence not found: " + scopeName + " / " + settingName);
    }

    private WorldSettingCandidateGroupConfirmRequest.Decision decision(
            WorldSettingCandidate candidate,
            WorldSettingOperation operation,
            String settingName,
            String value
    ) {
        return decision(candidate, operation, null, settingName, value);
    }

    private WorldSettingCandidateGroupConfirmRequest.Decision decision(
            WorldSettingCandidate candidate,
            WorldSettingOperation operation,
            String scopeName,
            String settingName,
            String value
    ) {
        return new WorldSettingCandidateGroupConfirmRequest.Decision(
                candidate.getId(),
                operation,
                WorldSettingCategory.RACE,
                candidate.getSubjectName(),
                scopeName,
                settingName,
                value,
                false,
                null
        );
    }

    private WorldSettingCandidateGroupConfirmRequest groupConfirmRequest(
            WorldSettingCandidateGroupConfirmRequest.Decision... decisions
    ) {
        return new WorldSettingCandidateGroupConfirmRequest(uploadBatch.getId(), List.of(decisions));
    }

    private WorldSettingCandidateConfirmRequest confirmRequest(
            WorldSettingOperation operation,
            String subjectName,
            String settingName,
            String value
    ) {
        return new WorldSettingCandidateConfirmRequest(
                operation,
                WorldSettingCategory.RACE,
                subjectName,
                settingName,
                value,
                false,
                null
        );
    }

    private void confirm(
            WorldSettingCandidate candidate,
            WorldSettingOperation operation,
            String value
    ) throws Exception {
        mockMvc.perform(post("/api/v1/works/{workId}/world-setting-candidates/{candidateId}/confirm",
                        work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest(
                                operation,
                                "바바리안",
                                "서식지",
                                value
                        ))))
                .andExpect(status().isOk());
    }

    private void clearData() {
        analysisJobRepository.deleteAll(analysisJobRepository.findAll().stream()
                .filter(job -> job.getJobType() == AnalysisJobType.WORLD_SETTING_COMPARISON
                        || job.getJobType() == AnalysisJobType.CHARACTER_FACT_COMPARISON)
                .toList());
        analysisJobRepository.flush();
        candidateRepository.deleteAll();
        worldSettingRepository.deleteAll();
        analysisJobRepository.deleteAll();
        aiTokenAccountRepository.deleteAll();
        episodeRepository.deleteAll();
        uploadFileRepository.deleteAll();
        uploadBatchRepository.deleteAll();
        workRepository.deleteAll();
        memberRepository.deleteAll();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record RootMoveFixture(
            WorldSetting target,
            WorldSettingCandidate candidate
    ) {
    }
}
