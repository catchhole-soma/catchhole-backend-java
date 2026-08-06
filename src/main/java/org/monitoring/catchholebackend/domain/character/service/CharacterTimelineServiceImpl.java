package org.monitoring.catchholebackend.domain.character.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterTimelineFactResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterTimelineResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterTimelineSummaryResponse;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterTimelineMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterTimelineCursor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterTimelineCursorCodec;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.repository.CharacterTimelineEpisodeCount;
import org.monitoring.catchholebackend.domain.character.repository.CharacterTimelineFactTypeCount;
import org.monitoring.catchholebackend.domain.character.repository.CharacterTimelineQueryRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterTimelineFactFilter;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CharacterTimelineServiceImpl implements CharacterTimelineService {

    private final WorkRepository workRepository;
    private final WorkCharacterRepository workCharacterRepository;
    private final EpisodeRepository episodeRepository;
    private final CharacterSettingSchemaRepository characterSettingSchemaRepository;
    private final CharacterTimelineQueryRepository characterTimelineQueryRepository;
    private final CharacterTimelineCursorCodec characterTimelineCursorCodec;
    private final CharacterTimelineMapper characterTimelineMapper;

    @Override
    public CharacterTimelineSummaryResponse getSummary(
            Long memberId,
            UUID workId,
            UUID characterId,
            CharacterTimelineFactFilter factType
    ) {
        WorkCharacter character = getOwnedActiveCharacter(memberId, workId, characterId);
        List<CharacterFactType> supportedTypes = CharacterTimelineFactFilter.supportedFactTypes();
        List<CharacterFactType> filteredTypes = factType.toFactTypes();

        List<CharacterTimelineFactTypeCount> typeCounts =
                characterTimelineQueryRepository.countByFactType(workId, characterId, supportedTypes);
        Map<CharacterFactType, Long> countsByType = toCountMap(typeCounts);
        long totalFactCount = supportedTypes.stream().mapToLong(type -> countsByType.getOrDefault(type, 0L)).sum();
        long filteredFactCount = filteredTypes.stream().mapToLong(type -> countsByType.getOrDefault(type, 0L)).sum();

        List<CharacterTimelineEpisodeCount> filteredEpisodeCounts =
                characterTimelineQueryRepository.countByEpisode(workId, characterId, filteredTypes);
        List<CharacterTimelineEpisodeCount> allEpisodeCounts = factType == CharacterTimelineFactFilter.ALL
                ? filteredEpisodeCounts
                : characterTimelineQueryRepository.countByEpisode(workId, characterId, supportedTypes);
        long episodeFactCount = filteredEpisodeCounts.stream()
                .mapToLong(CharacterTimelineEpisodeCount::factCount)
                .sum();

        return new CharacterTimelineSummaryResponse(
                character.getId(),
                character.getName(),
                findFirstAppearanceEpisodeNo(character, workId),
                totalFactCount,
                allEpisodeCounts.size(),
                factType,
                filteredFactCount,
                characterTimelineMapper.toFactTypeCountResponses(supportedTypes, typeCounts),
                characterTimelineMapper.toEpisodeResponses(filteredEpisodeCounts),
                filteredFactCount - episodeFactCount
        );
    }

    @Override
    public CharacterTimelineResponse getTimeline(
            Long memberId,
            UUID workId,
            UUID characterId,
            CharacterTimelineFactFilter factType,
            String cursor,
            Integer fromEpisodeNo,
            int size
    ) {
        getOwnedActiveCharacter(memberId, workId, characterId);
        if (cursor != null && fromEpisodeNo != null) {
            throw new AppException(CharacterErrorCode.CHARACTER_TIMELINE_CURSOR_INVALID);
        }

        CharacterTimelineCursor decodedCursor = cursor == null
                ? new CharacterTimelineCursor(characterId, factType, fromEpisodeNo, 0)
                : characterTimelineCursorCodec.decode(cursor);
        validateCursor(decodedCursor, characterId, factType);

        List<CharacterFact> queriedFacts =
                characterTimelineQueryRepository.findTimelineFacts(
                        workId,
                        characterId,
                        factType.toFactTypes(),
                        decodedCursor.fromEpisodeNo(),
                        decodedCursor.offset(),
                        size + 1
                );
        boolean hasNext = queriedFacts.size() > size;
        List<CharacterFact> pageFacts = hasNext
                ? queriedFacts.subList(0, size)
                : queriedFacts;
        List<CharacterSettingSchema> schemas = characterSettingSchemaRepository.findAllActiveForWork(workId);
        List<CharacterTimelineFactResponse> content = pageFacts.stream()
                .map(fact -> characterTimelineMapper.toFactResponse(fact, schemas))
                .toList();

        String nextCursor = hasNext
                ? characterTimelineCursorCodec.encode(new CharacterTimelineCursor(
                        characterId,
                        factType,
                        decodedCursor.fromEpisodeNo(),
                        Math.addExact(decodedCursor.offset(), content.size())
                ))
                : null;
        return new CharacterTimelineResponse(content, nextCursor, hasNext, content.size());
    }

    private WorkCharacter getOwnedActiveCharacter(Long memberId, UUID workId, UUID characterId) {
        workRepository.getOwnedWork(workId, memberId);
        return workCharacterRepository.findByIdAndWorkIdAndStatus(
                        characterId,
                        workId,
                        CharacterStatus.ACTIVE
                )
                .orElseThrow(() -> new AppException(CharacterErrorCode.CHARACTER_NOT_FOUND));
    }

    private Integer findFirstAppearanceEpisodeNo(WorkCharacter character, UUID workId) {
        UUID episodeId = character.getFirstAppearanceEpisodeId();
        if (episodeId == null) {
            return null;
        }
        return episodeRepository.findByIdAndWorkId(episodeId, workId)
                .map(episode -> episode.getEpisodeNo())
                .orElse(null);
    }

    private Map<CharacterFactType, Long> toCountMap(List<CharacterTimelineFactTypeCount> counts) {
        Map<CharacterFactType, Long> countsByType = new EnumMap<>(CharacterFactType.class);
        counts.forEach(count -> countsByType.put(count.factType(), count.count()));
        return countsByType;
    }

    private void validateCursor(
            CharacterTimelineCursor cursor,
            UUID characterId,
            CharacterTimelineFactFilter factType
    ) {
        if (!cursor.characterId().equals(characterId) || cursor.factType() != factType) {
            throw new AppException(CharacterErrorCode.CHARACTER_TIMELINE_CURSOR_INVALID);
        }
    }
}
