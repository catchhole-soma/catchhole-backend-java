package org.monitoring.catchholebackend.domain.worldsetting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
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
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingConsolidationStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
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
    private WorldSettingRepository worldSettingRepository;

    @Autowired
    private WorldSettingCandidateRepository candidateRepository;

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

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-comparisons/claim-next",
                                comparisonJobId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(SecurityConstant.WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateId").value(candidate.getId().toString()));
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
    @DisplayName("기존 대상의 ADD와 MERGE key를 한 그룹으로 확정하면 버전을 한 번만 증가시킨다")
    void confirmExistingTargetGroupIncrementsVersionOnce() throws Exception {
        WorldSetting target = worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "바바리안",
                "특징",
                "전투에 특화된 종족"
        ));
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
        WorldSettingCandidate trait = candidate("바바리안", "특징", "강인한 신체");
        trait.startComparison();
        trait.completeComparison(
                target,
                WorldSettingOperation.MERGE,
                "특징",
                "전투에 특화된 종족",
                "강인한 신체를 가진 전투 종족",
                "기존 특징 병합",
                objectMapper.createObjectNode(),
                LocalDateTime.now()
        );
        candidateRepository.saveAllAndFlush(List.of(habitat, trait));

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
    @DisplayName("서로 다른 원문 값을 사용자가 정리하지 않으면 반영하지 않는다")
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
        episodeRepository.deleteAll();
        uploadFileRepository.deleteAll();
        uploadBatchRepository.deleteAll();
        workRepository.deleteAll();
        memberRepository.deleteAll();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
