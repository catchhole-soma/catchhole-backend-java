package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:automatic-application-pending;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("자동 반영 대기 후보의 집계와 사용자 변경 보호")
class AutomaticApplicationPendingIntegrationTest {
    @Autowired EntityManager entities;
    @Autowired AnalysisRunStateService states;
    @Autowired JwtTokenProvider tokens;
    @Autowired MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();
    private Member owner;
    private Work work;
    private UploadBatch batch;
    private AnalysisJob job;
    private AnalysisJob next;
    private SettingCandidate character;
    private WorldSettingCandidate world;
    private String token;

    private void prepare(AnalysisReviewMode mode) {
        owner = Member.register("pending-review@example.invalid", "test-only", "01012345678", "검증 작가");
        entities.persist(owner);
        work = Work.create(owner, "자동 반영 대기 검증", WorkGenre.FANTASY, "격리 테스트");
        entities.persist(work);
        batch = UploadBatch.create(work, owner, UploadType.MULTI_EPISODE_MULTI_FILE, UploadSourceType.FILE);
        entities.persist(batch);
        job = newJob(1, mode);
        next = newJob(2, mode);
        states.initializeRun(List.of(job, next));
        ReflectionTestUtils.setField(job, "status", AnalysisJobStatus.RUNNING);
        ReflectionTestUtils.setField(job, "inputStateHash", "b".repeat(64));
        ReflectionTestUtils.setField(job, "automaticInputState", JsonNodeFactory.instance.objectNode().put("fixed", true));
        character = newCharacter(CharacterFactComparisonStatus.COMPLETED);
        world = newWorld(WorldSettingComparisonStatus.COMPLETED);
        entities.flush();
        token = tokens.generateAccessToken(owner);
    }

    @ParameterizedTest
    @CsvSource({"AUTOMATIC,PENDING,false,true", "AUTOMATIC,RUNNING,false,true",
            "AUTOMATIC,SUCCEEDED,false,false", "AUTOMATIC,FAILED,false,false", "AUTOMATIC,CANCELED,false,false",
            "AUTOMATIC,RUNNING,true,false", "AUTOMATIC,SUCCEEDED,true,false", "MANUAL,RUNNING,false,false"})
    @DisplayName("진행 중인 자동 원본 회차만 직접 확인 대신 진행 집계에 포함한다")
    void flagsAndCountsFollowSourceJob(AnalysisReviewMode mode, AnalysisJobStatus state, boolean applied,
                                      boolean waiting) throws Exception {
        prepare(mode);
        ReflectionTestUtils.setField(job, "status", state);
        if (applied) ReflectionTestUtils.setField(job, "automaticAppliedAt", LocalDateTime.now());
        newCharacter(CharacterFactComparisonStatus.FAILED);
        newWorld(WorldSettingComparisonStatus.FAILED);
        newCharacter(CharacterFactComparisonStatus.PENDING);
        newWorld(WorldSettingComparisonStatus.PENDING);
        var confirmedCharacter = newCharacter(CharacterFactComparisonStatus.COMPLETED);
        var confirmedWorld = newWorld(WorldSettingComparisonStatus.COMPLETED);
        ReflectionTestUtils.setField(confirmedCharacter, "reviewStatus", SettingCandidateReviewStatus.CONFIRMED);
        ReflectionTestUtils.setField(confirmedWorld, "reviewStatus", WorldSettingReviewStatus.CONFIRMED);
        var dismissedCharacter = newCharacter(CharacterFactComparisonStatus.COMPLETED);
        var dismissedWorld = newWorld(WorldSettingComparisonStatus.COMPLETED);
        ReflectionTestUtils.setField(dismissedCharacter, "reviewStatus", SettingCandidateReviewStatus.DISMISSED);
        ReflectionTestUtils.setField(dismissedWorld, "reviewStatus", WorldSettingReviewStatus.DISMISSED);
        entities.flush();
        assertThat(job.isAutomaticApplicationPending()).isEqualTo(waiting);
        assertThat(confirmedCharacter.isAutomaticApplicationPending()).isFalse();
        assertThat(confirmedWorld.isAutomaticApplicationPending()).isFalse();
        for (String domain : List.of("setting-candidates", "world-setting-candidates")) {
            String id = domain.equals("setting-candidates") ? character.getId().toString() : world.getId().toString();
            mvc.perform(auth(get(base(domain) + "/" + id).param("batchId", batch.getId().toString())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.automaticApplicationPending").value(waiting));
            mvc.perform(auth(get(base(domain)).param("batchId", batch.getId().toString())
                            .param("reviewStatus", "PENDING_REVIEW").param("size", "1")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalCandidateCount").value(5))
                    .andExpect(jsonPath("$.data.confirmedCandidateCount").value(1))
                    .andExpect(jsonPath("$.data.dismissedCandidateCount").value(1))
                    .andExpect(jsonPath("$.data.directReviewCandidateCount").value(waiting ? 0 : 2))
                    .andExpect(jsonPath("$.data.processingCandidateCount").value(waiting ? 3 : 1));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"character-edit", "character-match", "character-group-match", "character-confirm",
            "character-group-confirm", "character-dismiss", "character-recompare", "world-edit", "world-confirm",
            "world-group-confirm", "world-dismiss", "world-group-dismiss", "world-recompare", "world-resume"})
    @DisplayName("자동 반영 전 단건·그룹 조작은 무효화와 변경 없이 동일한 409로 거절한다")
    void rejectsMutationsBeforeInvalidatingRun(String action) throws Exception {
        prepare(AnalysisReviewMode.AUTOMATIC);
        Object sourceInput = job.getAutomaticInputState();
        Object sourceHash = job.getInputStateHash();
        if (action.equals("world-resume")) {
            ReflectionTestUtils.setField(world, "comparisonStatus", WorldSettingComparisonStatus.FAILED);
            ReflectionTestUtils.setField(world, "comparisonFailureCode", AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED);
        }
        entities.flush();
        mvc.perform(auth(mutation(action)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_AUTOMATIC_APPLICATION_PENDING"))
                .andExpect(jsonPath("$.message").value("이 회차의 설정을 자동으로 반영하고 있습니다. 완료된 뒤 다시 시도해 주세요."));
        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.RUNNING);
        assertThat(next.getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
        assertThat(job.getJournalStatus()).isNotEqualTo(AnalysisJournalStatus.INVALIDATED);
        assertThat(next.getJournalStatus()).isNotEqualTo(AnalysisJournalStatus.INVALIDATED);
        assertThat(job.getInputStateHash()).isEqualTo(sourceHash);
        assertThat(job.getAutomaticInputState()).isEqualTo(sourceInput);
        assertThat(character.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(world.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.PENDING_REVIEW);
        assertThat(world.getFinalOperation()).isNull();
    }

    @Test
    @DisplayName("완료된 앞 회차의 보류 후보를 수정하면 기존 정책대로 후속 분석을 무효화한다")
    void permitsCompletedEarlierReviewAndInvalidatesFuture() throws Exception {
        prepare(AnalysisReviewMode.AUTOMATIC);
        ReflectionTestUtils.setField(job, "status", AnalysisJobStatus.SUCCEEDED);
        ReflectionTestUtils.setField(job, "automaticAppliedAt", LocalDateTime.now());
        entities.flush();
        mvc.perform(auth(mutation("world-dismiss"))).andExpect(status().isOk());
        assertThat(world.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.DISMISSED);
        assertThat(next.getJournalStatus()).isEqualTo(AnalysisJournalStatus.INVALIDATED);
        assertThat(next.getStatus()).isEqualTo(AnalysisJobStatus.CANCELED);
    }

    private MockHttpServletRequestBuilder mutation(String action) throws Exception {
        String chars = base("setting-candidates"), worlds = base("world-setting-candidates");
        Map<String, Object> worldDecision = Map.of("candidateId", world.getId(), "operation", "ADD", "category", "RACE",
                "subjectName", "엘프", "settingName", "서식지", "value", "북부");
        return switch (action) {
            case "character-edit" -> body(patch(chars + "/" + character.getId()), Map.of("attributeName", "정신", "attributeValue", "36"));
            case "character-match" -> body(patch(chars + "/" + character.getId() + "/character-match"), Map.of("resolutionType", "CREATE_NEW", "entityName", "에르웬"));
            case "character-group-match" -> body(patch(chars + "/group-character-match"), Map.of("batchId", batch.getId(), "candidateIds", List.of(character.getId()), "resolutionType", "CREATE_NEW", "entityName", "에르웬"));
            case "character-confirm" -> body(post(chars + "/" + character.getId() + "/confirm"), Map.of("applicationMode", "APPLY_PROPOSAL"));
            case "character-group-confirm" -> body(post(chars + "/group-confirm"), Map.of("batchId", batch.getId(), "candidates", List.of(Map.of("candidateId", character.getId(), "applicationMode", "APPLY_PROPOSAL"))));
            case "character-dismiss" -> post(chars + "/" + character.getId() + "/dismiss");
            case "character-recompare" -> post(chars + "/" + character.getId() + "/recompare");
            case "world-edit" -> body(patch(worlds + "/decisions"), Map.of("batchId", batch.getId(), "candidates", List.of(worldDecision)));
            case "world-confirm" -> body(post(worlds + "/" + world.getId() + "/confirm"), worldDecision);
            case "world-group-confirm" -> body(post(worlds + "/group-confirm"), Map.of("batchId", batch.getId(), "candidates", List.of(worldDecision)));
            case "world-dismiss" -> body(post(worlds + "/" + world.getId() + "/dismiss"), Map.of());
            case "world-group-dismiss" -> body(post(worlds + "/group-dismiss"), Map.of("batchId", batch.getId(), "candidateIds", List.of(world.getId())));
            case "world-recompare" -> post(worlds + "/" + world.getId() + "/recompare");
            case "world-resume" -> post(worlds + "/batches/" + batch.getId() + "/resume-token-interrupted");
            default -> throw new IllegalArgumentException("알 수 없는 테스트 동작");
        };
    }

    private AnalysisJob newJob(int no, AnalysisReviewMode mode) {
        Episode episode = Episode.create(work, null, no, "원고", "pending-test/" + no, "v1", "a".repeat(64), 10);
        entities.persist(episode);
        AnalysisJob created = AnalysisJob.create(work, batch, episode, AnalysisJobType.SETTING_EXTRACTION);
        created.configureReviewMode(mode);
        return created;
    }

    private SettingCandidate newCharacter(CharacterFactComparisonStatus comparison) {
        var created = SettingCandidate.createCharacterDiscovery(work, job.getEpisode(), null, job,
                "에르웬", "에르웬", null, SettingCandidateMatchStatus.UNRESOLVED,
                JsonNodeFactory.instance.arrayNode(), BigDecimal.ONE, null);
        ReflectionTestUtils.setField(created, "comparisonStatus", comparison);
        entities.persist(created);
        return created;
    }

    private WorldSettingCandidate newWorld(WorldSettingComparisonStatus comparison) {
        var created = WorldSettingCandidate.create(work, job.getEpisode(), job,
                WorldSettingCategory.RACE, "엘프", "서식지", "북부", JsonNodeFactory.instance.arrayNode(), BigDecimal.ONE, null);
        ReflectionTestUtils.setField(created, "comparisonStatus", comparison);
        entities.persist(created);
        return created;
    }

    private String base(String domain) { return "/api/v1/works/" + work.getId() + "/" + domain; }
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object content) throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(content));
    }
}
