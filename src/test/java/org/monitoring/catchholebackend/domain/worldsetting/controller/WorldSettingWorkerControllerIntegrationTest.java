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
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
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
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
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
                WorldSettingCategory.RACE,
                "바바리안",
                "서식지",
                "혹한 지역"
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
                                    "settingName": "서식지",
                                    "extractedValue": "극지방",
                                    "evidenceSpans": [{
                                      "quote": "바바리안은 극지방에 산다.",
                                      "startOffset": 10,
                                      "endOffset": 25
                                    }],
                                    "extractionConfidence": 0.95,
                                    "rawExtractionJson": {"confidence": 0.95}
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].category").value("RACE"))
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
                        .param("category", "RACE"))
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
                .andExpect(jsonPath("$.data.targets[0].propertiesJson['서식지']").value("혹한 지역"));

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
                                  "matchedPropertyName": "서식지",
                                  "suggestedOperation": "UPDATE",
                                  "proposedSettingName": "서식지",
                                  "proposedValue": "극지방",
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
        assertThat(completedCandidate.getSuggestedOperation()).isEqualTo(WorldSettingOperation.UPDATE);
        assertThat(completedCandidate.getBeforeValue()).isEqualTo("혹한 지역");
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
