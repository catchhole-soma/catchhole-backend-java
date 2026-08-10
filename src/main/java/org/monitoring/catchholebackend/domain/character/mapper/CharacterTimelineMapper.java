package org.monitoring.catchholebackend.domain.character.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterTimelineEpisodeResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterTimelineFactFacetResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterTimelineFactKeyCountResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterTimelineFactResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterTimelineFactTypeCountResponse;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.processor.CharacterFactSourceResolver;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSettingDisplayNameResolver;
import org.monitoring.catchholebackend.domain.character.repository.CharacterTimelineEpisodeCount;
import org.monitoring.catchholebackend.domain.character.repository.CharacterTimelineFactDisplaySource;
import org.monitoring.catchholebackend.domain.character.repository.CharacterTimelineFactKeyCount;
import org.monitoring.catchholebackend.domain.character.repository.CharacterTimelineFactTypeCount;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterTimelineSourceType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CharacterTimelineMapper {

    private final CharacterSettingDisplayNameResolver characterSettingDisplayNameResolver;
    private final CharacterFactSourceResolver characterFactSourceResolver;

    public CharacterTimelineFactResponse toFactResponse(
            CharacterFact fact,
            List<CharacterSettingSchema> schemas
    ) {
        Episode sourceEpisode = characterFactSourceResolver.resolveEpisode(fact);
        return new CharacterTimelineFactResponse(
                fact.getId(),
                fact.getFactType(),
                fact.getFactKey(),
                fact.getFactType().getToKorean(),
                characterSettingDisplayNameResolver.resolve(
                        fact.getFactType(),
                        fact.getFactKey(),
                        fact.getValueJson(),
                        schemas
                ),
                fact.getFactValue(),
                sourceEpisode == null
                        ? CharacterTimelineSourceType.MANUAL
                        : CharacterTimelineSourceType.EPISODE,
                sourceEpisode == null ? null : sourceEpisode.getId(),
                sourceEpisode == null ? null : sourceEpisode.getEpisodeNo(),
                characterFactSourceResolver.hasEvidence(fact)
        );
    }

    public List<CharacterTimelineFactFacetResponse> toFactFacetResponses(
            List<CharacterFactType> supportedFactTypes,
            List<CharacterTimelineFactTypeCount> typeCounts,
            List<CharacterTimelineFactKeyCount> factKeyCounts,
            List<CharacterTimelineFactDisplaySource> factDisplaySources,
            List<CharacterSettingSchema> schemas
    ) {
        Map<CharacterFactType, List<CharacterTimelineFactKeyCount>> keysByType = factKeyCounts.stream()
                .collect(Collectors.groupingBy(CharacterTimelineFactKeyCount::factType));
        Map<CharacterFactType, Map<String, JsonNode>> displayValuesByType = new EnumMap<>(CharacterFactType.class);
        for (CharacterTimelineFactDisplaySource displaySource : factDisplaySources) {
            Map<String, JsonNode> displayValues = displayValuesByType.computeIfAbsent(
                    displaySource.factType(),
                    ignored -> new HashMap<>()
            );
            // Repository가 current 우선·최신순으로 정렬했으므로 key별 첫 값만 대표값으로 유지한다.
            if (!displayValues.containsKey(displaySource.factKey())) {
                displayValues.put(displaySource.factKey(), displaySource.valueJson());
            }
        }
        return supportedFactTypes.stream()
                .map(factType -> new CharacterTimelineFactFacetResponse(
                        factType,
                        factType.getToKorean(),
                        findCount(typeCounts, factType),
                        keysByType.getOrDefault(factType, List.of()).stream()
                                .map(factKeyCount -> new CharacterTimelineFactKeyCountResponse(
                                        factKeyCount.factKey(),
                                        characterSettingDisplayNameResolver.resolve(
                                                factType,
                                                factKeyCount.factKey(),
                                                displayValuesByType
                                                        .getOrDefault(factType, Map.of())
                                                        .get(factKeyCount.factKey()),
                                                schemas
                                        ),
                                        factKeyCount.count()
                                ))
                                .toList()
                ))
                .toList();
    }

    public List<CharacterTimelineFactTypeCountResponse> toFactTypeCountResponses(
            List<CharacterFactType> supportedFactTypes,
            List<CharacterTimelineFactTypeCount> counts
    ) {
        return supportedFactTypes.stream()
                .map(factType -> new CharacterTimelineFactTypeCountResponse(
                        factType,
                        factType.getToKorean(),
                        findCount(counts, factType)
                ))
                .toList();
    }

    public List<CharacterTimelineEpisodeResponse> toEpisodeResponses(
            List<CharacterTimelineEpisodeCount> episodeCounts
    ) {
        return episodeCounts.stream()
                .map(count -> new CharacterTimelineEpisodeResponse(
                        count.episodeId(),
                        count.episodeNo(),
                        count.factCount()
                ))
                .toList();
    }

    private long findCount(
            List<CharacterTimelineFactTypeCount> counts,
            CharacterFactType factType
    ) {
        return counts.stream()
                .filter(count -> count.factType() == factType)
                .mapToLong(CharacterTimelineFactTypeCount::count)
                .findFirst()
                .orElse(0L);
    }
}
