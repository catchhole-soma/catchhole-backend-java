package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceRepository;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceView;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("요청 안에서 재사용하는 분석 상태의 이력·변경·검증 보존")
class PreparedAnalysisStateTest {
    private final AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
    private final AnalysisJobSourceRepository jobSources = mock(AnalysisJobSourceRepository.class);
    private final AnalysisStateJournal journal = spy(new AnalysisStateJournal());
    private final AnalysisRunStateServiceImpl service = new AnalysisRunStateServiceImpl(jobs, journal, List.of(),
            mock(ApplicationEventPublisher.class), mock(AnalysisPendingReferenceSource.class), jobSources,
            mock(org.monitoring.catchholebackend.domain.validation.processor.ValidationSettingsLock.class));
    private AnalysisJob job;
    private Episode episode;

    @BeforeEach
    void setUp() {
        Work work = Work.create(Member.register("state@example.com", "pass", "01012345678", "작가"),
                "상태 재사용", WorkGenre.FANTASY, "설명");
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        episode = Episode.create(work, null, 25, "25화", "test/source", "v1", "b".repeat(64), 100);
        ReflectionTestUtils.setField(episode, "id", UUID.randomUUID());
        job = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(job, "id", UUID.randomUUID());
        job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        ObjectNode input = journal.emptyState();
        input.withObject("characters").putObject("hero").putArray("historyFacts")
                .addObject().put("factValue", "검술 2단").put("quote", "2단 검사의 검을 들었다");
        job.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, input);
        job.captureAutomaticInput(input);
        job.prepareOrderedInput(journal.hash(input));
        job.claim("test", "comparison", LocalDateTime.now().plusMinutes(10));
        ObjectNode envelope = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        envelope.put("formatVersion", 1).put("runId", job.getAnalysisRunId().toString())
                .put("workId", work.getId().toString()).put("generation", 1).put("sequence", 0)
                .put("episodeNo", 25).put("jobId", job.getId().toString()).put("inputStateHash", job.getInputStateHash());
        envelope.putArray("changes");
        job.replacePendingJournal(envelope);
        when(jobSources.findRunMembers(any(), any())).thenAnswer(ignored -> List.of(sourceView(job)));
    }

    @Test
    @DisplayName("535회 읽기와 새 변경 반영에서도 선행 조회는 한 번이며 전체 replay와 결과가 같다")
    void reusesInputAndReplaysOnlyNewChangesWithoutDroppingHistory() {
        var prepared = service.prepareState(job);
        JsonNode input = job.getAutomaticInputState().deepCopy();
        for (int i = 0; i < 535; i++) {
            assertThat(prepared.projectedState().at("/characters/hero/historyFacts/0/quote").asText())
                    .isEqualTo("2단 검사의 검을 들었다");
        }
        ObjectNode town = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        town.putObject("properties");
        AnalysisStateChange identity = change("new-identity", List.of("worldSettings", "town"), town);
        prepared.append(List.of(identity));
        AnalysisStateChange property = change("town-name", List.of("worldSettings", "town", "properties", "name"),
                com.fasterxml.jackson.databind.node.TextNode.valueOf("북부 마을"));
        prepared.append(List.of(property));
        prepared.append(List.of(identity, property));
        assertThat(job.getStateJournal().path("changes")).hasSize(2);
        assertThat(prepared.projectedState()).isEqualTo(new AnalysisStateJournal().apply(input, List.of(identity, property)));
        assertThat(job.getAutomaticInputState()).isEqualTo(input);
        verify(jobSources, times(1)).findRunMembers(any(), any());
        verifyNoInteractions(jobs);
        verify(journal, times(3)).apply(any(), any()); // 최초 replay 1회 + 실제 변경 2회, 535회 읽기는 재사용

    }

    @Test
    @DisplayName("다른 helper가 추가한 변경도 같은 요청의 후속 읽기에 보이고 다음 요청은 다시 검증한다")
    void refreshesAfterExternalAppendAndRevalidatesNewSession() {
        var prepared = service.prepareState(job);
        AnalysisStateChange added = change("reference", List.of("references", "rejected"),
                com.fasterxml.jackson.databind.node.TextNode.valueOf("사용자 반려"));
        service.appendValidatedChanges(job, job.getInputStateHash(), List.of(added));
        assertThat(prepared.projectedState().at("/references/rejected").asText()).isEqualTo("사용자 반려");
        clearInvocations(jobSources);
        var nextRequest = service.prepareState(job);
        assertThat(nextRequest.projectedState()).isEqualTo(prepared.projectedState());
        verify(jobSources).findRunMembers(any(), any());
    }

    @Test
    @DisplayName("다른 내용의 중복 event와 잘못된 후속 경로는 부분 반영 없이 거절한다")
    void invalidAppendDoesNotMutateJournalOrProjection() {
        var prepared = service.prepareState(job);
        var first = change("event", List.of("references", "value"), com.fasterxml.jackson.databind.node.TextNode.valueOf("첫 값"));
        prepared.append(List.of(first));
        JsonNode before = prepared.projectedState().deepCopy();
        JsonNode record = job.getStateJournal().deepCopy();
        assertThatThrownBy(() -> prepared.append(List.of(change("event", first.path(),
                com.fasterxml.jackson.databind.node.TextNode.valueOf("다른 값"))))).isInstanceOf(AppException.class);
        var valid = change("next", List.of("references", "next"), first.value());
        var invalid = change("missing-parent", List.of("characters", "missing", "slot"), first.value());
        assertThatThrownBy(() -> prepared.append(List.of(valid, invalid))).isInstanceOf(AppException.class);
        assertThat(job.getStateJournal()).isEqualTo(record);
        assertThat(prepared.projectedState()).isEqualTo(before);
    }

    @Test
    @DisplayName("재사용 중에도 lease 만료와 원문 변경 검증을 유지한다")
    void preservesLeaseAndSourceChecks() {
        var prepared = service.prepareState(job);
        var change = change("event", List.of("references", "value"), com.fasterxml.jackson.databind.node.TextNode.valueOf("값"));
        job.renewLease(LocalDateTime.now().minusSeconds(1));
        assertThatThrownBy(() -> prepared.append(List.of(change))).isInstanceOf(AppException.class);
        job.renewLease(LocalDateTime.now().plusMinutes(10));
        episode.updateContent(25, "수정", "changed", "v2", "c".repeat(64), 100);
        assertThatThrownBy(() -> prepared.append(List.of(change))).isInstanceOf(AppException.class);
        assertThat(job.getStateJournal().path("changes")).isEmpty();
    }

    @Test
    @DisplayName("저장된 입력 hash가 다르면 재사용 상태를 만들지 않는다")
    void rejectsMismatchingFrozenInput() {
        ReflectionTestUtils.setField(job, "inputStateHash", "a".repeat(64));
        assertThatThrownBy(() -> service.prepareState(job)).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("검증의 중간 복사를 줄여도 외부 반환 상태는 저장 입력·이력과 분리된다")
    void keepsPublicInputAndProjectionDetachedFromStoredEvidence() {
        JsonNode original = job.getAutomaticInputState().deepCopy();
        JsonNode record = job.getStateJournal().deepCopy();
        ((ObjectNode) service.getInputState(job).at("/characters/hero/historyFacts/0")).put("quote", "외부 변경");
        ((ObjectNode) service.getProjectedState(job).at("/characters/hero/historyFacts/0")).put("quote", "다른 변경");
        service.assertValidInput(job);
        assertThat(job.getAutomaticInputState()).isEqualTo(original);
        assertThat(job.getStateJournal()).isEqualTo(record);

        ReflectionTestUtils.setField(job, "stateJournal", null);
        ((ObjectNode) service.getProjectedState(job).at("/characters/hero/historyFacts/0")).put("quote", "빈 journal 변경");
        assertThat(job.getAutomaticInputState()).isEqualTo(original);
        ((ObjectNode) job.getAutomaticInputState().at("/characters/hero/historyFacts/0")).put("quote", "저장 입력 변조");
        assertThatThrownBy(() -> service.assertValidInput(job)).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("lease 확인은 자동 누적 입력을 다시 hash하지 않고, 입력을 읽는 소비자는 변조된 입력을 각자 거절한다")
    void leaseCheckSkipsAutomaticHashWhileInputConsumersStillReject() {
        clearInvocations(journal);
        service.assertValidLeaseInput(job);
        verify(journal, never()).hash(any());

        ((ObjectNode) job.getAutomaticInputState().at("/characters/hero/historyFacts/0")).put("quote", "저장 입력 변조");
        service.assertValidLeaseInput(job);
        verify(journal, never()).hash(any());
        var change = change("event", List.of("references", "value"), com.fasterxml.jackson.databind.node.TextNode.valueOf("값"));
        List<Runnable> consumers = List.of(() -> service.assertValidInput(job), () -> service.prepareState(job),
                () -> service.getProjectedState(job), () -> service.appendValidatedChanges(job, job.getInputStateHash(), List.of(change)),
                () -> service.seal(job));
        for (Runnable consumer : consumers) {
            assertThat(rejected(consumer).getResultCode()).isEqualTo(AnalysisJobErrorCode.ANALYSIS_RUN_STATE_CONFLICT);
        }
        assertThat(job.getStateJournal().path("changes")).isEmpty();
        assertThat(job.getJournalStatus()).isEqualTo(org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.PENDING);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"invalidated", "incomplete", "sourceChanged", "nullHash",
            "missingInput", "brokenChain", "predecessorIncomplete"})
    @DisplayName("lease 확인은 hash 없이도 무효화·미완료·원문 변경·hash 없음·입력 없음·선행 회차 오류를 전체 검증과 같은 코드로 거절한다")
    void leaseCheckKeepsScalarRejections(String scenario) {
        AnalysisJob target = job;
        switch (scenario) {
            case "invalidated" -> job.invalidateJournal("원문 변경");
            case "incomplete" -> job.markJournalIncomplete();
            case "sourceChanged" -> episode.updateContent(25, "수정", "changed", "v2", "c".repeat(64), 100);
            case "nullHash" -> ReflectionTestUtils.setField(job, "inputStateHash", null);
            case "missingInput" -> ReflectionTestUtils.setField(job, "automaticInputState", null);
            case "brokenChain" -> when(jobSources.findRunMembers(any(), any())).thenReturn(List.of());
            default -> target = automaticSuccessorOf(job);
        }
        AnalysisJob checked = target;
        clearInvocations(journal);
        AppException lease = rejected(() -> service.assertValidLeaseInput(checked));
        verify(journal, never()).hash(any());
        AppException full = rejected(() -> service.assertValidInput(checked));

        assertThat(lease.getResultCode()).isEqualTo("predecessorIncomplete".equals(scenario)
                ? AnalysisJobErrorCode.ANALYSIS_RUN_PREDECESSOR_INCOMPLETE : AnalysisJobErrorCode.ANALYSIS_RUN_STATE_CONFLICT);
        assertThat(lease.getResultCode()).isEqualTo(full.getResultCode());
    }

    @Test
    @DisplayName("수동 누적의 lease 확인은 지금처럼 S0를 replay해 hash를 확인한다")
    void manualLeaseCheckStillReplaysAndHashes() {
        JsonNode base = job.getAutomaticInputState().deepCopy();
        AnalysisJob manual = AnalysisJob.create(job.getWork(), null, episode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(manual, "id", UUID.randomUUID());
        manual.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, base);
        manual.prepareOrderedInput(journal.hash(base));
        manual.claim("test", "comparison", LocalDateTime.now().plusMinutes(10));
        when(jobs.findAllByAnalysisRunIdAndRunGenerationOrderByRunSequenceAsc(manual.getAnalysisRunId(), 1L))
                .thenReturn(List.of(manual));

        clearInvocations(journal);
        service.assertValidLeaseInput(manual);
        verify(journal).hash(any());

        ObjectNode tampered = (ObjectNode) base.deepCopy();
        ((ObjectNode) tampered.at("/characters/hero/historyFacts/0")).put("quote", "S0 변조");
        ReflectionTestUtils.setField(manual, "runBaseState", tampered);
        assertThat(rejected(() -> service.assertValidLeaseInput(manual)).getResultCode())
                .isEqualTo(AnalysisJobErrorCode.ANALYSIS_RUN_STATE_CONFLICT);
    }

    private AnalysisJob automaticSuccessorOf(AnalysisJob predecessor) {
        Episode next = Episode.create(predecessor.getWork(), null, 26, "26화", "next", "v1", "c".repeat(64), 100);
        ReflectionTestUtils.setField(next, "id", UUID.randomUUID());
        AnalysisJob successor = AnalysisJob.create(predecessor.getWork(), null, next, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(successor, "id", UUID.randomUUID());
        successor.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        successor.initializeOrderedRun(predecessor.getAnalysisRunId(), 1, 1, predecessor.getId(), null);
        ObjectNode input = journal.emptyState();
        successor.captureAutomaticInput(input);
        successor.prepareOrderedInput(journal.hash(input));
        successor.claim("test", "comparison", LocalDateTime.now().plusMinutes(10));
        // 선행 회차(job)는 RUNNING이라 아직 SUCCEEDED·SEALED·자동 반영 완료가 아니다.
        when(jobSources.findRunMembers(any(), any())).thenAnswer(ignored -> List.of(sourceView(predecessor), sourceView(successor)));
        return successor;
    }

    @Test
    @DisplayName("자동 누적 생성은 설정을 capture하지 않고 표지만 저장하며, claim 때 한 번 복사한 입력은 원본과 분리된다")
    void automaticRunStoresMarkerWithoutCaptureAndFreezesInputAtClaim() {
        var nodes = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        ObjectNode characters = nodes.objectNode();
        characters.putObject("hero").putArray("historyFacts").addObject().put("quote", "보존 근거");
        ObjectNode worlds = nodes.objectNode();
        worlds.putObject("town").put("value", "보존 세계관");
        ObjectNode references = nodes.objectNode();
        references.putObject("existing").put("quote", "기존 참고");
        Map<String, Integer> captures = new LinkedHashMap<>();
        var capturedSources = List.of(source("characters", characters, captures), source("worldSettings", worlds, captures),
                source("references", references, captures));
        var pending = mock(AnalysisPendingReferenceSource.class);
        ObjectNode advisory = nodes.objectNode();
        advisory.putObject("pending").put("quote", "미확정 근거");
        when(pending.capture(any())).thenReturn(advisory);
        var capturingService = new AnalysisRunStateServiceImpl(jobs, journal, capturedSources,
                mock(ApplicationEventPublisher.class), pending, jobSources,
                mock(org.monitoring.catchholebackend.domain.validation.processor.ValidationSettingsLock.class));
        AnalysisJob target = AnalysisJob.create(job.getWork(), null, episode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(target, "id", UUID.randomUUID());
        target.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        when(jobSources.findRunMembers(any(), any())).thenAnswer(ignored -> List.of(sourceView(target)));

        capturingService.initializeRun(List.of(target));
        assertThat(captures).isEmpty();
        assertThat(target.getRunBaseState()).isEqualTo(nodes.objectNode().put("automaticInputCapturedAtClaim", true));
        UUID workId = job.getWork().getId();
        verify(jobs).findLatestCurrentCharacterSourceJobEpisodeNo(workId);
        verify(jobs).findLatestConfirmedDiscoveryJobEpisodeNo(workId);
        verify(jobs).findLatestConfirmedWorldSourceJobEpisodeNo(workId);

        assertThat(capturingService.prepareInput(target)).isTrue();
        assertThat(captures).containsExactly(Map.entry("characters", 1), Map.entry("worldSettings", 1), Map.entry("references", 1));
        assertThat(references.has("pending")).isFalse();
        ((ObjectNode) characters.at("/hero/historyFacts/0")).put("quote", "원본 변경");
        ((ObjectNode) worlds.path("town")).put("value", "원본 변경");
        ((ObjectNode) advisory.path("pending")).put("quote", "참고 변경");
        assertThat(target.getAutomaticInputState().at("/characters/hero/historyFacts/0/quote").asText()).isEqualTo("보존 근거");
        assertThat(target.getAutomaticInputState().at("/worldSettings/town/value").asText()).isEqualTo("보존 세계관");
        assertThat(target.getAutomaticInputState().at("/references/pending/quote").asText()).isEqualTo("미확정 근거");
        assertThat(target.getRunBaseState()).isEqualTo(nodes.objectNode().put("automaticInputCapturedAtClaim", true));
        capturingService.assertValidInput(target);
    }

    @Test
    @DisplayName("수동 누적 생성은 지금처럼 세 도메인을 capture해 저장 경계에서 한 번 복사한 S0로 replay한다")
    void manualRunFreezesCapturedSourcesAtPersistenceBoundary() {
        var nodes = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        ObjectNode characters = nodes.objectNode();
        characters.putObject("hero").putArray("historyFacts").addObject().put("quote", "보존 근거");
        ObjectNode worlds = nodes.objectNode();
        worlds.putObject("town").put("value", "보존 세계관");
        ObjectNode references = nodes.objectNode();
        references.putObject("existing").put("quote", "기존 참고");
        Map<String, Integer> captures = new LinkedHashMap<>();
        var capturingService = new AnalysisRunStateServiceImpl(jobs, journal,
                List.of(source("characters", characters, captures), source("worldSettings", worlds, captures),
                        source("references", references, captures)),
                mock(ApplicationEventPublisher.class), mock(AnalysisPendingReferenceSource.class), jobSources,
                mock(org.monitoring.catchholebackend.domain.validation.processor.ValidationSettingsLock.class));
        AnalysisJob target = AnalysisJob.create(job.getWork(), null, episode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(target, "id", UUID.randomUUID());
        target.configureReviewMode(AnalysisReviewMode.MANUAL);

        capturingService.initializeRun(List.of(target));
        assertThat(captures).containsExactly(Map.entry("characters", 1), Map.entry("worldSettings", 1), Map.entry("references", 1));
        verify(jobs, never()).findLatestCurrentCharacterSourceJobEpisodeNo(any());
        verify(jobs, never()).findLatestConfirmedDiscoveryJobEpisodeNo(any());
        verify(jobs, never()).findLatestConfirmedWorldSourceJobEpisodeNo(any());
        ((ObjectNode) characters.at("/hero/historyFacts/0")).put("quote", "원본 변경");
        ((ObjectNode) worlds.path("town")).put("value", "원본 변경");
        ((ObjectNode) references.path("existing")).put("quote", "원본 변경");
        assertThat(target.getRunBaseState().at("/characters/hero/historyFacts/0/quote").asText()).isEqualTo("보존 근거");
        assertThat(target.getRunBaseState().at("/worldSettings/town/value").asText()).isEqualTo("보존 세계관");
        assertThat(target.getRunBaseState().at("/references/existing/quote").asText()).isEqualTo("기존 참고");
        assertThat(target.getRunBaseState().has("automaticInputCapturedAtClaim")).isFalse();

        when(jobs.findAllByAnalysisRunIdAndRunGenerationOrderByRunSequenceAsc(target.getAnalysisRunId(), 1L))
                .thenReturn(List.of(target));
        assertThat(capturingService.prepareInput(target)).isTrue();
        assertThat(capturingService.getInputState(target)).isEqualTo(target.getRunBaseState());
        assertThat(target.getAutomaticInputState()).isNull();
        assertThat(captures).containsExactly(Map.entry("characters", 1), Map.entry("worldSettings", 1), Map.entry("references", 1));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"currentCharacterSource", "confirmedDiscovery", "confirmedWorldSource"})
    @DisplayName("자동 생성의 고정 회차 검사는 수동 S0 순회와 같은 오류 코드·HTTP 상태·빈 context로 거절하고 앞 회차는 허용한다")
    void automaticFrozenSourceCheckRejectsWithSameErrorAsManualWalk(String query) {
        var nodes = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        ObjectNode characters = nodes.objectNode();
        characters.putObject("character:hero").putObject("slots").putObject("STAT:level")
                .putObject("provenance").putArray("currentSourceTimes").addObject().put("sourceEpisodeNo", 25);
        var walkingService = new AnalysisRunStateServiceImpl(jobs, journal,
                List.of(source("characters", characters, new LinkedHashMap<>()),
                        source("worldSettings", nodes.objectNode(), new LinkedHashMap<>())),
                mock(ApplicationEventPublisher.class), mock(AnalysisPendingReferenceSource.class), jobSources,
                mock(org.monitoring.catchholebackend.domain.validation.processor.ValidationSettingsLock.class));
        AnalysisJob manual = AnalysisJob.create(job.getWork(), null, episode, AnalysisJobType.SETTING_EXTRACTION);
        manual.configureReviewMode(AnalysisReviewMode.MANUAL);
        AppException walked = rejected(() -> walkingService.initializeRun(List.of(manual)));

        UUID workId = job.getWork().getId();
        java.util.function.IntConsumer latestFrozenEpisodeNo = value -> {
            switch (query) {
                case "currentCharacterSource" -> when(jobs.findLatestCurrentCharacterSourceJobEpisodeNo(workId)).thenReturn(value);
                case "confirmedDiscovery" -> when(jobs.findLatestConfirmedDiscoveryJobEpisodeNo(workId)).thenReturn(value);
                default -> when(jobs.findLatestConfirmedWorldSourceJobEpisodeNo(workId)).thenReturn(value);
            }
        };
        latestFrozenEpisodeNo.accept(25);
        AnalysisJob automatic = AnalysisJob.create(job.getWork(), null, episode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(automatic, "id", UUID.randomUUID());
        automatic.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        AppException scalar = rejected(() -> walkingService.initializeRun(List.of(automatic)));

        assertThat(walked.getResultCode()).isEqualTo(AnalysisJobErrorCode.ANALYSIS_FUTURE_HISTORY_CONFLICT);
        assertThat(scalar.getResultCode()).isEqualTo(walked.getResultCode());
        assertThat(scalar.getResultCode().getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
        assertThat(scalar.getMessage()).isEqualTo(walked.getMessage());
        assertThat(scalar.getErrorContext()).isEmpty();
        assertThat(walked.getErrorContext()).isEmpty();
        assertThat(automatic.getAnalysisRunId()).isNull();
        verify(jobs, never()).save(any());

        latestFrozenEpisodeNo.accept(24);
        walkingService.initializeRun(List.of(automatic));
        assertThat(automatic.getRunBaseState()).isEqualTo(nodes.objectNode().put("automaticInputCapturedAtClaim", true));
    }

    @Test
    @DisplayName("반영 방식이 다른 Job을 한 실행에 섞지 않는다")
    void rejectsMixedReviewModesInOneRun() {
        Episode next = Episode.create(job.getWork(), null, 26, "26화", "next", "v1", "c".repeat(64), 100);
        ReflectionTestUtils.setField(next, "id", UUID.randomUUID());
        AnalysisJob automatic = AnalysisJob.create(job.getWork(), null, episode, AnalysisJobType.SETTING_EXTRACTION);
        automatic.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        AnalysisJob manual = AnalysisJob.create(job.getWork(), null, next, AnalysisJobType.SETTING_EXTRACTION);
        manual.configureReviewMode(AnalysisReviewMode.MANUAL);
        var sourcesService = new AnalysisRunStateServiceImpl(jobs, journal,
                List.of(source("characters", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(), new LinkedHashMap<>()),
                        source("worldSettings", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(), new LinkedHashMap<>())),
                mock(ApplicationEventPublisher.class), mock(AnalysisPendingReferenceSource.class), jobSources,
                mock(org.monitoring.catchholebackend.domain.validation.processor.ValidationSettingsLock.class));

        assertThatThrownBy(() -> sourcesService.initializeRun(List.of(automatic, manual)))
                .isInstanceOfSatisfying(AppException.class, error ->
                        assertThat(error.getResultCode()).isEqualTo(AnalysisJobErrorCode.ANALYSIS_RUN_STATE_CONFLICT));
    }

    @Test
    @DisplayName("설정집 자동 실행은 회차 번호가 없어 미래 출처 조회와 capture 없이 표지만 저장한다")
    void settingBookRunSkipsFutureChecksAndCapture() {
        Work work = job.getWork();
        var batch = org.monitoring.catchholebackend.domain.upload.entity.UploadBatch.create(work, work.getMember(),
                org.monitoring.catchholebackend.domain.upload.type.UploadType.SETTING_BOOK,
                org.monitoring.catchholebackend.domain.upload.type.UploadSourceType.TEXT_PASTE);
        var book = org.monitoring.catchholebackend.domain.upload.entity.UploadFile.create(batch,
                org.monitoring.catchholebackend.domain.upload.type.UploadFileRole.SETTING_BOOK, "합성 설정집.txt", "text/plain", "synthetic/raw", 10);
        ReflectionTestUtils.setField(book, "id", UUID.randomUUID());
        var revision = org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision.create(book, 1,
                new org.monitoring.catchholebackend.global.storage.StoredTextObject("immutable/book.txt", null, "c".repeat(64), 10));
        ReflectionTestUtils.setField(revision, "id", UUID.randomUUID());
        book.linkCurrentRevision(revision);
        AnalysisJob bookJob = AnalysisJob.createSettingBook(work, book, revision);
        Map<String, Integer> captures = new LinkedHashMap<>();
        var nodes = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var capturingService = new AnalysisRunStateServiceImpl(jobs, journal,
                List.of(source("characters", nodes.objectNode(), captures), source("worldSettings", nodes.objectNode(), captures)),
                mock(ApplicationEventPublisher.class), mock(AnalysisPendingReferenceSource.class), jobSources,
                mock(org.monitoring.catchholebackend.domain.validation.processor.ValidationSettingsLock.class));

        capturingService.initializeRun(List.of(bookJob));

        assertThat(captures).isEmpty();
        assertThat(bookJob.getRunBaseState()).isEqualTo(nodes.objectNode().put("automaticInputCapturedAtClaim", true));
        verify(jobs).save(bookJob);
        verifyNoMoreInteractions(jobs);
    }

    private AppException rejected(Runnable call) {
        try {
            call.run();
        } catch (AppException exception) {
            return exception;
        }
        throw new AssertionError("AppException이 발생해야 합니다.");
    }

    private AnalysisStateSource source(String domain, ObjectNode captured, Map<String, Integer> captures) {
        return new AnalysisStateSource() {
            @Override public String domain() { return domain; }
            @Override public JsonNode capture(Work work) {
                captures.merge(domain, 1, Integer::sum);
                return captured;
            }
        };
    }

    @Test
    @DisplayName("v1 이력을 보존한 채 두 저장 경로가 증분을 추가하고 v2로 승격하여 재개한다")
    void upgradesOnlyOnHistoryAppendAndResumesMixedStoredJournal() {
        var json = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var history = ((com.fasterxml.jackson.databind.node.ArrayNode) job.getAutomaticInputState()
                .at("/characters/hero/historyFacts")).deepCopy();
        history.addObject().put("factValue", "검술 3단").put("quote", "과거 저장 인용");
        var legacy = new AnalysisStateChange("legacy", List.of("characters", "hero", "historyFacts"), history,
                false, "UPDATE", List.of());
        service.appendValidatedChanges(job, job.getInputStateHash(), List.of(legacy));
        JsonNode oldRecord = job.getStateJournal();
        assertThat(oldRecord.path("formatVersion").asInt()).isEqualTo(1);
        var first = AnalysisStateChange.appendHistory("new-1", "hero",
                json.arrayNode().add(json.objectNode().put("factValue", "검술 4단").put("quote", "새 근거 😀")), "UPDATE", List.of());
        var prepared = service.prepareState(job);
        prepared.append(List.of(first, first));
        assertThat(job.getStateJournal().path("formatVersion").asInt()).isEqualTo(2);
        assertThat(job.getStateJournal().path("changes").get(0)).isEqualTo(oldRecord.path("changes").get(0));
        assertThat(job.getStateJournal().path("changes")).hasSize(2);
        var second = AnalysisStateChange.appendHistory("new-2", "hero",
                json.arrayNode().add(json.objectNode().put("factValue", "검술 5단")), "HISTORY_ONLY", List.of());
        service.appendValidatedChanges(job, job.getInputStateHash(), List.of(second, second));
        JsonNode expected = new AnalysisStateJournal().apply(job.getAutomaticInputState(), List.of(legacy, first, second));
        assertThat(prepared.projectedState()).isEqualTo(expected);
        assertThat(service.prepareState(job).projectedState()).isEqualTo(expected);
        assertThat(service.getProjectedState(job)).isEqualTo(expected);
        assertThat(expected.at("/characters/hero/historyFacts")).hasSize(4);
        assertThat(job.getStateJournal().path("changes")).hasSize(3);
        assertThat(AnalysisStateJournal.FORMAT_VERSION).isEqualTo(1);
        service.seal(job);
        assertThat(job.getStateJournal().path("outputStateHash").asText()).isEqualTo(journal.hash(expected));
    }

    @Test
    @DisplayName("잘못된 증분 저장은 버전과 상태를 부분 변경하지 않으며 다른 모드의 event 재사용을 거절한다")
    void invalidHistoryAppendKeepsVersionAndProjectionAtomic() {
        var json = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var prepared = service.prepareState(job);
        JsonNode before = job.getStateJournal();
        JsonNode state = prepared.projectedState().deepCopy();
        var valid = AnalysisStateChange.appendHistory("history", "hero", json.arrayNode().add("이력"), "UPDATE", List.of());
        var invalid = AnalysisStateChange.appendHistory("missing", "missing", json.arrayNode(), "UPDATE", List.of());
        assertThatThrownBy(() -> prepared.append(List.of(valid, invalid))).isInstanceOf(AppException.class);
        assertThat(job.getStateJournal()).isEqualTo(before);
        assertThat(prepared.projectedState()).isEqualTo(state);
        assertThatThrownBy(() -> service.appendValidatedChanges(job, job.getInputStateHash(), List.of(valid, invalid)))
                .isInstanceOf(AppException.class);
        assertThat(job.getStateJournal()).isEqualTo(before);
        prepared.append(List.of(valid));
        var replace = new AnalysisStateChange(valid.eventId(), valid.path(), valid.value(), false, valid.operation(), List.of());
        assertThatThrownBy(() -> prepared.append(List.of(replace))).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("v1으로 위장한 증분 및 지원하지 않는 저장 버전은 읽기를 거절한다")
    void rejectsAppendInVersionOneAndUnknownStorageVersions() {
        var json = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        ObjectNode record = (ObjectNode) job.getStateJournal();
        var append = AnalysisStateChange.appendHistory("history", "hero", json.arrayNode(), "UPDATE", List.of());
        record.set("changes", journal.toJson(List.of(append)));
        job.replacePendingJournal(record);
        assertThatThrownBy(() -> service.prepareState(job)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.getProjectedState(job)).isInstanceOf(AppException.class);
        record.put("formatVersion", 3);
        job.replacePendingJournal(record);
        assertThatThrownBy(() -> service.prepareState(job)).isInstanceOf(AppException.class);
        record.put("formatVersion", 4_294_967_297L);
        job.replacePendingJournal(record);
        assertThatThrownBy(() -> service.prepareState(job)).isInstanceOf(AppException.class);
        record.put("formatVersion", 2);
        job.replacePendingJournal(record);
        assertThat(service.prepareState(job).projectedState()).isEqualTo(job.getAutomaticInputState());
    }

    @Test
    @DisplayName("수동 순차 실행의 다음 회차는 v2 이력과 근거를 이전 회차 hash 그대로 복원한다")
    void manualSuccessorReplaysVersionTwoPredecessorAndPreservesOutputHash() {
        Work work = job.getWork();
        JsonNode base = job.getAutomaticInputState().deepCopy();
        AnalysisJob first = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(first, "id", UUID.randomUUID());
        first.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, base);
        first.prepareOrderedInput(journal.hash(base));
        first.claim("test", "comparison", LocalDateTime.now().plusMinutes(10));
        ObjectNode record = (ObjectNode) job.getStateJournal();
        record.put("runId", first.getAnalysisRunId().toString()).put("jobId", first.getId().toString());
        first.replacePendingJournal(record);
        Episode nextEpisode = Episode.create(work, null, 26, "26화", "next", "v1", "c".repeat(64), 100);
        ReflectionTestUtils.setField(nextEpisode, "id", UUID.randomUUID());
        AnalysisJob next = AnalysisJob.create(work, null, nextEpisode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(next, "id", UUID.randomUUID());
        next.initializeOrderedRun(first.getAnalysisRunId(), 1, 1, first.getId(), null);
        when(jobs.findAllByAnalysisRunIdAndRunGenerationOrderByRunSequenceAsc(first.getAnalysisRunId(), 1L))
                .thenReturn(List.of(first, next));
        var json = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var append = AnalysisStateChange.appendHistory("first-history", "hero",
                json.arrayNode().add(json.objectNode().put("factValue", "검술 3단").put("quote", "이전 회차 근거")), "UPDATE", List.of());
        service.appendValidatedChanges(first, first.getInputStateHash(), List.of(append));
        service.seal(first);
        first.succeed("{}", null, null);
        JsonNode expected = journal.apply(base, List.of(append));
        assertThat(service.getInputState(next)).isEqualTo(expected);
        assertThat(first.getStateJournal().path("outputStateHash").asText()).isEqualTo(journal.hash(expected));
        assertThat(first.getStateJournal().path("formatVersion").asInt()).isEqualTo(2);
        assertThat(first.getRunBaseState()).isEqualTo(base);
    }

    private AnalysisStateChange change(String id, List<String> path, JsonNode value) {
        return new AnalysisStateChange(id, path, value, false, "IDENTITY", List.of(UUID.randomUUID()));
    }

    private AnalysisJobSourceView sourceView(AnalysisJob source) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", source.getId());
        values.put("workId", source.getWork().getId());
        values.put("analysisMode", source.getAnalysisMode());
        values.put("reviewMode", source.getReviewMode());
        values.put("status", source.getStatus());
        values.put("journalStatus", source.getJournalStatus());
        values.put("automaticAppliedAt", source.getAutomaticAppliedAt());
        values.put("analysisRunId", source.getAnalysisRunId());
        values.put("runGeneration", source.getRunGeneration());
        values.put("runSequence", source.getRunSequence());
        values.put("predecessorJobId", source.getPredecessorJobId());
        values.put("sourceKind", source.getSourceKind());
        values.put("sourceEpisodeNo", source.getSourceEpisodeNo());
        values.put("sourceContentHash", source.getSourceContentHash());
        values.put("sourceContentS3Key", source.getSourceContentS3Key());
        values.put("sourceContentS3Version", source.getSourceContentS3Version());
        values.put("episodeId", source.getEpisode().getId());
        values.put("episodeNo", source.getEpisode().getEpisodeNo());
        values.put("episodeStatus", source.getEpisode().getStatus());
        values.put("episodeContentHash", source.getEpisode().getContentHash());
        values.put("episodeContentS3Key", source.getEpisode().getContentS3Key());
        values.put("episodeContentS3Version", source.getEpisode().getContentS3Version());
        return new SpelAwareProxyProjectionFactory().createProjection(AnalysisJobSourceView.class, values);
    }
}
