package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisRunContextMapper;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.service.CharacterAnalysisStateService;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonBatchRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonDecisionRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonDecisionSourceRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingWorkerMapper;
import org.monitoring.catchholebackend.domain.worldsetting.service.OrderedWorldSettingWorker;
import org.monitoring.catchholebackend.domain.worldsetting.type.*;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("명시적 새 누적 실행의 정확한 원문 주장 반려 보존")
class AnalysisHumanRejectionPolicyTest {
    private final SettingCandidateRepository characters = mock(SettingCandidateRepository.class);
    private final WorldSettingCandidateRepository worlds = mock(WorldSettingCandidateRepository.class);
    private final AnalysisStateJournal journal = new AnalysisStateJournal();
    private final AnalysisHumanRejectionPolicy policy = new AnalysisHumanRejectionPolicy(characters, worlds, journal);
    private final AnalysisRunStateService states = mock(AnalysisRunStateService.class);
    private final AtomicReference<JsonNode> state = new AtomicReference<>();
    private Work work;
    private Episode episode;
    private AnalysisJob priorJob;
    private AnalysisJob nextJob;
    private UUID target;

    @BeforeEach
    void setUp() {
        work = Work.create(Member.register("rejection@example.com", "pass", "01012345678", "작가"),
                "작품", WorkGenre.FANTASY, "설명");
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        episode = Episode.create(work, null, 2, "2화", "source", "v1", "b".repeat(64), 100);
        ReflectionTestUtils.setField(episode, "id", UUID.randomUUID());
        priorJob = job();
        nextJob = job();
        target = UUID.randomUUID();
        when(states.getProjectedState(nextJob)).thenAnswer(call -> state.get().deepCopy());
        doAnswer(call -> {
            List<AnalysisStateChange> changes = call.getArgument(2);
            state.set(journal.apply(state.get(), changes));
            return null;
        }).when(states).appendValidatedChanges(eq(nextJob), any(), any());
        when(states.prepareState(nextJob)).thenAnswer(call -> new AnalysisRunStateService.PreparedState() {
            @Override public JsonNode projectedState() { return states.getProjectedState(nextJob); }
            @Override public void append(List<AnalysisStateChange> changes) {
                states.appendValidatedChanges(nextJob, nextJob.getInputStateHash(), changes);
            }
        });
    }

    @Test
    @DisplayName("사용자가 반려한 원문 설정을 새 Job에서 다시 추출해도 비교 전에 EXCLUDE로 보존한다")
    void sameSourceReextractionDoesNotReinjectRejectedCharacterFact() {
        SettingCandidate rejected = character(priorJob, "북부 출신", 4);
        rejected.recordUserModification();
        rejected.dismiss();
        capture(List.of(rejected), List.of());
        SettingCandidate extractedAgain = character(nextJob, "북부 출신", 4);
        when(characters.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(nextJob.getId()))
                .thenReturn(List.of(extractedAgain));
        CharacterAnalysisStateService service = new CharacterAnalysisStateService(
                states, characters, new CharacterAnalysisStateMapper(), policy, mock(org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.class));

        service.prepareProvisionalCandidates(nextJob);

        assertThat(extractedAgain.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.COMPLETED);
        assertThat(extractedAgain.getSuggestedOperation()).isEqualTo(CharacterFactOperation.EXCLUDE);
        assertThat(extractedAgain.getReviewStatus()).isEqualTo(SettingCandidateReviewStatus.PENDING_REVIEW);
        assertThat(extractedAgain.getAttributeValue()).isEqualTo("북부 출신");
        assertThat(extractedAgain.isUserModified()).isFalse();
        assertThat(state.get().path("characters")).isEmpty();
        assertThat(state.get().path("references").path("human-rejection:" + extractedAgain.getId())
                .path("rejectedCandidateId").asText()).isEqualTo(rejected.getId().toString());
    }

    @Test
    @DisplayName("S0 반려 정책에는 원문 인용과 값 대신 해시만 남고 이후 후보 변경과 분리된다")
    void snapshotStoresOnlyHashAndImmutableIdentifiers() {
        SettingCandidate rejected = character(priorJob, "비공개값", 4);
        rejected.recordUserModification();
        rejected.dismiss();
        capture(List.of(rejected), List.of());
        String snapshot = state.get().toString();
        assertThat(snapshot).doesNotContain("비공개값", "그는 북부 출신이다", "evidence", "quote");
        JsonNode reference = state.get().path("references").elements().next();
        assertThat(reference.path("fingerprint").asText()).matches("[0-9a-f]{64}");
        ReflectionTestUtils.setField(rejected, "attributeValue", "나중에 바뀐 값");
        assertThat(state.get().toString()).isEqualTo(snapshot);
        assertThat(policy.match(character(nextJob, "비공개값", 4), state.get())).isPresent();
    }

    @Test
    @DisplayName("원문 해시나 값 경로 근거 대상이 달라지면 동일한 반려로 임의 합치지 않는다")
    void differingSourceClaimAndIdentityDoNotMatch() {
        SettingCandidate rejected = character(priorJob, "북부 출신", 4);
        rejected.recordUserModification();
        rejected.dismiss();
        capture(List.of(rejected), List.of());
        assertThat(policy.match(character(nextJob, "남부 출신", 4), state.get())).isEmpty();
        assertThat(policy.match(character(nextJob, "북부 출신", 9), state.get())).isEmpty();
        SettingCandidate differentPath = character(nextJob, "북부 출신", 4);
        ReflectionTestUtils.setField(differentPath, "attributeName", "profile.home");
        assertThat(policy.match(differentPath, state.get())).isEmpty();
        SettingCandidate namesake = character(nextJob, "북부 출신", 4);
        ReflectionTestUtils.setField(namesake, "matchedCharacterId", UUID.randomUUID());
        assertThat(policy.match(namesake, state.get())).isEmpty();
        ReflectionTestUtils.setField(nextJob, "sourceEpisodeNo", 3);
        assertThat(policy.match(character(nextJob, "북부 출신", 4), state.get())).isEmpty();
        ReflectionTestUtils.setField(nextJob, "sourceEpisodeNo", 2);
        ReflectionTestUtils.setField(nextJob, "sourceContentHash", "c".repeat(64));
        assertThat(policy.match(character(nextJob, "북부 출신", 4), state.get())).isEmpty();
    }

    @Test
    @DisplayName("원문 파기나 위치 없는 인용을 이름과 값만으로 동일 주장이라고 취급하지 않는다")
    void missingSourceEvidenceCannotCreateOrMatchConstraint() {
        SettingCandidate rejected = character(priorJob, "북부 출신", 4);
        rejected.recordUserModification();
        rejected.dismiss();
        capture(List.of(rejected), List.of());
        SettingCandidate noOffsets = character(nextJob, "북부 출신", 4);
        ReflectionTestUtils.setField(noOffsets, "evidenceSpans", JsonNodeFactory.instance.arrayNode()
                .add(JsonNodeFactory.instance.objectNode().put("quote", "그는 북부 출신이다")));
        assertThat(policy.match(noOffsets, state.get())).isEmpty();
        ReflectionTestUtils.setField(rejected, "evidenceSpans", null);
        capture(List.of(rejected), List.of());
        assertThat(state.get().path("references")).isEmpty();
    }

    @Test
    @DisplayName("AI 자동 EXCLUDE와 legacy 출처는 사용자 반려 정책을 만들지 않는다")
    void automaticExclusionAndLegacySourceAreNotHumanConstraints() {
        SettingCandidate automatic = character(priorJob, "북부 출신", 4);
        automatic.dismiss();
        capture(List.of(automatic), List.of());
        assertThat(state.get().path("references")).isEmpty();
        automatic.recordUserModification();
        ReflectionTestUtils.setField(automatic, "userModified", true);
        ReflectionTestUtils.setField(priorJob, "analysisMode", AnalysisMode.CONFIRMED_ONLY);
        capture(List.of(automatic), List.of());
        assertThat(state.get().path("references")).isEmpty();
    }

    @Test
    @DisplayName("세계관 사용자 반려도 실제 대상과 같은 속성 값 근거에만 일치한다")
    void worldPropertyRejectionRequiresExactTargetPathValueAndEvidence() {
        WorldSettingCandidate rejected = world(priorJob, "북부", 4);
        rejected.dismiss("비공개 반려 사유", work.getMember());
        capture(List.of(), List.of(rejected));
        assertThat(policy.match(world(nextJob, "북부", 4), state.get())).isPresent();
        assertThat(policy.match(world(nextJob, "남부", 4), state.get())).isEmpty();
        assertThat(policy.match(world(nextJob, "북부", 9), state.get())).isEmpty();
        assertThat(state.get().toString()).doesNotContain("비공개 반려 사유", "북부");
    }

    @Test
    @DisplayName("세계관 반려 원본은 비교 묶음 생성 전에 제외되고 현재 속성에는 재주입되지 않는다")
    void worldClaimPreservesRejectedRawCandidateWithoutCallingComparison() {
        WorldSettingCandidate rejected = world(priorJob, "북부", 4);
        rejected.dismiss(null, work.getMember());
        capture(List.of(), List.of(rejected));
        WorldSettingCandidate extracted = world(nextJob, "북부", 4);
        when(worlds.findSubjectResolutionCandidatesForUpdate(nextJob.getId(), WorldSettingReviewStatus.PENDING_REVIEW,
                WorldSettingComparisonStatus.PENDING)).thenReturn(List.of(extracted));
        when(worlds.findComparisonClaimCandidates(eq(nextJob.getId()), eq(WorldSettingReviewStatus.PENDING_REVIEW),
                eq(WorldSettingComparisonStatus.PENDING), any())).thenAnswer(call ->
                extracted.getComparisonStatus() == WorldSettingComparisonStatus.PENDING ? List.of(extracted) : List.of());
        WorldSettingComparisonBatchRepository batches = mock(WorldSettingComparisonBatchRepository.class);
        WorldSettingComparisonDecisionRepository decisions = mock(WorldSettingComparisonDecisionRepository.class);
        OrderedWorldSettingWorker worker = new OrderedWorldSettingWorker(new org.monitoring.catchholebackend.domain.analysis.processor.AnalysisFailureDiagnosticsSanitizer(org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class)), states, journal, new AnalysisRunContextMapper(new org.monitoring.catchholebackend.domain.character.mapper.CharacterFactEvidenceMapper(new org.monitoring.catchholebackend.domain.character.processor.CharacterFactSourceResolver())),
                worlds, batches, decisions, mock(WorldSettingComparisonDecisionSourceRepository.class),
                mock(WorldSettingRepository.class), new WorldSettingWorkerMapper(), new WorldSettingAnalysisStateMapper(), policy);

        assertThat(worker.claimBatch(nextJob)).isEmpty();

        assertThat(extracted.getComparisonStatus()).isEqualTo(WorldSettingComparisonStatus.COMPLETED);
        assertThat(extracted.getSuggestedOperation()).isEqualTo(WorldSettingSuggestedOperation.EXCLUDE);
        assertThat(extracted.getReviewStatus()).isEqualTo(WorldSettingReviewStatus.PENDING_REVIEW);
        assertThat(extracted.getExtractedValue()).isEqualTo("북부");
        assertThat(state.get().path("worldSettings")).isEmpty();
        assertThat(state.get().path("references").path("human-rejection:" + extracted.getId())
                .path("rejectedCandidateId").asText()).isEqualTo(rejected.getId().toString());
        verifyNoInteractions(batches, decisions);
    }

    @Test
    @DisplayName("같은 이름의 임시 인물은 discovery 원문 근거까지 같아야 반려가 이어진다")
    void provisionalIdentityUsesFrozenAnchorEvidenceRatherThanName() {
        SettingCandidate oldAnchor = discovery(priorJob, 1);
        SettingCandidate newAnchor = discovery(nextJob, 1);
        SettingCandidate rejected = character(priorJob, "북부 출신", 4);
        provisional(rejected, oldAnchor);
        rejected.recordUserModification();
        rejected.dismiss();
        capture(List.of(rejected), List.of());
        SettingCandidate extracted = character(nextJob, "북부 출신", 4);
        provisional(extracted, newAnchor);
        assertThat(policy.match(extracted, state.get())).isPresent();
        ReflectionTestUtils.setField(newAnchor, "evidenceSpans", evidence(30));
        assertThat(policy.match(extracted, state.get())).isEmpty();
    }

    @Test
    @DisplayName("해당 도메인의 반려 정책이 없으면 후보와 발견 원문을 조회하지 않는다")
    void matchingSkipsDescriptorsWhenDomainHasNoHumanRejection() {
        var character = mock(SettingCandidate.class);
        var world = mock(WorldSettingCandidate.class);
        var matching = policy.beginMatching();
        ObjectNode current = journal.emptyState();
        ObjectNode reference = current.withObject("references").putObject("other");
        reference.put("kind", AnalysisHumanRejectionPolicy.KIND);
        reference.put("policy", AnalysisHumanRejectionPolicy.POLICY);
        reference.put("domain", "worldSettings");
        assertThat(matching.match(character, current)).isEmpty();
        reference.put("domain", "characters");
        assertThat(matching.match(world, current)).isEmpty();
        reference.put("policy", "UNSUPPORTED");
        assertThat(matching.match(character, current)).isEmpty();
        assertThat(policy.match(character, null)).isEmpty();
        assertThat(policy.match(world, journal.emptyState())).isEmpty();
        verifyNoInteractions(character, world, characters, worlds);
    }

    @Test
    @DisplayName("한 요청의 같은 발견 인물은 한 번 조회하고 반려 결과는 독립 복사로 반환한다")
    void matchingReusesCharacterAnchorWithoutSharingReturnedReference() {
        SettingCandidate anchor = discovery(priorJob, 1);
        SettingCandidate rejected = character(priorJob, "북부 출신", 4);
        provisional(rejected, anchor);
        rejected.recordUserModification();
        rejected.dismiss();
        capture(List.of(rejected), List.of());
        SettingCandidate first = character(nextJob, "북부 출신", 4);
        SettingCandidate second = character(nextJob, "북부 출신", 4);
        provisional(first, anchor);
        provisional(second, anchor);
        clearInvocations(characters, worlds);
        var matching = policy.beginMatching();

        JsonNode reference = matching.match(first, state.get()).orElseThrow();
        ((ObjectNode) reference).put("fingerprint", "changed outside the session");
        assertThat(matching.match(second, state.get())).isPresent();
        verify(characters, times(1)).findByIdAndWorkId(anchor.getId(), work.getId());
        verifyNoInteractions(worlds);
        assertThat(policy.match(first, state.get())).isPresent();
        verify(characters, times(2)).findByIdAndWorkId(anchor.getId(), work.getId());
    }

    @Test
    @DisplayName("같은 세계관 발견 후보를 재사용해도 각 호출의 최신 반려 기록을 읽는다")
    void worldMatchingReusesAnchorAndObservesReferenceChanges() {
        WorldSettingCandidate anchor = world(priorJob, "북부", 1);
        WorldSettingCandidate rejected = world(priorJob, "북부", 4);
        provisional(rejected, anchor);
        rejected.dismiss(null, work.getMember());
        capture(List.of(), List.of(rejected));
        JsonNode capturedReferences = state.get().path("references").deepCopy();
        WorldSettingCandidate first = world(nextJob, "북부", 4);
        WorldSettingCandidate second = world(nextJob, "북부", 4);
        provisional(first, anchor);
        provisional(second, anchor);
        clearInvocations(characters, worlds);
        var matching = policy.beginMatching();
        ObjectNode current = journal.emptyState();

        assertThat(matching.match(first, current)).isEmpty();
        verifyNoInteractions(worlds);
        current.set("references", capturedReferences);
        assertThat(matching.match(first, current)).isPresent();
        assertThat(matching.match(second, current)).isPresent();
        current.withObject("references").removeAll();
        assertThat(matching.match(second, current)).isEmpty();
        verify(worlds, times(1)).findByIdAndWorkId(anchor.getId(), work.getId());
        verifyNoInteractions(characters);
    }

    @Test
    @DisplayName("발견 후보 캐시는 작품별로 나뉘며 조회 실패도 요청 안에서만 재사용한다")
    void missingAnchorCacheIsWorkScopedAndDoesNotOutliveSession() {
        WorldSettingCandidate anchor = world(priorJob, "북부", 1);
        WorldSettingCandidate rejected = world(priorJob, "북부", 4);
        provisional(rejected, anchor);
        rejected.dismiss(null, work.getMember());
        capture(List.of(), List.of(rejected));
        WorldSettingCandidate candidate = world(nextJob, "북부", 4);
        provisional(candidate, anchor);
        Work otherWork = Work.create(work.getMember(), "다른 작품", WorkGenre.FANTASY, "설명");
        ReflectionTestUtils.setField(otherWork, "id", UUID.randomUUID());
        WorldSettingCandidate foreignAnchor = world(nextJob, "북부", 4);
        provisional(foreignAnchor, anchor);
        ReflectionTestUtils.setField(foreignAnchor, "work", otherWork);
        when(worlds.findByIdAndWorkId(anchor.getId(), otherWork.getId())).thenReturn(Optional.empty());
        clearInvocations(worlds);
        var matching = policy.beginMatching();

        assertThat(matching.match(candidate, state.get())).isPresent();
        assertThat(matching.match(foreignAnchor, state.get())).isEmpty();
        assertThat(matching.match(foreignAnchor, state.get())).isEmpty();
        verify(worlds, times(1)).findByIdAndWorkId(anchor.getId(), work.getId());
        verify(worlds, times(1)).findByIdAndWorkId(anchor.getId(), otherWork.getId());
        assertThat(policy.beginMatching().match(foreignAnchor, state.get())).isEmpty();
        verify(worlds, times(2)).findByIdAndWorkId(anchor.getId(), otherWork.getId());
    }

    private void provisional(WorldSettingCandidate candidate, WorldSettingCandidate anchor) {
        ReflectionTestUtils.setField(candidate, "provisionalSubjectKey", "provisional-world:" + anchor.getId());
        ReflectionTestUtils.setField(candidate, "resolvedTargetWorldSettingIds", JsonNodeFactory.instance.arrayNode());
        when(worlds.findByIdAndWorkId(anchor.getId(), work.getId())).thenReturn(Optional.of(anchor));
    }

    private void provisional(SettingCandidate candidate, SettingCandidate anchor) {
        ReflectionTestUtils.setField(candidate, "matchedCharacterId", null);
        ReflectionTestUtils.setField(candidate, "provisionalSubjectKey", "provisional-character:" + anchor.getId());
        when(characters.findByIdAndWorkId(anchor.getId(), work.getId())).thenReturn(Optional.of(anchor));
    }

    private SettingCandidate discovery(AnalysisJob job, int offset) {
        SettingCandidate candidate = character(job, "북부 출신", offset);
        ReflectionTestUtils.setField(candidate, "candidateKind", SettingCandidateKind.CHARACTER_DISCOVERY);
        return candidate;
    }

    private void capture(List<SettingCandidate> rejectedCharacters, List<WorldSettingCandidate> rejectedWorlds) {
        when(characters.findAllByWorkIdAndReviewStatusOrderByCreatedAtDesc(work.getId(), SettingCandidateReviewStatus.DISMISSED))
                .thenReturn(rejectedCharacters);
        when(worlds.findAllByWorkIdAndReviewStatusOrderByCreatedAtDesc(work.getId(), WorldSettingReviewStatus.DISMISSED))
                .thenReturn(rejectedWorlds);
        ObjectNode initial = journal.emptyState();
        initial.set("references", policy.capture(work));
        state.set(initial);
    }

    private AnalysisJob job() {
        AnalysisJob job = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(job, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(job, "analysisMode", AnalysisMode.ORDERED_PROVISIONAL);
        ReflectionTestUtils.setField(job, "analysisRunId", UUID.randomUUID());
        ReflectionTestUtils.setField(job, "sourceContentHash", "b".repeat(64));
        ReflectionTestUtils.setField(job, "sourceEpisodeNo", 2);
        ReflectionTestUtils.setField(job, "inputStateHash", "a".repeat(64));
        return job;
    }

    private SettingCandidate character(AnalysisJob job, String value, int offset) {
        SettingCandidate candidate = SettingCandidate.create(work, episode, UUID.randomUUID(), job,
                SettingEntityType.CHARACTER, "유진", "유진", target, SettingCandidateMatchStatus.MATCHED,
                "profile.origin", value, SettingValueType.STRING, null, evidence(offset), BigDecimal.ONE, null);
        ReflectionTestUtils.setField(candidate, "id", UUID.randomUUID());
        return candidate;
    }

    private WorldSettingCandidate world(AnalysisJob job, String value, int offset) {
        WorldSettingCandidate candidate = WorldSettingCandidate.create(work, episode, job, WorldSettingCategory.RACE,
                "설인", "서식지", value, evidence(offset), BigDecimal.ONE, null);
        ReflectionTestUtils.setField(candidate, "id", UUID.randomUUID());
        candidate.resolveOrderedSubject(WorldSettingSubjectResolutionType.EXISTING, "world:" + target, "설인",
                JsonNodeFactory.instance.arrayNode().add(target.toString()), JsonNodeFactory.instance.arrayNode());
        return candidate;
    }

    private JsonNode evidence(int offset) {
        return JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode()
                .put("quote", "그는 북부 출신이다").put("startOffset", offset).put("endOffset", offset + 10));
    }
}
