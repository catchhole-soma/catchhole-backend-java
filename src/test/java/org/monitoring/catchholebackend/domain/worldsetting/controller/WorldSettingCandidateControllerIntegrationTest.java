package org.monitoring.catchholebackend.domain.worldsetting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
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
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateConfirmRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDismissRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
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
@DisplayName("세계관 설정 후보 API 통합 테스트")
class WorldSettingCandidateControllerIntegrationTest {

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
    @DisplayName("묶음 전체 집계와 세계관 분류·제안 작업 필터를 분리해 조회한다")
    void getCandidatesReturnsBatchCountsAndFilteredPage() throws Exception {
        WorldSettingCandidate completed = candidate("바바리안", "서식지", "혹한 지역");
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
                .andExpect(jsonPath("$.data.candidates.totalElements").value(1))
                .andExpect(jsonPath("$.data.candidates.content[0].suggestedOperation").value("ADD"));
    }

    @Test
    @DisplayName("신규 대상 ADD 후보를 확정하고 같은 요청을 중복 반영하지 않는다")
    void confirmNewSubjectIsIdempotent() throws Exception {
        WorldSettingCandidate candidate = candidate("바바리안", "서식지", "혹한 지역");
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
                .andExpect(jsonPath("$.data.properties.서식지").value("설원"))
                .andExpect(jsonPath("$.data.propertyEvidence[0].latestEvidence").doesNotExist())
                .andExpect(jsonPath("$.data.propertyEvidence[0].history.length()").value(2))
                .andExpect(jsonPath("$.data.propertyEvidence[0].history[0].value").value("사막"))
                .andExpect(jsonPath("$.data.propertyEvidence[0].history[1].value").value("설원"));
    }

    @Test
    @DisplayName("후보 분류·대상·설정명 수정은 비교 제안을 비우고 대기 상태로 돌린다")
    void updateCandidateRequestsRecomparison() throws Exception {
        WorldSettingCandidate candidate = candidate("바바리안", "서식지", "혹한 지역");
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

        mockMvc.perform(patch("/api/v1/works/{workId}/world-setting-candidates/{candidateId}",
                        work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorldSettingCandidateUpdateRequest(
                                WorldSettingCategory.LOCATION,
                                "북부 설원",
                                "기후"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("LOCATION"))
                .andExpect(jsonPath("$.data.subjectName").value("북부 설원"))
                .andExpect(jsonPath("$.data.settingName").value("기후"))
                .andExpect(jsonPath("$.data.comparisonStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.suggestedOperation").doesNotExist())
                .andExpect(jsonPath("$.data.proposedValue").doesNotExist());
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
        return WorldSettingCandidate.create(
                work,
                episode,
                analysisJob,
                WorldSettingCategory.RACE,
                subjectName,
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
