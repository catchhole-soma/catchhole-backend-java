package org.monitoring.catchholebackend.domain.character.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.CharacterFactRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
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
@DisplayName("설정 후보 검토 API 통합 테스트")
class SettingCandidateControllerIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WorkRepository workRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private UploadFileRepository uploadFileRepository;

    @Autowired
    private UploadBatchRepository uploadBatchRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private SettingCandidateRepository settingCandidateRepository;

    @Autowired
    private WorkCharacterRepository workCharacterRepository;

    @Autowired
    private CharacterFactRepository characterFactRepository;

    @Autowired
    private CharacterSnapshotSourceRepository characterSnapshotSourceRepository;

    @Autowired
    private CharacterSettingSchemaRepository characterSettingSchemaRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Member member;
    private Member otherMember;
    private Work work;
    private Work otherWork;
    private UploadBatch uploadBatch;
    private Episode episode;
    private AnalysisJob analysisJob;
    private String accessToken;

    @BeforeEach
    void setUp() {
        characterSnapshotSourceRepository.deleteAll();
        characterFactRepository.deleteAll();
        // 후보를 FK로 참조하는 hidden 비교 Job을 먼저 지워 양방향 FK 정리 순서를 지킨다.
        analysisJobRepository.deleteAll(analysisJobRepository.findAll().stream()
                .filter(job -> job.getJobType() == AnalysisJobType.CHARACTER_FACT_COMPARISON)
                .toList());
        analysisJobRepository.flush();
        settingCandidateRepository.deleteAll();
        workCharacterRepository.deleteAll();
        analysisJobRepository.deleteAll();
        episodeRepository.deleteAll();
        uploadFileRepository.deleteAll();
        uploadBatchRepository.deleteAll();
        characterSettingSchemaRepository.deleteAll();
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
        episode = episodeRepository.save(Episode.create(
                work,
                null,
                1,
                "1화",
                "works/%s/episodes/1.txt".formatted(work.getId()),
                "version-1",
                "hash-1",
                100
        ));
        analysisJob = analysisJobRepository.save(AnalysisJob.create(
                work,
                uploadBatch,
                episode,
                AnalysisJobType.SETTING_EXTRACTION
        ));
        characterSettingSchemaRepository.save(settingSchema(
                null,
                "age",
                null,
                CharacterFactType.AGE,
                SettingValueType.NUMBER,
                "나이"
        ));
        accessToken = jwtTokenProvider.generateAccessToken(member);
    }

    @Test
    @DisplayName("설정 후보 목록을 응답한다")
    void getSettingCandidatesReturnsCandidatesForAuthenticatedWork() throws Exception {
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "age",
                "17"
        ));

        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.batchId").value(uploadBatch.getId().toString()))
                .andExpect(jsonPath("$.data.episodeStartNo").value(1))
                .andExpect(jsonPath("$.data.episodeEndNo").value(1))
                .andExpect(jsonPath("$.data.episodeCount").value(1))
                .andExpect(jsonPath("$.data.totalCandidateCount").value(1))
                .andExpect(jsonPath("$.data.reviewedCandidateCount").value(0))
                .andExpect(jsonPath("$.data.pendingCandidateCount").value(1))
                .andExpect(jsonPath("$.data.matchRequiredCandidateCount").value(0))
                .andExpect(jsonPath("$.data.candidates.content[0].id").value(candidate.getId().toString()))
                .andExpect(jsonPath("$.data.candidates.content[0].workId").value(work.getId().toString()))
                .andExpect(jsonPath("$.data.candidates.content[0].episodeNo").value(1))
                .andExpect(jsonPath("$.data.candidates.content[0].entityName").value("아리아"))
                .andExpect(jsonPath("$.data.candidates.content[0].rawEntityMention").doesNotExist())
                .andExpect(jsonPath("$.data.candidates.content[0].matchedCharacterId").doesNotExist())
                .andExpect(jsonPath("$.data.candidates.content[0].matchStatus").value("UNRESOLVED"))
                .andExpect(jsonPath("$.data.candidates.content[0].attributeName").value("age"))
                .andExpect(jsonPath("$.data.candidates.content[0].attributeNameEditable").value(false))
                .andExpect(jsonPath("$.data.candidates.content[0].attributeNamePrefix").doesNotExist())
                .andExpect(jsonPath("$.data.candidates.content[0].reviewStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.candidates.content[0].rawAiResultJson").doesNotExist())
                .andExpect(jsonPath("$.data.candidates.page").value(0))
                .andExpect(jsonPath("$.data.candidates.size").value(20))
                .andExpect(jsonPath("$.data.candidates.totalElements").value(1))
                .andExpect(jsonPath("$.data.candidates.totalPages").value(1))
                .andExpect(jsonPath("$.data.candidates.hasNext").value(false));
    }

    @Test
    @DisplayName("같은 캐릭터 이름의 대기 후보를 하나의 그룹으로 묶는다")
    void getSettingCandidatesGroupsPendingCandidatesByCharacterName() throws Exception {
        settingCandidateRepository.saveAll(List.of(
                candidate(work, episode, analysisJob, "아리아", "age", "17"),
                candidate(work, episode, analysisJob, "  아리아  ", "level", "3")
        ));

        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString())
                        .queryParam("reviewStatus", "PENDING_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups.totalElements").value(1))
                .andExpect(jsonPath("$.data.groups.content[0].groupKey").value("아리아"))
                .andExpect(jsonPath("$.data.groups.content[0].entityName").value("아리아"))
                .andExpect(jsonPath("$.data.groups.content[0].candidateCount").value(2))
                .andExpect(jsonPath("$.data.groups.content[0].candidates.length()").value(2))
                .andExpect(jsonPath("$.data.groups.content[0].candidates[0].rawAiResultJson").doesNotExist());
    }

    @Test
    @DisplayName("그룹 검토 화면은 구형 단건 페이지를 제외해 중복 응답을 만들지 않는다")
    void getSettingCandidatesCanExcludeLegacyCandidatePage() throws Exception {
        settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "age",
                "17"
        ));

        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString())
                        .queryParam("includeLegacyCandidates", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups.content.length()").value(1))
                .andExpect(jsonPath("$.data.groups.content[0].candidates.length()").value(1))
                .andExpect(jsonPath("$.data.candidates").doesNotExist());
    }

    @Test
    @DisplayName("미상 캐릭터 그룹은 이름을 파악한 그룹보다 마지막에 반환한다")
    void getSettingCandidatesPlacesUnknownCharacterGroupLast() throws Exception {
        settingCandidateRepository.saveAll(List.of(
                candidate(work, episode, analysisJob, "미상", "age", "18"),
                candidate(work, episode, analysisJob, "아리아", "level", "2")
        ));

        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString())
                        .queryParam("reviewStatus", "PENDING_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups.content[0].entityName").value("아리아"))
                .andExpect(jsonPath("$.data.groups.content[1].entityName").value("미상"));
    }

    @Test
    @DisplayName("같은 이름의 모든 대기 후보를 선택한 기존 캐릭터에 일괄 연결한다")
    void updateSettingCandidateGroupCharacterMatchConnectsEveryPendingCandidate() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "나은"));
        SettingCandidate age = settingCandidateRepository.save(
                candidate(work, episode, analysisJob, "수아", "age", "18")
        );
        SettingCandidate level = settingCandidateRepository.save(
                candidate(work, episode, analysisJob, "수아", "level", "2")
        );

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/setting-candidates/group-character-match",
                                work.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "batchId", uploadBatch.getId(),
                                "candidateIds", List.of(age.getId(), level.getId()),
                                "resolutionType", "MATCH_EXISTING",
                                "matchedCharacterId", character.getId()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groupKey").value("나은"))
                .andExpect(jsonPath("$.data.candidates.length()").value(2))
                .andExpect(jsonPath("$.data.candidates[*].entityName")
                        .value(containsInAnyOrder("나은", "나은")))
                .andExpect(jsonPath("$.data.candidates[*].matchedCharacterId")
                        .value(containsInAnyOrder(
                                character.getId().toString(),
                                character.getId().toString()
                        )))
                .andExpect(jsonPath("$.data.candidates[*].matchStatus")
                        .value(containsInAnyOrder("MATCHED", "MATCHED")));

        assertThat(settingCandidateRepository.findAllById(List.of(age.getId(), level.getId())))
                .allSatisfy(candidate -> {
                    assertThat(candidate.getEntityName()).isEqualTo("나은");
                    assertThat(candidate.getMatchedCharacterId()).isEqualTo(character.getId());
                    assertThat(candidate.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.MATCHED);
                });
    }

    @Test
    @DisplayName("신규 캐릭터의 같은 이름 후보를 한 번에 확정해 캐릭터 하나와 이력을 만든다")
    void confirmSettingCandidateGroupCreatesOneCharacterAndConfirmsAllRows() throws Exception {
        characterSettingSchemaRepository.save(settingSchema(
                null, "level", null, CharacterFactType.LEVEL,
                SettingValueType.NUMBER, "레벨"
        ));
        SettingCandidate age = settingCandidateRepository.save(
                candidate(work, episode, analysisJob, "Aria Smith", "age", "17")
        );
        SettingCandidate level = settingCandidateRepository.save(
                candidate(work, episode, analysisJob, "aria  smith", "level", "3")
        );

        mockMvc.perform(post("/api/v1/works/{workId}/setting-candidates/group-confirm", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "batchId", uploadBatch.getId(),
                                "candidates", List.of(
                                        java.util.Map.of(
                                                "candidateId", age.getId(),
                                                "applicationMode", "APPLY_PROPOSAL"
                                        ),
                                        java.util.Map.of(
                                                "candidateId", level.getId(),
                                                "applicationMode", "APPLY_PROPOSAL"
                                        )
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates.length()").value(2))
                .andExpect(jsonPath("$.data.candidates[*].reviewStatus")
                        .value(containsInAnyOrder("CONFIRMED", "CONFIRMED")));

        assertThat(workCharacterRepository.findAll()).hasSize(1);
        assertThat(workCharacterRepository.findAll().getFirst().getName()).isEqualTo("Aria Smith");
        assertThat(characterFactRepository.findAll()).hasSize(2);
        assertThat(settingCandidateRepository.findAll())
                .extracting(SettingCandidate::getReviewStatus)
                .containsOnly(SettingCandidateReviewStatus.CONFIRMED);
    }

    @Test
    @DisplayName("그룹 전체 확정은 EXCLUDE 제안을 설정이나 이력 없이 자동 무시한다")
    void confirmSettingCandidateGroupAutomaticallyDismissesExcludeProposal() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "아리아"));
        SettingCandidate duplicate = candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "profile.species",
                "바바리안"
        );
        duplicate.matchExistingCharacter(character);
        duplicate.startComparison();
        duplicate.recordComparisonContext(0L, "context-hash");
        duplicate.completeComparison(
                CharacterFactOperation.EXCLUDE,
                CharacterFactType.PROFILE,
                "profile.species",
                null,
                null,
                objectMapper.createArrayNode(),
                CharacterFactTemporalScope.PRESENT,
                "현재 캐릭터의 종족과 같은 내용입니다.",
                objectMapper.createObjectNode().put("operation", "EXCLUDE"),
                LocalDateTime.of(2026, 8, 12, 12, 0)
        );
        settingCandidateRepository.save(duplicate);

        mockMvc.perform(post("/api/v1/works/{workId}/setting-candidates/group-confirm", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "batchId", uploadBatch.getId(),
                                "candidates", List.of(java.util.Map.of(
                                        "candidateId", duplicate.getId(),
                                        "applicationMode", "HISTORY_ONLY"
                                ))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates[0].reviewStatus").value("DISMISSED"));

        SettingCandidate saved = settingCandidateRepository.findById(duplicate.getId()).orElseThrow();
        assertThat(saved.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.DISMISSED);
        assertThat(characterFactRepository.findAll()).isEmpty();
        assertThat(characterSnapshotSourceRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("그룹 확정 후보 배열의 null 원소를 validation 오류로 거절한다")
    void confirmSettingCandidateGroupRejectsNullCandidateDecision() throws Exception {
        mockMvc.perform(post("/api/v1/works/{workId}/setting-candidates/group-confirm", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "batchId", uploadBatch.getId(),
                                "candidates", java.util.Arrays.asList((Object) null)
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("후보가 없어도 과거 다회차 작업의 보존된 대상 회차 범위를 응답한다")
    void getSettingCandidatesReturnsLegacyTargetEpisodeRangeWhenBatchHasNoCandidates() throws Exception {
        Episode fifthEpisode = episodeRepository.save(Episode.create(
                work,
                null,
                5,
                "5화",
                "works/%s/episodes/5.txt".formatted(work.getId()),
                "version-5",
                "hash-5",
                500
        ));
        analysisJobRepository.deleteAll();
        AnalysisJob legacyJob = AnalysisJob.create(
                work,
                uploadBatch,
                null,
                AnalysisJobType.SETTING_EXTRACTION
        );
        legacyJob.addTargetEpisodes(List.of(episode, fifthEpisode));
        analysisJobRepository.save(legacyJob);

        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.episodeStartNo").value(1))
                .andExpect(jsonPath("$.data.episodeEndNo").value(5))
                .andExpect(jsonPath("$.data.episodeCount").value(2))
                .andExpect(jsonPath("$.data.totalCandidateCount").value(0))
                .andExpect(jsonPath("$.data.reviewedCandidateCount").value(0))
                .andExpect(jsonPath("$.data.pendingCandidateCount").value(0))
                .andExpect(jsonPath("$.data.matchRequiredCandidateCount").value(0))
                .andExpect(jsonPath("$.data.candidates.content").isEmpty())
                .andExpect(jsonPath("$.data.candidates.totalElements").value(0))
                .andExpect(jsonPath("$.data.candidates.totalPages").value(0));
    }

    @Test
    @DisplayName("필터와 무관한 전체 집계와 필터된 후보 페이지를 구분한다")
    void getSettingCandidatesSeparatesBatchCountsFromFilteredPage() throws Exception {
        SettingCandidate ambiguous = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "미상",
                "age",
                "17",
                SettingValueType.NUMBER,
                valueJson("17"),
                SettingCandidateMatchStatus.AMBIGUOUS
        ));
        SettingCandidate confirmed = candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "level",
                "23"
        );
        confirmed.confirm();
        settingCandidateRepository.save(confirmed);
        SettingCandidate dismissed = candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "stats.strength",
                "40"
        );
        dismissed.dismiss();
        settingCandidateRepository.save(dismissed);

        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString())
                        .queryParam("reviewStatus", "PENDING_REVIEW")
                        .queryParam("matchStatuses", "AMBIGUOUS")
                        .queryParam("page", "0")
                        .queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCandidateCount").value(3))
                .andExpect(jsonPath("$.data.reviewedCandidateCount").value(2))
                .andExpect(jsonPath("$.data.pendingCandidateCount").value(1))
                .andExpect(jsonPath("$.data.matchRequiredCandidateCount").value(1))
                .andExpect(jsonPath("$.data.candidates.content.length()").value(1))
                .andExpect(jsonPath("$.data.candidates.content[0].id").value(ambiguous.getId().toString()))
                .andExpect(jsonPath("$.data.candidates.totalElements").value(1))
                .andExpect(jsonPath("$.data.candidates.size").value(1));
    }

    @Test
    @DisplayName("연결 상태 목록으로 직접 연결과 같은 이름 자동 연결 후보를 함께 페이지 조회한다")
    void getSettingCandidatesFiltersMultipleConnectedStatuses() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "아리아"));
        SettingCandidate matched = candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "age",
                "17"
        );
        matched.matchExistingCharacter(character);
        settingCandidateRepository.save(matched);
        SettingCandidate automaticallyMatched = candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "level",
                "5"
        );
        automaticallyMatched.autoMatchSameNameCharacter(character);
        settingCandidateRepository.save(automaticallyMatched);
        settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "신규 인물",
                "stats.strength",
                "12"
        ));

        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString())
                        .queryParam(
                                "matchStatuses",
                                "MATCHED",
                                "AUTO_MATCHED_BY_NAME"
                        )
                        .queryParam("page", "0")
                        .queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCandidateCount").value(3))
                .andExpect(jsonPath("$.data.candidates.totalElements").value(2))
                .andExpect(jsonPath("$.data.candidates.content.length()").value(2))
                .andExpect(jsonPath("$.data.candidates.content[*].matchStatus").value(
                        containsInAnyOrder("MATCHED", "AUTO_MATCHED_BY_NAME")
                ));
    }

    @Test
    @DisplayName("다른 작품의 업로드 묶음은 찾을 수 없음으로 숨긴다")
    void getSettingCandidatesRejectsBatchFromAnotherWork() throws Exception {
        UploadBatch otherBatch = uploadBatchRepository.save(UploadBatch.create(
                otherWork,
                otherMember,
                UploadType.INITIAL_IMPORT,
                UploadSourceType.FILE
        ));

        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", otherBatch.getId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SETTING_CANDIDATE_BATCH_NOT_FOUND"));
    }

    @Test
    @DisplayName("설정 후보 목록은 batchId와 유효한 페이지 범위를 요구한다")
    void getSettingCandidatesValidatesRequiredBatchAndPageRange() throws Exception {
        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_INVALID_ARGUMENT"));

        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString())
                        .queryParam("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates", work.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString())
                        .queryParam("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("설정 후보 상세 조회에서 JSON payload를 응답한다")
    void getSettingCandidateReturnsJsonPayloads() throws Exception {
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "age",
                "17"
        ));

        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates/{candidateId}", work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(candidate.getId().toString()))
                .andExpect(jsonPath("$.data.episodeId").value(episode.getId().toString()))
                .andExpect(jsonPath("$.data.episodeNo").value(1))
                .andExpect(jsonPath("$.data.analysisJobId").value(analysisJob.getId().toString()))
                .andExpect(jsonPath("$.data.entityType").value("CHARACTER"))
                .andExpect(jsonPath("$.data.rawEntityMention").doesNotExist())
                .andExpect(jsonPath("$.data.matchedCharacterId").doesNotExist())
                .andExpect(jsonPath("$.data.matchStatus").value("UNRESOLVED"))
                .andExpect(jsonPath("$.data.attributeNameEditable").value(false))
                .andExpect(jsonPath("$.data.attributeNamePrefix").doesNotExist())
                .andExpect(jsonPath("$.data.valueType").value("NUMBER"))
                .andExpect(jsonPath("$.data.valueJson.value").value(17))
                .andExpect(jsonPath("$.data.evidenceSpans[0].paragraph_index").value(1))
                .andExpect(jsonPath("$.data.rawAiResultJson.raw_value").value("17"));
    }

    @Test
    @DisplayName("활성 schema로 해석할 수 없는 후보 상세도 읽기 전용 설정명으로 응답한다")
    void getSettingCandidateReturnsConservativeEditMetadataWhenSchemaDoesNotMatch() throws Exception {
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "unknown.attribute",
                "알 수 없음"
        ));

        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates/{candidateId}", work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(candidate.getId().toString()))
                .andExpect(jsonPath("$.data.attributeName").value("unknown.attribute"))
                .andExpect(jsonPath("$.data.attributeNameEditable").value(false))
                .andExpect(jsonPath("$.data.attributeNamePrefix").doesNotExist());
    }

    @Test
    @DisplayName("상세 후보가 현재 검토 업로드 묶음과 다르면 찾을 수 없음으로 응답한다")
    void getSettingCandidateRejectsCandidateFromAnotherBatch() throws Exception {
        UploadBatch otherBatch = uploadBatchRepository.save(UploadBatch.create(
                work,
                member,
                UploadType.INITIAL_IMPORT,
                UploadSourceType.FILE
        ));
        AnalysisJob otherJob = analysisJobRepository.save(AnalysisJob.create(
                work,
                otherBatch,
                episode,
                AnalysisJobType.SETTING_EXTRACTION
        ));
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                otherJob,
                "아리아",
                "age",
                "17"
        ));

        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates/{candidateId}", work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SETTING_CANDIDATE_NOT_FOUND"));
    }

    @Test
    @DisplayName("검토 대기 설정 후보의 보정 가능 필드만 수정한다")
    void updateSettingCandidateUpdatesEditableFieldsOnly() throws Exception {
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "age",
                "17"
        ));

        mockMvc.perform(patch("/api/v1/works/{workId}/setting-candidates/{candidateId}", work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "attributeName": "  age  ",
                                  "attributeValue": "  23  "
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("설정 후보가 수정되었습니다."))
                .andExpect(jsonPath("$.data.id").value(candidate.getId().toString()))
                .andExpect(jsonPath("$.data.episodeId").value(episode.getId().toString()))
                .andExpect(jsonPath("$.data.sourceChunkId").value(candidate.getSourceChunkId().toString()))
                .andExpect(jsonPath("$.data.analysisJobId").value(analysisJob.getId().toString()))
                .andExpect(jsonPath("$.data.entityName").value("아리아"))
                .andExpect(jsonPath("$.data.attributeName").value("age"))
                .andExpect(jsonPath("$.data.attributeNameEditable").value(false))
                .andExpect(jsonPath("$.data.attributeNamePrefix").doesNotExist())
                .andExpect(jsonPath("$.data.attributeValue").value("23"))
                .andExpect(jsonPath("$.data.valueType").value("NUMBER"))
                .andExpect(jsonPath("$.data.valueJson.value").value(23))
                .andExpect(jsonPath("$.data.valueJson.source").doesNotExist())
                .andExpect(jsonPath("$.data.evidenceSpans[0].paragraph_index").value(1))
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.rawAiResultJson.raw_value").value("17"));

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm",
                                work.getId(),
                                candidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("CONFIRMED"));

        WorkCharacter character = workCharacterRepository.findByWorkIdAndNameAndStatus(
                work.getId(),
                "아리아",
                CharacterStatus.ACTIVE
        ).orElseThrow();
        assertThat(character.getCurrentAge()).isEqualTo(23);
        List<CharacterFact> facts =
                characterFactRepository.findAllByWorkCharacterIdAndFactTypeAndFactKeyOrderByEffectiveFromEpisodeNoDescCreatedAtDesc(
                        character.getId(),
                        CharacterFactType.AGE,
                        "age"
                );
        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().getValueJson()).hasToString("{\"value\":23}");
    }

    @Test
    @DisplayName("설정명과 값이 같으면 복합 JSON과 최초 근거를 그대로 보존한다")
    void updateSettingCandidatePreservesRichJsonWhenContentIsUnchanged() throws Exception {
        characterSettingSchemaRepository.save(settingSchema(
                work,
                "skills.skill",
                "skill.*",
                CharacterFactType.SKILL,
                SettingValueType.JSON,
                CharacterSettingMergePolicy.UPSERT_BY_NAME
        ));
        JsonNode valueJson = objectMapper.createObjectNode()
                .put("name", "화염 검술")
                .put("level", 5)
                .put("effect", "화염 공격");
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                " skill.화염 검술 ",
                " Lv.5 ",
                SettingValueType.JSON,
                valueJson
        ));

        mockMvc.perform(patch("/api/v1/works/{workId}/setting-candidates/{candidateId}", work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "attributeName": " skill.화염 검술 ",
                                  "attributeValue": " Lv.5 "
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributeName").value("skill.화염_검술"))
                .andExpect(jsonPath("$.data.attributeNameEditable").value(true))
                .andExpect(jsonPath("$.data.attributeNamePrefix").value("skill."))
                .andExpect(jsonPath("$.data.attributeValue").value("Lv.5"))
                .andExpect(jsonPath("$.data.valueType").value("JSON"))
                .andExpect(jsonPath("$.data.valueJson.name").value("화염 검술"))
                .andExpect(jsonPath("$.data.valueJson.level").value(5))
                .andExpect(jsonPath("$.data.valueJson.effect").value("화염 공격"))
                .andExpect(jsonPath("$.data.evidenceSpans[0].paragraph_index").value(1))
                .andExpect(jsonPath("$.data.rawAiResultJson.raw_value").value(" Lv.5 "));

        SettingCandidate saved = settingCandidateRepository.findById(candidate.getId()).orElseThrow();
        assertThat(saved.getAttributeName()).isEqualTo("skill.화염_검술");
        assertThat(saved.getAttributeValue()).isEqualTo("Lv.5");
        assertThat(saved.getValueJson()).isEqualTo(valueJson);
        assertThat(saved.getEvidenceSpans()).isEqualTo(evidenceSpans());
        assertThat(saved.getRawAiResultJson()).isEqualTo(rawAiResultJson(" Lv.5 "));
    }

    @Test
    @DisplayName("동적 JSON 후보 수정은 suffix를 정규화하고 name 외 구조화 속성을 제거한다")
    void updateSettingCandidateNormalizesDynamicNameAndKeepsJsonNameOnly() throws Exception {
        characterSettingSchemaRepository.save(settingSchema(
                work,
                "skills.skill",
                "skill.*",
                CharacterFactType.SKILL,
                SettingValueType.JSON,
                CharacterSettingMergePolicy.UPSERT_BY_NAME
        ));
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "skill.파이어볼",
                "Lv.3",
                SettingValueType.JSON,
                objectMapper.createObjectNode()
                        .put("name", "파이어볼")
                        .put("level", 3)
                        .put("effect", "화염 공격")
        ));

        mockMvc.perform(patch("/api/v1/works/{workId}/setting-candidates/{candidateId}", work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "attributeName": " skill.화염 검술 ",
                                  "attributeValue": " 주력기 "
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributeName").value("skill.화염_검술"))
                .andExpect(jsonPath("$.data.attributeNameEditable").value(true))
                .andExpect(jsonPath("$.data.attributeNamePrefix").value("skill."))
                .andExpect(jsonPath("$.data.attributeValue").value("주력기"))
                .andExpect(jsonPath("$.data.valueType").value("JSON"))
                .andExpect(jsonPath("$.data.valueJson.name").value("화염 검술"))
                .andExpect(jsonPath("$.data.valueJson.level").doesNotExist())
                .andExpect(jsonPath("$.data.valueJson.effect").doesNotExist())
                .andExpect(jsonPath("$.data.evidenceSpans[0].paragraph_index").value(1))
                .andExpect(jsonPath("$.data.rawAiResultJson.raw_value").value("Lv.3"));

        SettingCandidate saved = settingCandidateRepository.findById(candidate.getId()).orElseThrow();
        assertThat(saved.getValueJson()).hasToString("{\"name\":\"화염 검술\"}");
        assertThat(saved.getEvidenceSpans()).isEqualTo(evidenceSpans());
        assertThat(saved.getRawAiResultJson()).isEqualTo(rawAiResultJson("Lv.3"));
    }

    @Test
    @DisplayName("고정 schema 후보의 설정명 변경은 거절한다")
    void updateSettingCandidateRejectsFixedAttributeNameChange() throws Exception {
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "age",
                "17"
        ));

        mockMvc.perform(patch("/api/v1/works/{workId}/setting-candidates/{candidateId}", work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "attributeName": "level",
                                  "attributeValue": "17"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("SETTING_CANDIDATE_ATTRIBUTE_NAME_NOT_EDITABLE"));

        SettingCandidate saved = settingCandidateRepository.findById(candidate.getId()).orElseThrow();
        assertThat(saved.getAttributeName()).isEqualTo("age");
        assertThat(saved.getAttributeValue()).isEqualTo("17");
        assertThat(saved.getValueJson()).isEqualTo(valueJson("17"));
    }

    @Test
    @DisplayName("설정 후보를 기존 캐릭터에 연결한다")
    void updateSettingCandidateCharacterMatchConnectsExistingCharacter() throws Exception {
        WorkCharacter character = workCharacterRepository.save(character(work, "이안"));
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "미상",
                "age",
                "17"
        ));

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/character-match",
                                work.getId(),
                                candidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "resolutionType": "MATCH_EXISTING",
                                  "matchedCharacterId": "%s"
                                }
                                """.formatted(character.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("설정 후보 캐릭터 연결이 수정되었습니다."))
                .andExpect(jsonPath("$.data.id").value(candidate.getId().toString()))
                .andExpect(jsonPath("$.data.entityName").value("이안"))
                .andExpect(jsonPath("$.data.matchedCharacterId").value(character.getId().toString()))
                .andExpect(jsonPath("$.data.matchStatus").value("MATCHED"))
                .andExpect(jsonPath("$.data.attributeNameEditable").value(false))
                .andExpect(jsonPath("$.data.attributeNamePrefix").doesNotExist())
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING_REVIEW"));

        SettingCandidate saved = settingCandidateRepository.findById(candidate.getId()).orElseThrow();
        assertThat(saved.getEntityName()).isEqualTo("이안");
        assertThat(saved.getMatchedCharacterId()).isEqualTo(character.getId());
        assertThat(saved.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.MATCHED);
        assertThat(saved.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("설정 후보를 confirm 전 새 캐릭터 등록 예정으로 지정한다")
    void updateSettingCandidateCharacterMatchMarksAsNewCharacter() throws Exception {
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "미상",
                "age",
                "17"
        ));

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/character-match",
                                work.getId(),
                                candidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "resolutionType": "CREATE_NEW",
                                  "entityName": "  아리아  "
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(candidate.getId().toString()))
                .andExpect(jsonPath("$.data.entityName").value("아리아"))
                .andExpect(jsonPath("$.data.matchedCharacterId").doesNotExist())
                .andExpect(jsonPath("$.data.matchStatus").value("UNRESOLVED"))
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING_REVIEW"));

        SettingCandidate saved = settingCandidateRepository.findById(candidate.getId()).orElseThrow();
        assertThat(saved.getEntityName()).isEqualTo("아리아");
        assertThat(saved.getMatchedCharacterId()).isNull();
        assertThat(saved.getMatchStatus()).isEqualTo(SettingCandidateMatchStatus.UNRESOLVED);
        assertThat(saved.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("새 캐릭터 이름이 기존 캐릭터와 같으면 캐릭터 연결 해소를 거절한다")
    void updateSettingCandidateCharacterMatchRejectsDuplicateNewCharacterName() throws Exception {
        workCharacterRepository.save(character(work, "아리아"));
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "미상",
                "age",
                "17"
        ));

        mockMvc.perform(patch(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/character-match",
                                work.getId(),
                                candidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "resolutionType": "CREATE_NEW",
                                  "entityName": "아리아"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED"));
    }

    @Test
    @DisplayName("확정 또는 무시된 설정 후보 수정은 거절한다")
    void updateSettingCandidateRejectsNonPendingCandidates() throws Exception {
        SettingCandidate confirmed = candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "age",
                "17"
        );
        confirmed.confirm();
        confirmed = settingCandidateRepository.save(confirmed);

        SettingCandidate dismissed = candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "level",
                "23"
        );
        dismissed.dismiss();
        dismissed = settingCandidateRepository.save(dismissed);

        assertEditRejected(confirmed);
        assertEditRejected(dismissed);
    }

    @Test
    @DisplayName("검토 대기 설정 후보를 확정한다")
    void confirmSettingCandidateConfirmsPendingCandidate() throws Exception {
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "age",
                "17"
        ));

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm",
                                work.getId(),
                                candidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("설정 후보가 확정되었습니다."))
                .andExpect(jsonPath("$.data.id").value(candidate.getId().toString()))
                .andExpect(jsonPath("$.data.reviewStatus").value("CONFIRMED"));

        SettingCandidate saved = settingCandidateRepository.findById(candidate.getId()).orElseThrow();
        assertThat(saved.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.CONFIRMED);

        WorkCharacter character = workCharacterRepository.findByWorkIdAndNameAndStatus(
                work.getId(),
                "아리아",
                CharacterStatus.ACTIVE
        ).orElseThrow();
        List<CharacterFact> facts =
                characterFactRepository.findAllByWorkCharacterIdAndFactTypeAndFactKeyOrderByEffectiveFromEpisodeNoDescCreatedAtDesc(
                        character.getId(),
                        CharacterFactType.AGE,
                        "age"
                );
        assertThat(character.getCurrentAge()).isEqualTo(17);
        assertThat(character.getFirstAppearanceEpisodeId()).isEqualTo(episode.getId());
        assertThat(facts).hasSize(1);
        assertThat(characterSnapshotSourceRepository.existsBySourceFactId(facts.getFirst().getId())).isTrue();
        assertThat(facts.getFirst().getFactValue()).isEqualTo("17");
        assertThat(facts.getFirst().getEffectiveFromEpisodeNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("첫 신규 캐릭터 확정으로 연결된 형제 후보는 2차 비교 전 확정을 거절한다")
    void confirmSettingCandidatesWithSameNewCharacterUsesSingleCharacter() throws Exception {
        characterSettingSchemaRepository.save(settingSchema(
                null,
                "level",
                null,
                CharacterFactType.LEVEL,
                SettingValueType.NUMBER,
                "레벨"
        ));
        SettingCandidate ageCandidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "age",
                "17"
        ));
        SettingCandidate levelCandidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "  아리아  ",
                "level",
                "5"
        ));

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm",
                                work.getId(),
                                ageCandidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk());

        WorkCharacter character = workCharacterRepository.findByWorkIdAndNameAndStatus(
                work.getId(),
                "아리아",
                CharacterStatus.ACTIVE
        ).orElseThrow();
        SettingCandidate linkedFirst = settingCandidateRepository.findById(ageCandidate.getId()).orElseThrow();
        SettingCandidate linkedSibling = settingCandidateRepository.findById(levelCandidate.getId()).orElseThrow();
        assertThat(linkedFirst.getMatchedCharacterId()).isEqualTo(character.getId());
        assertThat(linkedFirst.getMatchStatus())
                .isEqualTo(SettingCandidateMatchStatus.AUTO_MATCHED_BY_NAME);
        assertThat(linkedSibling.getEntityName()).isEqualTo("아리아");
        assertThat(linkedSibling.getMatchedCharacterId()).isEqualTo(character.getId());
        assertThat(linkedSibling.getMatchStatus())
                .isEqualTo(SettingCandidateMatchStatus.AUTO_MATCHED_BY_NAME);
        assertThat(linkedSibling.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm",
                                work.getId(),
                                levelCandidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SETTING_CANDIDATE_COMPARISON_NOT_READY"));

        WorkCharacter updatedCharacter = workCharacterRepository.findById(character.getId()).orElseThrow();
        List<WorkCharacter> characters = workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId());
        assertThat(characters).hasSize(1);
        assertThat(characters.getFirst().getId()).isEqualTo(updatedCharacter.getId());
        assertThat(updatedCharacter.getCurrentAge()).isEqualTo(17);
        assertThat(updatedCharacter.getCurrentLevel()).isNull();
        assertThat(characterFactRepository.findAll()).hasSize(1);
        assertThat(settingCandidateRepository.findById(ageCandidate.getId()).orElseThrow().getMatchStatus())
                .isEqualTo(SettingCandidateMatchStatus.AUTO_MATCHED_BY_NAME);
        assertThat(settingCandidateRepository.findById(levelCandidate.getId()).orElseThrow().getMatchStatus())
                .isEqualTo(SettingCandidateMatchStatus.AUTO_MATCHED_BY_NAME);
        assertThat(settingCandidateRepository.findById(levelCandidate.getId()).orElseThrow().getReviewStatus())
                .isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("활성 schema와 매칭되지 않는 후보 확정은 rollback하고 부수효과를 남기지 않는다")
    void confirmSettingCandidateRollsBackUnmatchedSchema() throws Exception {
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "profile",
                "북부 기사단"
        ));

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm",
                                work.getId(),
                                candidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("SETTING_CANDIDATE_SCHEMA_NOT_MATCHED"));

        SettingCandidate saved = settingCandidateRepository.findById(candidate.getId()).orElseThrow();
        assertThat(saved.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId())).isEmpty();
        assertThat(characterFactRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("schema와 값 타입이 다른 후보 확정은 rollback하고 부수효과를 남기지 않는다")
    void confirmSettingCandidateRollsBackMismatchedValueType() throws Exception {
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "age",
                "열일곱",
                SettingValueType.STRING,
                objectMapper.createObjectNode().put("value", "열일곱")
        ));

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm",
                                work.getId(),
                                candidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("SETTING_CANDIDATE_VALUE_TYPE_MISMATCH"));

        SettingCandidate saved = settingCandidateRepository.findById(candidate.getId()).orElseThrow();
        assertThat(saved.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId())).isEmpty();
        assertThat(characterFactRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("AGE와 LEVEL 후보가 0 이상 int 정수가 아니면 확정을 rollback한다")
    void confirmSettingCandidateRollsBackInvalidCoreSnapshotValues() throws Exception {
        characterSettingSchemaRepository.save(settingSchema(
                null,
                "level",
                null,
                CharacterFactType.LEVEL,
                SettingValueType.NUMBER,
                "레벨"
        ));
        List<SettingCandidate> candidates = settingCandidateRepository.saveAll(List.of(
                candidate(
                        work,
                        episode,
                        analysisJob,
                        "아리아",
                        "age",
                        "-1",
                        SettingValueType.NUMBER,
                        objectMapper.createObjectNode().put("value", -1)
                ),
                candidate(
                        work,
                        episode,
                        analysisJob,
                        "아리아",
                        "level",
                        "23.5",
                        SettingValueType.NUMBER,
                        objectMapper.createObjectNode().put("value", new BigDecimal("23.5"))
                ),
                candidate(
                        work,
                        episode,
                        analysisJob,
                        "아리아",
                        "age",
                        "2147483648",
                        SettingValueType.NUMBER,
                        objectMapper.createObjectNode().put("value", 2147483648L)
                )
        ));

        for (SettingCandidate candidate : candidates) {
            mockMvc.perform(post(
                                    "/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm",
                                    work.getId(),
                                    candidate.getId()
                            )
                            .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("SETTING_CANDIDATE_VALUE_INVALID"));

            SettingCandidate saved = settingCandidateRepository.findById(candidate.getId()).orElseThrow();
            assertThat(saved.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        }
        assertThat(workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId())).isEmpty();
        assertThat(characterFactRepository.findAll()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "padded-key",
            "blank-key",
            "long-key",
            "reserved-key",
            "padded-text",
            "empty-text"
    })
    @DisplayName("왕복할 수 없는 구조화 공개 속성은 후보 확정을 rollback한다")
    void confirmSettingCandidateRollsBackInvalidStructuredProperties(String invalidCase) throws Exception {
        characterSettingSchemaRepository.save(settingSchema(
                work,
                "skills.skill",
                "skill.*",
                CharacterFactType.SKILL,
                SettingValueType.JSON,
                CharacterSettingMergePolicy.UPSERT_BY_NAME
        ));
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "skill.검술",
                "검술",
                SettingValueType.JSON,
                invalidStructuredValueJson(invalidCase)
        ));

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm",
                                work.getId(),
                                candidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SETTING_CANDIDATE_VALUE_JSON_INVALID"));

        assertThat(settingCandidateRepository.findById(candidate.getId()).orElseThrow().getReviewStatus())
                .isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId())).isEmpty();
        assertThat(characterFactRepository.findAll()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"missing-value", "mismatched-value"})
    @DisplayName("공개 속성이 있는 scalar 후보는 호환되는 value envelope가 필요하다")
    void confirmSettingCandidateRequiresCompatibleScalarEnvelope(String invalidCase) throws Exception {
        characterSettingSchemaRepository.save(settingSchema(
                work,
                "profile.rank",
                null,
                CharacterFactType.PROFILE,
                SettingValueType.STRING
        ));
        var valueJson = objectMapper.createObjectNode().put("name", "등급");
        if (invalidCase.equals("mismatched-value")) {
            valueJson.put("value", 3);
        }
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "profile.rank",
                "기사",
                SettingValueType.STRING,
                valueJson
        ));

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm",
                                work.getId(),
                                candidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SETTING_CANDIDATE_VALUE_JSON_INVALID"));

        assertThat(settingCandidateRepository.findById(candidate.getId()).orElseThrow().getReviewStatus())
                .isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId())).isEmpty();
        assertThat(characterFactRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("미지원 merge policy 후보 확정은 409로 rollback하고 부수효과를 남기지 않는다")
    void confirmSettingCandidateRollsBackUnsupportedMergePolicy() throws Exception {
        characterSettingSchemaRepository.save(settingSchema(
                work,
                "status.log",
                null,
                CharacterFactType.STATUS,
                SettingValueType.JSON,
                CharacterSettingMergePolicy.APPEND
        ));
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "status.log",
                "기록",
                SettingValueType.JSON,
                objectMapper.createObjectNode().put("name", "기록")
        ));

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm",
                                work.getId(),
                                candidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code")
                        .value("SETTING_CANDIDATE_MERGE_POLICY_UNSUPPORTED"));

        SettingCandidate saved = settingCandidateRepository.findById(candidate.getId()).orElseThrow();
        assertThat(saved.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId())).isEmpty();
        assertThat(characterFactRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("전역과 작품 schema가 같은 단계에서 매칭되면 확정을 rollback한다")
    void confirmSettingCandidateRollsBackAmbiguousSchemaMatch() throws Exception {
        characterSettingSchemaRepository.save(settingSchema(
                work,
                "age",
                null,
                CharacterFactType.AGE,
                SettingValueType.NUMBER
        ));
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "age",
                "17"
        ));

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm",
                                work.getId(),
                                candidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("SETTING_CANDIDATE_SCHEMA_MATCH_AMBIGUOUS"));

        SettingCandidate saved = settingCandidateRepository.findById(candidate.getId()).orElseThrow();
        assertThat(saved.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId())).isEmpty();
        assertThat(characterFactRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("검토 대기 설정 후보를 무시한다")
    void dismissSettingCandidateDismissesPendingCandidate() throws Exception {
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "level",
                "23"
        ));

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/dismiss",
                                work.getId(),
                                candidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("설정 후보가 무시되었습니다."))
                .andExpect(jsonPath("$.data.id").value(candidate.getId().toString()))
                .andExpect(jsonPath("$.data.reviewStatus").value("DISMISSED"));

        SettingCandidate saved = settingCandidateRepository.findById(candidate.getId()).orElseThrow();
        assertThat(saved.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.DISMISSED);
    }

    @Test
    @DisplayName("이미 같은 검토 상태인 설정 후보 전이는 성공으로 응답한다")
    void reviewStatusTransitionAllowsSameStatusRetry() throws Exception {
        SettingCandidate confirmed = candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "age",
                "17"
        );
        confirmed.confirm();
        confirmed = settingCandidateRepository.save(confirmed);

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm",
                                work.getId(),
                                confirmed.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(confirmed.getId().toString()))
                .andExpect(jsonPath("$.data.reviewStatus").value("CONFIRMED"));
    }

    @Test
    @DisplayName("이미 확정된 설정 후보 재시도는 CharacterFact를 중복 생성하지 않는다")
    void confirmSettingCandidateRetryDoesNotDuplicateCharacterFact() throws Exception {
        SettingCandidate candidate = settingCandidateRepository.save(candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "age",
                "17"
        ));

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm",
                                work.getId(),
                                candidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm",
                                work.getId(),
                                candidate.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("CONFIRMED"));

        WorkCharacter character = workCharacterRepository.findByWorkIdAndNameAndStatus(
                work.getId(),
                "아리아",
                CharacterStatus.ACTIVE
        ).orElseThrow();
        List<CharacterFact> facts =
                characterFactRepository.findAllByWorkCharacterIdAndFactTypeAndFactKeyOrderByEffectiveFromEpisodeNoDescCreatedAtDesc(
                        character.getId(),
                        CharacterFactType.AGE,
                        "age"
                );
        assertThat(facts).hasSize(1);
        assertThat(characterSnapshotSourceRepository.existsBySourceFactId(facts.getFirst().getId())).isTrue();
    }

    @Test
    @DisplayName("확정 또는 무시된 설정 후보의 반대 검토 상태 전이는 거절한다")
    void reviewStatusTransitionRejectsOppositeReviewedStatus() throws Exception {
        SettingCandidate confirmed = candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "age",
                "17"
        );
        confirmed.confirm();
        confirmed = settingCandidateRepository.save(confirmed);

        SettingCandidate dismissed = candidate(
                work,
                episode,
                analysisJob,
                "아리아",
                "level",
                "23"
        );
        dismissed.dismiss();
        dismissed = settingCandidateRepository.save(dismissed);

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/dismiss",
                                work.getId(),
                                confirmed.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath(
                        "$.message",
                        containsString("현재 검토 상태가 CONFIRMED(확정됨)인 설정 후보는 DISMISSED(무시됨)로 전환할 수 없습니다.")
                ))
                .andExpect(jsonPath("$.error.code").value("SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT"));

        mockMvc.perform(post(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm",
                                work.getId(),
                                dismissed.getId()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath(
                        "$.message",
                        containsString("현재 검토 상태가 DISMISSED(무시됨)인 설정 후보는 CONFIRMED(확정됨)로 전환할 수 없습니다.")
                ))
                .andExpect(jsonPath("$.error.code").value("SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT"));

        assertThat(workCharacterRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId())).isEmpty();
    }

    @Test
    @DisplayName("다른 회원 작품의 설정 후보 목록 조회는 거절한다")
    void getSettingCandidatesRejectsOtherMemberWork() throws Exception {
        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates", otherWork.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .queryParam("batchId", uploadBatch.getId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("WORK_NOT_FOUND"));
    }

    @Test
    @DisplayName("다른 회원 작품의 설정 후보 확정은 거절한다")
    void confirmSettingCandidateRejectsOtherMemberWork() throws Exception {
        mockMvc.perform(post(
                                "/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm",
                                otherWork.getId(),
                                UUID.randomUUID()
                        )
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("WORK_NOT_FOUND"));
    }

    @Test
    @DisplayName("설정 후보 목록 조회는 인증을 요구한다")
    void getSettingCandidatesRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/works/{workId}/setting-candidates", work.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("설정 후보 확정은 인증을 요구한다")
    void confirmSettingCandidateRequiresAuthentication() throws Exception {
        mockMvc.perform(post(
                        "/api/v1/works/{workId}/setting-candidates/{candidateId}/confirm",
                        work.getId(),
                        UUID.randomUUID()
                ))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHORIZED"));
    }

    private SettingCandidate candidate(
            Work targetWork,
            Episode targetEpisode,
            AnalysisJob targetAnalysisJob,
            String entityName,
            String attributeName,
            String attributeValue
    ) {
        return candidate(
                targetWork,
                targetEpisode,
                targetAnalysisJob,
                entityName,
                attributeName,
                attributeValue,
                SettingValueType.NUMBER,
                valueJson(attributeValue),
                SettingCandidateMatchStatus.UNRESOLVED
        );
    }

    private SettingCandidate candidate(
            Work targetWork,
            Episode targetEpisode,
            AnalysisJob targetAnalysisJob,
            String entityName,
            String attributeName,
            String attributeValue,
            SettingValueType valueType,
            JsonNode candidateValueJson
    ) {
        return candidate(
                targetWork,
                targetEpisode,
                targetAnalysisJob,
                entityName,
                attributeName,
                attributeValue,
                valueType,
                candidateValueJson,
                SettingCandidateMatchStatus.UNRESOLVED
        );
    }

    private SettingCandidate candidate(
            Work targetWork,
            Episode targetEpisode,
            AnalysisJob targetAnalysisJob,
            String entityName,
            String attributeName,
            String attributeValue,
            SettingValueType valueType,
            JsonNode candidateValueJson,
            SettingCandidateMatchStatus matchStatus
    ) {
        return SettingCandidate.create(
                targetWork,
                targetEpisode,
                UUID.randomUUID(),
                targetAnalysisJob,
                SettingEntityType.CHARACTER,
                entityName,
                null,
                null,
                matchStatus,
                attributeName,
                attributeValue,
                valueType,
                candidateValueJson,
                evidenceSpans(),
                new BigDecimal("0.8000"),
                rawAiResultJson(attributeValue)
        );
    }

    private WorkCharacter character(Work targetWork, String name) {
        return WorkCharacter.create(
                targetWork,
                name,
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
    }

    private void assertEditRejected(SettingCandidate candidate) throws Exception {
        mockMvc.perform(patch("/api/v1/works/{workId}/setting-candidates/{candidateId}", work.getId(), candidate.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "attributeName": "level",
                                  "attributeValue": "23"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("SETTING_CANDIDATE_NOT_EDITABLE"));
    }

    private JsonNode valueJson(String value) {
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return objectMapper.createObjectNode()
                    .put("value", value);
        }
        return objectMapper.createObjectNode()
                .put("value", Integer.parseInt(digits));
    }

    private JsonNode evidenceSpans() {
        return objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                        .put("paragraph_index", 1)
                        .put("quote", "열일곱 살의 아리아는 북부 기사단의 훈련장을 빠져나왔다."));
    }

    private JsonNode rawAiResultJson(String value) {
        return objectMapper.createObjectNode()
                .put("raw_value", value);
    }

    private JsonNode invalidStructuredValueJson(String invalidCase) {
        var valueJson = objectMapper.createObjectNode().put("value", "검술");
        return switch (invalidCase) {
            case "padded-key" -> valueJson.put(" level ", 3);
            case "blank-key" -> valueJson.put("   ", 3);
            case "long-key" -> valueJson.put("x".repeat(101), 3);
            case "reserved-key" -> valueJson.put(" value ", "검술");
            case "padded-text" -> valueJson.put("name", " 검술 ");
            case "empty-text" -> valueJson.put("name", "");
            default -> throw new IllegalArgumentException("지원하지 않는 테스트 케이스입니다.");
        };
    }

    private CharacterSettingSchema settingSchema(
            Work schemaWork,
            String schemaKey,
            String attributePattern,
            CharacterFactType factType,
            SettingValueType valueType,
            String... aliases
    ) {
        return settingSchema(
                schemaWork,
                schemaKey,
                attributePattern,
                factType,
                valueType,
                CharacterSettingMergePolicy.REPLACE,
                aliases
        );
    }

    private CharacterSettingSchema settingSchema(
            Work schemaWork,
            String schemaKey,
            String attributePattern,
            CharacterFactType factType,
            SettingValueType valueType,
            CharacterSettingMergePolicy mergePolicy,
            String... aliases
    ) {
        var aliasesJson = objectMapper.createArrayNode();
        for (String alias : aliases) {
            aliasesJson.add(alias);
        }
        return CharacterSettingSchema.create(
                schemaWork,
                schemaKey,
                attributePattern,
                schemaKey,
                factType,
                valueType,
                CharacterSettingValueSemantics.BASE_VALUE,
                mergePolicy,
                aliasesJson,
                CharacterSettingSchemaSource.SYSTEM_SEED,
                true
        );
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}
