package org.monitoring.catchholebackend.domain.character.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterTimelineFactFilter;
import org.monitoring.catchholebackend.global.exception.AppException;

@DisplayName("캐릭터 타임라인 다중 필터 정규화")
class CharacterTimelineFilterSelectionTest {

    @Test
    @DisplayName("선택 순서와 중복이 달라도 같은 cursor fingerprint를 만든다")
    void normalizesSelectionBeforeCreatingCursorFingerprint() {
        CharacterTimelineFilterSelection first = CharacterTimelineFilterSelection.from(
                CharacterTimelineFactFilter.ALL,
                List.of(CharacterTimelineFactFilter.STAT, CharacterTimelineFactFilter.PROFILE),
                List.of("stats.strength", "profile.height", "stats.strength")
        );
        CharacterTimelineFilterSelection second = CharacterTimelineFilterSelection.from(
                CharacterTimelineFactFilter.ALL,
                List.of(CharacterTimelineFactFilter.PROFILE, CharacterTimelineFactFilter.STAT),
                List.of("profile.height", "stats.strength")
        );

        assertThat(first.factTypes()).containsExactly(CharacterFactType.PROFILE, CharacterFactType.STAT);
        assertThat(first.explicitFactTypes()).containsExactly(CharacterFactType.PROFILE, CharacterFactType.STAT);
        assertThat(first.factKeys()).containsExactly("profile.height", "stats.strength");
        assertThat(first.cursorFingerprint()).isEqualTo(second.cursorFingerprint());
    }

    @Test
    @DisplayName("기존 단일 필터의 유효 유형은 종류별 상위 선택으로 노출하지 않는다")
    void separatesLegacyFilterFromExplicitMultiSelection() {
        CharacterTimelineFilterSelection legacySelection = CharacterTimelineFilterSelection.from(
                CharacterTimelineFactFilter.STATUS,
                null,
                null
        );

        assertThat(legacySelection.factTypes()).containsExactly(CharacterFactType.STATUS);
        assertThat(legacySelection.explicitFactTypes()).isEmpty();
    }

    @Test
    @DisplayName("기존 단일 필터와 다중 필터를 함께 전달하면 거절한다")
    void rejectsMixedLegacyAndMultiSelection() {
        assertThatThrownBy(() -> CharacterTimelineFilterSelection.from(
                CharacterTimelineFactFilter.STATUS,
                null,
                List.of("status.injury")
        ))
                .isInstanceOf(AppException.class)
                .extracting(exception -> ((AppException) exception).getResultCode())
                .isEqualTo(CharacterErrorCode.CHARACTER_TIMELINE_FILTER_INVALID);
    }
}
