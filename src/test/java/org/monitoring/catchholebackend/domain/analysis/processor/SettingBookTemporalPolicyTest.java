package org.monitoring.catchholebackend.domain.analysis.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisCurrentSourceTimePayload;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;
import org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind;

class SettingBookTemporalPolicyTest {
    @Test void automaticPresentUpdateAllowsOptionalBookPositionsInBothUploadOrdersWhileManualStaysStrict() {
        var book = book(SettingBookReferenceTimeKind.UNSPECIFIED, null, null, CharacterFactTemporalScope.PRESENT);
        var episode = episode(3);
        assertThat(SettingBookTemporalPolicy.requiresReview(episode, List.of(book), true, true)).isFalse();
        assertThat(SettingBookTemporalPolicy.requiresReview(book, List.of(episode), true, true)).isFalse();
        assertThat(SettingBookTemporalPolicy.requiresReview(episode, List.of(book), true)).isTrue();
        assertThat(SettingBookTemporalPolicy.requiresReview(book, List.of(episode), true)).isTrue();
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void compatibleDestinationMergeAllowsKnownSameOrEarlierButUpdateAndRemovalStayHeld(int incomingEpisode) {
        var incoming = book(SettingBookReferenceTimeKind.EPISODE, incomingEpisode, null, CharacterFactTemporalScope.PRESENT);
        var current = episode(2);
        assertThat(SettingBookTemporalPolicy.requiresReview(incoming, List.of(current), false, true)).isFalse();
        assertThat(SettingBookTemporalPolicy.requiresReview(incoming, List.of(current), true, true)).isTrue();
        assertThat(SettingBookTemporalPolicy.requiresReview(episode(incomingEpisode), List.of(book(SettingBookReferenceTimeKind.EPISODE, 2, null, CharacterFactTemporalScope.PRESENT)), true, true)).isTrue();
    }

    @Test void knownLaterCurrentCannotHideBehindAnOptionalMergedBookSource() {
        assertThat(SettingBookTemporalPolicy.requiresReview(episode(3), List.of(
                book(SettingBookReferenceTimeKind.UNSPECIFIED, null, null, CharacterFactTemporalScope.PRESENT),
                book(SettingBookReferenceTimeKind.EPISODE, 4, null, CharacterFactTemporalScope.PRESENT)), true, true)).isTrue();
        assertThat(SettingBookTemporalPolicy.requiresReview(episode(5), List.of(
                book(SettingBookReferenceTimeKind.UNSPECIFIED, null, null, CharacterFactTemporalScope.PRESENT),
                book(SettingBookReferenceTimeKind.EPISODE, 4, null, CharacterFactTemporalScope.PRESENT)), true, true)).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"PAST", "HYPOTHETICAL", "UNKNOWN"})
    void nonPresentIncomingAndCurrentRemainProtected(String scope) {
        var nonPresent = book(SettingBookReferenceTimeKind.UNSPECIFIED, null, null, CharacterFactTemporalScope.valueOf(scope));
        assertThat(SettingBookTemporalPolicy.requiresReview(nonPresent, List.of(episode(1)), false, true)).isTrue();
        assertThat(SettingBookTemporalPolicy.requiresReview(episode(3), List.of(nonPresent), true, true)).isTrue();
        assertThat(SettingBookTemporalPolicy.requiresReview(episode(3), List.of(nonPresent), false, true)).isTrue();
    }

    @Test void automaticExistingDestinationMergeRequiresPresentCurrentOriginsWhileLegacyProbeStaysUnchanged() {
        var incoming = book(SettingBookReferenceTimeKind.UNSPECIFIED, null, null, CharacterFactTemporalScope.PRESENT);
        var unknown = new WorkerAnalysisCurrentSourceTimePayload(null, null, null, null, null, CharacterFactTemporalScope.UNKNOWN);
        assertThat(SettingBookTemporalPolicy.requiresReview(incoming, List.of(), false, true)).isTrue();
        assertThat(SettingBookTemporalPolicy.requiresReview(incoming, List.of(unknown), false, true)).isTrue();
        assertThat(SettingBookTemporalPolicy.requiresReview(incoming, List.of(), false)).isFalse();
        assertThat(SettingBookTemporalPolicy.requiresReview(incoming, List.of(unknown), false)).isFalse();
        assertThat(SettingBookTemporalPolicy.requiresReview(incoming, List.of(incoming), false, true)).isFalse();
    }

    @Test void optionalPositionDoesNotWaiveAnchorInconsistencyOrUnknownLegacyCurrent() {
        assertThat(SettingBookTemporalPolicy.requiresReview(book(SettingBookReferenceTimeKind.WORK_START, null, 1, CharacterFactTemporalScope.PRESENT), List.of(), false, true)).isTrue();
        assertThat(SettingBookTemporalPolicy.requiresReview(book(SettingBookReferenceTimeKind.EPISODE, 2, 3, CharacterFactTemporalScope.PRESENT), List.of(), false, true)).isTrue();
        assertThat(SettingBookTemporalPolicy.requiresReview(book(SettingBookReferenceTimeKind.UNSPECIFIED, null, null, CharacterFactTemporalScope.PRESENT),
                List.of(new WorkerAnalysisCurrentSourceTimePayload(null, null, null, null, null, CharacterFactTemporalScope.UNKNOWN)), true, true)).isTrue();
    }

    private WorkerAnalysisCurrentSourceTimePayload episode(int number) {
        return new WorkerAnalysisCurrentSourceTimePayload(AnalysisSourceKind.EPISODE, number, null, null, null, CharacterFactTemporalScope.PRESENT);
    }
    private WorkerAnalysisCurrentSourceTimePayload book(SettingBookReferenceTimeKind kind, Integer reference, Integer effective, CharacterFactTemporalScope scope) {
        return new WorkerAnalysisCurrentSourceTimePayload(AnalysisSourceKind.SETTING_BOOK, null, kind, reference, effective, scope);
    }
}
