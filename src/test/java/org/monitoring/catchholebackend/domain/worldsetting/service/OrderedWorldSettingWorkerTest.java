package org.monitoring.catchholebackend.domain.worldsetting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisRunContextMapper;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisHumanRejectionPolicy;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.worldsetting.dto.WorldSettingComparisonDiagnostic;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchCompleteRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchCompleteRequest.Decision;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchCompleteRequest.ContextVersion;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchContextRequest;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonBatch;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonDecision;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingWorkerMapper;
import org.monitoring.catchholebackend.domain.worldsetting.repository.*;
import org.monitoring.catchholebackend.domain.worldsetting.type.*;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("세계관 누적 Worker의 고정 입력과 검증 기록")
class OrderedWorldSettingWorkerTest {
    private final AnalysisRunStateService stateService = mock(AnalysisRunStateService.class);
    private final AnalysisStateJournal journal = new AnalysisStateJournal();
    private final WorldSettingCandidateRepository candidates = mock(WorldSettingCandidateRepository.class);
    private final WorldSettingComparisonBatchRepository batches = mock(WorldSettingComparisonBatchRepository.class);
    private final WorldSettingComparisonDecisionRepository decisions = mock(WorldSettingComparisonDecisionRepository.class);
    private final WorldSettingComparisonDecisionSourceRepository sources = mock(WorldSettingComparisonDecisionSourceRepository.class);
    private final WorldSettingRepository settings = mock(WorldSettingRepository.class);
    private final AtomicReference<JsonNode> state = new AtomicReference<>();
    private final WorldSettingWorkerMapper mapper = new WorldSettingWorkerMapper();
    private final OrderedWorldSettingWorker worker = new OrderedWorldSettingWorker(stateService, journal,
            new AnalysisRunContextMapper(new org.monitoring.catchholebackend.domain.character.mapper.CharacterFactEvidenceMapper(new org.monitoring.catchholebackend.domain.character.processor.CharacterFactSourceResolver())), candidates, batches, decisions, sources, settings, mapper,
            new WorldSettingAnalysisStateMapper(), mock(AnalysisHumanRejectionPolicy.class));
    private AnalysisJob job;
    private WorldSettingCandidate candidate;
    private WorldSettingComparisonBatch batch;
    private String key;
    private int pendingSequence;

    @BeforeEach
    void setUp() {
        Work work = Work.create(Member.register("world@example.com", "pass", "01012345678", "작가"),
                "작품", WorkGenre.FANTASY, "설명");
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        Episode episode = Episode.create(work, null, 2, "2화", "test/source", "v1", "b".repeat(64), 100);
        ReflectionTestUtils.setField(episode, "id", UUID.randomUUID());
        job = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(job, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(job, "analysisMode", AnalysisMode.ORDERED_PROVISIONAL);
        ReflectionTestUtils.setField(job, "analysisRunId", UUID.randomUUID());
        ReflectionTestUtils.setField(job, "runGeneration", 1L);
        ReflectionTestUtils.setField(job, "inputStateHash", "a".repeat(64));
        ReflectionTestUtils.setField(job, "sourceContentHash", "b".repeat(64));
        candidate = WorldSettingCandidate.create(work, episode, job, WorldSettingCategory.RACE,
                "설인", "서식지", "북부 설원에서만 산다", mapper.toJsonNode(List.of()), BigDecimal.ONE, null);
        ReflectionTestUtils.setField(candidate, "id", UUID.randomUUID());
        key = "provisional-world:" + UUID.randomUUID();
        candidate.resolveOrderedSubject(WorldSettingSubjectResolutionType.EXISTING, key, "설인",
                mapper.toJsonNode(List.of()), mapper.toJsonNode(List.of(key)));
        batch = WorldSettingComparisonBatch.createOrdered(work, episode, job, WorldSettingCategory.RACE,
                null, WorldSettingSubjectResolutionType.EXISTING, key, "설인", mapper.toJsonNode(List.of()),
                mapper.toJsonNode(List.of(key)), 1);
        ReflectionTestUtils.setField(batch, "id", UUID.randomUUID());
        candidate.startComparison(batch, "C1");
        ObjectNode initial = journal.emptyState();
        ObjectNode target = initial.withObject("worldSettings").putObject(key);
        target.putNull("actualWorldSettingId");
        target.put("provisionalSubjectKey", key);
        target.put("category", "RACE");
        target.put("subjectName", "설인");
        target.put("version", 1);
        target.putObject("propertiesJson").put("서식지", "북부");
        target.putObject("provenanceByPath").putObject(WorldSettingAnalysisStateMapper.pathKey(null, "서식지"))
                .put("confirmationStatus", "PROVISIONAL").put("sourceEpisodeNo", 1);
        state.set(initial);
        when(stateService.getProjectedState(job)).thenAnswer(invocation -> state.get().deepCopy());
        doAnswer(invocation -> {
            List<AnalysisStateChange> changes = invocation.getArgument(2);
            state.set(journal.apply(state.get(), changes));
            return null;
        }).when(stateService).appendValidatedChanges(eq(job), eq(job.getInputStateHash()), any());
        when(batches.findByIdAndWorkIdForUpdate(batch.getId(), work.getId())).thenReturn(Optional.of(batch));
        when(candidates.findAllByComparisonBatchIdOrderByCreatedAtAscIdAsc(batch.getId())).thenReturn(List.of(candidate));
        when(decisions.saveAndFlush(any())).thenAnswer(invocation -> {
            WorldSettingComparisonDecision value = invocation.getArgument(0);
            ReflectionTestUtils.setField(value, "id", UUID.randomUUID());
            return value;
        });
    }

    @Test
    @DisplayName("실제 FK 없이 앞 회차 임시 속성과 같은 문맥으로 MERGE를 검증해 불변 최종값을 기록한다")
    void completesAgainstProvisionalPropertyWithoutActualEntity() {
        var context = worker.getContext(job, batch.getId(), new WorkerWorldSettingComparisonBatchContextRequest(List.of(), List.of(key)));
        assertThat(context.targets().getFirst().worldSettingId()).isNull();
        assertThat(context.targets().getFirst().provisionalSubjectKey()).isEqualTo(key);
        assertThat(context.targets().getFirst().properties().getFirst().provenance()).isNotNull();
        worker.completeBatch(job, batch.getId(), request(context.contextToken(), WorldSettingConsolidationStatus.SINGLE));
        assertThat(candidate.getBeforeValue()).isEqualTo("북부");
        assertThat(candidate.getTargetWorldSetting()).isNull();
        assertThat(candidate.getProvisionalSubjectKey()).isEqualTo(key);
        assertThat(state.get().path("worldSettings").path(key).path("propertiesJson").path("서식지").asText())
                .isEqualTo("북부의 설원");
        String appliedHash = journal.hash(state.get());
        ReflectionTestUtils.setField(candidate, "extractedValue", "나중에 편집한 원본 후보");
        assertThat(journal.hash(state.get())).isEqualTo(appliedHash);
        verifyNoInteractions(settings);
    }

    @Test
    @DisplayName("CONFLICT 제안은 현재 속성을 덮지 않고 출처를 가진 별도 참고 기록으로 남긴다")
    void conflictRetainsReferenceInsteadOfOverwritingProperty() {
        var context = worker.getContext(job, batch.getId(), new WorkerWorldSettingComparisonBatchContextRequest(List.of(), List.of(key)));
        worker.completeBatch(job, batch.getId(), request(context.contextToken(), WorldSettingConsolidationStatus.CONFLICT));
        assertThat(state.get().path("worldSettings").path(key).path("propertiesJson").path("서식지").asText()).isEqualTo("북부");
        JsonNode reference = state.get().path("references").elements().next();
        assertThat(reference.path("consolidationStatus").asText()).isEqualTo("CONFLICT");
        assertThat(reference.path("sourceCandidateIds").get(0).asText()).isEqualTo(candidate.getId().toString());
        verifyNoInteractions(settings);
    }

    @Test
    @DisplayName("문맥 발급 뒤 입력 상태가 바뀌면 후보와 기록을 쓰기 전에 전체 완료를 거절한다")
    void staleContextRejectsBeforeAnyDecisionWrite() {
        var context = worker.getContext(job, batch.getId(), new WorkerWorldSettingComparisonBatchContextRequest(List.of(), List.of(key)));
        ((ObjectNode) state.get().path("worldSettings").path(key).path("propertiesJson")).put("서식지", "남부");
        assertThatThrownBy(() -> worker.completeBatch(job, batch.getId(), request(context.contextToken(), WorldSettingConsolidationStatus.SINGLE)))
                .isInstanceOf(AppException.class);
        assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.PROCESSING);
        verifyNoInteractions(decisions, sources, settings);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "1층")
    @DisplayName("명시된 후보 범위와 다른 기존 경로는 정상 비교 완료와 참고 기록만 남긴다")
    void scopeMismatchPreservesBothPathsWithoutChangingProjectedProperties(String matchedScope) {
        configureScopedCandidateAndTarget(matchedScope);
        JsonNode originalTarget = state.get().path("worldSettings").path(key).deepCopy();
        var context = worker.getContext(job, batch.getId(), new WorkerWorldSettingComparisonBatchContextRequest(List.of(), List.of(key)));

        worker.completeBatch(job, batch.getId(), scopeMismatchRequest(context.contextToken(), matchedScope, "서식지", "외부", "서식지", List.of("C1"), WorldSettingSuggestedOperation.REVIEW_REQUIRED));

        assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(candidate.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.PENDING_REVIEW);
        assertThat(candidate.getSuggestedOperation()).isEqualTo(WorldSettingSuggestedOperation.REVIEW_REQUIRED);
        assertThat(candidate.getComparisonReviewReason()).isEqualTo(WorldSettingComparisonReviewReason.SCOPE_MISMATCH);
        assertThat(candidate.getMatchedScopeName()).isEqualTo(matchedScope);
        assertThat(candidate.getMatchedPropertyName()).isEqualTo("서식지");
        assertThat(candidate.getProposedScopeName()).isEqualTo("외부");
        assertThat(candidate.getBeforeValue()).isEqualTo("북부");
        assertThat(state.get().path("worldSettings").path(key)).isEqualTo(originalTarget);
        JsonNode reference = state.get().path("references").elements().next();
        assertThat(reference.path("operation").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(reference.path("comparisonReviewReason").asText()).isEqualTo("SCOPE_MISMATCH");
        assertThat(reference.path("scopeName").asText()).isEqualTo("외부");
        assertThat(reference.path("matchedScopeName").isNull()).isEqualTo(matchedScope == null);
        if (matchedScope != null) assertThat(reference.path("matchedScopeName").asText()).isEqualTo(matchedScope);
        assertThat(reference.path("matchedPropertyName").asText()).isEqualTo("서식지");
        assertThat(reference.path("beforeValue").asText()).isEqualTo("북부");
        assertThat(reference.path("sourceCandidateIds").get(0).asText()).isEqualTo(candidate.getId().toString());
        verifyNoInteractions(settings);
    }

    @Test
    @DisplayName("같은 경로의 두 후보는 각각 범위 검토로 보존하고 독립된 새 설정은 함께 반영한다")
    void samePathReviewsDoNotBlockIndependentSettingInOneCompletion() {
        configureScopedCandidateAndTarget(null);
        ReflectionTestUtils.setField(job, "reviewMode", AnalysisReviewMode.AUTOMATIC);
        var second = WorldSettingCandidate.create(job.getWork(), job.getEpisode(), job, WorldSettingCategory.RACE,
                "설인", "외부", "서식지", "다른 북부 설원에서도 산다", mapper.toJsonNode(List.of()), BigDecimal.ONE, null);
        var third = WorldSettingCandidate.create(job.getWork(), job.getEpisode(), job, WorldSettingCategory.RACE,
                "설인", "외부", "활동 시간", "밤에 활동한다", mapper.toJsonNode(List.of()), BigDecimal.ONE, null);
        int index = 2;
        for (var added : List.of(second, third)) {
            ReflectionTestUtils.setField(added, "id", UUID.randomUUID());
            added.resolveOrderedSubject(WorldSettingSubjectResolutionType.EXISTING, key, "설인",
                    mapper.toJsonNode(List.of()), mapper.toJsonNode(List.of(key)));
            added.startComparison(batch, "C" + index++);
        }
        ReflectionTestUtils.setField(batch, "candidateCount", 3);
        when(candidates.findAllByComparisonBatchIdOrderByCreatedAtAscIdAsc(batch.getId()))
                .thenReturn(List.of(candidate, second, third));
        var context = worker.getContext(job, batch.getId(), new WorkerWorldSettingComparisonBatchContextRequest(List.of(), List.of(key)));
        var result = new WorkerWorldSettingComparisonBatchCompleteRequest(List.of(new ContextVersion(null, 1, key)), List.of(
                new Decision("D1", List.of("C1"), "설인", null, null, "서식지", List.of(),
                        WorldSettingConsolidationStatus.SINGLE, WorldSettingSuggestedOperation.REVIEW_REQUIRED,
                        WorldSettingComparisonReviewReason.SCOPE_MISMATCH, "외부", "서식지", candidate.getExtractedValue(),
                        "기존 서식지와 외부 서식지의 범위를 확인해야 합니다.", Map.of(), key),
                new Decision("D2", List.of("C2"), "설인", null, null, "서식지", List.of(),
                        WorldSettingConsolidationStatus.SINGLE, WorldSettingSuggestedOperation.REVIEW_REQUIRED,
                        WorldSettingComparisonReviewReason.SCOPE_MISMATCH, "외부", "서식지", second.getExtractedValue(),
                        "기존 서식지와 외부 서식지의 범위를 확인해야 합니다.", Map.of(), key),
                new Decision("D3", List.of("C3"), "설인", null, null, null, List.of(),
                        WorldSettingConsolidationStatus.SINGLE, WorldSettingSuggestedOperation.ADD,
                        null, "외부", "활동 시간", third.getExtractedValue(), "새 활동 시간을 기록합니다.", Map.of(), key)
        ), Map.of(), context.contextToken());

        worker.completeBatch(job, batch.getId(), result);

        assertThat(batch.getStatus()).isEqualTo(WorldSettingComparisonBatchStatus.COMPLETED);
        for (var held : List.of(candidate, second)) {
            assertThat(held.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
            assertThat(held.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.PENDING_REVIEW);
            assertThat(held.getComparisonReviewReason()).isEqualTo(WorldSettingComparisonReviewReason.SCOPE_MISMATCH);
            assertThat(held.getBeforeValue()).isEqualTo("북부");
            assertThat(held.getProposedValue()).isEqualTo(held.getExtractedValue());
        }
        assertThat(third.getSuggestedOperation()).isEqualTo(WorldSettingSuggestedOperation.ADD);
        assertThat(state.get().path("references").size()).isEqualTo(2);
        var properties = state.get().path("worldSettings").path(key).path("propertiesJson");
        assertThat(properties.path("서식지").asText()).isEqualTo("북부");
        assertThat(properties.path("외부").path("활동 시간").asText()).isEqualTo("밤에 활동한다");
        assertThat(state.get().path("worldSettings").path(key).path("version").asInt()).isEqualTo(2);
        verifyNoInteractions(settings);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-source-scope", "same-scope", "missing-property", "unknown-property", "moved-proposal", "renamed-proposal", "multiple-sources", "concrete-update"})
    @DisplayName("범위 불일치 검토는 없는 경로·범위 이동·다중 출처·직접 갱신을 허용하지 않는다")
    void scopeMismatchRejectsInvalidPathsAndMultipleSourcesBeforeWriting(String invalidCase) {
        configureScopedCandidateAndTarget("1층");
        String matchedScope = invalidCase.equals("same-scope") ? "외부" : "1층";
        String matchedProperty = invalidCase.equals("missing-property") ? null
                : invalidCase.equals("unknown-property") ? "없는 속성" : "서식지";
        String proposedScope = invalidCase.equals("moved-proposal") ? "1층" : "외부";
        String proposedProperty = invalidCase.equals("renamed-proposal") ? "다른 속성" : "서식지";
        List<String> sourceRefs = List.of("C1");
        if (invalidCase.equals("missing-source-scope")) {
            ReflectionTestUtils.setField(candidate, "scopeName", null);
            ReflectionTestUtils.setField(batch, "rawScopeName", null);
        }
        if (invalidCase.equals("same-scope")) {
            configureScopedCandidateAndTarget("외부");
        }
        if (invalidCase.equals("multiple-sources")) {
            var second = WorldSettingCandidate.create(job.getWork(), job.getEpisode(), job, WorldSettingCategory.RACE,
                    "설인", "외부", "서식지", "또 다른 원문", mapper.toJsonNode(List.of()), BigDecimal.ONE, null);
            ReflectionTestUtils.setField(second, "id", UUID.randomUUID());
            second.resolveOrderedSubject(WorldSettingSubjectResolutionType.EXISTING, key, "설인", mapper.toJsonNode(List.of()), mapper.toJsonNode(List.of(key)));
            second.startComparison(batch, "C2");
            ReflectionTestUtils.setField(batch, "candidateCount", 2);
            when(candidates.findAllByComparisonBatchIdOrderByCreatedAtAscIdAsc(batch.getId())).thenReturn(List.of(candidate, second));
            sourceRefs = List.of("C1", "C2");
        }
        var context = worker.getContext(job, batch.getId(), new WorkerWorldSettingComparisonBatchContextRequest(List.of(), List.of(key)));
        var request = scopeMismatchRequest(context.contextToken(), matchedScope, matchedProperty, proposedScope,
                proposedProperty, sourceRefs, invalidCase.equals("concrete-update")
                        ? WorldSettingSuggestedOperation.UPDATE : WorldSettingSuggestedOperation.REVIEW_REQUIRED);

        assertThatThrownBy(() -> worker.completeBatch(job, batch.getId(), request)).isInstanceOf(AppException.class);

        assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.PROCESSING);
        assertThat(state.get().path("references").isEmpty()).isTrue();
        verifyNoInteractions(decisions, sources, settings);
    }

    private void configureScopedCandidateAndTarget(String matchedScope) {
        ReflectionTestUtils.setField(candidate, "scopeName", "외부");
        ReflectionTestUtils.setField(batch, "rawScopeName", "외부");
        var target = (ObjectNode) state.get().path("worldSettings").path(key);
        ObjectNode properties = target.putObject("propertiesJson");
        if (matchedScope == null) properties.put("서식지", "북부");
        else properties.putObject(matchedScope).put("서식지", "북부");
        target.putObject("provenanceByPath").putObject(WorldSettingAnalysisStateMapper.pathKey(matchedScope, "서식지"))
                .put("confirmationStatus", "PROVISIONAL").put("sourceEpisodeNo", 1);
    }

    @Test
    @DisplayName("범위 없는 단일 후보는 이름이 다른 기존 scoped 속성을 명시적 검토로 연결한다")
    void unscopedReviewPreservesDifferentSourceAndMatchedNames() {
        configureScopedCandidateAndTarget("1층");
        ReflectionTestUtils.setField(candidate, "scopeName", null);
        ReflectionTestUtils.setField(candidate, "settingName", "분포");
        ReflectionTestUtils.setField(batch, "rawScopeName", null);
        var context = worker.getContext(job, batch.getId(), new WorkerWorldSettingComparisonBatchContextRequest(List.of(), List.of(key)));
        var request = new WorkerWorldSettingComparisonBatchCompleteRequest(List.of(new ContextVersion(null, 1, key)),
                List.of(new Decision("D1", List.of("C1"), "설인", null, "1층", "서식지", List.of(),
                        WorldSettingConsolidationStatus.SINGLE, WorldSettingSuggestedOperation.REVIEW_REQUIRED,
                        WorldSettingComparisonReviewReason.SCOPE_UNRESOLVED, null, "분포", "북부 설원", "범위를 확인해야 합니다.", Map.of(), key)),
                Map.of(), context.contextToken());
        worker.completeBatch(job, batch.getId(), request);
        assertThat(candidate.getSettingName()).isEqualTo("분포");
        assertThat(candidate.getProposedSettingName()).isEqualTo("분포");
        assertThat(candidate.getMatchedPropertyName()).isEqualTo("서식지");
        assertThat(candidate.getBeforeValue()).isEqualTo("북부");
        assertThat(candidate.getComparisonReviewReason()).isEqualTo(WorldSettingComparisonReviewReason.SCOPE_UNRESOLVED);
        assertThat(state.get().path("worldSettings").path(key).path("version").asInt()).isEqualTo(1);
        assertThat(state.get().path("references").elements().next().path("matchedScopeName").asText()).isEqualTo("1층");
    }

    @Test
    @DisplayName("동명 범위 미확정 후보는 기존처럼 다중 출처를 공유 검토로 묶어 보존한다")
    void sameNameUnresolvedScopePreservesExistingMultiSourceReview() {
        configureScopedCandidateAndTarget("1층");
        ReflectionTestUtils.setField(candidate, "scopeName", null);
        ReflectionTestUtils.setField(batch, "rawScopeName", null);
        var second = addSecondCandidate();
        ReflectionTestUtils.setField(second, "settingName", "서식지");
        var context = worker.getContext(job, batch.getId(), new WorkerWorldSettingComparisonBatchContextRequest(List.of(), List.of(key)));
        worker.completeBatch(job, batch.getId(), new WorkerWorldSettingComparisonBatchCompleteRequest(
                List.of(new ContextVersion(null, 1, key)),
                List.of(new Decision("D1", List.of("C1", "C2"), "설인", null, "1층", "서식지", List.of(),
                        WorldSettingConsolidationStatus.MERGED, WorldSettingSuggestedOperation.REVIEW_REQUIRED,
                        WorldSettingComparisonReviewReason.SCOPE_UNRESOLVED, null, "서식지", "북부 설원", "범위를 확인해야 합니다.", Map.of(), key)),
                Map.of(), context.contextToken()));
        assertThat(List.of(candidate, second)).allSatisfy(source -> {
            assertThat(source.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
            assertThat(source.getComparisonReviewReason()).isEqualTo(WorldSettingComparisonReviewReason.SCOPE_UNRESOLVED);
        });
        assertThat(candidate.getComparisonDecision()).isSameAs(second.getComparisonDecision());
        assertThat(state.get().path("worldSettings").path(key).path("version").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("정상 결정과 독립 실패를 함께 저장하고 재시도해도 값·실패 참고를 중복 적용하지 않는다")
    void mixedCompletionPersistsSuccessfulDecisionAndFailureDiagnosticsAtomically() {
        ReflectionTestUtils.setField(job, "reviewMode", AnalysisReviewMode.AUTOMATIC);
        var failed = addSecondCandidate();
        var context = worker.getContext(job, batch.getId(), new WorkerWorldSettingComparisonBatchContextRequest(List.of(), List.of(key)));
        var diagnostic = new WorldSettingComparisonDiagnostic(2, "PROPOSED_PATH_MISMATCH", List.of("C2"),
                List.of(new WorldSettingComparisonDiagnostic.SelectedProperty(null, key, null, "서식지")));
        var initial = new WorldSettingComparisonDiagnostic(1, "RESPONSE_PARSE_ERROR", List.of(), List.of());
        var success = request(context.contextToken(), WorldSettingConsolidationStatus.SINGLE);
        var completion = new WorkerWorldSettingComparisonBatchCompleteRequest(success.contextVersions(), success.decisions(),
                Map.of(), context.contextToken(), List.of(new WorkerWorldSettingComparisonBatchCompleteRequest.Failure(
                        List.of("C2"), AnalysisFailureCode.COMPARISON_VALIDATION_FAILED, "비교 경로 오류", List.of(diagnostic))), List.of(initial));

        worker.completeBatch(job, batch.getId(), completion);
        worker.completeBatch(job, batch.getId(), completion);

        assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(candidate.getComparisonDiagnostics().size()).isEqualTo(1);
        assertThat(failed.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.FAILED);
        assertThat(failed.canDeferFailedComparison()).isTrue();
        assertThat(failed.getComparisonDiagnostics().size()).isEqualTo(2);
        assertThat(failed.getComparisonDiagnostics().path(1).path("selectedProperties").path(0).path("propertyName").asText()).isEqualTo("서식지");
        assertThat(batch.getStatus()).isEqualTo(WorldSettingComparisonBatchStatus.COMPLETED);
        assertThat(state.get().path("worldSettings").path(key).path("propertiesJson").path("서식지").asText()).isEqualTo("북부의 설원");
        assertThat(state.get().path("references").size()).isEqualTo(1);
        assertThat(state.get().path("references").elements().next().path("sourceCandidateIds").path(0).asText()).isEqualTo(failed.getId().toString());
        verify(decisions, times(1)).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"manual", "missing-coverage", "duplicate-coverage", "unknown-source", "unknown-diagnostic-source", "unknown-diagnostic-target", "unknown-diagnostic-property", "non-candidate-failure"})
    @DisplayName("부분 완료는 자동 모드·정확한 coverage·고정 입력의 진단만 허용하고 오류 시 쓰지 않는다")
    void rejectsUnsafeMixedCompletionBeforeWriting(String invalidCase) {
        if (!invalidCase.equals("manual")) ReflectionTestUtils.setField(job, "reviewMode", AnalysisReviewMode.AUTOMATIC);
        var failed = addSecondCandidate();
        var context = worker.getContext(job, batch.getId(), new WorkerWorldSettingComparisonBatchContextRequest(List.of(), List.of(key)));
        var success = request(context.contextToken(), WorldSettingConsolidationStatus.SINGLE);
        var diagnostic = new WorldSettingComparisonDiagnostic(1, "PROPOSED_PATH_MISMATCH",
                List.of(invalidCase.equals("unknown-diagnostic-source") ? "C3" : "C2"),
                List.of(new WorldSettingComparisonDiagnostic.SelectedProperty(null,
                        invalidCase.equals("unknown-diagnostic-target") ? "provisional-world:unknown" : key, null,
                        invalidCase.equals("unknown-diagnostic-property") ? "없는 경로" : "서식지")));
        var failure = new WorkerWorldSettingComparisonBatchCompleteRequest.Failure(
                List.of(invalidCase.equals("duplicate-coverage") ? "C1" : invalidCase.equals("unknown-source") ? "C3" : "C2"),
                invalidCase.equals("non-candidate-failure") ? AnalysisFailureCode.WORKER_LEASE_EXPIRED : AnalysisFailureCode.COMPARISON_VALIDATION_FAILED,
                "비교 실패", List.of(diagnostic));
        var completion = new WorkerWorldSettingComparisonBatchCompleteRequest(success.contextVersions(), success.decisions(),
                Map.of(), context.contextToken(), invalidCase.equals("missing-coverage") ? List.of() : List.of(failure));

        assertThatThrownBy(() -> worker.completeBatch(job, batch.getId(), completion)).isInstanceOf(AppException.class);
        assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.PROCESSING);
        assertThat(failed.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.PROCESSING);
        assertThat(state.get().path("references").isEmpty()).isTrue();
        verifyNoInteractions(decisions, sources, settings);
    }

    private WorldSettingCandidate addSecondCandidate() {
        var second = WorldSettingCandidate.create(job.getWork(), job.getEpisode(), job, WorldSettingCategory.RACE,
                "설인", candidate.getScopeName(), "수명", "알 수 없음", mapper.toJsonNode(List.of()), BigDecimal.ONE, null);
        ReflectionTestUtils.setField(second, "id", UUID.randomUUID());
        second.resolveOrderedSubject(WorldSettingSubjectResolutionType.EXISTING, key, "설인", mapper.toJsonNode(List.of()), mapper.toJsonNode(List.of(key)));
        second.startComparison(batch, "C2");
        ReflectionTestUtils.setField(batch, "candidateCount", 2);
        when(candidates.findAllByComparisonBatchIdOrderByCreatedAtAscIdAsc(batch.getId())).thenReturn(List.of(candidate, second));
        return second;
    }

    @Test
    @DisplayName("전체 후보가 독립 실패한 자동 묶음도 정확한 coverage로 종료하고 실패 참고만 보존한다")
    void allFailuresCompleteWithoutAnyProjectedPropertyWrite() {
        ReflectionTestUtils.setField(job, "reviewMode", AnalysisReviewMode.AUTOMATIC);
        var context = worker.getContext(job, batch.getId(), new WorkerWorldSettingComparisonBatchContextRequest(List.of(), List.of(key)));
        var before = state.get().path("worldSettings").deepCopy();
        worker.completeBatch(job, batch.getId(), new WorkerWorldSettingComparisonBatchCompleteRequest(
                List.of(new ContextVersion(null, 1, key)), List.of(), Map.of(), context.contextToken(),
                List.of(new WorkerWorldSettingComparisonBatchCompleteRequest.Failure(List.of("C1"),
                        AnalysisFailureCode.LLM_RESPONSE_PARSE_ERROR, "해석 실패", List.of()))));
        assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.FAILED);
        assertThat(candidate.canDeferFailedComparison()).isTrue();
        assertThat(batch.getStatus()).isEqualTo(WorldSettingComparisonBatchStatus.COMPLETED);
        assertThat(state.get().path("references").size()).isEqualTo(1);
        assertThat(state.get().path("worldSettings")).isEqualTo(before);
        verifyNoInteractions(decisions, settings);
    }

    @Test
    @DisplayName("완전 복구된 정상 결과도 실패 없이 시도별 안전한 진단 이력을 보존한다")
    void fullyRecoveredCompletionPreservesDiagnostics() {
        var context = worker.getContext(job, batch.getId(), new WorkerWorldSettingComparisonBatchContextRequest(List.of(), List.of(key)));
        var success = request(context.contextToken(), WorldSettingConsolidationStatus.SINGLE);
        var diagnostic = new WorldSettingComparisonDiagnostic(1, "PROPOSED_PATH_MISMATCH", List.of("C1"),
                List.of(new WorldSettingComparisonDiagnostic.SelectedProperty(null, key, null, "서식지")));
        worker.completeBatch(job, batch.getId(), new WorkerWorldSettingComparisonBatchCompleteRequest(
                success.contextVersions(), success.decisions(), Map.of(), context.contextToken(), List.of(), List.of(diagnostic)));
        assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(candidate.getComparisonDiagnostics().size()).isEqualTo(1);
        assertThat(candidate.getComparisonDiagnostics().toString()).contains("PROPOSED_PATH_MISMATCH", "서식지")
                .doesNotContain("북부", "rawComparisonJson", "targetReferenceValid");
    }

    @Test
    @DisplayName("공통·실패 진단이 합쳐서 30개를 넘으면 최근 시도를 보존하고 정상 결정 저장은 계속한다")
    void mergedDiagnosticHistoryKeepsLatestThirtyWithoutRejectingValidCompletion() {
        ReflectionTestUtils.setField(job, "reviewMode", AnalysisReviewMode.AUTOMATIC);
        var failed = addSecondCandidate();
        var context = worker.getContext(job, batch.getId(), new WorkerWorldSettingComparisonBatchContextRequest(List.of(), List.of(key)));
        var history = new java.util.ArrayList<WorldSettingComparisonDiagnostic>();
        // 최초 3회에 각 8개 오류, 그 뒤 한 후보의 복구 1회에 8개 오류를 보고했다.
        for (int index = 2; index < 24; index++) history.add(new WorldSettingComparisonDiagnostic(
                index / 8 + 1, "SCHEMA_INVALID_" + index, List.of("C1", "C2"), List.of()));
        for (int index = 0; index < 8; index++) history.add(new WorldSettingComparisonDiagnostic(
                4, "RECOVERY_INVALID_" + index, List.of("C2"), List.of()));
        var failureHistory = history.stream().map(entry -> new WorldSettingComparisonDiagnostic(
                entry.attempt(), entry.rule(), List.of("C2"), entry.selectedProperties())).toList();
        var success = request(context.contextToken(), WorldSettingConsolidationStatus.SINGLE);

        worker.completeBatch(job, batch.getId(), new WorkerWorldSettingComparisonBatchCompleteRequest(
                success.contextVersions(), success.decisions(), Map.of(), context.contextToken(),
                List.of(new WorkerWorldSettingComparisonBatchCompleteRequest.Failure(List.of("C2"),
                        AnalysisFailureCode.COMPARISON_VALIDATION_FAILED, "분리 비교 검증 실패", failureHistory)), history));

        assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(failed.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.FAILED);
        assertThat(failed.getComparisonDiagnostics().size()).isEqualTo(30);
        assertThat(failed.getComparisonDiagnostics().path(29).path("attempt").asInt()).isEqualTo(4);
        assertThat(failed.getComparisonDiagnostics()).allSatisfy(entry -> assertThat(entry.path("attempt").asInt()).isGreaterThanOrEqualTo(2));
        for (int index = 0; index < 8; index++) assertThat(failed.getComparisonDiagnostics().toString()).contains("RECOVERY_INVALID_" + index);
        assertThat(state.get().path("worldSettings").path(key).path("propertiesJson").path("서식지").asText()).isEqualTo("북부의 설원");
    }

    @Test
    @DisplayName("자동 대상 연결 실패만 원인과 함께 보류하고 정상 후보의 연결 결과를 저장한다")
    void subjectFailureIsDeferredWithoutInventingAnAmbiguousTarget() {
        ReflectionTestUtils.setField(job, "reviewMode", AnalysisReviewMode.AUTOMATIC);
        var failed = pendingCandidate("연결 실패", "근거가 있는 설정", false);
        var healthy = pendingCandidate("정상", "정상 설정", false);
        stubPending(List.of(failed, healthy));
        var result = worker.resolveSubjects(job,
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest(List.of(
                        new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput(
                                failed.getId(), List.of(), List.of(), false, AnalysisFailureCode.COMPARISON_VALIDATION_FAILED),
                        new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput(
                                healthy.getId(), List.of(), List.of(key), false))));
        assertThat(result.resolutions()).extracting(value -> value.resolutionType())
                .containsExactly(WorldSettingSubjectResolutionType.FAILED, WorldSettingSubjectResolutionType.EXISTING);
        assertThat(failed.canDeferFailedComparison()).isTrue();
        assertThat(failed.getCanonicalSubjectKey()).isEqualTo("failed:" + failed.getId());
        assertThat(failed.getResolvedTargetWorldSettingIds()).isEmpty();
        assertThat(failed.getResolvedProvisionalSubjectKeys()).isEmpty();
        assertThat(healthy.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.PENDING);
        verifyNoInteractions(settings);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AI_TOKEN_QUOTA_EXHAUSTED", "WORKER_LEASE_EXPIRED", "UNEXPECTED_ERROR"})
    @DisplayName("실행 전체 오류는 대상 연결 실패 보류로 저장하지 않는다")
    void subjectExecutionFailureCannotBeDeferred(String code) {
        ReflectionTestUtils.setField(job, "reviewMode", AnalysisReviewMode.AUTOMATIC);
        var pending = pendingCandidate("실패", "설정", false);
        stubPending(List.of(pending));
        assertThatThrownBy(() -> worker.resolveSubjects(job,
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest(List.of(
                        new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput(
                                pending.getId(), List.of(), List.of(), false, AnalysisFailureCode.valueOf(code))))))
                .isInstanceOf(AppException.class);
        assertThat(pending.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.PENDING);
    }

    @Test
    @DisplayName("연결 실패 이후 명시적 재비교 준비는 실패 분류와 참조를 함께 해제한다")
    void clearingSubjectFailureCannotLeaveAnInvalidResolutionShape() {
        ReflectionTestUtils.setField(job, "reviewMode", AnalysisReviewMode.AUTOMATIC);
        var pending = pendingCandidate("연결 실패", "설정", false);
        pending.deferSubjectResolution(AnalysisFailureCode.LLM_RESPONSE_PARSE_ERROR);
        pending.markRecomparisonRequired();
        assertThat(pending.getPreparationFailureStage()).isNull();
        assertThat(pending.getSubjectResolutionType()).isNull();
        assertThat(pending.getCanonicalSubjectKey()).isNull();
        assertThat(pending.getResolvedTargetWorldSettingIds()).isNull();
        assertThat(pending.hasSubjectResolution()).isFalse();
    }

    @Test
    @DisplayName("같은 세계관 후보 21개는 자동 분석에서 20개와 1개로 나누어 비교한다")
    void automaticLargeGroupIsSplitInsteadOfFailed() {
        ReflectionTestUtils.setField(job, "reviewMode", AnalysisReviewMode.AUTOMATIC);
        var pending = java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> pendingCandidate("설정" + index, "설정값", true)).toList();
        stubPending(pending);
        var first = worker.claimBatch(job).orElseThrow();
        var second = worker.claimBatch(job).orElseThrow();
        assertThat(first.candidates()).hasSize(20);
        assertThat(second.candidates()).hasSize(1);
        assertThat(pending).allMatch(value -> value.getComparisonStatus() == WorldSettingComparisonStatus.PROCESSING);
    }

    @Test
    @DisplayName("세계관 후보 하나가 너무 길면 보류하고 뒤의 정상 후보 비교를 계속한다")
    void automaticOversizedCandidateDoesNotBlockLaterCandidate() {
        ReflectionTestUtils.setField(job, "reviewMode", AnalysisReviewMode.AUTOMATIC);
        var large = pendingCandidate("긴 설정", "가".repeat(31000), true);
        var normal = pendingCandidate("정상 설정", "정상", true);
        stubPending(List.of(large, normal));
        var claimed = worker.claimBatch(job).orElseThrow();
        assertThat(claimed.candidates()).extracting(value -> value.candidateId()).containsExactly(normal.getId());
        assertThat(large.canDeferFailedComparison()).isTrue();
    }

    @Test
    @DisplayName("기존 세계관 비교 문맥이 너무 커도 해당 후보만 보류한다")
    void automaticOversizedContextIsDeferredBeforeWorkerReceivesBatch() {
        ReflectionTestUtils.setField(job, "reviewMode", AnalysisReviewMode.AUTOMATIC);
        ((ObjectNode) state.get().path("worldSettings").path(key).path("propertiesJson")).put("장문", "가".repeat(31000));
        var pending = pendingCandidate("설정", "짧은 값", true);
        stubPending(List.of(pending));
        assertThat(worker.claimBatch(job)).isEmpty();
        assertThat(pending.canDeferFailedComparison()).isTrue();
        assertThat(pending.getComparisonBatch()).isNull();
    }

    @Test
    @DisplayName("대상이 모호한 긴 원문은 비교 크기 오류로 바꾸지 않고 정상 확인 대상으로 남긴다")
    void ambiguousOversizedCandidateIsHeldBeforeInputLimitValidation() {
        ReflectionTestUtils.setField(job, "reviewMode", AnalysisReviewMode.AUTOMATIC);
        var pending = pendingCandidate("대상 모호", "가".repeat(31000), false);
        pending.resolveOrderedSubject(WorldSettingSubjectResolutionType.AMBIGUOUS, "ambiguous:" + pending.getId(),
                pending.getSubjectName(), mapper.toJsonNode(List.of()), mapper.toJsonNode(List.of()));
        stubPending(List.of(pending));
        assertThat(worker.claimBatch(job)).isEmpty();
        assertThat(pending.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(pending.getComparisonReviewReason()).isEqualTo(WorldSettingComparisonReviewReason.SUBJECT_UNRESOLVED);
        assertThat(pending.getPreparationFailureStage()).isNull();
    }

    private WorldSettingCandidate pendingCandidate(String settingName, String value, boolean resolved) {
        var next = WorldSettingCandidate.create(job.getWork(), candidate.getSourceEpisode(), job, WorldSettingCategory.RACE,
                "설인", settingName, value, mapper.toJsonNode(List.of()), BigDecimal.ONE, null);
        ReflectionTestUtils.setField(next, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(next, "createdAt", java.time.LocalDateTime.of(2026, 9, 11, 12, 0)
                .plusNanos(pendingSequence++));
        if (resolved) next.resolveOrderedSubject(WorldSettingSubjectResolutionType.EXISTING, key, "설인",
                mapper.toJsonNode(List.of()), mapper.toJsonNode(List.of(key)));
        return next;
    }

    private void stubPending(List<WorldSettingCandidate> values) {
        when(candidates.findSubjectResolutionCandidatesForUpdate(eq(job.getId()), any(), any()))
                .thenAnswer(call -> values.stream().filter(value -> value.getComparisonStatus() == WorldSettingComparisonStatus.PENDING).toList());
        when(candidates.findComparisonClaimCandidates(eq(job.getId()), any(), any(), any()))
                .thenAnswer(call -> values.stream().filter(value -> value.getComparisonStatus() == WorldSettingComparisonStatus.PENDING).limit(1).toList());
        when(candidates.findComparisonBatchCandidatesForUpdate(eq(job.getId()), any(), any(), any(), any(), any()))
                .thenAnswer(call -> values.stream().filter(value -> value.getComparisonStatus() == WorldSettingComparisonStatus.PENDING
                        && java.util.Objects.equals(value.getCanonicalSubjectKey(), call.getArgument(3))).toList());
        when(candidates.findAllByAnalysisJobIdAndComparisonStatus(eq(job.getId()), eq(WorldSettingComparisonStatus.FAILED)))
                .thenAnswer(call -> values.stream().filter(value -> value.getComparisonStatus() == WorldSettingComparisonStatus.FAILED).toList());
        when(batches.saveAndFlush(any())).thenAnswer(call -> {
            WorldSettingComparisonBatch created = call.getArgument(0);
            ReflectionTestUtils.setField(created, "id", UUID.randomUUID());
            return created;
        });
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "서식지")
    @DisplayName("일반 검토는 속성 선택이 없어도 완료하며 원본 주체와 값을 미확정 참고로 보존한다")
    void generalUncertaintyPreservesOriginalReferenceWithoutApplying(String matchedProperty) {
        ReflectionTestUtils.setField(candidate, "subjectName", "원문의 설인");
        JsonNode before = state.get().path("worldSettings").deepCopy();
        var context = worker.getContext(job, batch.getId(), new WorkerWorldSettingComparisonBatchContextRequest(List.of(), List.of(key)));
        worker.completeBatch(job, batch.getId(), generalReviewRequest(context.contextToken(), key, null,
                matchedProperty, WorldSettingComparisonReviewReason.GENERAL_UNCERTAINTY));
        assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(candidate.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.PENDING_REVIEW);
        assertThat(candidate.getComparisonReviewReason()).isEqualTo(WorldSettingComparisonReviewReason.GENERAL_UNCERTAINTY);
        assertThat(state.get().path("worldSettings")).isEqualTo(before);
        JsonNode reference = state.get().path("references").elements().next();
        assertThat(reference.path("subjectName").asText()).isEqualTo("원문의 설인");
        assertThat(reference.path("settingName").asText()).isEqualTo(candidate.getSettingName());
        assertThat(reference.path("proposedValue").asText()).isEqualTo(candidate.getExtractedValue());
        assertThat(reference.path("comparisonReviewReason").asText()).isEqualTo("GENERAL_UNCERTAINTY");
        assertThat(reference.path("confirmationStatus").asText()).isEqualTo("UNCONFIRMED");
        assertThat(reference.has("targetRef")).isFalse();
        ReflectionTestUtils.setField(job, "automaticInputState", state.get().deepCopy());
        var contextMapper = new AnalysisRunContextMapper(new org.monitoring.catchholebackend.domain.character.mapper.CharacterFactEvidenceMapper(
                new org.monitoring.catchholebackend.domain.character.processor.CharacterFactSourceResolver()));
        assertThat(contextMapper.toResponse(job).unresolvedReferences()).singleElement().satisfies(payload -> {
            assertThat(payload.subjectName()).isEqualTo("원문의 설인");
            assertThat(payload.value()).isEqualTo(candidate.getExtractedValue());
        });
        verifyNoInteractions(settings);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknownTarget", "unknownProperty", "scopeWithoutProperty", "falseScopeReview"})
    @DisplayName("일반 검토도 없는 대상이나 경로를 허용하지 않고 범위 검토의 기존 조건을 완화하지 않는다")
    void generalUncertaintyDoesNotBypassReferenceValidation(String invalidCase) {
        var context = worker.getContext(job, batch.getId(), new WorkerWorldSettingComparisonBatchContextRequest(List.of(), List.of(key)));
        String target = invalidCase.equals("unknownTarget") ? "provisional-world:" + UUID.randomUUID() : key;
        String matchedScope = invalidCase.equals("scopeWithoutProperty") ? "없는 범위" : null;
        String matchedProperty = invalidCase.equals("unknownProperty") ? "없는 설정" : null;
        var reason = invalidCase.equals("falseScopeReview") ? WorldSettingComparisonReviewReason.SCOPE_UNRESOLVED
                : WorldSettingComparisonReviewReason.GENERAL_UNCERTAINTY;
        assertThatThrownBy(() -> worker.completeBatch(job, batch.getId(),
                generalReviewRequest(context.contextToken(), target, matchedScope, matchedProperty, reason)))
                .isInstanceOf(AppException.class);
        assertThat(candidate.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.PROCESSING);
        verifyNoInteractions(decisions, sources);
    }

    private WorkerWorldSettingComparisonBatchCompleteRequest generalReviewRequest(String token, String target,
            String matchedScope, String matchedProperty, WorldSettingComparisonReviewReason reason) {
        return new WorkerWorldSettingComparisonBatchCompleteRequest(List.of(new ContextVersion(null, 1, key)),
                List.of(new Decision("D1", List.of("C1"), "설인", null, matchedScope, matchedProperty, List.of(),
                        WorldSettingConsolidationStatus.SINGLE, WorldSettingSuggestedOperation.REVIEW_REQUIRED, reason,
                        candidate.getScopeName(), candidate.getSettingName(), candidate.getExtractedValue(),
                        "같은 대상의 정보인지 원문을 확인해 주세요.", Map.of(), target)), Map.of(), token);
    }

    private WorkerWorldSettingComparisonBatchCompleteRequest scopeMismatchRequest(String token, String matchedScope,
            String matchedProperty, String proposedScope, String proposedProperty, List<String> sourceRefs,
            WorldSettingSuggestedOperation operation) {
        return new WorkerWorldSettingComparisonBatchCompleteRequest(List.of(new ContextVersion(null, 1, key)),
                List.of(new Decision("D1", sourceRefs, "설인", null, matchedScope, matchedProperty, List.of(),
                        sourceRefs.size() == 1 ? WorldSettingConsolidationStatus.SINGLE : WorldSettingConsolidationStatus.MERGED,
                        operation, operation == WorldSettingSuggestedOperation.REVIEW_REQUIRED
                                ? WorldSettingComparisonReviewReason.SCOPE_MISMATCH : null,
                        proposedScope, proposedProperty, "북부 설원에서만 산다", "원문 범위와 기존 경로가 다릅니다.", Map.of(), key)), Map.of(), token);
    }

    private WorkerWorldSettingComparisonBatchCompleteRequest request(String token, WorldSettingConsolidationStatus consolidation) {
        return new WorkerWorldSettingComparisonBatchCompleteRequest(List.of(new ContextVersion(null, 1, key)),
                List.of(new Decision("D1", List.of("C1"), "설인", null, null, "서식지", List.of(), consolidation,
                        WorldSettingSuggestedOperation.MERGE, null, null, "서식지", "북부의 설원", "앞 회차 내용을 구체화",
                        Map.of(), key)), Map.of(), token);
    }
}
