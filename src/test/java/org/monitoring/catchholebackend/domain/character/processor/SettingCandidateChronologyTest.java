package org.monitoring.catchholebackend.domain.character.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
