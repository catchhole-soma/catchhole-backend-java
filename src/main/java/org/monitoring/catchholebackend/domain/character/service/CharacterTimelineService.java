package org.monitoring.catchholebackend.domain.character.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterTimelineResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterTimelineSummaryResponse;
import org.monitoring.catchholebackend.domain.character.type.CharacterTimelineFactFilter;

public interface CharacterTimelineService {

    CharacterTimelineSummaryResponse getSummary(
            Long memberId,
            UUID workId,
            UUID characterId,
            CharacterTimelineFactFilter factType,
            List<CharacterTimelineFactFilter> factTypes,
            List<String> factKeys
    );

    CharacterTimelineResponse getTimeline(
            Long memberId,
            UUID workId,
            UUID characterId,
            CharacterTimelineFactFilter factType,
            List<CharacterTimelineFactFilter> factTypes,
            List<String> factKeys,
            String cursor,
            Integer fromEpisodeNo,
            int size
    );
}
