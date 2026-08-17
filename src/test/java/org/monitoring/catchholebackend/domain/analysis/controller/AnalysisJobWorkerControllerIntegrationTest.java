package org.monitoring.catchholebackend.domain.analysis.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenAccountRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenGrantRepository;
import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenUsageRepository;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
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
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.global.config.security.SecurityConstant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("분석 작업 Worker 내부 API 통합 테스트")
class AnalysisJobWorkerControllerIntegrationTest {

    private static final String INTERNAL_API_KEY = "local-development-internal-api-key";
    private static final String CLAIM_URL = "/api/internal/v1/analysis-jobs/claim";
    private static final String WORKER_LEASE_TOKEN_HEADER =
            SecurityConstant.WORKER_LEASE_TOKEN_HEADER;
    private static final String DEFAULT_CLAIM_BODY = """
            {"allowedJobTypes":["SETTING_EXTRACTION","EPISODE_VALIDATION"]}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorldSettingCandidateRepository worldSettingCandidateRepository;

    @Autowired
    private WorldSettingRepository worldSettingRepository;

    @Autowired
    private AiTokenUsageRepository aiTokenUsageRepository;

    @Autowired
    private AiTokenGrantRepository aiTokenGrantRepository;

    @Autowired
    private AiTokenAccountRepository aiTokenAccountRepository;

    @Autowired
    private WorkCharacterRepository workCharacterRepository;

    @Autowired
    private SettingCandidateRepository settingCandidateRepository;

    @Autowired
    private CharacterSettingSchemaRepository characterSettingSchemaRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Member member;
    private Work work;
    private UploadBatch uploadBatch;
    private UploadFile uploadFile;
    private Episode firstEpisode;
    private Episode secondEpisode;

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
        worldSettingRepository.deleteAll();
        analysisJobRepository.deleteAll();
        episodeRepository.deleteAll();
        uploadFileRepository.deleteAll();
        uploadBatchRepository.deleteAll();
        workCharacterRepository.deleteAll();
        characterSettingSchemaRepository.deleteAll();
        workRepository.deleteAll();
        memberRepository.deleteAll();

        member = memberRepository.save(Member.register(
                "writer@example.com",
                "encoded-password",
                "01012345678",
                "작가"
        ));
        work = workRepository.save(Work.create(member, "내 작품", WorkGenre.FANTASY, "내 설명"));
        uploadBatch = uploadBatchRepository.save(UploadBatch.create(
                work,
                member,
                UploadType.MULTI_EPISODE_SINGLE_FILE,
                UploadSourceType.FILE
        ));
        uploadFile = uploadFileRepository.save(parsedEpisodeFile(uploadBatch, "episodes.txt", 1, 2, 2));
        secondEpisode = episodeRepository.save(
                episode(2, "두 번째 회차", "works/%s/episodes/2.txt".formatted(work.getId())));
        firstEpisode = episodeRepository.save(
                episode(1, "첫 번째 회차", "works/%s/episodes/1.txt".formatted(work.getId())));
    }

    @Test
    @DisplayName("내부 API key가 없으면 claim 요청을 거절한다")
    void claimRequiresInternalApiKey() throws Exception {
        mockMvc.perform(post(CLAIM_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("내부 API key가 일치하지 않으면 claim 요청을 거절한다")
    void claimRejectsInvalidInternalApiKey() throws Exception {
        mockMvc.perform(post(CLAIM_URL)
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, "wrong-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("회원 JWT만 있는 claim 요청을 거절한다")
    void claimRejectsMemberJwtWithoutInternalApiKey() throws Exception {
        String accessToken = jwtTokenProvider.generateAccessToken(member);

        mockMvc.perform(post(CLAIM_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("대기 중인 분석 작업이 없으면 204를 응답한다")
    void claimReturnsNoContentWhenNoPendingJob() throws Exception {
        mockMvc.perform(post(CLAIM_URL)
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DEFAULT_CLAIM_BODY))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("활성 registry schema가 없으면 빈 characterSettingSchemas를 응답한다")
    void claimReturnsEmptyCharacterSettingSchemasWhenRegistryHasNoRows() throws Exception {
        analysisJobRepository.save(
                episodeJob(AnalysisJobType.EPISODE_VALIDATION, firstEpisode)
        );

        mockMvc.perform(post(CLAIM_URL)
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DEFAULT_CLAIM_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobType").value("EPISODE_VALIDATION"))
                .andExpect(jsonPath("$.data.characterSettingSchemas", hasSize(0)))
                .andExpect(jsonPath("$.data.knownCharacters", hasSize(0)))
                .andExpect(jsonPath("$.data.episode.episodeId").value(firstEpisode.getId().toString()));
    }

    @Test
    @DisplayName("가장 오래된 대기 작업을 claim하고 회차·캐릭터·설정 schema를 응답한다")
    void claimOldestPendingJobAndReturnsEpisodeMetadata() throws Exception {
        AnalysisJob firstJob = analysisJobRepository.save(
                episodeJob(AnalysisJobType.SETTING_EXTRACTION, firstEpisode)
        );
        Thread.sleep(10);
        AnalysisJob secondJob = analysisJobRepository.save(
                episodeJob(AnalysisJobType.EPISODE_VALIDATION, secondEpisode)
        );
        WorkCharacter knownCharacter = workCharacterRepository.save(WorkCharacter.create(
                work,
                "아리아",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));
        WorkCharacter archivedCharacter = WorkCharacter.create(
                work,
                "보관된 인물",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        archivedCharacter.archive();
        workCharacterRepository.save(archivedCharacter);
        Work otherWork = workRepository.save(Work.create(member, "다른 작품", WorkGenre.ETC, "다른 schema 범위"));
        characterSettingSchemaRepository.save(settingSchema(
                null,
                "statuses.condition",
                "status.*",
                "상태",
                CharacterFactType.STATUS,
                SettingValueType.JSON,
                aliases(),
                CharacterSettingSchemaSource.SYSTEM_SEED,
                true
        ));
        characterSettingSchemaRepository.save(settingSchema(
                work,
                "stats.physique",
                null,
                "육체",
                CharacterFactType.STAT,
                SettingValueType.NUMBER,
                aliases("육체", "physical", "physique"),
                CharacterSettingSchemaSource.DEV_SEED,
                true
        ));
        characterSettingSchemaRepository.save(settingSchema(
                work,
                "stats.disabled",
                null,
                "비활성",
                CharacterFactType.STAT,
                SettingValueType.NUMBER,
                aliases("비활성"),
                CharacterSettingSchemaSource.DEV_SEED,
                false
        ));
        characterSettingSchemaRepository.save(settingSchema(
                otherWork,
                "stats.other_work",
                null,
                "다른 작품",
                CharacterFactType.STAT,
                SettingValueType.NUMBER,
                aliases("다른 작품"),
                CharacterSettingSchemaSource.DEV_SEED,
                true
        ));

        mockMvc.perform(post(CLAIM_URL)
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "modelName": "gpt-4.1-mini",
                                  "currentStep": "원문 청킹",
                                  "allowedJobTypes": ["SETTING_EXTRACTION", "EPISODE_VALIDATION"]
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
                .andExpect(jsonPath("$.data.characterSettingSchemas", hasSize(2)))
                .andExpect(jsonPath("$.data.characterSettingSchemas[0].schemaKey").value("stats.physique"))
                .andExpect(jsonPath("$.data.characterSettingSchemas[0].displayName").value("육체"))
                .andExpect(jsonPath("$.data.characterSettingSchemas[0].attributePattern").value(nullValue()))
                .andExpect(jsonPath("$.data.characterSettingSchemas[0].aliases", hasSize(3)))
                .andExpect(jsonPath("$.data.characterSettingSchemas[0].aliases[0]").value("육체"))
                .andExpect(jsonPath("$.data.characterSettingSchemas[0].valueType").value("NUMBER"))
                .andExpect(jsonPath("$.data.characterSettingSchemas[0].source").doesNotExist())
                .andExpect(jsonPath("$.data.characterSettingSchemas[0].id").doesNotExist())
                .andExpect(jsonPath("$.data.characterSettingSchemas[0].workId").doesNotExist())
                .andExpect(jsonPath("$.data.characterSettingSchemas[0].enabled").doesNotExist())
                .andExpect(jsonPath("$.data.characterSettingSchemas[0].mergePolicy").doesNotExist())
                .andExpect(jsonPath("$.data.characterSettingSchemas[1].schemaKey").value("statuses.condition"))
                .andExpect(jsonPath("$.data.characterSettingSchemas[1].attributePattern").value("status.*"))
                .andExpect(jsonPath("$.data.characterSettingSchemas[1].aliases", hasSize(0)))
                .andExpect(jsonPath("$.data.characterSettingSchemas[1].valueType").value("JSON"))
                .andExpect(jsonPath("$.data.knownCharacters", hasSize(1)))
                .andExpect(jsonPath("$.data.knownCharacters[0].characterId").value(knownCharacter.getId().toString()))
                .andExpect(jsonPath("$.data.knownCharacters[0].name").value("아리아"))
                .andExpect(jsonPath("$.data.episode.episodeId").value(firstEpisode.getId().toString()))
                .andExpect(jsonPath("$.data.episode.episodeNo").value(1))
                .andExpect(jsonPath("$.data.episode.title").value("첫 번째 회차"))
                .andExpect(jsonPath("$.data.episode.contentS3Key").value("works/%s/episodes/1.txt".formatted(work.getId())))
                .andExpect(jsonPath("$.data.episode.contentS3Version").value("v1"))
                .andExpect(jsonPath("$.data.episode.contentHash").value("hash-1"))
                .andExpect(jsonPath("$.data.episode.charCount").value(1001))
                .andExpect(jsonPath("$.data.episodes").doesNotExist());

        AnalysisJob claimedJob = analysisJobRepository.findById(firstJob.getId()).orElseThrow();
        AnalysisJob pendingJob = analysisJobRepository.findById(secondJob.getId()).orElseThrow();
        assertThat(claimedJob.getStatus()).isEqualTo(AnalysisJobStatus.RUNNING);
        assertThat(claimedJob.getModelName()).isEqualTo("gpt-4.1-mini");
        assertThat(claimedJob.getCurrentStep()).isEqualTo("원문 청킹");
        assertThat(pendingJob.getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
    }

    @Test
    @DisplayName("출처 Job과 회차가 없는 레거시 캐릭터 후보 재비교도 nullable payload로 claim한다")
    void claimLegacyCharacterComparisonWithoutBatchAndEpisode() throws Exception {
        characterSettingSchemaRepository.save(settingSchema(
                work,
                "stats.strength",
                null,
                "근력",
                CharacterFactType.STAT,
                SettingValueType.NUMBER,
                aliases("근력"),
                CharacterSettingSchemaSource.DEV_SEED,
                true
        ));
        SettingCandidate candidate = settingCandidateRepository.save(SettingCandidate.create(
                work,
                null,
                null,
                null,
                SettingEntityType.CHARACTER,
                "아리아",
                "stats.strength",
                "10",
                SettingValueType.NUMBER,
                objectMapper.createObjectNode().put("value", 10),
                objectMapper.createArrayNode(),
                new BigDecimal("0.90"),
                objectMapper.createObjectNode()
        ));
        AnalysisJob comparisonJob = analysisJobRepository.save(
                AnalysisJob.createCharacterFactComparison(candidate)
        );

        mockMvc.perform(post(CLAIM_URL)
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "modelName": "gpt-5.6-terra",
                                  "currentStep": "CHARACTER_FACT_COMPARISON",
                                  "allowedJobTypes": ["CHARACTER_FACT_COMPARISON"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisJobId").value(comparisonJob.getId().toString()))
                .andExpect(jsonPath("$.data.settingCandidateId").value(candidate.getId().toString()))
                .andExpect(jsonPath("$.data.batchId").value(nullValue()))
                .andExpect(jsonPath("$.data.episode").value(nullValue()))
                .andExpect(jsonPath("$.data.characterSettingSchemas", hasSize(1)))
                .andExpect(jsonPath("$.data.characterSettingSchemas[0].schemaKey").value("stats.strength"))
                .andExpect(jsonPath("$.data.knownCharacters", hasSize(0)));
    }

    @Test
    @DisplayName("active hidden Job에 위임된 후보는 원 분석 Job이 claim하거나 실패 처리하지 않는다")
    void activeHiddenComparisonOwnsCandidateAcrossSourceJobFailure() throws Exception {
        AnalysisJob sourceJob = episodeJob(AnalysisJobType.SETTING_EXTRACTION, firstEpisode);
        sourceJob.claim("gpt-5.6-terra", "캐릭터 비교", LocalDateTime.now().plusMinutes(5));
        sourceJob = analysisJobRepository.save(sourceJob);
        UUID sourceJobId = sourceJob.getId();
        WorkCharacter character = workCharacterRepository.save(WorkCharacter.create(
                work,
                "아리아",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));
        SettingCandidate candidate = settingCandidateRepository.save(SettingCandidate.create(
                work,
                firstEpisode,
                UUID.randomUUID(),
                sourceJob,
                SettingEntityType.CHARACTER,
                character.getName(),
                character.getName(),
                character.getId(),
                SettingCandidateMatchStatus.MATCHED,
                "age",
                "17",
                SettingValueType.NUMBER,
                objectMapper.createObjectNode().put("value", 17),
                objectMapper.createArrayNode(),
                new BigDecimal("0.90"),
                objectMapper.createObjectNode()
        ));
        AnalysisJob hiddenJob = analysisJobRepository.save(
                AnalysisJob.createCharacterFactComparison(candidate)
        );

        List<SettingCandidate> sourceClaimCandidates = new TransactionTemplate(transactionManager)
                .execute(status -> settingCandidateRepository.findComparisonClaimCandidates(
                        sourceJobId,
                        SettingCandidateReviewStatus.PENDING_REVIEW,
                        CharacterFactComparisonStatus.PENDING,
                        PageRequest.of(0, 1)
                ));
        assertThat(sourceClaimCandidates).isEmpty();
        assertThat(settingCandidateRepository.existsByAnalysisJobIdAndComparisonStatusIn(
                sourceJob.getId(),
                List.of(CharacterFactComparisonStatus.PENDING, CharacterFactComparisonStatus.PROCESSING)
        )).isFalse();
        assertThat(settingCandidateRepository.findAllByAnalysisJobIdAndComparisonStatusIn(
                sourceJob.getId(),
                List.of(CharacterFactComparisonStatus.PENDING, CharacterFactComparisonStatus.PROCESSING)
        )).isEmpty();

        mockMvc.perform(post("/api/internal/v1/analysis-jobs/{analysisJobId}/fail", sourceJob.getId())
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, sourceJob.getLeaseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"errorMessage\":\"원 분석 실패\"}"))
                .andExpect(status().isOk());

        assertThat(settingCandidateRepository.findById(candidate.getId()).orElseThrow().getComparisonStatus())
                .isEqualTo(CharacterFactComparisonStatus.PENDING);

        hiddenJob.claim("gpt-5.6-terra", "캐릭터 재비교", LocalDateTime.now().plusMinutes(5));
        analysisJobRepository.save(hiddenJob);
        mockMvc.perform(post("/api/internal/v1/analysis-jobs/{analysisJobId}/fail", hiddenJob.getId())
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, hiddenJob.getLeaseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"errorMessage\":\"provider 호출 전 실패\"}"))
                .andExpect(status().isOk());

        assertThat(settingCandidateRepository.findById(candidate.getId()).orElseThrow().getComparisonStatus())
                .isEqualTo(CharacterFactComparisonStatus.FAILED);
    }

    @Test
    @DisplayName("기각된 세계관 후보의 만료 hidden Job을 no-op 완료하고 다음 Job을 처리한다")
    void dismissedWorldCandidateDoesNotBlockHiddenComparisonQueue() throws Exception {
        AnalysisJob sourceJob = analysisJobRepository.save(
                episodeJob(AnalysisJobType.SETTING_EXTRACTION, firstEpisode)
        );
        WorldSettingCandidate dismissedCandidate = worldSettingCandidateRepository.saveAndFlush(
                worldCandidate(sourceJob, "폐허", "상태", "봉인됨")
        );
        AnalysisJob expiredHiddenJob = AnalysisJob.createWorldSettingComparison(dismissedCandidate);
        expiredHiddenJob.claim(
                "gpt-5.6-terra",
                "세계관 비교 재개",
                LocalDateTime.now().minusMinutes(1)
        );
        analysisJobRepository.saveAndFlush(expiredHiddenJob);
        dismissedCandidate.dismiss("사용자가 제외함", member);
        worldSettingCandidateRepository.saveAndFlush(dismissedCandidate);

        String reclaimedJobResponse = mockMvc.perform(post(CLAIM_URL)
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "modelName": "gpt-5.6-terra",
                                  "currentStep": "WORLD_SETTING_COMPARISON",
                                  "allowedJobTypes": ["WORLD_SETTING_COMPARISON"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisJobId").value(expiredHiddenJob.getId().toString()))
                .andExpect(jsonPath("$.data.claimAttemptCount").value(2))
                .andReturn().getResponse().getContentAsString();
        UUID reclaimedLeaseToken = UUID.fromString(
                objectMapper.readTree(reclaimedJobResponse).at("/data/leaseToken").asText()
        );

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/world-setting-comparisons/claim-next",
                                expiredHiddenJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, reclaimedLeaseToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/complete",
                                expiredHiddenJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, reclaimedLeaseToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        assertThat(analysisJobRepository.findById(expiredHiddenJob.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.SUCCEEDED);
        assertThat(worldSettingCandidateRepository.findById(dismissedCandidate.getId()))
                .get()
                .satisfies(candidate -> {
                    assertThat(candidate.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.DISMISSED);
                    assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.PENDING);
                });

        WorldSettingCandidate nextCandidate = worldSettingCandidateRepository.saveAndFlush(
                worldCandidate(sourceJob, "마탑", "위치", "황도 중앙")
        );
        AnalysisJob nextHiddenJob = analysisJobRepository.saveAndFlush(
                AnalysisJob.createWorldSettingComparison(nextCandidate)
        );

        mockMvc.perform(post(CLAIM_URL)
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "modelName": "gpt-5.6-terra",
                                  "currentStep": "WORLD_SETTING_COMPARISON",
                                  "allowedJobTypes": ["WORLD_SETTING_COMPARISON"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisJobId").value(nextHiddenJob.getId().toString()));

        List<AnalysisJob> hiddenJobs = analysisJobRepository.findAllById(
                List.of(expiredHiddenJob.getId(), nextHiddenJob.getId())
        );
        hiddenJobs.forEach(AnalysisJob::unlinkWorldSettingCandidate);
        analysisJobRepository.saveAllAndFlush(hiddenJobs);
    }

    @Test
    @DisplayName("null lease를 가진 기존 RUNNING 작업을 재claim하고 이전 token을 거절한다")
    void claimRecoversLegacyRunningJobsWithIncompleteLeases() throws Exception {
        AnalysisJob missingTokenJob = analysisJobRepository.save(
                episodeJob(AnalysisJobType.EPISODE_VALIDATION, firstEpisode)
        );
        AnalysisJob missingExpiryJob = analysisJobRepository.save(
                episodeJob(AnalysisJobType.EPISODE_VALIDATION, secondEpisode)
        );
        UUID staleLeaseToken = UUID.randomUUID();
        jdbcTemplate.update("""
                update analysis_jobs
                   set status = 'RUNNING',
                       lease_token = null,
                       lease_expires_at = ?,
                       claim_attempt_count = 1
                 where id = ?
                """, LocalDateTime.now().plusMinutes(5), missingTokenJob.getId());
        jdbcTemplate.update("""
                update analysis_jobs
                   set status = 'RUNNING',
                       lease_token = ?,
                       lease_expires_at = null,
                       claim_attempt_count = 1
                 where id = ?
                """, staleLeaseToken, missingExpiryJob.getId());

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/heartbeat",
                                missingExpiryJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, staleLeaseToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_JOB_LEASE_CONFLICT"));

        String firstClaim = mockMvc.perform(post(CLAIM_URL)
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DEFAULT_CLAIM_BODY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondClaim = mockMvc.perform(post(CLAIM_URL)
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DEFAULT_CLAIM_BODY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Set<UUID> reclaimedJobIds = Set.of(
                UUID.fromString(objectMapper.readTree(firstClaim).at("/data/analysisJobId").asText()),
                UUID.fromString(objectMapper.readTree(secondClaim).at("/data/analysisJobId").asText())
        );
        assertThat(reclaimedJobIds).containsExactlyInAnyOrder(
                missingTokenJob.getId(),
                missingExpiryJob.getId()
        );
        assertThat(analysisJobRepository.findAllById(reclaimedJobIds))
                .allSatisfy(job -> {
                    assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.RUNNING);
                    assertThat(job.getClaimAttemptCount()).isEqualTo(2);
                    assertThat(job.getLeaseToken()).isNotNull();
                    assertThat(job.getLeaseExpiresAt()).isNotNull();
                });

        mockMvc.perform(post(
                                "/api/internal/v1/analysis-jobs/{analysisJobId}/heartbeat",
                                missingExpiryJob.getId()
                        )
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, staleLeaseToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_JOB_LEASE_CONFLICT"));
    }

    @Test
    @DisplayName("분석 대상 회차가 없으면 claim한 작업을 실패 처리한다")
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
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DEFAULT_CLAIM_BODY))
                .andExpect(status().isNoContent());

        AnalysisJob failedJob = analysisJobRepository.findById(analysisJob.getId()).orElseThrow();
        assertThat(failedJob.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(failedJob.getErrorMessage()).contains("분석 대상 회차가 없습니다.");
    }

    @Test
    @DisplayName("복수 회차를 가진 과거 작업만 실패 처리하고 대상 회차 상태는 유지한다")
    void claimMarksLegacyMultiEpisodeJobFailed() throws Exception {
        firstEpisode.markAnalyzed();
        episodeRepository.save(firstEpisode);
        AnalysisJob analysisJob = AnalysisJob.create(
                work, uploadBatch, null, AnalysisJobType.EPISODE_VALIDATION);
        analysisJob.addTargetEpisodes(List.of(firstEpisode, secondEpisode));
        analysisJobRepository.save(analysisJob);

        mockMvc.perform(post(CLAIM_URL)
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DEFAULT_CLAIM_BODY))
                .andExpect(status().isNoContent());

        AnalysisJob failedJob = analysisJobRepository.findById(analysisJob.getId()).orElseThrow();
        assertThat(failedJob.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(failedJob.getErrorMessage()).contains("정확히 한 회차");
        assertThat(episodeRepository.findById(firstEpisode.getId()).orElseThrow().getStatus())
                .isEqualTo(org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.ANALYZED);
        assertThat(episodeRepository.findById(secondEpisode.getId()).orElseThrow().getStatus())
                .isEqualTo(org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.UPLOADED);
    }

    @Test
    @DisplayName("실행 중인 작업의 현재 진행 단계를 갱신한다")
    void updateProgressUpdatesRunningJobCurrentStep() throws Exception {
        AnalysisJob analysisJob = runningJob();

        mockMvc.perform(patch("/api/internal/v1/analysis-jobs/{analysisJobId}/progress", analysisJob.getId())
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, analysisJob.getLeaseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentStep": "LLM 전처리",
                                  "episodeStatus": "PREPROCESSING"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        AnalysisJob updatedJob = analysisJobRepository.findById(analysisJob.getId()).orElseThrow();
        assertThat(updatedJob.getCurrentStep()).isEqualTo("LLM 전처리");
        assertThat(episodeRepository.findById(firstEpisode.getId()).orElseThrow().getStatus())
                .isEqualTo(org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.PREPROCESSING);
        assertThat(episodeRepository.findById(secondEpisode.getId()).orElseThrow().getStatus())
                .isEqualTo(org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.UPLOADED);
    }

    @Test
    @DisplayName("실행 중인 작업을 완료하고 서버 원장의 토큰 합계를 기록한다")
    void completeRunningJobRecordsResultMetadata() throws Exception {
        AnalysisJob analysisJob = runningJob();

        mockMvc.perform(post("/api/internal/v1/analysis-jobs/{analysisJobId}/complete", analysisJob.getId())
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, analysisJob.getLeaseToken())
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
        // Worker가 임의로 보낸 값은 신뢰하지 않고, 이 테스트에는 정산 원장이 없으므로 0을 기록한다.
        assertThat(completedJob.getInputTokenCount()).isZero();
        assertThat(completedJob.getOutputTokenCount()).isZero();
        assertThat(completedJob.getCompletedAt()).isNotNull();
        assertThat(episodeRepository.findById(firstEpisode.getId()).orElseThrow().getStatus())
                .isEqualTo(org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.ANALYZED);
        assertThat(episodeRepository.findById(secondEpisode.getId()).orElseThrow().getStatus())
                .isEqualTo(org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.UPLOADED);
    }

    @Test
    @DisplayName("AI 요청을 예약·정산하고 분석 작업에 실제 합계를 기록한다")
    void reserveAndSettleAiTokensRecordsAuthoritativeJobTotals() throws Exception {
        AnalysisJob analysisJob = runningJob();
        UUID requestId = UUID.randomUUID();

        mockMvc.perform(post("/api/internal/v1/ai-token-usages/reserve")
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, analysisJob.getLeaseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "%s",
                                  "analysisJobId": "%s",
                                  "purpose": "SETTING_EXTRACTION",
                                  "attempt": 1,
                                  "modelName": "gpt-4.1-mini",
                                  "reservedTokens": 1000
                                }
                                """.formatted(requestId, analysisJob.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESERVED"));

        mockMvc.perform(post("/api/internal/v1/ai-token-usages/{requestId}/settle", requestId)
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputTokens": 120,
                                  "cachedInputTokens": 20,
                                  "outputTokens": 30,
                                  "outcome": "SUCCESS"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/internal/v1/analysis-jobs/{analysisJobId}/complete", analysisJob.getId())
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, analysisJob.getLeaseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summaryJson\":\"{}\"}"))
                .andExpect(status().isOk());

        AnalysisJob completedJob = analysisJobRepository.findById(analysisJob.getId()).orElseThrow();
        assertThat(completedJob.getInputTokenCount()).isEqualTo(120);
        assertThat(completedJob.getOutputTokenCount()).isEqualTo(30);

        String accessToken = jwtTokenProvider.generateAccessToken(member);
        mockMvc.perform(get("/api/v1/ai-token-usages/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usedTokens").value(150))
                .andExpect(jsonPath("$.data.reservedTokens").value(0))
                .andExpect(jsonPath("$.data.exhausted").value(false));
    }

    @Test
    @DisplayName("작업 종료와 정산 순서가 뒤바뀌어도 최종 토큰 합계를 동기화한다")
    void lateSettlementSynchronizesCompletedJobTotals() throws Exception {
        AnalysisJob analysisJob = runningJob();
        UUID requestId = UUID.randomUUID();

        mockMvc.perform(post("/api/internal/v1/ai-token-usages/reserve")
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, analysisJob.getLeaseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "%s",
                                  "analysisJobId": "%s",
                                  "purpose": "SETTING_EXTRACTION",
                                  "attempt": 1,
                                  "modelName": "gpt-5.6-terra",
                                  "reservedTokens": 1000
                                }
                                """.formatted(requestId, analysisJob.getId())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/internal/v1/analysis-jobs/{analysisJobId}/complete", analysisJob.getId())
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, analysisJob.getLeaseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summaryJson\":\"{}\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/internal/v1/ai-token-usages/{requestId}/settle", requestId)
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputTokens": 150,
                                  "cachedInputTokens": 50,
                                  "outputTokens": 40,
                                  "outcome": "SUCCESS"
                                }
                                """))
                .andExpect(status().isOk());

        AnalysisJob completedJob = analysisJobRepository.findById(analysisJob.getId()).orElseThrow();
        assertThat(completedJob.getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
        assertThat(completedJob.getInputTokenCount()).isEqualTo(150);
        assertThat(completedJob.getOutputTokenCount()).isEqualTo(40);
    }

    @Test
    @DisplayName("같은 요청 예약과 해제를 재호출해도 회원 예약량을 중복 변경하지 않는다")
    void reserveAndReleaseAreIdempotent() throws Exception {
        AnalysisJob analysisJob = runningJob();
        UUID requestId = UUID.randomUUID();
        String reserveBody = """
                {
                  "requestId": "%s",
                  "analysisJobId": "%s",
                  "purpose": "SUBJECT_RESOLUTION",
                  "attempt": 1,
                  "modelName": "gpt-4.1-mini",
                  "reservedTokens": 1000
                }
                """.formatted(requestId, analysisJob.getId());

        for (int retry = 0; retry < 2; retry++) {
            mockMvc.perform(post("/api/internal/v1/ai-token-usages/reserve")
                            .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                            .header(WORKER_LEASE_TOKEN_HEADER, analysisJob.getLeaseToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reserveBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("RESERVED"));
        }

        for (int retry = 0; retry < 2; retry++) {
            mockMvc.perform(post("/api/internal/v1/ai-token-usages/{requestId}/release", requestId)
                            .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"outcome\":\"USAGE_UNAVAILABLE\"}"))
                    .andExpect(status().isOk());
        }

        String accessToken = jwtTokenProvider.generateAccessToken(member);
        mockMvc.perform(get("/api/v1/ai-token-usages/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usedTokens").value(0))
                .andExpect(jsonPath("$.data.reservedTokens").value(0));
    }

    @Test
    @DisplayName("남은 한도보다 큰 AI 요청은 provider 호출 전에 안정적인 오류 코드로 거절한다")
    void reserveRejectsRequestOverRemainingQuota() throws Exception {
        AnalysisJob analysisJob = runningJob();

        mockMvc.perform(post("/api/internal/v1/ai-token-usages/reserve")
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, analysisJob.getLeaseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "%s",
                                  "analysisJobId": "%s",
                                  "purpose": "CHUNK_EMBEDDING",
                                  "attempt": 1,
                                  "modelName": "text-embedding-3-small",
                                  "reservedTokens": 2000001
                                }
                                """.formatted(UUID.randomUUID(), analysisJob.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AI_TOKEN_QUOTA_EXHAUSTED"));
    }

    @Test
    @DisplayName("실행 중인 작업을 실패 처리하고 오류 메시지를 기록한다")
    void failRunningJobRecordsErrorMessage() throws Exception {
        AnalysisJob analysisJob = runningJob();

        mockMvc.perform(post("/api/internal/v1/analysis-jobs/{analysisJobId}/fail", analysisJob.getId())
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, analysisJob.getLeaseToken())
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
        assertThat(episodeRepository.findById(firstEpisode.getId()).orElseThrow().getStatus())
                .isEqualTo(org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.FAILED);
        assertThat(episodeRepository.findById(secondEpisode.getId()).orElseThrow().getStatus())
                .isEqualTo(org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.UPLOADED);
    }

    @Test
    @DisplayName("세계관 후보 게시 뒤 토큰 부족은 완료 후보를 보존하고 남은 비교만 재개 가능하게 중단한다")
    void tokenQuotaAfterWorldCandidatesPreservesExtractionAndInterruptsRemainingComparisons() throws Exception {
        AnalysisJob analysisJob = episodeJob(AnalysisJobType.SETTING_EXTRACTION, firstEpisode);
        analysisJob.claim("gpt-5.6-terra", "세계관 비교", LocalDateTime.now().plusMinutes(5));
        analysisJob.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
        analysisJobRepository.saveAndFlush(analysisJob);

        WorldSettingCandidate completed = worldCandidate(analysisJob, "바바리안", "서식지", "혹한 지역");
        completed.startComparison();
        completed.completeComparison(
                null,
                WorldSettingOperation.ADD,
                "서식지",
                null,
                "혹한 지역",
                "새 설정",
                objectMapper.createObjectNode().put("operation", "ADD"),
                LocalDateTime.now()
        );
        WorldSettingCandidate pending = worldCandidate(analysisJob, "마탑", "위치", "황도 중앙");
        WorldSettingCandidate processing = worldCandidate(analysisJob, "왕국", "수도", "아르덴");
        processing.startComparison();
        WorldSettingCandidate dismissed = worldCandidate(analysisJob, "폐허", "상태", "봉인됨");
        dismissed.dismiss("사용자가 제외함", member);
        worldSettingCandidateRepository.saveAllAndFlush(List.of(completed, pending, processing, dismissed));

        mockMvc.perform(post("/api/internal/v1/analysis-jobs/{analysisJobId}/fail", analysisJob.getId())
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, analysisJob.getLeaseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "failureCode": "AI_TOKEN_QUOTA_EXHAUSTED",
                                  "errorMessage": "Client error 409 for url https://internal.example/token/reserve"
                                }
                                """))
                .andExpect(status().isOk());

        AnalysisJob failedJob = analysisJobRepository.findById(analysisJob.getId()).orElseThrow();
        assertThat(failedJob.getFailureCode()).isEqualTo(AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED);
        assertThat(failedJob.isResumableTokenInterruption()).isTrue();
        assertThat(episodeRepository.findById(firstEpisode.getId()).orElseThrow().getStatus())
                .isEqualTo(org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.ANALYZED);
        assertThat(worldSettingCandidateRepository.findById(completed.getId()).orElseThrow().getComparisonStatus())
                .isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(worldSettingCandidateRepository.findAllById(List.of(pending.getId(), processing.getId())))
                .allSatisfy(candidate -> {
                    assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.FAILED);
                    assertThat(candidate.getComparisonFailureCode())
                            .isEqualTo(AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED);
                });
        assertThat(worldSettingCandidateRepository.findById(dismissed.getId()))
                .get()
                .satisfies(candidate -> {
                    assertThat(candidate.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.DISMISSED);
                    assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.PENDING);
                    assertThat(candidate.getComparisonFailureCode()).isNull();
                });

        String accessToken = jwtTokenProvider.generateAccessToken(member);
        mockMvc.perform(get("/api/v1/works/{workId}/analysis-jobs/{analysisJobId}",
                                work.getId(), analysisJob.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failureCode").value("AI_TOKEN_QUOTA_EXHAUSTED"))
                .andExpect(jsonPath("$.data.tokenInterruptedAfterExtraction").value(true))
                .andExpect(jsonPath("$.data.errorMessage").value("AI 토큰이 부족해 분석이 중단되었습니다."));
    }

    @Test
    @DisplayName("추출 작업이 아닌 실패는 게시 체크포인트가 있어도 재개 가능한 토큰 중단으로 분류하지 않는다")
    void tokenQuotaAfterWorldCandidatesIsNotResumableForEpisodeValidation() throws Exception {
        AnalysisJob analysisJob = episodeJob(AnalysisJobType.EPISODE_VALIDATION, firstEpisode);
        analysisJob.claim("gpt-5.6-terra", "회차 검증", LocalDateTime.now().plusMinutes(5));
        analysisJob.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
        analysisJobRepository.saveAndFlush(analysisJob);

        mockMvc.perform(post("/api/internal/v1/analysis-jobs/{analysisJobId}/fail", analysisJob.getId())
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, analysisJob.getLeaseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "failureCode": "AI_TOKEN_QUOTA_EXHAUSTED",
                                  "errorMessage": "provider raw error"
                                }
                                """))
                .andExpect(status().isOk());

        AnalysisJob failedJob = analysisJobRepository.findById(analysisJob.getId()).orElseThrow();
        assertThat(failedJob.isResumableTokenInterruption()).isFalse();
        assertThat(episodeRepository.findById(firstEpisode.getId()).orElseThrow().getStatus())
                .isEqualTo(org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.FAILED);

        String accessToken = jwtTokenProvider.generateAccessToken(member);
        mockMvc.perform(get("/api/v1/works/{workId}/analysis-jobs/{analysisJobId}",
                                work.getId(), analysisJob.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenInterruptedAfterExtraction").value(false));
    }

    @Test
    @DisplayName("실행 중이 아닌 작업의 상태 변경을 거절한다")
    void statusUpdateRejectsNonRunningJob() throws Exception {
        AnalysisJob analysisJob = analysisJobRepository.save(
                episodeJob(AnalysisJobType.EPISODE_VALIDATION, firstEpisode)
        );

        mockMvc.perform(post("/api/internal/v1/analysis-jobs/{analysisJobId}/complete", analysisJob.getId())
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_JOB_STATUS_CONFLICT"));
    }

    @Test
    @DisplayName("존재하지 않는 작업의 상태 변경 요청에 404를 응답한다")
    void statusUpdateReturnsNotFoundForUnknownJob() throws Exception {
        mockMvc.perform(patch("/api/internal/v1/analysis-jobs/{analysisJobId}/progress", java.util.UUID.randomUUID())
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
                        .header(WORKER_LEASE_TOKEN_HEADER, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentStep": "원문 청킹",
                                  "episodeStatus": "CHUNKING"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_JOB_NOT_FOUND"));
    }

    private AnalysisJob runningJob() {
        AnalysisJob analysisJob = episodeJob(AnalysisJobType.EPISODE_VALIDATION, firstEpisode);
        analysisJob.claim("gpt-4.1-mini", "원문 청킹", LocalDateTime.now().plusMinutes(5));
        return analysisJobRepository.save(analysisJob);
    }

    private AnalysisJob episodeJob(AnalysisJobType jobType, Episode episode) {
        return AnalysisJob.create(work, uploadBatch, episode, jobType);
    }

    private WorldSettingCandidate worldCandidate(
            AnalysisJob analysisJob,
            String subjectName,
            String settingName,
            String value
    ) {
        return WorldSettingCandidate.create(
                work,
                firstEpisode,
                analysisJob,
                WorldSettingCategory.RACE,
                subjectName,
                settingName,
                value,
                objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                        .put("quote", subjectName + " 설정 근거")
                        .put("startOffset", 0)
                        .put("endOffset", 8)),
                new BigDecimal("0.9000"),
                objectMapper.createObjectNode().put("subjectName", subjectName)
        );
    }

    private CharacterSettingSchema settingSchema(
            Work schemaWork,
            String schemaKey,
            String attributePattern,
            String displayName,
            CharacterFactType factType,
            SettingValueType valueType,
            JsonNode aliases,
            CharacterSettingSchemaSource source,
            boolean enabled
    ) {
        return CharacterSettingSchema.create(
                schemaWork,
                schemaKey,
                attributePattern,
                displayName,
                factType,
                valueType,
                CharacterSettingValueSemantics.BASE_VALUE,
                factType == CharacterFactType.STATUS
                        ? CharacterSettingMergePolicy.UPSERT_BY_NAME
                        : CharacterSettingMergePolicy.REPLACE,
                aliases,
                source,
                enabled
        );
    }

    private JsonNode aliases(String... values) {
        var aliases = objectMapper.createArrayNode();
        for (String value : values) {
            aliases.add(value);
        }
        return aliases;
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
        file.markEpisodesParsed(startNo, endNo, episodeCount);
        return file;
    }
}
