package org.monitoring.catchholebackend.domain.character.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.test.util.ReflectionTestUtils;

class CharacterNarrativeTimeTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(value = CharacterFactOperation.class, names = {"ADD", "REMOVE", "EXCLUDE", "HISTORY_ONLY"})
    void presentObservationCanAdvanceEvenWithoutCreatingCurrentValue(CharacterFactOperation operation) throws Exception {
        var candidate = candidate(operation, CharacterFactTemporalScope.PRESENT);
        var time = time();
        var marker = CharacterNarrativeTime.selection(candidate, true, null, time);
        assertThat(marker.path("selected").path("description").asText()).isEqualTo("장마 첫날");
        assertThat(marker.path("selected").path("sourceCandidateId").asText()).isEqualTo(candidate.getId().toString());
        assertThat(marker.path("selected").path("evidenceSpans")).isEqualTo(time.path("referenceTime").path("evidenceSpans"));
        assertThat(time.path("referenceTime").has("sourceCandidateId")).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = CharacterFactTemporalScope.class, names = {"PAST", "HYPOTHETICAL", "UNKNOWN"})
    void pastOrUnknownObservationCannotAdvance(CharacterFactTemporalScope temporalScope) throws Exception {
        var candidate = candidate(CharacterFactOperation.HISTORY_ONLY, temporalScope);
        var time = time();
        assertThatThrownBy(() -> CharacterNarrativeTime.selection(candidate, true, null, time))
                .isInstanceOf(CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException.class);
    }

    @Test void reviewOrHoldCannotAdvance() throws Exception {
        var time = time();
        var review = candidate(CharacterFactOperation.REVIEW_REQUIRED, CharacterFactTemporalScope.PRESENT);
        assertThatThrownBy(() -> CharacterNarrativeTime.selection(review, true, null, time))
                .isInstanceOf(CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException.class);
        var held = candidate(CharacterFactOperation.HISTORY_ONLY, CharacterFactTemporalScope.PRESENT);
        ReflectionTestUtils.setField(held, "automaticReviewHoldReason", org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason.SETTING_TIME_CONFIRMATION_REQUIRED);
        assertThatThrownBy(() -> CharacterNarrativeTime.selection(held, true, null, time))
                .isInstanceOf(CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException.class);
    }

    @Test void unlocatedRepeatedQuoteCannotAdvance() throws Exception {
        var candidate = candidate(CharacterFactOperation.EXCLUDE, CharacterFactTemporalScope.PRESENT);
        var time = time();
        ((com.fasterxml.jackson.databind.node.ObjectNode) time.path("referenceTime").path("evidenceSpans").get(0)).putNull("startOffset");
        assertThatThrownBy(() -> CharacterNarrativeTime.selection(candidate, true, null, time))
                .isInstanceOf(CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException.class);
    }

    @Test void timeSelectionIsInvalidatedWhenCharacterClockChangesBeforeApply() throws Exception {
        var candidate = candidate(CharacterFactOperation.EXCLUDE, CharacterFactTemporalScope.PRESENT);
        var marker = CharacterNarrativeTime.selection(candidate, true, null, time());
        ReflectionTestUtils.setField(candidate, "rawComparisonJson", json.createObjectNode().set("validatedNarrativeTime", marker));
        assertThat(CharacterNarrativeTime.selected(candidate, null)).isEqualTo(marker.path("selected"));
        var changedCurrent = json.createObjectNode().put("description", "장마 둘째날");
        assertThat(CharacterNarrativeTime.selected(candidate, changedCurrent)).isNull();
    }

    @Test void modelDuplicateExclusionKeepsValidatedTimeSelectionForFinalization() throws Exception {
        var candidate = candidate(CharacterFactOperation.EXCLUDE, CharacterFactTemporalScope.PRESENT);
        var marker = CharacterNarrativeTime.selection(candidate, true, null, time());
        ReflectionTestUtils.setField(candidate, "rawComparisonJson", json.createObjectNode().set("validatedNarrativeTime", marker));
        assertThat(candidate.dismiss()).isTrue();
        assertThat(candidate.getRawComparisonJson().path("validatedNarrativeTime")).isEqualTo(marker);
        assertThat(CharacterNarrativeTime.selected(candidate, null)).isEqualTo(marker.path("selected"));
    }

    private SettingCandidate candidate(CharacterFactOperation operation, CharacterFactTemporalScope temporalScope) {
        var work = Work.create(Member.register("narrative@example.invalid", "secret", "01000000000", "작가"), "시간", WorkGenre.FANTASY, "");
        var candidate = SettingCandidate.create(work, null, UUID.randomUUID(), null, SettingEntityType.CHARACTER,
                "서린", "status.흉터", "흉터", SettingValueType.JSON, json.createObjectNode().put("active", true),
                json.createArrayNode(), null, json.createObjectNode());
        ReflectionTestUtils.setField(candidate, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(candidate, "suggestedOperation", operation);
        ReflectionTestUtils.setField(candidate, "temporalScope", temporalScope);
        return candidate;
    }

    private JsonNode time() throws Exception {
        return json.readTree("""
                {"factTime":{"description":"208년 사고","evidenceSpans":[{"quote":"208년","startOffset":0,"endOffset":4,
                  "sourceChunkId":"10000000-0000-4000-8000-000000000001"}]},
                 "referenceTime":{"description":"장마 첫날","evidenceSpans":[{"quote":"장마 첫날","startOffset":9,"endOffset":14,
                  "sourceChunkId":"10000000-0000-4000-8000-000000000002"}]}}
                """);
    }
}
