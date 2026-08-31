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
