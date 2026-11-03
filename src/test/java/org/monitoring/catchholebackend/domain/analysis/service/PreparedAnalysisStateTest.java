package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("요청 안에서 재사용하는 분석 상태의 이력·변경·검증 보존")
class PreparedAnalysisStateTest {
    private final AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
    private final AnalysisStateJournal journal = spy(new AnalysisStateJournal());
    private final AnalysisRunStateServiceImpl service = new AnalysisRunStateServiceImpl(jobs, journal, List.of(),
            mock(ApplicationEventPublisher.class), mock(AnalysisPendingReferenceSource.class));
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
        when(jobs.findAllByAnalysisRunIdAndRunGenerationOrderByRunSequenceAsc(any(), any())).thenReturn(List.of(job));
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
        verify(jobs, times(1)).findAllByAnalysisRunIdAndRunGenerationOrderByRunSequenceAsc(any(), any());
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
        clearInvocations(jobs);
        var nextRequest = service.prepareState(job);
        assertThat(nextRequest.projectedState()).isEqualTo(prepared.projectedState());
        verify(jobs).findAllByAnalysisRunIdAndRunGenerationOrderByRunSequenceAsc(any(), any());
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
}
