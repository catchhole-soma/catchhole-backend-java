package org.monitoring.catchholebackend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisHumanRejectionPolicy;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFactComparisonBatch;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("캐릭터 비교 보류 시 서사 시점 복원")
class CharacterNarrativeTimeRollbackTest {
    private final JsonNodeFactory json = JsonNodeFactory.instance;

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void holdingLaterRecoveryKeepsEarlierIndependentTimeAndHistory(boolean checkTime) {
        assertHoldingLaterRecovery(checkTime, false);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    @DisplayName("인물 묶음의 회복 후보 보류는 독립적인 앞 시점과 이력을 보존한다")
    void wholePersonBatchHoldingRecoveryKeepsEarlierIndependentTimeAndHistory(boolean checkTime) {
        assertHoldingLaterRecovery(checkTime, true);
    }

    private void assertHoldingLaterRecovery(boolean checkTime, boolean wholePerson) {
        var journal = new AnalysisStateJournal();
        var states = mock(AnalysisRunStateService.class);
        var candidates = mock(SettingCandidateRepository.class);
        var evidence = mock(CharacterCurrentSupportingEvidenceResolver.class);
        var job = mock(AnalysisJob.class);
        var work = Work.create(Member.register("rollback@example.invalid", "secret", "01000000000", "작가"), "시점", WorkGenre.FANTASY, "");
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        UUID ownerId = UUID.randomUUID();
        String ref = CharacterAnalysisStateMapper.persistedRef(ownerId);
        when(job.getId()).thenReturn(UUID.randomUUID());
        when(job.getWork()).thenReturn(work);
        when(job.isOrderedProvisional()).thenReturn(true);
        when(job.isAutomaticReview()).thenReturn(true);
        when(job.getSourceKind()).thenReturn(AnalysisSourceKind.EPISODE);
        when(job.getSourceEpisodeNo()).thenReturn(3);
        when(job.getInputStateHash()).thenReturn("input");
        ObjectNode baseline = journal.emptyState();
        ObjectNode owner = json.objectNode().put("name", "서린").put("actualCharacterId", ownerId.toString()).put("snapshotVersion", 0);
        owner.putArray("protectedSlots"); owner.putObject("absences");
        owner.set("currentNarrativeTime", point("T0", UUID.randomUUID()));
        owner.putArray("historyFacts").add(json.objectNode().put("factKey", "status.이전_사건").put("operation", "HISTORY_ONLY"));
        var old = owner.putObject("slots").putObject("STATUS:status.검지_경직").put("factValue", "경직");
        old.putObject("valueJson").put("active", true);
        old.putObject("provenance").putArray("currentSourceTimes").add(json.objectNode().put("sourceKind", "EPISODE").put("sourceEpisodeNo", 2).put("temporalScope", "PRESENT"));
        old.putArray("supportingSources").add(json.objectNode().put("sourceCandidateId", UUID.randomUUID().toString()));
        ((ObjectNode) baseline.path("characters")).set(ref, owner);
        var projected = new AtomicReference<JsonNode>(baseline.deepCopy());
        List<AnalysisStateChange> recorded = new ArrayList<>();
        ObjectNode log = json.objectNode(); log.putArray("changes");
        when(job.getStateJournal()).thenReturn(log);
        when(states.getInputState(job)).thenReturn(baseline);
        when(states.getProjectedState(job)).thenAnswer(call -> projected.get());
        doAnswer(call -> {
            List<AnalysisStateChange> changes = call.getArgument(2);
            recorded.addAll(changes);
            projected.set(journal.apply(baseline, recorded));
            log.set("changes", journal.toJson(recorded));
            return null;
        }).when(states).appendValidatedChanges(eq(job), eq("input"), any());
        when(evidence.captureCandidate(any(), any())).thenReturn(json.objectNode().put("availability", "VERIFIED"));
        var service = new CharacterAnalysisStateService(states, candidates, new CharacterAnalysisStateMapper(), mock(AnalysisHumanRejectionPolicy.class), evidence);
        var c1 = candidate(work, job, ownerId, CharacterFactOperation.EXCLUDE, CharacterFactType.PROFILE, "profile.직업");
        choose(c1, owner.path("currentNarrativeTime"), "T1");
        service.recordDecision(job, c1, entry(CharacterFactType.PROFILE, "profile.직업"), List.of(), List.of());
        when(candidates.findByIdAndWorkIdForUpdate(c1.getId(), work.getId())).thenReturn(java.util.Optional.of(c1));
        var c2 = candidate(work, job, ownerId, CharacterFactOperation.REMOVE, CharacterFactType.STATUS, "status.회복");
        if (wholePerson) when(c2.getCharacterComparisonBatch().getCanonicalFactType()).thenReturn(null);
        c2.recordNarrativeTime(null);
        choose(c2, projected.get().path("characters").path(ref).path("currentNarrativeTime"), "T2");
        ReflectionTestUtils.setField(c2, "removedSnapshotEntriesJson", json.arrayNode().add(json.objectNode().put("factType", "STATUS").put("factKey", "status.검지_경직")));
        service.recordDecision(job, c2, entry(CharacterFactType.STATUS, "status.회복"), List.of(new CharacterSnapshotSlot(CharacterFactType.STATUS, "status.검지_경직")), List.of());
        assertThat(projected.get().path("characters").path(ref).path("currentNarrativeTime").path("description").asText()).isEqualTo("T2");
        var c3 = candidate(work, job, ownerId, CharacterFactOperation.REVIEW_REQUIRED, CharacterFactType.STATUS, "status.회복");
        when(candidates.findByIdAndWorkIdForUpdate(c2.getId(), work.getId())).thenReturn(java.util.Optional.of(c2));
        service.recordDecision(job, c3, entry(CharacterFactType.STATUS, "status.회복"), List.of(), List.of(c2.getId()), "Q2");
        JsonNode result = projected.get().path("characters").path(ref);
        assertThat(c2.getAutomaticReviewHoldReason()).isNotNull();
        if (checkTime) assertThat(result.path("currentNarrativeTime").path("description").asText()).isEqualTo("T1");
        else {
            assertThat(result.path("historyFacts")).hasSize(1);
            assertThat(result.path("historyFacts").get(0).path("factKey").asText()).isEqualTo("status.이전_사건");
        }
    }

    private SettingCandidate candidate(Work work, AnalysisJob job, UUID owner, CharacterFactOperation operation, CharacterFactType type, String key) {
        var c = SettingCandidate.create(work, null, UUID.randomUUID(), job, SettingEntityType.CHARACTER, "서린", key, "관측", SettingValueType.JSON, json.objectNode().put("active", true), json.arrayNode(), null, json.objectNode());
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(c, "matchedCharacterId", owner);
        ReflectionTestUtils.setField(c, "suggestedOperation", operation);
        ReflectionTestUtils.setField(c, "temporalScope", CharacterFactTemporalScope.PRESENT);
        ReflectionTestUtils.setField(c, "resolvedCanonicalFactKey", key);
        var batch = mock(CharacterFactComparisonBatch.class); when(batch.getCanonicalFactType()).thenReturn(type);
        ReflectionTestUtils.setField(c, "characterComparisonBatch", batch);
        return c;
    }
    private CharacterSnapshotEntry entry(CharacterFactType type, String key) {
        return new CharacterSnapshotEntry(new CharacterSnapshotSlot(type, key), "관측", json.objectNode().put("active", true), true);
    }
    private ObjectNode point(String description, UUID source) {
        var point = json.objectNode().put("description", description).put("sourceCandidateId", source.toString());
        point.putArray("evidenceSpans"); return point;
    }
    private void choose(SettingCandidate candidate, JsonNode before, String description) {
        var marker = json.objectNode().put("advance", true);
        marker.set("expectedCurrent", org.monitoring.catchholebackend.domain.character.processor.CharacterNarrativeTime.value(before));
        marker.set("selected", point(description, candidate.getId()));
        ReflectionTestUtils.setField(candidate, "rawComparisonJson", json.objectNode().set("validatedNarrativeTime", marker));
    }
}
