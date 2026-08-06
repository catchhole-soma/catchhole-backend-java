package org.monitoring.catchholebackend.domain.character.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterTimelineResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterTimelineSummaryResponse;
import org.monitoring.catchholebackend.domain.character.type.CharacterTimelineFactFilter;

public interface CharacterTimelineService {

    CharacterTimelineSummaryResponse getSummary(
            Long memberId,
            UUID workId,
            UUID characterId,
            CharacterTimelineFactFilter factType
    );

    CharacterTimelineResponse getTimeline(
            Long memberId,
            UUID workId,
            UUID characterId,
            CharacterTimelineFactFilter factType,
            String cursor,
            Integer fromEpisodeNo,
            int size
    );
}
