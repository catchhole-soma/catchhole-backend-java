package org.monitoring.catchholebackend.domain.character.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;

@DisplayName("설정 후보 시간순 정렬 단위 테스트")
class SettingCandidateChronologyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("같은 회차 후보는 생성 시각보다 원문 등장 순서로 정렬한다")
    void sortsSameEpisodeByEvidenceOffsetBeforeCreatedAt() {
        Episode episode = org.mockito.Mockito.mock(Episode.class);
        org.mockito.Mockito.when(episode.getEpisodeNo()).thenReturn(5);
        SettingCandidate laterInText = candidate(episode, 200, LocalDateTime.of(2026, 8, 1, 10, 0));
        SettingCandidate earlierInText = candidate(episode, 20, LocalDateTime.of(2026, 8, 1, 11, 0));

        assertThat(SettingCandidateChronology.sorted(List.of(laterInText, earlierInText)))
                .containsExactly(earlierInText, laterInText);
    }

    @ParameterizedTest
    @ValueSource(strings = {"start_offset", "startOffset"})
    @DisplayName("실제 저장형과 구형 근거 모두 생성 시각이 같고 UUID가 반대여도 원문 순서를 따른다")
    void preservesEvidenceOrderWhenCreationTimeTiesAndIdentifiersReverse(String field) {
        Episode episode = org.mockito.Mockito.mock(Episode.class);
        org.mockito.Mockito.when(episode.getEpisodeNo()).thenReturn(1);
        LocalDateTime sameTime = LocalDateTime.of(2026, 9, 10, 15, 44, 16);
        SettingCandidate earlier = candidate(episode, 10, sameTime);
        SettingCandidate later = candidate(episode, 14, sameTime);
        org.mockito.Mockito.when(earlier.getId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        org.mockito.Mockito.when(later.getId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        org.mockito.Mockito.when(earlier.getEvidenceSpans()).thenReturn(
                objectMapper.createArrayNode().add(objectMapper.createObjectNode().put(field, 10)));
        org.mockito.Mockito.when(later.getEvidenceSpans()).thenReturn(
                objectMapper.createArrayNode().add(objectMapper.createObjectNode().put(field, 14)));
        assertThat(SettingCandidateChronology.sorted(List.of(later, earlier)))
                .containsExactly(earlier, later);
    }

    @Test
    @DisplayName("기존 묶음의 순서와 미배정 원문 위치를 섞어도 입력 순열에 무관한 순서를 유지한다")
    void preservesExistingBatchesAndUnassignedPositionsWithoutComparatorCycles() {
        Episode episode = org.mockito.Mockito.mock(Episode.class);
        org.mockito.Mockito.when(episode.getEpisodeNo()).thenReturn(1);
        LocalDateTime time = LocalDateTime.of(2026, 9, 10, 12, 0);
        SettingCandidate firstAssigned = candidate(episode, 50, time);
        SettingCandidate secondAssigned = candidate(episode, 10, time);
        SettingCandidate laterBatch = candidate(episode, 20, time);
        SettingCandidate unassigned = candidate(episode, 30, time);
        var firstBatch = org.mockito.Mockito.mock(
                org.monitoring.catchholebackend.domain.character.entity.CharacterFactComparisonBatch.class);
        var secondBatch = org.mockito.Mockito.mock(
                org.monitoring.catchholebackend.domain.character.entity.CharacterFactComparisonBatch.class);
        org.mockito.Mockito.when(firstBatch.getId()).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.when(firstBatch.getCreatedAt()).thenReturn(time);
        org.mockito.Mockito.when(secondBatch.getId()).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.when(secondBatch.getCreatedAt()).thenReturn(time.plusSeconds(1));
        org.mockito.Mockito.when(firstAssigned.getCharacterComparisonBatch()).thenReturn(firstBatch);
        org.mockito.Mockito.when(firstAssigned.getCharacterComparisonCandidateRef()).thenReturn("C1");
        org.mockito.Mockito.when(secondAssigned.getCharacterComparisonBatch()).thenReturn(firstBatch);
        org.mockito.Mockito.when(secondAssigned.getCharacterComparisonCandidateRef()).thenReturn("C2");
        org.mockito.Mockito.when(laterBatch.getCharacterComparisonBatch()).thenReturn(secondBatch);
        org.mockito.Mockito.when(laterBatch.getCharacterComparisonCandidateRef()).thenReturn("C1");
        List<SettingCandidate> values = List.of(firstAssigned, secondAssigned, laterBatch, unassigned);
        for (SettingCandidate a : values) {
            for (SettingCandidate b : values) {
                for (SettingCandidate c : values) {
                    for (SettingCandidate d : values) {
                        var permutation = List.of(a, b, c, d);
                        if (permutation.stream().distinct().count() == 4) {
                            assertThat(SettingCandidateChronology.sorted(permutation))
                                    .containsExactly(firstAssigned, secondAssigned, unassigned, laterBatch);
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("한 후보의 두 표기 근거가 섞여 있어도 가장 이른 유효한 위치를 사용한다")
    void findsEarliestEvidenceAcrossBothStoredFormats() {
        Episode episode = org.mockito.Mockito.mock(Episode.class);
        org.mockito.Mockito.when(episode.getEpisodeNo()).thenReturn(1);
        SettingCandidate mixed = candidate(episode, 300, LocalDateTime.of(2026, 9, 10, 11, 0));
        SettingCandidate middle = candidate(episode, 100, LocalDateTime.of(2026, 9, 10, 10, 0));
        org.mockito.Mockito.when(mixed.getEvidenceSpans()).thenReturn(objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("start_offset", 300))
                .add(objectMapper.createObjectNode().put("startOffset", 20)));
        assertThat(SettingCandidateChronology.sorted(List.of(middle, mixed))).containsExactly(mixed, middle);
    }

    private SettingCandidate candidate(Episode episode, int startOffset, LocalDateTime createdAt) {
        SettingCandidate candidate = org.mockito.Mockito.mock(SettingCandidate.class);
        org.mockito.Mockito.when(candidate.getEpisode()).thenReturn(episode);
        org.mockito.Mockito.when(candidate.getEvidenceSpans()).thenReturn(
                objectMapper.createArrayNode().add(
                        objectMapper.createObjectNode().put("startOffset", startOffset)
                )
        );
        org.mockito.Mockito.when(candidate.getCreatedAt()).thenReturn(createdAt);
        org.mockito.Mockito.when(candidate.getId()).thenReturn(UUID.randomUUID());
        return candidate;
    }
}
