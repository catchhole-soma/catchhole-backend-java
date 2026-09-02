package org.monitoring.catchholebackend.domain.worldsetting.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonDecisionRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonValidationReason;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation;
import org.monitoring.catchholebackend.global.config.security.SecurityConstant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("세계관 설정 Worker 내부 API 통합 테스트")
class WorldSettingWorkerControllerIntegrationTest {

    private static final String INTERNAL_API_KEY = "local-development-internal-api-key";
    private static final String WORKER_LEASE_TOKEN_HEADER =
            SecurityConstant.WORKER_LEASE_TOKEN_HEADER;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WorkRepository workRepository;

    @Autowired
    private UploadBatchRepository uploadBatchRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private WorldSettingRepository worldSettingRepository;

    @Autowired
    private WorldSettingCandidateRepository candidateRepository;

    @Autowired
    private WorldSettingComparisonDecisionRepository comparisonDecisionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Work work;
    private Episode episode;
    private AnalysisJob analysisJob;
    private UUID leaseToken;

    @BeforeEach
    void setUp() {
        clearData();
        Member member = memberRepository.save(Member.register(
                "world-worker@example.com",
                "encoded-password",
                "01077778888",
                "Worker 작가"
        ));
        work = workRepository.save(Work.create(member, "설원 전기", WorkGenre.FANTASY, "Worker 테스트"));
        UploadBatch batch = uploadBatchRepository.save(UploadBatch.create(
                work,
                member,
                UploadType.SINGLE_EPISODE,
                UploadSourceType.FILE
        ));
        episode = episodeRepository.save(Episode.create(
                work,
                null,
                1,
                "1화",
                "works/%s/episodes/1.txt".formatted(work.getId()),
                "v1",
                "hash-1",
                100
        ));
        analysisJob = AnalysisJob.create(work, batch, episode, AnalysisJobType.SETTING_EXTRACTION);
        analysisJob.updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_CANDIDATES_SAVED);
        leaseToken = analysisJob.claim(
                "gpt-5.6-terra",
                "WORLD_SETTING_EXTRACTION",
                LocalDateTime.now().plusMinutes(5)
        );
        analysisJob = analysisJobRepository.save(analysisJob);
    }

    @AfterEach
    void tearDown() {
        clearData();
    }

    @Test
    @DisplayName("후보 게시부터 문맥 비교와 Job 완료까지 lease와 version 계약을 지킨다")
    void publishesComparesAndCompletesWorldSettingCandidate() throws Exception {
        WorldSetting target = worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.LOCATION,
                "미궁",
                "1층",
                "방향별 몬스터 출몰 규칙",
                "동쪽에서 고블린이 출몰한다."
        ));

        MvcResult publishResult = mockMvc.perform(put(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "candidates": [{
                                    "category": "LOCATION",
                                    "subjectName": "미궁",
                                    "scopeName": "1층",
                                    "settingName": "방향별 몬스터 출몰 규칙",
                                    "extractedValue": "방향마다 출몰 몬스터가 달라진다.",
                                    "evidenceSpans": [{
                                      "quote": "1층은 방향마다 출몰하는 몬스터가 바뀐다.",
                                      "startOffset": 10,
                                      "endOffset": 25
                                    }],
                                    "extractionConfidence": 0.95,
                                    "rawExtractionJson": {"confidence": 0.95}
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].category").value("LOCATION"))
                .andExpect(jsonPath("$.data[0].scopeName").value("1층"))
                .andReturn();
        JsonNode publishBody = objectMapper.readTree(publishResult.getResponse().getContentAsString());
        UUID candidateId = UUID.fromString(publishBody.at("/data/0/candidateId").asText());

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-comparisons/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateId").value(candidateId.toString()));

        mockMvc.perform(get(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-subjects",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .param("category", "LOCATION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subjects[0].worldSettingId").value(target.getId().toString()));

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates/{candidateId}/comparison-context",
                                analysisJob.getId(),
                                candidateId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetWorldSettingIds":["%s"]}
                                """.formatted(target.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exactTargetWorldSettingId").value(target.getId().toString()))
                .andExpect(jsonPath("$.data.targets[0].properties[0].scopeName").value("1층"))
                .andExpect(jsonPath("$.data.targets[0].properties[0].settingName")
                        .value("방향별 몬스터 출몰 규칙"))
                .andExpect(jsonPath("$.data.targets[0].properties[0].value")
                        .value("동쪽에서 고블린이 출몰한다."));

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates/{candidateId}/comparison-complete",
                                analysisJob.getId(),
                                candidateId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetWorldSettingId": "%s",
                                  "matchedScopeName": "1층",
                                  "matchedPropertyName": "방향별 몬스터 출몰 규칙",
                                  "consolidationStatus": "SINGLE",
                                  "suggestedOperation": "UPDATE",
                                  "proposedScopeName": "1층",
                                  "proposedSettingName": "방향별 몬스터 출몰 규칙",
                                  "proposedValue": "방향마다 출몰 몬스터가 달라진다.",
                                  "comparisonReason": "새 근거가 기존 값을 대체한다.",
                                  "exactTargetWorldSettingId": "%s",
                                  "contextVersions": [{
                                    "worldSettingId": "%s",
                                    "version": 0
                                  }],
                                  "rawComparisonJson": {"operation": "UPDATE"}
                                }
                                """.formatted(target.getId(), target.getId(), target.getId())))
                .andExpect(status().isOk());

        WorldSettingCandidate completedCandidate = candidateRepository.findById(candidateId).orElseThrow();
        assertThat(completedCandidate.getComparisonStatus())
                .isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(completedCandidate.getScopeName()).isEqualTo("1층");
        assertThat(completedCandidate.getProposedScopeName()).isEqualTo("1층");
        assertThat(completedCandidate.getSuggestedOperation()).isEqualTo(WorldSettingSuggestedOperation.UPDATE);
        assertThat(completedCandidate.getBeforeValue()).isEqualTo("동쪽에서 고블린이 출몰한다.");
        assertThat(completedCandidate.getBaseWorldSettingVersion()).isZero();

        mockMvc.perform(patch("/api/internal/v1/analysis-jobs/{analysisJobId}/progress", analysisJob.getId())
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentStep": "WORLD_SETTING_COMPARISON",
                                  "episodeStatus": "ANALYZING",
                                  "checkpointStage": "WORLD_COMPARISONS_FINISHED"
                                }
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/internal/v1/analysis-jobs/{analysisJobId}/complete", analysisJob.getId())
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        assertThat(analysisJobRepository.findById(analysisJob.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("원자 주체 해소 상한을 넘는 세계관 후보 게시는 거절한다")
    void rejectsCandidatePublicationBeyondSubjectResolutionLimit() throws Exception {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int index = 0; index < 501; index++) {
            candidates.add(Map.of(
                    "category", "LOCATION",
                    "subjectName", "미궁 " + index,
                    "settingName", "설정 " + index,
                    "extractedValue", "값 " + index,
                    "evidenceSpans", List.of(Map.of("quote", "근거 " + index)),
                    "extractionConfidence", 0.95
            ));
        }

        mockMvc.perform(put(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("candidates", candidates))))
                .andExpect(status().isBadRequest());

        assertThat(candidateRepository.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(
                analysisJob.getId()
        )).isEmpty();
    }

    @Test
    @DisplayName("기존 설정과 중복되어 제외한 후보도 비교 당시 기존값을 보존한다")
    void completesDuplicateExcludeWithBeforeValue() throws Exception {
        WorldSetting target = worldSettingRepository.save(WorldSetting.create(
                work,
                WorldSettingCategory.IMPORTANT_ITEM,
                "포션",
                "회복 효과",
                "사용하면 신체를 빠르게 재생시킨다."
        ));

        MvcResult publishResult = mockMvc.perform(put(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "candidates": [{
                                    "category": "IMPORTANT_ITEM",
                                    "subjectName": "포션",
                                    "settingName": "상처 치료 효과",
                                    "extractedValue": "상처 부위에 사용하면 빠르게 재생된다.",
                                    "evidenceSpans": [{"quote": "피가 끓으며 빠르게 재생됐다."}],
                                    "extractionConfidence": 0.95
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        UUID candidateId = UUID.fromString(objectMapper
                .readTree(publishResult.getResponse().getContentAsString())
                .at("/data/0/candidateId")
                .asText());

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-comparisons/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk());

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates/{candidateId}/comparison-complete",
                                analysisJob.getId(),
                                candidateId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetWorldSettingId": "%s",
                                  "matchedPropertyName": "회복 효과",
                                  "consolidationStatus": "SINGLE",
                                  "suggestedOperation": "EXCLUDE",
                                  "proposedSettingName": "상처 치료 효과",
                                  "proposedValue": "상처 부위에 사용하면 빠르게 재생된다.",
                                  "comparisonReason": "기존 회복 효과와 의미가 같아 별도로 반영하지 않는다.",
                                  "exactTargetWorldSettingId": "%s",
                                  "contextVersions": [{
                                    "worldSettingId": "%s",
                                    "version": 0
                                  }],
                                  "rawComparisonJson": {"operation": "EXCLUDE"}
                                }
                                """.formatted(target.getId(), target.getId(), target.getId())))
                .andExpect(status().isOk());

        WorldSettingCandidate completedCandidate = candidateRepository.findById(candidateId).orElseThrow();
        assertThat(completedCandidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(completedCandidate.getSuggestedOperation()).isEqualTo(WorldSettingSuggestedOperation.EXCLUDE);
        assertThat(completedCandidate.getBeforeValue()).isEqualTo("사용하면 신체를 빠르게 재생시킨다.");
    }

    @Test
    @DisplayName("범위 없는 동명 후보는 concrete 비교를 거절하고 범위 확인 필요로 완료한다")
    void completesUnscopedSameNameCandidateAsScopeReviewRequired() throws Exception {
        WorldSetting target = worldSettingRepository.saveAndFlush(WorldSetting.create(
                work,
                WorldSettingCategory.LOCATION,
                "미궁",
                "1층",
                "광원",
                "벽에 붙은 수정들이 광원 역할을 한다."
        ));
        long initialVersion = target.getVersion();

        MvcResult publishResult = mockMvc.perform(put(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "candidates": [{
                                    "category": "LOCATION",
                                    "subjectName": "미궁",
                                    "settingName": "광원",
                                    "extractedValue": "벽과 천장의 수정들이 주변을 밝힌다.",
                                    "evidenceSpans": [{"quote": "수정들이 빛을 뿜어 주변을 밝혔다."}],
                                    "extractionConfidence": 0.95
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        UUID candidateId = UUID.fromString(objectMapper
                .readTree(publishResult.getResponse().getContentAsString())
                .at("/data/0/candidateId")
                .asText());

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-comparisons/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk());

        String concreteCrossScopePayload = """
                {
                  "targetWorldSettingId": "%s",
                  "matchedScopeName": "1층",
                  "matchedPropertyName": "광원",
                  "consolidationStatus": "SINGLE",
                  "suggestedOperation": "UPDATE",
                  "proposedScopeName": "1층",
                  "proposedSettingName": "광원",
                  "proposedValue": "벽과 천장의 수정들이 주변을 밝힌다.",
                  "comparisonReason": "기존 광원을 갱신한다.",
                  "exactTargetWorldSettingId": "%s",
                  "contextVersions": [{"worldSettingId": "%s", "version": %d}]
                }
                """.formatted(target.getId(), target.getId(), target.getId(), initialVersion);
        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates/{candidateId}/comparison-complete",
                                analysisJob.getId(),
                                candidateId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(concreteCrossScopePayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("WORLD_SETTING_COMPARISON_TARGET_INVALID"))
                .andExpect(jsonPath("$.error.context.reasonCode")
                        .value("PROPOSED_PATH_MISMATCH"));

        String rootAddPayload = """
                {
                  "targetWorldSettingId": "%s",
                  "consolidationStatus": "SINGLE",
                  "suggestedOperation": "ADD",
                  "proposedSettingName": "광원",
                  "proposedValue": "벽과 천장의 수정들이 주변을 밝힌다.",
                  "comparisonReason": "루트 광원 설정을 추가한다.",
                  "exactTargetWorldSettingId": "%s",
                  "contextVersions": [{"worldSettingId": "%s", "version": %d}]
                }
                """.formatted(target.getId(), target.getId(), target.getId(), initialVersion);
        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates/{candidateId}/comparison-complete",
                                analysisJob.getId(),
                                candidateId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rootAddPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("WORLD_SETTING_COMPARISON_TARGET_INVALID"))
                .andExpect(jsonPath("$.error.context.reasonCode")
                        .value("SCOPE_REVIEW_REQUIRED"));

        String targetlessRootAddPayload = """
                {
                  "consolidationStatus": "SINGLE",
                  "suggestedOperation": "ADD",
                  "proposedSettingName": "광원",
                  "proposedValue": "벽과 천장의 수정들이 주변을 밝힌다.",
                  "comparisonReason": "루트 광원 설정을 추가한다.",
                  "exactTargetWorldSettingId": "%s",
                  "contextVersions": [{"worldSettingId": "%s", "version": %d}]
                }
                """.formatted(target.getId(), target.getId(), initialVersion);
        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates/{candidateId}/comparison-complete",
                                analysisJob.getId(),
                                candidateId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(targetlessRootAddPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("WORLD_SETTING_COMPARISON_TARGET_INVALID"))
                .andExpect(jsonPath("$.error.context.reasonCode")
                        .value("SCOPE_REVIEW_REQUIRED"));

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates/{candidateId}/comparison-complete",
                                analysisJob.getId(),
                                candidateId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetWorldSettingId": "%s",
                                  "matchedScopeName": "1층",
                                  "matchedPropertyName": "광원",
                                  "consolidationStatus": "SINGLE",
                                  "suggestedOperation": "REVIEW_REQUIRED",
                                  "comparisonReviewReason": "SCOPE_UNRESOLVED",
                                  "proposedSettingName": "광원",
                                  "proposedValue": "벽과 천장의 수정들이 주변을 밝힌다.",
                                  "comparisonReason": "후보의 적용 범위 확인이 필요합니다.",
                                  "exactTargetWorldSettingId": "%s",
                                  "contextVersions": [{"worldSettingId": "%s", "version": %d}],
                                  "rawComparisonJson": {"operation": "REVIEW_REQUIRED"}
                                }
                                """.formatted(
                                target.getId(),
                                target.getId(),
                                target.getId(),
                                initialVersion
                        )))
                .andExpect(status().isOk());

        WorldSettingCandidate completed = candidateRepository.findById(candidateId).orElseThrow();
        assertThat(completed.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(completed.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.PENDING_REVIEW);
        assertThat(completed.getSuggestedOperation()).isEqualTo(WorldSettingSuggestedOperation.REVIEW_REQUIRED);
        assertThat(completed.getComparisonReviewReason().name()).isEqualTo("SCOPE_UNRESOLVED");
        assertThat(completed.getMatchedScopeName()).isEqualTo("1층");
        assertThat(completed.getMatchedPropertyName()).isEqualTo("광원");
        assertThat(completed.getProposedScopeName()).isNull();
        assertThat(completed.getBeforeValue()).isEqualTo("벽에 붙은 수정들이 광원 역할을 한다.");

        WorldSetting unchanged = worldSettingRepository.findById(target.getId()).orElseThrow();
        assertThat(unchanged.getVersion()).isEqualTo(initialVersion);
        assertThat(unchanged.getPropertyValue("1층", "광원"))
                .isEqualTo("벽에 붙은 수정들이 광원 역할을 한다.");
        assertThat(unchanged.getPropertyValue(null, "광원")).isNull();
    }

    @Test
    @DisplayName("같은 회차와 raw 범위의 후보를 한 묶음에서 최종 설정안 하나로 연결한다")
    void completesTwoSourceCandidatesAsOneComparisonDecision() throws Exception {
        MvcResult publishResult = mockMvc.perform(put(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "candidates": [{
                                    "category": "MONSTER",
                                    "subjectName": "고블린",
                                    "settingName": "함정 사용",
                                    "extractedValue": "사냥감의 이동 경로에 함정을 설치한다.",
                                    "evidenceSpans": [{
                                      "quote": "고블린들은 길목마다 함정을 파 두었다.",
                                      "startOffset": 10,
                                      "endOffset": 31
                                    }],
                                    "extractionConfidence": 0.95
                                  }, {
                                    "category": "MONSTER",
                                    "subjectName": "고블린",
                                    "settingName": "매복 습성",
                                    "extractedValue": "숨어서 사냥감이 가까워지기를 기다린다.",
                                    "evidenceSpans": [{
                                      "quote": "수풀에 숨은 고블린들이 일제히 튀어나왔다.",
                                      "startOffset": 40,
                                      "endOffset": 63
                                    }],
                                    "extractionConfidence": 0.95
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode published = objectMapper.readTree(publishResult.getResponse().getContentAsString());
        UUID firstCandidateId = UUID.fromString(published.at("/data/0/candidateId").asText());
        UUID secondCandidateId = UUID.fromString(published.at("/data/1/candidateId").asText());
        resolveSubjects(Map.of(
                firstCandidateId, List.of(),
                secondCandidateId, List.of()
        ));

        MvcResult claimResult = mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates.length()").value(2))
                .andExpect(jsonPath("$.data.candidates[0].candidateRef").value("C1"))
                .andExpect(jsonPath("$.data.candidates[0].candidateId")
                        .value(firstCandidateId.toString()))
                .andExpect(jsonPath("$.data.candidates[1].candidateRef").value("C2"))
                .andExpect(jsonPath("$.data.candidates[1].candidateId")
                        .value(secondCandidateId.toString()))
                .andReturn();
        UUID comparisonBatchId = UUID.fromString(objectMapper
                .readTree(claimResult.getResponse().getContentAsString())
                .at("/data/comparisonBatchId")
                .asText());

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/context",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetWorldSettingIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates.length()").value(2))
                .andExpect(jsonPath("$.data.targets.length()").value(0));

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contextVersions": [],
                                  "decisions": [{
                                    "decisionRef": "D1",
                                    "sourceCandidateRefs": ["C1"],
                                    "canonicalSubjectName": "고블린",
                                    "consolidationStatus": "SINGLE",
                                    "suggestedOperation": "ADD",
                                    "proposedScopeName": "전투 특성",
                                    "proposedSettingName": "사냥 전술",
                                    "proposedValue": "함정을 설치한다.",
                                    "comparisonReason": "첫 후보만 잘못 포함한 응답이다."
                                  }]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.context.reasonCode")
                        .value("BATCH_SOURCE_COVERAGE_INVALID"));

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contextVersions": [],
                                  "decisions": [{
                                    "decisionRef": "D1",
                                    "sourceCandidateRefs": ["C1", "C2"],
                                    "canonicalSubjectName": "고블린",
                                    "consolidationStatus": "MERGED",
                                    "suggestedOperation": "ADD",
                                    "proposedScopeName": "전투 특성",
                                    "proposedSettingName": "사냥 전술",
                                    "proposedValue": "함정을 설치하고 매복한다.",
                                    "comparisonReason": "두 후보를 묶는다."
                                  }, {
                                    "decisionRef": "D2",
                                    "sourceCandidateRefs": ["C2"],
                                    "canonicalSubjectName": "고블린",
                                    "consolidationStatus": "SINGLE",
                                    "suggestedOperation": "ADD",
                                    "proposedSettingName": "매복 습성",
                                    "proposedValue": "숨어 기다린다.",
                                    "comparisonReason": "두 번째 후보가 중복되었다."
                                  }]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.context.reasonCode")
                        .value("BATCH_SOURCE_REF_DUPLICATED"));

        assertThat(candidateRepository.findById(firstCandidateId).orElseThrow()
                .getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.PROCESSING);
        assertThat(candidateRepository.findById(secondCandidateId).orElseThrow()
                .getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.PROCESSING);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from world_setting_comparison_decisions where comparison_batch_id = ?",
                Long.class,
                comparisonBatchId
        )).isZero();

        String syntheticSingletonPayload = """
                {
                  "contextVersions": [],
                  "decisions": [{
                    "decisionRef": "D1",
                    "sourceCandidateRefs": ["C1", "C2"],
                    "canonicalSubjectName": "고블린",
                    "consolidationStatus": "MERGED",
                    "suggestedOperation": "ADD",
                    "proposedScopeName": "전투 특성",
                    "proposedSettingName": "사냥 전술",
                    "proposedValue": "이동 경로에 함정을 설치한 뒤 숨어서 매복한다.",
                    "comparisonReason": "두 근거가 고블린의 한 가지 사냥 전술을 보완한다."
                  }],
                  "rawComparisonJson": {"schemaVersion": "world-comparison-batch-v1"}
                }
                """;
        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(syntheticSingletonPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.context.reasonCode")
                        .value("SYNTHETIC_SCOPE_SINGLETON"));

        String completionPayload = """
                {
                  "contextVersions": [],
                  "decisions": [{
                    "decisionRef": "D1",
                    "sourceCandidateRefs": ["C1", "C2"],
                    "canonicalSubjectName": "고블린",
                    "consolidationStatus": "MERGED",
                    "suggestedOperation": "ADD",
                    "proposedSettingName": "사냥 전술",
                    "proposedValue": "이동 경로에 함정을 설치한 뒤 숨어서 매복한다.",
                    "comparisonReason": "두 근거가 고블린의 한 가지 사냥 전술을 보완한다.",
                    "rawComparisonJson": {"attempt": 1, "model": "gpt-5.6-luna"}
                  }],
                  "rawComparisonJson": {
                    "schemaVersion": "world-comparison-batch-v1",
                    "attempt": 1
                  }
                }
                """;
        String reorderedCompletionPayload = """
                {
                  "rawComparisonJson": {
                    "attempt": 1,
                    "schemaVersion": "world-comparison-batch-v1"
                  },
                  "decisions": [{
                    "rawComparisonJson": {"model": "gpt-5.6-luna", "attempt": 1},
                    "comparisonReason": "두 근거가 고블린의 한 가지 사냥 전술을 보완한다.",
                    "proposedValue": "이동 경로에 함정을 설치한 뒤 숨어서 매복한다.",
                    "proposedSettingName": "사냥 전술",
                    "suggestedOperation": "ADD",
                    "consolidationStatus": "MERGED",
                    "canonicalSubjectName": "고블린",
                    "sourceCandidateRefs": ["C2", "C1"],
                    "decisionRef": "D1"
                  }],
                  "contextVersions": []
                }
                """;
        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completionPayload))
                .andExpect(status().isOk());
        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reorderedCompletionPayload))
                .andExpect(status().isOk());

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completionPayload.replace(
                                "이동 경로에 함정을 설치한 뒤 숨어서 매복한다.",
                                "다른 최종값"
                        )))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_COMPARISON_BATCH_COMPLETION_CONFLICT"));

        assertThat(candidateRepository.findById(firstCandidateId).orElseThrow())
                .extracting(
                        WorldSettingCandidate::getComparisonStatus,
                        WorldSettingCandidate::getProposedScopeName,
                        WorldSettingCandidate::getProposedSettingName
                )
                .containsExactly(
                        WorldSettingComparisonStatus.COMPLETED,
                        null,
                        "사냥 전술"
                );
        assertThat(candidateRepository.findById(secondCandidateId).orElseThrow())
                .extracting(
                        WorldSettingCandidate::getComparisonStatus,
                        WorldSettingCandidate::getProposedScopeName,
                        WorldSettingCandidate::getProposedSettingName
                )
                .containsExactly(
                        WorldSettingComparisonStatus.COMPLETED,
                        null,
                        "사냥 전술"
                );
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from world_setting_comparison_decisions where comparison_batch_id = ?",
                Long.class,
                comparisonBatchId
        )).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from world_setting_comparison_decision_sources",
                Long.class
        )).isEqualTo(2L);
        assertThat(candidateRepository.findById(firstCandidateId).orElseThrow()
                .getComparisonDecision().getId())
                .isEqualTo(candidateRepository.findById(secondCandidateId).orElseThrow()
                        .getComparisonDecision().getId());
    }

    @Test
    @DisplayName("하나의 비교 묶음은 source를 정확히 한 번씩 포함한 여러 설정안으로 완료한다")
    void completesOneBatchAsMultipleDisjointDecisions() throws Exception {
        MvcResult publishResult = mockMvc.perform(put(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "candidates": [{
                                    "category": "MONSTER",
                                    "subjectName": "고블린",
                                    "settingName": "주 무기",
                                    "extractedValue": "곤봉을 사용한다.",
                                    "evidenceSpans": [{"quote": "고블린이 곤봉을 휘둘렀다."}],
                                    "extractionConfidence": 0.95
                                  }, {
                                    "category": "MONSTER",
                                    "subjectName": "고블린",
                                    "settingName": "서식지",
                                    "extractedValue": "미궁 1층에 서식한다.",
                                    "evidenceSpans": [{"quote": "미궁 1층에 고블린 소굴이 있었다."}],
                                    "extractionConfidence": 0.95
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode published = objectMapper.readTree(publishResult.getResponse().getContentAsString());
        UUID weaponCandidateId = UUID.fromString(published.at("/data/0/candidateId").asText());
        UUID habitatCandidateId = UUID.fromString(published.at("/data/1/candidateId").asText());
        resolveSubjects(Map.of(
                weaponCandidateId, List.of(),
                habitatCandidateId, List.of()
        ));

        MvcResult claimResult = mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates.length()").value(2))
                .andReturn();
        UUID comparisonBatchId = UUID.fromString(objectMapper
                .readTree(claimResult.getResponse().getContentAsString())
                .at("/data/comparisonBatchId")
                .asText());

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/context",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetWorldSettingIds\":[]}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contextVersions": [],
                                  "decisions": [{
                                    "decisionRef": "D1",
                                    "sourceCandidateRefs": ["C1"],
                                    "canonicalSubjectName": "고블린",
                                    "consolidationStatus": "SINGLE",
                                    "suggestedOperation": "ADD",
                                    "proposedSettingName": "전투 정보",
                                    "proposedValue": "곤봉을 사용한다.",
                                    "comparisonReason": "첫 번째 설정안"
                                  }, {
                                    "decisionRef": "D2",
                                    "sourceCandidateRefs": ["C2"],
                                    "canonicalSubjectName": "고블린",
                                    "consolidationStatus": "SINGLE",
                                    "suggestedOperation": "ADD",
                                    "proposedSettingName": " 전투 정보 ",
                                    "proposedValue": "미궁 1층에 서식한다.",
                                    "comparisonReason": "중복 최종 경로"
                                  }]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.context.reasonCode")
                        .value("BATCH_PROPOSED_PATH_DUPLICATED"));

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contextVersions": [],
                                  "decisions": [{
                                    "decisionRef": "D1",
                                    "sourceCandidateRefs": ["C1"],
                                    "canonicalSubjectName": "고블린",
                                    "consolidationStatus": "SINGLE",
                                    "suggestedOperation": "ADD",
                                    "proposedSettingName": "주 무기",
                                    "proposedValue": "곤봉을 사용한다.",
                                    "comparisonReason": "무기 근거를 별도 속성으로 정리한다."
                                  }, {
                                    "decisionRef": "D2",
                                    "sourceCandidateRefs": ["C2"],
                                    "canonicalSubjectName": "고블린",
                                    "consolidationStatus": "SINGLE",
                                    "suggestedOperation": "ADD",
                                    "proposedSettingName": "서식지",
                                    "proposedValue": "미궁 1층에 서식한다.",
                                    "comparisonReason": "서식지 근거를 별도 속성으로 정리한다."
                                  }]
                                }
                                """))
                .andExpect(status().isOk());

        WorldSettingCandidate weapon = candidateRepository.findById(weaponCandidateId).orElseThrow();
        WorldSettingCandidate habitat = candidateRepository.findById(habitatCandidateId).orElseThrow();
        assertThat(weapon.getComparisonDecision().getId())
                .isNotEqualTo(habitat.getComparisonDecision().getId());
        assertThat(jdbcTemplate.queryForObject(
                """
                        select decision.decision_ref
                        from world_setting_candidates candidate
                        join world_setting_comparison_decisions decision
                          on decision.id = candidate.comparison_decision_id
                        where candidate.id = ?
                        """,
                String.class,
                weaponCandidateId
        )).isEqualTo("D1");
        assertThat(jdbcTemplate.queryForObject(
                """
                        select decision.decision_ref
                        from world_setting_candidates candidate
                        join world_setting_comparison_decisions decision
                          on decision.id = candidate.comparison_decision_id
                        where candidate.id = ?
                        """,
                String.class,
                habitatCandidateId
        )).isEqualTo("D2");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from world_setting_comparison_decisions where comparison_batch_id = ?",
                Long.class,
                comparisonBatchId
        )).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from world_setting_comparison_decision_sources where comparison_batch_id = ?",
                Long.class,
                comparisonBatchId
        )).isEqualTo(2L);
    }

    @Test
    @DisplayName("한 batch가 같은 top-level 이름을 root 값과 범위로 동시에 제안하면 완료를 거절한다")
    void rejectsCrossDecisionTopLevelScalarObjectConflict() throws Exception {
        MvcResult publishResult = mockMvc.perform(put(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "candidates": [{
                                    "category": "RACE",
                                    "subjectName": "바바리안",
                                    "settingName": "신체",
                                    "extractedValue": "강인하다",
                                    "evidenceSpans": [{"quote": "바바리안의 신체는 강인했다."}],
                                    "extractionConfidence": 0.95
                                  }, {
                                    "category": "RACE",
                                    "subjectName": "바바리안",
                                    "settingName": "근력",
                                    "extractedValue": "높다",
                                    "evidenceSpans": [{"quote": "근력이 높았다."}],
                                    "extractionConfidence": 0.95
                                  }, {
                                    "category": "RACE",
                                    "subjectName": "바바리안",
                                    "settingName": "민첩",
                                    "extractedValue": "빠르다",
                                    "evidenceSpans": [{"quote": "움직임이 빨랐다."}],
                                    "extractionConfidence": 0.95
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode published = objectMapper.readTree(
                publishResult.getResponse().getContentAsString()
        );
        UUID bodyCandidateId = UUID.fromString(published.at("/data/0/candidateId").asText());
        UUID strengthCandidateId = UUID.fromString(published.at("/data/1/candidateId").asText());
        UUID agilityCandidateId = UUID.fromString(published.at("/data/2/candidateId").asText());
        resolveSubjects(Map.of(
                bodyCandidateId, List.of(),
                strengthCandidateId, List.of(),
                agilityCandidateId, List.of()
        ));

        MvcResult claimResult = mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates.length()").value(3))
                .andReturn();
        UUID comparisonBatchId = UUID.fromString(objectMapper
                .readTree(claimResult.getResponse().getContentAsString())
                .at("/data/comparisonBatchId")
                .asText());
        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/context",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetWorldSettingIds\":[]}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contextVersions": [],
                                  "decisions": [{
                                    "decisionRef": "D1",
                                    "sourceCandidateRefs": ["C1"],
                                    "canonicalSubjectName": "바바리안",
                                    "consolidationStatus": "SINGLE",
                                    "suggestedOperation": "ADD",
                                    "proposedSettingName": "신체",
                                    "proposedValue": "강인하다",
                                    "comparisonReason": "root 신체를 제안한다."
                                  }, {
                                    "decisionRef": "D2",
                                    "sourceCandidateRefs": ["C2"],
                                    "canonicalSubjectName": "바바리안",
                                    "consolidationStatus": "SINGLE",
                                    "suggestedOperation": "ADD",
                                    "proposedScopeName": "신체",
                                    "proposedSettingName": "근력",
                                    "proposedValue": "높다",
                                    "comparisonReason": "신체 범위의 근력을 제안한다."
                                  }, {
                                    "decisionRef": "D3",
                                    "sourceCandidateRefs": ["C3"],
                                    "canonicalSubjectName": "바바리안",
                                    "consolidationStatus": "SINGLE",
                                    "suggestedOperation": "ADD",
                                    "proposedScopeName": "신체",
                                    "proposedSettingName": "민첩",
                                    "proposedValue": "빠르다",
                                    "comparisonReason": "신체 범위의 민첩을 제안한다."
                                  }]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.context.reasonCode")
                        .value("PROPOSED_PATH_CONFLICT"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from world_setting_comparison_decisions where comparison_batch_id = ?",
                Long.class,
                comparisonBatchId
        )).isZero();
    }

    @Test
    @DisplayName("서로 다른 raw 별칭을 같은 기존 canonical 대상에 해소하면 한 묶음으로 claim한다")
    void claimsRawAliasesResolvedToSameTargetAsOneBatchIdempotently() throws Exception {
        WorldSetting target = worldSettingRepository.saveAndFlush(WorldSetting.create(
                work,
                WorldSettingCategory.MONSTER,
                "고블린",
                "무기",
                "곤봉"
        ));
        List<WorldSettingCandidate> candidates = candidateRepository.saveAllAndFlush(List.of(
                WorldSettingCandidate.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.MONSTER,
                        "고블린 떼",
                        null,
                        "함정 사용",
                        "길목에 함정을 설치한다.",
                        objectMapper.readTree("[{\"quote\":\"고블린 떼가 함정을 팠다.\"}]"),
                        new BigDecimal("0.95"),
                        null
                ),
                WorldSettingCandidate.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.MONSTER,
                        "고블린 무리",
                        null,
                        "매복 습성",
                        "수풀에 숨어 기다린다.",
                        objectMapper.readTree("[{\"quote\":\"고블린 무리가 숨었다.\"}]"),
                        new BigDecimal("0.95"),
                        null
                )
        ));
        analysisJob.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
        analysisJobRepository.saveAndFlush(analysisJob);

        mockMvc.perform(get(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-subject-resolutions/pending",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates.length()").value(2));

        Map<UUID, List<UUID>> resolutions = Map.of(
                candidates.get(0).getId(), List.of(target.getId()),
                candidates.get(1).getId(), List.of(target.getId())
        );
        resolveSubjects(resolutions);
        resolveSubjects(resolutions);

        mockMvc.perform(get(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-subject-resolutions/pending",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates.length()").value(0));

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolutionType").value("EXISTING"))
                .andExpect(jsonPath("$.data.canonicalSubjectKey")
                        .value("TARGET:" + target.getId()))
                .andExpect(jsonPath("$.data.canonicalSubjectName").value("고블린"))
                .andExpect(jsonPath("$.data.resolvedTargetWorldSettingIds[0]")
                        .value(target.getId().toString()))
                .andExpect(jsonPath("$.data.candidates.length()").value(2));
    }

    @Test
    @DisplayName("서로 다른 기존 canonical 대상에 해소한 후보는 독립 묶음으로 claim한다")
    void claimsCandidatesResolvedToDifferentTargetsAsSeparateBatches() throws Exception {
        WorldSetting goblin = worldSettingRepository.saveAndFlush(WorldSetting.create(
                work,
                WorldSettingCategory.MONSTER,
                "고블린",
                "무기",
                "곤봉"
        ));
        WorldSetting orc = worldSettingRepository.saveAndFlush(WorldSetting.create(
                work,
                WorldSettingCategory.MONSTER,
                "오크",
                "무기",
                "도끼"
        ));
        List<WorldSettingCandidate> candidates = candidateRepository.saveAllAndFlush(List.of(
                WorldSettingCandidate.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.MONSTER,
                        "초록 약탈자",
                        null,
                        "함정 사용",
                        "함정을 설치한다.",
                        objectMapper.readTree("[{\"quote\":\"함정을 팠다.\"}]"),
                        new BigDecimal("0.95"),
                        null
                ),
                WorldSettingCandidate.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.MONSTER,
                        "붉은 전사",
                        null,
                        "매복 습성",
                        "숨어 기다린다.",
                        objectMapper.readTree("[{\"quote\":\"수풀에 숨었다.\"}]"),
                        new BigDecimal("0.95"),
                        null
                )
        ));
        analysisJob.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
        analysisJobRepository.saveAndFlush(analysisJob);
        resolveSubjects(Map.of(
                candidates.get(0).getId(), List.of(goblin.getId()),
                candidates.get(1).getId(), List.of(orc.getId())
        ));

        List<String> canonicalKeys = new ArrayList<>();
        for (int claim = 0; claim < 2; claim++) {
            MvcResult result = mockMvc.perform(post(
                                    "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                            + "/world-setting-comparison-batches/claim-next",
                                    analysisJob.getId()
                            )
                            .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                            .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.candidates.length()").value(1))
                    .andReturn();
            canonicalKeys.add(objectMapper
                    .readTree(result.getResponse().getContentAsString())
                    .at("/data/canonicalSubjectKey")
                    .asText());
        }
        assertThat(canonicalKeys).containsExactlyInAnyOrder(
                "TARGET:" + goblin.getId(),
                "TARGET:" + orc.getId()
        );
    }

    @Test
    @DisplayName("기존 대상이 둘 이상 남은 주체 해소 결과는 candidate 전용 ambiguous 묶음으로 claim한다")
    void claimsAmbiguousSubjectResolutionAsCandidateSingleton() throws Exception {
        WorldSetting goblin = worldSettingRepository.saveAndFlush(WorldSetting.create(
                work,
                WorldSettingCategory.MONSTER,
                "고블린",
                "무기",
                "곤봉"
        ));
        WorldSetting orc = worldSettingRepository.saveAndFlush(WorldSetting.create(
                work,
                WorldSettingCategory.MONSTER,
                "오크",
                "무기",
                "도끼"
        ));
        WorldSettingCandidate candidate = candidateRepository.saveAndFlush(
                WorldSettingCandidate.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.MONSTER,
                        "초록 약탈자",
                        null,
                        "함정 사용",
                        "함정을 설치한다.",
                        objectMapper.readTree("[{\"quote\":\"함정을 팠다.\"}]"),
                        new BigDecimal("0.95"),
                        null
                )
        );
        analysisJob.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
        analysisJobRepository.saveAndFlush(analysisJob);

        JsonNode resolution = resolveSubjects(Map.of(
                candidate.getId(),
                List.of(orc.getId(), goblin.getId())
        ));
        assertThat(resolution.at("/data/resolutions/0/resolutionType").asText())
                .isEqualTo("AMBIGUOUS");

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolutionType").value("AMBIGUOUS"))
                .andExpect(jsonPath("$.data.canonicalSubjectKey")
                        .value("AMBIGUOUS:" + candidate.getId()))
                .andExpect(jsonPath("$.data.candidates.length()").value(1))
                .andExpect(jsonPath("$.data.resolvedTargetWorldSettingIds.length()").value(2));
    }

    @Test
    @DisplayName("주체 해소 요청의 중복·누락·외부 대상은 전체 저장을 롤백한다")
    void rollsBackInvalidSubjectResolutionRequests() throws Exception {
        List<WorldSettingCandidate> candidates = candidateRepository.saveAllAndFlush(List.of(
                WorldSettingCandidate.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.MONSTER,
                        "고블린",
                        null,
                        "함정 사용",
                        "함정을 설치한다.",
                        objectMapper.readTree("[{\"quote\":\"함정을 팠다.\"}]"),
                        new BigDecimal("0.95"),
                        null
                ),
                WorldSettingCandidate.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.MONSTER,
                        "오크",
                        null,
                        "매복 습성",
                        "숨어 기다린다.",
                        objectMapper.readTree("[{\"quote\":\"수풀에 숨었다.\"}]"),
                        new BigDecimal("0.95"),
                        null
                )
        ));
        analysisJob.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
        analysisJobRepository.saveAndFlush(analysisJob);

        Member otherMember = memberRepository.save(Member.register(
                "other-world-worker@example.com",
                "encoded-password",
                "01099990000",
                "다른 작가"
        ));
        Work otherWork = workRepository.save(Work.create(
                otherMember,
                "다른 작품",
                WorkGenre.FANTASY,
                "외부 대상 검증"
        ));
        WorldSetting foreignTarget = worldSettingRepository.saveAndFlush(WorldSetting.create(
                otherWork,
                WorldSettingCategory.MONSTER,
                "외부 고블린",
                "무기",
                "창"
        ));

        assertResolutionRejected("""
                {"resolutions":[
                  {"candidateId":"%s","targetWorldSettingIds":[]},
                  {"candidateId":"%s","targetWorldSettingIds":[]},
                  {"candidateId":"%s","targetWorldSettingIds":[]}
                ]}
                """.formatted(
                candidates.get(0).getId(),
                candidates.get(0).getId(),
                candidates.get(1).getId()
        ), candidates);
        assertResolutionRejected("""
                {"resolutions":[
                  {"candidateId":"%s","targetWorldSettingIds":[]}
                ]}
                """.formatted(candidates.get(0).getId()), candidates);
        assertResolutionRejected("""
                {"resolutions":[
                  {"candidateId":"%s","targetWorldSettingIds":[]},
                  {"candidateId":"%s","targetWorldSettingIds":["%s"]}
                ]}
                """.formatted(
                candidates.get(0).getId(),
                candidates.get(1).getId(),
                foreignTarget.getId()
        ), candidates);
        assertResolutionRejected("""
                {"resolutions":[
                  {"candidateId":"%s","targetWorldSettingIds":["%s","%s"]},
                  {"candidateId":"%s","targetWorldSettingIds":[]}
                ]}
                """.formatted(
                candidates.get(0).getId(),
                foreignTarget.getId(),
                foreignTarget.getId(),
                candidates.get(1).getId()
        ), candidates);
        assertResolutionRejected("""
                {"resolutions":[
                  {"candidateId":"%s","targetWorldSettingIds":[]},
                  {"candidateId":"%s","targetWorldSettingIds":[]},
                  {"candidateId":"%s","targetWorldSettingIds":[]}
                ]}
                """.formatted(
                candidates.get(0).getId(),
                candidates.get(1).getId(),
                UUID.randomUUID()
        ), candidates);
    }

    @Test
    @DisplayName("claim 뒤 canonical 대상이 삭제되면 묶음을 원자적으로 폐기하고 주체 해소부터 재시작한다")
    void resetsClaimedBatchWhenResolvedTargetIsDeleted() throws Exception {
        WorldSetting target = worldSettingRepository.saveAndFlush(WorldSetting.create(
                work,
                WorldSettingCategory.MONSTER,
                "고블린",
                "무기",
                "곤봉"
        ));
        WorldSettingCandidate candidate = candidateRepository.saveAndFlush(
                WorldSettingCandidate.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.MONSTER,
                        "초록 약탈자",
                        null,
                        "함정 사용",
                        "함정을 설치한다.",
                        objectMapper.readTree("[{\"quote\":\"함정을 팠다.\"}]"),
                        new BigDecimal("0.95"),
                        null
                )
        );
        analysisJob.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
        analysisJobRepository.saveAndFlush(analysisJob);
        resolveSubjects(Map.of(candidate.getId(), List.of(target.getId())));

        MvcResult claimResult = mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andReturn();
        UUID staleBatchId = UUID.fromString(objectMapper
                .readTree(claimResult.getResponse().getContentAsString())
                .at("/data/comparisonBatchId")
                .asText());
        worldSettingRepository.deleteById(target.getId());
        worldSettingRepository.flush();

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/context",
                                analysisJob.getId(),
                                staleBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetWorldSettingIds":["%s"]}
                                """.formatted(target.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_SUBJECT_RESOLUTION_STALE"));

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}"
                                        + "/reset-stale-subject-resolution",
                                analysisJob.getId(),
                                staleBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk());

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}"
                                        + "/reset-stale-subject-resolution",
                                analysisJob.getId(),
                                staleBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk());

        WorldSettingCandidate reset = candidateRepository.findById(candidate.getId()).orElseThrow();
        assertThat(reset.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.PENDING);
        assertThat(reset.getSubjectResolutionType()).isNull();
        assertThat(reset.getComparisonBatch()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from world_setting_comparison_batches "
                        + "where id = ? and status = 'FAILED'",
                Long.class,
                staleBatchId
        )).isEqualTo(1L);

        mockMvc.perform(get(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-subject-resolutions/pending",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates[0].candidateId")
                        .value(candidate.getId().toString()));
        resolveSubjects(Map.of(candidate.getId(), List.of()));
        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolutionType").value("NEW"))
                .andExpect(jsonPath("$.data.comparisonBatchId")
                        .value(org.hamcrest.Matchers.not(staleBatchId.toString())));
    }

    @Test
    @DisplayName("묶음 비교 중 기존 설정 version이 바뀌면 전체 완료를 거절하고 최신 문맥으로 재시도한다")
    void retriesWholeBatchAfterWorldSettingContextBecomesStale() throws Exception {
        WorldSetting target = worldSettingRepository.saveAndFlush(WorldSetting.create(
                work,
                WorldSettingCategory.MONSTER,
                "고블린",
                "무기",
                "곤봉"
        ));
        MvcResult publishResult = mockMvc.perform(put(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "candidates": [{
                                    "category": "MONSTER",
                                    "subjectName": "고블린",
                                    "settingName": "장비",
                                    "extractedValue": "단검도 사용한다.",
                                    "evidenceSpans": [{"quote": "고블린이 단검을 꺼냈다."}],
                                    "extractionConfidence": 0.95
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        UUID candidateId = UUID.fromString(objectMapper
                .readTree(publishResult.getResponse().getContentAsString())
                .at("/data/0/candidateId")
                .asText());
        resolveSubjects(Map.of(candidateId, List.of(target.getId())));

        MvcResult claimResult = mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates.length()").value(1))
                .andReturn();
        UUID comparisonBatchId = UUID.fromString(objectMapper
                .readTree(claimResult.getResponse().getContentAsString())
                .at("/data/comparisonBatchId")
                .asText());

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/context",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetWorldSettingIds":["%s"]}
                                """.formatted(target.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targets[0].version").value(0));

        target.addProperty("서식지", "동굴");
        worldSettingRepository.saveAndFlush(target);

        String staleCompletion = batchMergeCompletion(target.getId(), 0);
        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staleCompletion))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_COMPARISON_CONTEXT_STALE"));

        assertThat(candidateRepository.findById(candidateId).orElseThrow()
                .getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.PROCESSING);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from world_setting_comparison_decisions where comparison_batch_id = ?",
                Long.class,
                comparisonBatchId
        )).isZero();

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/context",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetWorldSettingIds":["%s"]}
                                """.formatted(target.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targets[0].version").value(1));

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchMergeCompletion(target.getId(), 1)))
                .andExpect(status().isOk());

        assertThat(candidateRepository.findById(candidateId).orElseThrow()
                .getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
    }

    @Test
    @DisplayName("묶음 ADD는 실제 기존 root 설정만 새 제안 범위로 이동하도록 snapshot을 저장한다")
    void completesBatchAddWithExistingRootPropertyMoveSnapshot() throws Exception {
        WorldSetting target = worldSettingRepository.saveAndFlush(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "바바리안",
                "생명력",
                "선택 가능한 종족 중 가장 높다"
        ));
        MvcResult publishResult = mockMvc.perform(put(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "candidates": [{
                                    "category": "RACE",
                                    "subjectName": "바바리안",
                                    "settingName": "근력 기댓값",
                                    "extractedValue": "높다",
                                    "evidenceSpans": [{"quote": "근력 기댓값도 높아서"}],
                                    "extractionConfidence": 0.95
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        UUID candidateId = UUID.fromString(objectMapper
                .readTree(publishResult.getResponse().getContentAsString())
                .at("/data/0/candidateId")
                .asText());
        resolveSubjects(Map.of(candidateId, List.of(target.getId())));

        MvcResult claimResult = mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andReturn();
        UUID comparisonBatchId = UUID.fromString(objectMapper
                .readTree(claimResult.getResponse().getContentAsString())
                .at("/data/comparisonBatchId")
                .asText());

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/context",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetWorldSettingIds":["%s"]}
                                """.formatted(target.getId())))
                .andExpect(status().isOk());

        String invalidSameName = rootMoveCompletion(
                target.getId(),
                "근력 기댓값",
                "근력 기댓값",
                "생명력"
        );
        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidSameName))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.context.reasonCode")
                        .value("SCOPE_SETTING_NAME_DUPLICATED"));

        String invalidMissingRoot = rootMoveCompletion(
                target.getId(),
                "신체",
                "근력 기댓값",
                "없는 설정"
        );
        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidMissingRoot))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.context.reasonCode")
                        .value("ROOT_PROPERTY_MOVE_INVALID"));

        String validRootMove = rootMoveCompletion(
                target.getId(),
                "신체",
                "근력 기댓값",
                "생명력"
        );
        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRootMove.replace(
                                "[\"생명력\"]",
                                "[\"생명력\", \" 생명력 \"]"
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.context.reasonCode")
                        .value("ROOT_PROPERTY_MOVE_DUPLICATED"));
        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRootMove.replace(
                                "\"suggestedOperation\": \"ADD\"",
                                "\"suggestedOperation\": \"UPDATE\""
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.context.reasonCode")
                        .value("ROOT_PROPERTY_MOVE_NOT_ALLOWED"));

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRootMove))
                .andExpect(status().isOk());

        UUID comparisonDecisionId = candidateRepository.findById(candidateId).orElseThrow()
                .getComparisonDecision()
                .getId();
        var completedDecision = comparisonDecisionRepository.findById(comparisonDecisionId)
                .orElseThrow();
        assertThat(completedDecision.getExistingRootPropertyNamesToMove())
                .containsExactly("생명력");
        assertThat(completedDecision.getExistingRootPropertyMoveSnapshots())
                .singleElement()
                .satisfies(snapshot -> {
                    assertThat(snapshot.settingName()).isEqualTo("생명력");
                    assertThat(snapshot.beforeValue()).isEqualTo("선택 가능한 종족 중 가장 높다");
                });
    }

    @Test
    @DisplayName("한 batch에서 이동할 root를 다른 설정안이 동시에 갱신하면 전체 완료를 거절한다")
    void rejectsRootMoveAndUpdateOfSameSourceProperty() throws Exception {
        WorldSetting target = worldSettingRepository.saveAndFlush(WorldSetting.create(
                work,
                WorldSettingCategory.RACE,
                "바바리안",
                "생명력",
                "선택 가능한 종족 중 가장 높다"
        ));
        MvcResult publishResult = mockMvc.perform(put(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "candidates": [{
                                    "category": "RACE",
                                    "subjectName": "바바리안",
                                    "settingName": "근력 기댓값",
                                    "extractedValue": "높다",
                                    "evidenceSpans": [{"quote": "근력 기댓값도 높다."}],
                                    "extractionConfidence": 0.95
                                  }, {
                                    "category": "RACE",
                                    "subjectName": "바바리안",
                                    "settingName": "생명력",
                                    "extractedValue": "전보다 더 높아졌다",
                                    "evidenceSpans": [{"quote": "생명력이 더 높아졌다."}],
                                    "extractionConfidence": 0.95
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode published = objectMapper.readTree(
                publishResult.getResponse().getContentAsString()
        );
        UUID strengthCandidateId = UUID.fromString(
                published.at("/data/0/candidateId").asText()
        );
        UUID lifeCandidateId = UUID.fromString(
                published.at("/data/1/candidateId").asText()
        );
        resolveSubjects(Map.of(
                strengthCandidateId, List.of(target.getId()),
                lifeCandidateId, List.of(target.getId())
        ));

        MvcResult claimResult = mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andReturn();
        UUID comparisonBatchId = UUID.fromString(objectMapper
                .readTree(claimResult.getResponse().getContentAsString())
                .at("/data/comparisonBatchId")
                .asText());
        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/context",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetWorldSettingIds":["%s"]}
                                """.formatted(target.getId())))
                .andExpect(status().isOk());

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contextVersions": [{
                                    "worldSettingId": "%s",
                                    "version": 0
                                  }],
                                  "decisions": [{
                                    "decisionRef": "D1",
                                    "sourceCandidateRefs": ["C1"],
                                    "canonicalSubjectName": "바바리안",
                                    "targetWorldSettingId": "%s",
                                    "existingRootPropertyNamesToMove": ["생명력"],
                                    "consolidationStatus": "SINGLE",
                                    "suggestedOperation": "ADD",
                                    "proposedScopeName": "신체",
                                    "proposedSettingName": "근력 기댓값",
                                    "proposedValue": "높다",
                                    "comparisonReason": "신체 범위로 묶는다."
                                  }, {
                                    "decisionRef": "D2",
                                    "sourceCandidateRefs": ["C2"],
                                    "canonicalSubjectName": "바바리안",
                                    "targetWorldSettingId": "%s",
                                    "matchedPropertyName": "생명력",
                                    "consolidationStatus": "SINGLE",
                                    "suggestedOperation": "UPDATE",
                                    "proposedSettingName": "생명력",
                                    "proposedValue": "전보다 더 높아졌다",
                                    "comparisonReason": "기존 생명력을 갱신한다."
                                  }]
                                }
                                """.formatted(target.getId(), target.getId(), target.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.context.reasonCode")
                        .value("ROOT_PROPERTY_MOVE_CONFLICT"));

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contextVersions": [{
                                    "worldSettingId": "%s",
                                    "version": 0
                                  }],
                                  "decisions": [{
                                    "decisionRef": "D1",
                                    "sourceCandidateRefs": ["C1"],
                                    "canonicalSubjectName": "바바리안",
                                    "targetWorldSettingId": "%s",
                                    "existingRootPropertyNamesToMove": ["생명력"],
                                    "consolidationStatus": "SINGLE",
                                    "suggestedOperation": "ADD",
                                    "proposedScopeName": "신체",
                                    "proposedSettingName": "근력 기댓값",
                                    "proposedValue": "높다",
                                    "comparisonReason": "신체 범위로 묶는다."
                                  }, {
                                    "decisionRef": "D2",
                                    "sourceCandidateRefs": ["C2"],
                                    "canonicalSubjectName": "바바리안",
                                    "targetWorldSettingId": "%s",
                                    "consolidationStatus": "SINGLE",
                                    "suggestedOperation": "ADD",
                                    "proposedScopeName": "신체",
                                    "proposedSettingName": "생명력",
                                    "proposedValue": "전보다 더 높아졌다",
                                    "comparisonReason": "이동 목적지와 중복되는 설정안이다."
                                  }]
                                }
                                """.formatted(target.getId(), target.getId(), target.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.context.reasonCode")
                        .value("BATCH_PROPOSED_PATH_DUPLICATED"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from world_setting_comparison_decisions where comparison_batch_id = ?",
                Long.class,
                comparisonBatchId
        )).isZero();
    }

    @Test
    @DisplayName("원문에 명시된 raw 범위를 그대로 유지하는 ADD는 child 하나여도 완료한다")
    void allowsSingletonWhenProposedScopeMatchesRawScope() throws Exception {
        WorldSettingCandidate candidate = candidateRepository.saveAndFlush(
                WorldSettingCandidate.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.IMPORTANT_ITEM,
                        "철검",
                        "장비",
                        "착용 조건",
                        "전사만 착용할 수 있다.",
                        objectMapper.readTree("[{\"quote\":\"이 검은 전사만 들 수 있다.\"}]"),
                        new BigDecimal("0.95"),
                        null
                )
        );
        analysisJob.updateCheckpointStage(
                AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED
        );
        analysisJobRepository.saveAndFlush(analysisJob);
        resolveSubjects(Map.of(candidate.getId(), List.of()));

        MvcResult claimResult = mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andReturn();
        UUID comparisonBatchId = UUID.fromString(objectMapper
                .readTree(claimResult.getResponse().getContentAsString())
                .at("/data/comparisonBatchId")
                .asText());
        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/context",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetWorldSettingIds\":[]}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contextVersions": [],
                                  "decisions": [{
                                    "decisionRef": "D1",
                                    "sourceCandidateRefs": ["C1"],
                                    "canonicalSubjectName": "철검",
                                    "consolidationStatus": "SINGLE",
                                    "suggestedOperation": "ADD",
                                    "proposedScopeName": "장비",
                                    "proposedSettingName": "착용 조건",
                                    "proposedValue": "전사만 착용할 수 있다.",
                                    "comparisonReason": "원문에 명시된 장비 범위를 유지한다."
                                  }]
                                }
                                """))
                .andExpect(status().isOk());

        WorldSettingCandidate completed = candidateRepository.findById(candidate.getId())
                .orElseThrow();
        assertThat(completed.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(completed.getProposedScopeName()).isEqualTo("장비");
    }

    @Test
    @DisplayName("기존 canonical 대상이 없는 서로 다른 raw 주체는 별도 묶음으로 claim한다")
    void claimsTargetlessDifferentSubjectsAsSeparateBatches() throws Exception {
        List<WorldSettingCandidate> candidates = candidateRepository.saveAllAndFlush(List.of(
                WorldSettingCandidate.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.MONSTER,
                        "고블린",
                        null,
                        "함정 사용",
                        "길목에 함정을 설치한다.",
                        objectMapper.readTree("[{\"quote\":\"고블린이 함정을 팠다.\"}]"),
                        new BigDecimal("0.95"),
                        null
                ),
                WorldSettingCandidate.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.MONSTER,
                        "오크",
                        null,
                        "매복 습성",
                        "수풀에 숨어 기다린다.",
                        objectMapper.readTree("[{\"quote\":\"오크가 수풀에 숨었다.\"}]"),
                        new BigDecimal("0.95"),
                        null
                )
        ));
        analysisJob.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
        analysisJobRepository.saveAndFlush(analysisJob);
        Map<UUID, List<UUID>> newSubjectResolutions = new LinkedHashMap<>();
        candidates.forEach(candidate -> newSubjectResolutions.put(candidate.getId(), List.of()));
        resolveSubjects(newSubjectResolutions);

        List<UUID> comparisonBatchIds = new ArrayList<>();
        for (int claim = 0; claim < 2; claim++) {
            MvcResult claimResult = mockMvc.perform(post(
                                    "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                            + "/world-setting-comparison-batches/claim-next",
                                    analysisJob.getId()
                            )
                            .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                            .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.candidates.length()").value(1))
                    .andReturn();
            comparisonBatchIds.add(UUID.fromString(objectMapper
                    .readTree(claimResult.getResponse().getContentAsString())
                    .at("/data/comparisonBatchId")
                    .asText()));
        }
        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isNoContent());

        assertThat(candidateRepository.findAllById(candidates.stream()
                .map(WorldSettingCandidate::getId)
                .toList()))
                .allMatch(candidate -> candidate.getComparisonStatus()
                        == WorldSettingComparisonStatus.PROCESSING);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from world_setting_comparison_batches",
                Long.class
        )).isEqualTo(2L);
        assertThat(comparisonBatchIds).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("출력 상한을 넘긴 묶음은 원본값 그대로 사용자 검토 대상으로 완료한다")
    void completesOutputLimitedBatchAsDeterministicReviewDecisions() throws Exception {
        List<WorldSettingCandidate> candidates = candidateRepository.saveAllAndFlush(List.of(
                WorldSettingCandidate.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.MONSTER,
                        "고블린",
                        null,
                        "서식지",
                        "동굴",
                        objectMapper.readTree("[{\"quote\":\"동굴에 산다.\"}]"),
                        new BigDecimal("0.95"),
                        null
                ),
                WorldSettingCandidate.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.MONSTER,
                        "고블린",
                        null,
                        "무기",
                        "검\n몽둥이",
                        objectMapper.readTree("[{\"quote\":\"검과 몽둥이를 들었다.\"}]"),
                        new BigDecimal("0.95"),
                        null
                )
        ));
        analysisJob.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
        analysisJobRepository.saveAndFlush(analysisJob);
        resolveSubjects(Map.of(
                candidates.get(0).getId(), List.of(),
                candidates.get(1).getId(), List.of()
        ));

        MvcResult claimResult = mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates.length()").value(2))
                .andReturn();
        UUID comparisonBatchId = UUID.fromString(objectMapper
                .readTree(claimResult.getResponse().getContentAsString())
                .at("/data/comparisonBatchId")
                .asText());

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/context",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetWorldSettingIds\":[]}"))
                .andExpect(status().isOk());

        String completionPayload = """
                {
                  "contextVersions": [],
                  "decisions": [{
                    "decisionRef": "D1",
                    "sourceCandidateRefs": ["C1"],
                    "canonicalSubjectName": "고블린",
                    "existingRootPropertyNamesToMove": [],
                    "consolidationStatus": "SINGLE",
                    "suggestedOperation": "REVIEW_REQUIRED",
                    "comparisonReviewReason": "BATCH_LIMIT_EXCEEDED",
                    "proposedSettingName": "서식지",
                    "proposedValue": "동굴",
                    "comparisonReason": "비교 결과가 출력 한도를 넘어 자동 비교하지 않았습니다."
                  }, {
                    "decisionRef": "D2",
                    "sourceCandidateRefs": ["C2"],
                    "canonicalSubjectName": "고블린",
                    "existingRootPropertyNamesToMove": [],
                    "consolidationStatus": "CONFLICT",
                    "suggestedOperation": "REVIEW_REQUIRED",
                    "comparisonReviewReason": "BATCH_LIMIT_EXCEEDED",
                    "proposedSettingName": "무기",
                    "proposedValue": "검\\n몽둥이",
                    "comparisonReason": "비교 결과가 출력 한도를 넘어 자동 비교하지 않았습니다."
                  }],
                  "rawComparisonJson": {"reviewReason": "BATCH_LIMIT_EXCEEDED"}
                }
                """;
        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completionPayload.replace("\"동굴\"", "\"숲\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.context.reasonCode")
                        .value("BATCH_CONSOLIDATION_STATUS_INVALID"));

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/{comparisonBatchId}/complete",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completionPayload))
                .andExpect(status().isOk());

        List<WorldSettingCandidate> persisted = candidateRepository.findAllById(
                candidates.stream().map(WorldSettingCandidate::getId).toList()
        );
        assertThat(persisted)
                .allMatch(candidate -> candidate.getReviewStatus()
                        == WorldSettingReviewStatus.PENDING_REVIEW)
                .allMatch(candidate -> candidate.getComparisonStatus()
                        == WorldSettingComparisonStatus.COMPLETED)
                .allMatch(candidate -> candidate.getSuggestedOperation()
                        == WorldSettingSuggestedOperation.REVIEW_REQUIRED)
                .allMatch(candidate -> candidate.getComparisonReviewReason().name()
                        .equals("BATCH_LIMIT_EXCEEDED"));
        assertThat(worldSettingRepository.count()).isZero();
        assertThat(comparisonDecisionRepository.count()).isEqualTo(2L);
    }

    @Test
    @DisplayName("묶음 상한을 넘으면 후보를 조용히 나누지 않고 전체를 사용자 확인 대상으로 남긴다")
    void holdsWholeOversizedBatchForReviewWithoutSplitting() throws Exception {
        List<WorldSettingCandidate> oversizedCandidates = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            oversizedCandidates.add(WorldSettingCandidate.create(
                    work,
                    episode,
                    analysisJob,
                    WorldSettingCategory.MONSTER,
                    "고블린",
                    null,
                    "전투 특성 " + index,
                    "원자 설정값 " + index,
                    objectMapper.readTree(
                            "[{\"quote\":\"원문 근거 %d\",\"startOffset\":%d}]"
                                    .formatted(index, index * 10)
                    ),
                    new BigDecimal("0.95"),
                    null
            ));
        }
        candidateRepository.saveAllAndFlush(oversizedCandidates);
        analysisJob.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
        analysisJobRepository.saveAndFlush(analysisJob);
        Map<UUID, List<UUID>> oversizedResolutions = new LinkedHashMap<>();
        oversizedCandidates.forEach(candidate -> oversizedResolutions.put(
                candidate.getId(),
                List.of()
        ));
        resolveSubjects(oversizedResolutions);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post(
                                    "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                            + "/world-setting-comparison-batches/claim-next",
                                    analysisJob.getId()
                            )
                            .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                            .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                    .andExpect(status().isNoContent());
        }

        List<WorldSettingCandidate> persisted = candidateRepository.findAllById(
                oversizedCandidates.stream().map(WorldSettingCandidate::getId).toList()
        );
        assertThat(persisted).hasSize(21);
        assertThat(persisted)
                .allMatch(candidate -> candidate.getComparisonStatus()
                        == WorldSettingComparisonStatus.COMPLETED)
                .allMatch(candidate -> candidate.getSuggestedOperation()
                        == WorldSettingSuggestedOperation.REVIEW_REQUIRED)
                .allMatch(candidate -> candidate.getComparisonReviewReason().name()
                        .equals("BATCH_LIMIT_EXCEEDED"));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from world_setting_comparison_batches where status = 'REVIEW_REQUIRED'",
                Long.class
        )).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from world_setting_comparison_decisions",
                Long.class
        )).isEqualTo(21L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from world_setting_comparison_decision_sources",
                Long.class
        )).isEqualTo(21L);
    }

    @Test
    @DisplayName("서로 다른 canonical 주체 21개는 상한을 합산하지 않고 각각 claim한다")
    void doesNotOverflowTwentyOneDifferentCanonicalSubjectsTogether() throws Exception {
        List<WorldSettingCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            candidates.add(WorldSettingCandidate.create(
                    work,
                    episode,
                    analysisJob,
                    WorldSettingCategory.MONSTER,
                    "몬스터 " + index,
                    null,
                    "전투 특성",
                    "원자 설정값 " + index,
                    objectMapper.readTree(
                            "[{\"quote\":\"원문 근거 %d\",\"startOffset\":%d}]"
                                    .formatted(index, index * 10)
                    ),
                    new BigDecimal("0.95"),
                    null
            ));
        }
        candidateRepository.saveAllAndFlush(candidates);
        analysisJob.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
        analysisJobRepository.saveAndFlush(analysisJob);
        Map<UUID, List<UUID>> resolutions = new LinkedHashMap<>();
        candidates.forEach(candidate -> resolutions.put(candidate.getId(), List.of()));
        resolveSubjects(resolutions);

        for (int claim = 0; claim < 21; claim++) {
            mockMvc.perform(post(
                                    "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                            + "/world-setting-comparison-batches/claim-next",
                                    analysisJob.getId()
                            )
                            .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                            .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.candidates.length()").value(1));
        }
        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from world_setting_comparison_batches "
                        + "where status = 'PROCESSING' and candidate_count = 1",
                Long.class
        )).isEqualTo(21L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from world_setting_comparison_batches "
                        + "where status = 'REVIEW_REQUIRED'",
                Long.class
        )).isZero();
    }

    @Test
    @DisplayName("quota 실패는 묶음의 모든 후보를 함께 실패시키고 같은 보고는 멱등 처리한다")
    void failsWholeBatchAtomicallyWhenQuotaIsExhausted() throws Exception {
        List<WorldSettingCandidate> candidates = candidateRepository.saveAllAndFlush(List.of(
                WorldSettingCandidate.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.MONSTER,
                        "고블린",
                        null,
                        "함정 사용",
                        "길목에 함정을 설치한다.",
                        objectMapper.readTree("[{\"quote\":\"함정을 팠다.\"}]"),
                        new BigDecimal("0.95"),
                        null
                ),
                WorldSettingCandidate.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.MONSTER,
                        "고블린",
                        null,
                        "매복 습성",
                        "수풀에 숨어 기다린다.",
                        objectMapper.readTree("[{\"quote\":\"수풀에 숨었다.\"}]"),
                        new BigDecimal("0.95"),
                        null
                )
        ));
        analysisJob.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
        analysisJobRepository.saveAndFlush(analysisJob);
        resolveSubjects(Map.of(
                candidates.get(0).getId(), List.of(),
                candidates.get(1).getId(), List.of()
        ));

        MvcResult claimResult = mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andReturn();
        UUID comparisonBatchId = UUID.fromString(objectMapper
                .readTree(claimResult.getResponse().getContentAsString())
                .at("/data/comparisonBatchId")
                .asText());
        String failurePayload = """
                {
                  "failureCode": "AI_TOKEN_QUOTA_EXHAUSTED",
                  "errorMessage": "AI token quota is exhausted."
                }
                """;

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post(
                                    "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                            + "/world-setting-comparison-batches/{comparisonBatchId}/fail",
                                    analysisJob.getId(),
                                    comparisonBatchId
                            )
                            .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                            .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(failurePayload))
                    .andExpect(status().isOk());
        }

        assertThat(candidateRepository.findAllById(
                candidates.stream().map(WorldSettingCandidate::getId).toList()
        ))
                .allMatch(candidate -> candidate.getComparisonStatus()
                        == WorldSettingComparisonStatus.FAILED)
                .allMatch(candidate -> candidate.getComparisonFailureCode()
                        == AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from world_setting_comparison_batches "
                        + "where status = 'FAILED' and failure_code = 'AI_TOKEN_QUOTA_EXHAUSTED'",
                Long.class
        )).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from world_setting_comparison_decisions",
                Long.class
        )).isZero();
    }

    @Test
    @DisplayName("묶음 실패는 null 코드를 정규화한 전체 원본 사유 기준으로 멱등 처리한다")
    void makesNormalizedWholeBatchFailureRequestIdempotent() throws Exception {
        WorldSettingCandidate candidate = candidateRepository.saveAndFlush(
                WorldSettingCandidate.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.MONSTER,
                        "고블린",
                        null,
                        "함정 사용",
                        "길목에 함정을 설치한다.",
                        objectMapper.readTree("[{\"quote\":\"함정을 팠다.\"}]"),
                        new BigDecimal("0.95"),
                        null
                )
        );
        analysisJob.updateCheckpointStage(
                AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED
        );
        analysisJobRepository.saveAndFlush(analysisJob);
        resolveSubjects(Map.of(candidate.getId(), List.of()));

        MvcResult claimResult = mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/claim-next",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken))
                .andExpect(status().isOk())
                .andReturn();
        UUID comparisonBatchId = UUID.fromString(objectMapper
                .readTree(claimResult.getResponse().getContentAsString())
                .at("/data/comparisonBatchId")
                .asText());
        String failurePayload = """
                {
                  "failureCode": null,
                  "errorMessage": " unexpected provider failure ",
                  "sourceErrorCode": "ORIGINAL_PROVIDER_ERROR"
                }
                """;

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post(
                                    "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                            + "/world-setting-comparison-batches/"
                                            + "{comparisonBatchId}/fail",
                                    analysisJob.getId(),
                                    comparisonBatchId
                            )
                            .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                            .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(failurePayload))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-comparison-batches/"
                                        + "{comparisonBatchId}/fail",
                                analysisJob.getId(),
                                comparisonBatchId
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(failurePayload.replace(
                                "ORIGINAL_PROVIDER_ERROR",
                                "DIFFERENT_PROVIDER_ERROR"
                        )))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_COMPARISON_BATCH_STATUS_CONFLICT"));

        WorldSettingCandidate failed = candidateRepository.findById(candidate.getId())
                .orElseThrow();
        assertThat(failed.getComparisonFailureCode())
                .isEqualTo(AnalysisFailureCode.UNEXPECTED_ERROR);
        assertThat(failed.getComparisonErrorMessage())
                .isEqualTo("unexpected provider failure");
        assertThat(failed.getComparisonSourceErrorCode())
                .isEqualTo("ORIGINAL_PROVIDER_ERROR");
    }

    @Test
    @DisplayName("주체 해소 중 토큰 부족은 아직 대기 중인 세계관 후보를 중단한다")
    void interruptsPendingComparisonWhenSubjectResolutionExhaustsQuota() throws Exception {
        WorldSettingCandidate candidate = candidateRepository.saveAndFlush(
                WorldSettingCandidate.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.LOCATION,
                        "미궁",
                        null,
                        "광원",
                        "수정이 주변을 밝힌다.",
                        objectMapper.readTree("[{\"quote\":\"수정이 빛났다.\"}]"),
                        new BigDecimal("0.95"),
                        null
                )
        );

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-candidates/{candidateId}/comparison-fail",
                                analysisJob.getId(),
                                candidate.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "failureCode": "AI_TOKEN_QUOTA_EXHAUSTED",
                                  "errorMessage": "AI token quota is exhausted."
                                }
                                """))
                .andExpect(status().isOk());

        WorldSettingCandidate interrupted = candidateRepository.findById(candidate.getId())
                .orElseThrow();
        assertThat(interrupted.getComparisonStatus())
                .isEqualTo(WorldSettingComparisonStatus.FAILED);
        assertThat(interrupted.getComparisonFailureCode())
                .isEqualTo(AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED);
        assertThat(interrupted.getComparisonErrorMessage())
                .isEqualTo("AI token quota is exhausted.");
    }

    @Test
    @DisplayName("토큰 부족 외 실패는 아직 대기 중인 세계관 후보에 기록하지 않는다")
    void rejectsNonQuotaFailureForPendingComparison() throws Exception {
        WorldSettingCandidate candidate = candidateRepository.saveAndFlush(
                WorldSettingCandidate.create(
                        work,
                        episode,
                        analysisJob,
                        WorldSettingCategory.LOCATION,
                        "미궁",
                        null,
                        "광원",
                        "수정이 주변을 밝힌다.",
                        objectMapper.readTree("[{\"quote\":\"수정이 빛났다.\"}]"),
                        new BigDecimal("0.95"),
                        null
                )
        );

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-candidates/{candidateId}/comparison-fail",
                                analysisJob.getId(),
                                candidate.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "failureCode": "UNEXPECTED_ERROR",
                                  "errorMessage": "unexpected failure"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT"));

        WorldSettingCandidate pending = candidateRepository.findById(candidate.getId())
                .orElseThrow();
        assertThat(pending.getComparisonStatus())
                .isEqualTo(WorldSettingComparisonStatus.PENDING);
        assertThat(pending.getComparisonFailureCode()).isNull();
    }

    @Test
    @DisplayName("세계관 비교 실패는 상위 실패 코드와 Spring 원본 원인을 분리해 저장한다")
    void storesComparisonFailureSourceSeparately() throws Exception {
        WorldSettingCandidate candidate = WorldSettingCandidate.create(
                work,
                episode,
                analysisJob,
                WorldSettingCategory.LOCATION,
                "미궁",
                null,
                "광원",
                "수정이 주변을 밝힌다.",
                objectMapper.readTree("[{\"quote\":\"수정이 빛났다.\"}]"),
                new BigDecimal("0.95"),
                null
        );
        candidate.startComparison();
        candidate = candidateRepository.saveAndFlush(candidate);

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-candidates/{candidateId}/comparison-fail",
                                analysisJob.getId(),
                                candidate.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "failureCode": "COMPARISON_VALIDATION_FAILED",
                                  "errorMessage": "backend request failed",
                                  "sourceErrorCode": "WORLD_SETTING_COMPARISON_TARGET_INVALID",
                                  "sourceReasonCode": "PROPOSED_PATH_MISMATCH"
                                }
                                """))
                .andExpect(status().isOk());

        WorldSettingCandidate failed = candidateRepository.findById(candidate.getId()).orElseThrow();
        assertThat(failed.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.FAILED);
        assertThat(failed.getComparisonFailureCode())
                .isEqualTo(AnalysisFailureCode.COMPARISON_VALIDATION_FAILED);
        assertThat(failed.getComparisonSourceErrorCode())
                .isEqualTo("WORLD_SETTING_COMPARISON_TARGET_INVALID");
        assertThat(failed.getComparisonSourceReasonCode())
                .isEqualTo(WorldSettingComparisonValidationReason.PROPOSED_PATH_MISMATCH);
    }

    @Test
    @DisplayName("stale lease로는 후보를 게시할 수 없다")
    void rejectsCandidatePublishWithStaleLease() throws Exception {
        mockMvc.perform(put(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidates\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_JOB_LEASE_CONFLICT"));
    }

    @Test
    @DisplayName("같은 대상과 설정명의 후보가 중복되면 비교 후보 게시 전에 거절한다")
    void rejectsDuplicateSettingNamesBeforePublishing() throws Exception {
        mockMvc.perform(put(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "candidates": [{
                                    "category": "IMPORTANT_ITEM",
                                    "subjectName": "메시지 스톤",
                                    "settingName": "기능",
                                    "extractedValue": "서로 대화할 수 있다.",
                                    "evidenceSpans": [{"quote": "메시지 스톤끼리 대화할 수 있다."}],
                                    "extractionConfidence": 0.95
                                  }, {
                                    "category": "IMPORTANT_ITEM",
                                    "subjectName": " 메시지 스톤 ",
                                    "settingName": " 기능 ",
                                    "extractedValue": "신호를 보낼 수 있다.",
                                    "evidenceSpans": [{"quote": "짧게 읊조려 신호를 보냈다."}],
                                    "extractionConfidence": 0.95
                                  }]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_CANDIDATE_SETTING_NAME_DUPLICATED"));

        assertThat(candidateRepository.count()).isZero();
    }

    @Test
    @DisplayName("정해진 세 단계가 아닌 추출 신뢰도는 후보 게시 전에 거절한다")
    void rejectsUnsupportedExtractionConfidence() throws Exception {
        mockMvc.perform(put(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-candidates",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "candidates": [{
                                    "category": "RACE",
                                    "subjectName": "바바리안",
                                    "settingName": "서식지",
                                    "extractedValue": "극지방",
                                    "evidenceSpans": [{"quote": "원문 근거"}],
                                    "extractionConfidence": 0.70
                                  }]
                                }
                                """))
                .andExpect(status().isBadRequest());

        assertThat(candidateRepository.count()).isZero();
    }

    private JsonNode resolveSubjects(Map<UUID, List<UUID>> targetsByCandidate)
            throws Exception {
        List<Map<String, Object>> resolutions = targetsByCandidate.entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "candidateId", entry.getKey(),
                        "targetWorldSettingIds", entry.getValue()
                ))
                .toList();
        MvcResult result = mockMvc.perform(put(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-subject-resolutions",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "resolutions",
                                resolutions
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assertResolutionRejected(
            String payload,
            List<WorldSettingCandidate> candidates
    ) throws Exception {
        mockMvc.perform(put(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}"
                                        + "/world-setting-subject-resolutions",
                                analysisJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, leaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("WORLD_SETTING_SUBJECT_RESOLUTION_INVALID"));
        assertThat(candidateRepository.findAllById(candidates.stream()
                .map(WorldSettingCandidate::getId)
                .toList()))
                .allMatch(candidate -> candidate.getSubjectResolutionType() == null)
                .allMatch(candidate -> candidate.getCanonicalSubjectKey() == null)
                .allMatch(candidate -> candidate.getResolvedTargetWorldSettingIds() == null);
    }

    private String batchMergeCompletion(UUID targetId, long version) {
        return """
                {
                  "contextVersions": [{
                    "worldSettingId": "%s",
                    "version": %d
                  }],
                  "decisions": [{
                    "decisionRef": "D1",
                    "sourceCandidateRefs": ["C1"],
                    "canonicalSubjectName": "고블린",
                    "targetWorldSettingId": "%s",
                    "matchedPropertyName": "무기",
                    "consolidationStatus": "SINGLE",
                    "suggestedOperation": "MERGE",
                    "proposedSettingName": "무기",
                    "proposedValue": "곤봉과 단검을 사용한다.",
                    "comparisonReason": "기존 무기 정보와 새 근거가 함께 성립한다."
                  }]
                }
                """.formatted(targetId, version, targetId);
    }

    private String rootMoveCompletion(
            UUID targetId,
            String proposedScopeName,
            String proposedSettingName,
            String existingRootPropertyName
    ) {
        return """
                {
                  "contextVersions": [{
                    "worldSettingId": "%s",
                    "version": 0
                  }],
                  "decisions": [{
                    "decisionRef": "D1",
                    "sourceCandidateRefs": ["C1"],
                    "canonicalSubjectName": "바바리안",
                    "targetWorldSettingId": "%s",
                    "existingRootPropertyNamesToMove": ["%s"],
                    "consolidationStatus": "SINGLE",
                    "suggestedOperation": "ADD",
                    "proposedScopeName": "%s",
                    "proposedSettingName": "%s",
                    "proposedValue": "높다",
                    "comparisonReason": "두 신체 관련 설정을 공통 범위로 정리한다."
                  }]
                }
                """.formatted(
                targetId,
                targetId,
                existingRootPropertyName,
                proposedScopeName,
                proposedSettingName
        );
    }

    private void clearData() {
        candidateRepository.deleteAll();
        worldSettingRepository.deleteAll();
        analysisJobRepository.deleteAll();
        episodeRepository.deleteAll();
        uploadBatchRepository.deleteAll();
        workRepository.deleteAll();
        memberRepository.deleteAll();
    }
}
