package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisRunContextMapper;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.service.CharacterAnalysisStateService;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingWorkerMapper;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonBatchRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonDecisionRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonDecisionSourceRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.service.OrderedWorldSettingWorker;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;

@DisplayName("자동 분석의 개별 비교 실패는 미확정 참고로만 보존한다")
class AutomaticComparisonFailureJournalTest {
    private final AnalysisJob job = mock(AnalysisJob.class);
    private final AnalysisRunStateService states = mock(AnalysisRunStateService.class);
    private final SettingCandidateRepository characters = mock(SettingCandidateRepository.class);
    private final WorldSettingCandidateRepository worlds = mock(WorldSettingCandidateRepository.class);
    private final AnalysisStateJournal journal = new AnalysisStateJournal();
    private final AnalysisHumanRejectionPolicy rejections = mock(AnalysisHumanRejectionPolicy.class);
    private final SettingCandidate character = mock(SettingCandidate.class);
    private final WorldSettingCandidate world = mock(WorldSettingCandidate.class);
    private final UUID candidateId = UUID.randomUUID();
    private final ObjectNode evidence = JsonNodeFactory.instance.objectNode().put("quote", "원문에 나온 설정");
    private final ObjectNode sourceValue = JsonNodeFactory.instance.objectNode().put("value", 36);
    private final ObjectNode stateJournal = JsonNodeFactory.instance.objectNode();
    private final List<AnalysisStateChange> changes = new ArrayList<>();
    private final CharacterAnalysisStateService characterService = new CharacterAnalysisStateService(
            states, characters, new CharacterAnalysisStateMapper(), rejections);
    private final OrderedWorldSettingWorker worldService = new OrderedWorldSettingWorker(states, journal,
            mock(AnalysisRunContextMapper.class), worlds, mock(WorldSettingComparisonBatchRepository.class),
            mock(WorldSettingComparisonDecisionRepository.class), mock(WorldSettingComparisonDecisionSourceRepository.class),
            mock(WorldSettingRepository.class), new WorldSettingWorkerMapper(), new WorldSettingAnalysisStateMapper(), rejections);

    @BeforeEach
    void setUp() {
        when(job.getId()).thenReturn(UUID.randomUUID());
        when(job.isOrderedProvisional()).thenReturn(true);
        when(job.isAutomaticReview()).thenReturn(true);
        when(job.getInputStateHash()).thenReturn("a".repeat(64));
        stateJournal.putArray("changes");
        when(job.getStateJournal()).thenReturn(stateJournal);
        when(states.getProjectedState(job)).thenReturn(journal.emptyState());
        doAnswer(call -> {
            changes.addAll(call.<List<AnalysisStateChange>>getArgument(2));
            return null;
        }).when(states).appendValidatedChanges(eq(job), eq("a".repeat(64)), any());
        Episode episode = mock(Episode.class);
        when(episode.getEpisodeNo()).thenReturn(3);
        when(character.getId()).thenReturn(candidateId);
        when(character.getEntityName()).thenReturn("비요른");
        when(character.getAttributeName()).thenReturn("정신");
        when(character.getAttributeValue()).thenReturn("36");
        when(character.getValueJson()).thenReturn(sourceValue);
        when(character.getEvidenceSpans()).thenReturn(evidence);
        when(character.getEpisode()).thenReturn(episode);
        when(character.getReviewStatus()).thenReturn(SettingCandidateReviewStatus.PENDING_REVIEW);
        when(character.getComparisonErrorMessage()).thenReturn("private stack trace and credentials");
        when(characters.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(job.getId())).thenReturn(List.of(character));
        when(world.getId()).thenReturn(candidateId);
        when(world.getCategory()).thenReturn(WorldSettingCategory.RACE);
        when(world.getSubjectName()).thenReturn("설인");
        when(world.getSettingName()).thenReturn("서식지");
        when(world.getExtractedValue()).thenReturn("북부 설원");
        when(world.getEvidenceSpans()).thenReturn(evidence);
        when(world.getSourceEpisode()).thenReturn(episode);
        when(world.getComparisonErrorMessage()).thenReturn("private stack trace and credentials");
        when(worlds.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(job.getId())).thenReturn(List.of(world));
        status("FAILED");
        failureCode(AnalysisFailureCode.COMPARISON_VALIDATION_FAILED);
        when(character.canDeferFailedComparison()).thenAnswer(call -> character.getComparisonStatus()
                == CharacterFactComparisonStatus.FAILED && character.getComparisonFailureCode().isCandidateComparisonFailure());
        when(world.canDeferFailedComparison()).thenAnswer(call -> world.getComparisonStatus()
                == WorldSettingComparisonStatus.FAILED && world.getComparisonFailureCode().isCandidateComparisonFailure());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("실패 후보의 값과 근거를 불변 참고로 남기며 현재 설정과 실패 상태를 바꾸지 않는다")
    void preservesFailedEvidenceOnlyAsReference(boolean characterDomain) {
        finalizeDomain(characterDomain);
        assertThat(changes).hasSize(1);
        AnalysisStateChange change = changes.getFirst();
        assertThat(change.path().getFirst()).isEqualTo("references");
        assertThat(change.sourceCandidateIds()).containsExactly(candidateId);
        assertThat(change.value().path("confirmationStatus").asText()).isEqualTo("UNCONFIRMED");
        assertThat(change.value().path(characterDomain ? "factValue" : "value").asText())
                .isEqualTo(characterDomain ? "36" : "북부 설원");
        assertThat(change.value().path("sourceEpisodeNo").asInt()).isEqualTo(3);
        assertThat(change.value().toString()).doesNotContain("private", "credentials");
        evidence.put("quote", "나중에 바꾼 근거");
        sourceValue.put("value", 99);
        assertThat(change.value().path("evidenceSpans").path("quote").asText()).isEqualTo("원문에 나온 설정");
        if (characterDomain) assertThat(change.value().path("valueJson").path("value").asInt()).isEqualTo(36);
        JsonNode projected = journal.apply(journal.emptyState(), changes);
        assertThat(projected.path("characters").size()).isZero();
        assertThat(projected.path("worldSettings").size()).isZero();
        assertThat(character.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.FAILED);
        assertThat(world.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.FAILED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("수동 분석의 비교 실패는 기존처럼 봉인 검증에서 누락으로 남긴다")
    void preservesManualFailureBehavior(boolean characterDomain) {
        when(job.isAutomaticReview()).thenReturn(false);
        finalizeDomain(characterDomain);
        assertThat(changes).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"true,PENDING", "true,PROCESSING", "false,PENDING", "false,PROCESSING"})
    @DisplayName("아직 비교 중인 후보를 검토 대기로 가장하지 않는다")
    void neverCoversUnfinishedComparison(boolean characterDomain, String comparisonStatus) {
        status(comparisonStatus);
        finalizeDomain(characterDomain);
        assertThat(changes).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"true,AI_TOKEN_QUOTA_EXHAUSTED", "true,WORKER_LEASE_EXPIRED",
            "false,AI_TOKEN_QUOTA_EXHAUSTED", "false,WORKER_LEASE_EXPIRED",
            "true,UNEXPECTED_ERROR", "false,UNEXPECTED_ERROR"})
    @DisplayName("토큰 부족·실행 권한 만료·내부 오류는 자동 진행할 개별 실패로 처리하지 않는다")
    void neverCoversInterruptedExecution(boolean characterDomain, AnalysisFailureCode code) {
        failureCode(code);
        finalizeDomain(characterDomain);
        assertThat(changes).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("이미 기록된 후보는 재시도에서 실패 참고를 중복 기록하지 않는다")
    void doesNotDuplicateCoveredCandidate(boolean characterDomain) {
        stateJournal.withArray("changes").addObject()
                .put("eventId", (characterDomain ? "character-comparison-failed:" : "world-comparison-failed:") + candidateId)
                .putArray("sourceCandidateIds").add(candidateId.toString());
        finalizeDomain(characterDomain);
        assertThat(changes).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("주체 등록에 사용한 근거도 비교 실패의 원문 참고를 별도로 남긴다")
    void identityCoverageDoesNotHideFailedReference(boolean characterDomain) {
        stateJournal.withArray("changes").addObject().put("eventId", "identity:" + candidateId)
                .putArray("sourceCandidateIds").add(candidateId.toString());
        finalizeDomain(characterDomain);
        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().value().path("confirmationStatus").asText()).isEqualTo("UNCONFIRMED");
    }

    private void status(String value) {
        when(character.getComparisonStatus()).thenReturn(CharacterFactComparisonStatus.valueOf(value));
        when(world.getComparisonStatus()).thenReturn(WorldSettingComparisonStatus.valueOf(value));
    }

    private void failureCode(AnalysisFailureCode code) {
        when(character.getComparisonFailureCode()).thenReturn(code);
        when(world.getComparisonFailureCode()).thenReturn(code);
    }

    private void finalizeDomain(boolean characterDomain) {
        if (characterDomain) characterService.finalizeChanges(job);
        else worldService.finalizeChanges(job);
    }
}
