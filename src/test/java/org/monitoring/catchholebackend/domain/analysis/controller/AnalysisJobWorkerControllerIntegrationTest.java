package org.monitoring.catchholebackend.domain.analysis.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics;
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
@DisplayName("분석 작업 Worker 내부 API 통합 테스트")
class AnalysisJobWorkerControllerIntegrationTest {

    private static final String INTERNAL_API_KEY = "local-development-internal-api-key";
    private static final String CLAIM_URL = "/api/internal/v1/analysis-jobs/claim";

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
    private WorkCharacterRepository workCharacterRepository;

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
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("활성 registry schema가 없으면 빈 characterSettingSchemas를 응답한다")
    void claimReturnsEmptyCharacterSettingSchemasWhenRegistryHasNoRows() throws Exception {
        analysisJobRepository.save(
                episodeJob(AnalysisJobType.EPISODE_VALIDATION, firstEpisode)
        );

        mockMvc.perform(post(CLAIM_URL)
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY))
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
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY))
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
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY))
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
    @DisplayName("실행 중인 작업을 완료하고 결과 메타데이터를 기록한다")
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
        assertThat(episodeRepository.findById(firstEpisode.getId()).orElseThrow().getStatus())
                .isEqualTo(org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.ANALYZED);
        assertThat(episodeRepository.findById(secondEpisode.getId()).orElseThrow().getStatus())
                .isEqualTo(org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.UPLOADED);
    }

    @Test
    @DisplayName("실행 중인 작업을 실패 처리하고 오류 메시지를 기록한다")
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
        assertThat(episodeRepository.findById(firstEpisode.getId()).orElseThrow().getStatus())
                .isEqualTo(org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.FAILED);
        assertThat(episodeRepository.findById(secondEpisode.getId()).orElseThrow().getStatus())
                .isEqualTo(org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.UPLOADED);
    }

    @Test
    @DisplayName("실행 중이 아닌 작업의 상태 변경을 거절한다")
    void statusUpdateRejectsNonRunningJob() throws Exception {
        AnalysisJob analysisJob = analysisJobRepository.save(
                episodeJob(AnalysisJobType.EPISODE_VALIDATION, firstEpisode)
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
    @DisplayName("존재하지 않는 작업의 상태 변경 요청에 404를 응답한다")
    void statusUpdateReturnsNotFoundForUnknownJob() throws Exception {
        mockMvc.perform(patch("/api/internal/v1/analysis-jobs/{analysisJobId}/progress", java.util.UUID.randomUUID())
                        .header(SecurityConstant.INTERNAL_API_KEY_HEADER, INTERNAL_API_KEY)
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
        analysisJob.start("gpt-4.1-mini", "원문 청킹");
        return analysisJobRepository.save(analysisJob);
    }

    private AnalysisJob episodeJob(AnalysisJobType jobType, Episode episode) {
        return AnalysisJob.create(work, uploadBatch, episode, jobType);
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
