package org.monitoring.catchholebackend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.test.util.ReflectionTestUtils;

class CharacterNarrativeTimeFinalizationTest {
    @Test void sameBatchTimestampAndReverseIdsCannotReorderTheValidatedTimeChain() {
        var json = JsonNodeFactory.instance;
        var work = Work.create(Member.register("final-time@example.invalid", "secret", "01000000000", "작가"), "시간", WorkGenre.FANTASY, "");
        ReflectionTestUtils.setField(work, "id", UUID.randomUUID());
        var character = WorkCharacter.create(work, "서린", null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(character, "id", UUID.randomUUID());
        var job = mock(AnalysisJob.class);
        when(job.getId()).thenReturn(UUID.randomUUID());
        var candidates = mock(SettingCandidateRepository.class);
        var characters = mock(WorkCharacterRepository.class);
        var confirmation = mock(CharacterAnalysisConfirmation.class);
        var finalizer = mock(SettingCandidatePromotionServiceImpl.class, CALLS_REAL_METHODS);
        var changes = mock(org.springframework.context.ApplicationEventPublisher.class);
        ReflectionTestUtils.setField(finalizer, "validationEvents", changes);
        ReflectionTestUtils.setField(finalizer, "settingCandidateRepository", candidates);
        ReflectionTestUtils.setField(finalizer, "workCharacterRepository", characters);
        ReflectionTestUtils.setField(finalizer, "analysisConfirmation", confirmation);
        when(characters.findByIdAndWorkIdForUpdate(character.getId(), work.getId())).thenReturn(java.util.Optional.of(character));
        when(confirmation.resolvedCharacterId(any())).thenReturn(character.getId());
        List<SettingCandidate> ordered = new ArrayList<>();
        ObjectNode previous = null;
        var sameTime = LocalDateTime.of(2026, 10, 13, 12, 0);
        for (int i = 1; i <= 3; i++) {
            var c = SettingCandidate.create(work, null, UUID.randomUUID(), job, SettingEntityType.CHARACTER,
                    "서린", "profile.직업", "관측", SettingValueType.STRING, json.objectNode().put("value", "기사"),
                    json.arrayNode(), null, json.objectNode());
            ReflectionTestUtils.setField(c, "id", new UUID(0, 4 - i));
            ReflectionTestUtils.setField(c, "matchedCharacterId", character.getId());
            ReflectionTestUtils.setField(c, "comparedAt", sameTime);
            var selected = json.objectNode().put("description", "T" + i).put("sourceCandidateId", c.getId().toString());
            selected.putArray("evidenceSpans");
            var marker = json.objectNode().put("advance", true);
            marker.set("expectedCurrent", previous == null ? json.nullNode() : previous.deepCopy());
            marker.set("selected", selected);
            ReflectionTestUtils.setField(c, "rawComparisonJson", json.objectNode().set("validatedNarrativeTime", marker));
            c.confirm();
            ordered.add(c);
            previous = selected;
        }
        when(candidates.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(job.getId())).thenReturn(ordered);

        finalizer.finalizeNarrativeTimes(job);

        assertThat(character.getCurrentNarrativeTime().path("description").asText()).isEqualTo("T3");
        assertThat(character.getSnapshotVersion()).isEqualTo(1);
        verify(changes, times(3)).publishEvent(new org.monitoring.catchholebackend.domain.validation.event.ValidationStagingEvents.ConfirmedSettingsChanged(work.getId()));
    }
}
