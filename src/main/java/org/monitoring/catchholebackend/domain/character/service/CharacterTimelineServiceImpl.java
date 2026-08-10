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
import org.monitoring.catchholebackend.domain.character.processor.CharacterTimelineFilterSelection;
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
            CharacterTimelineFactFilter factType,
            List<CharacterTimelineFactFilter> factTypes,
            List<String> factKeys
    ) {
        WorkCharacter character = getOwnedActiveCharacter(memberId, workId, characterId);
        CharacterTimelineFilterSelection selection = CharacterTimelineFilterSelection.from(
                factType,
                factTypes,
                factKeys
        );
        List<CharacterFactType> supportedTypes = CharacterTimelineFactFilter.supportedFactTypes();

        List<CharacterTimelineFactTypeCount> typeCounts =
                characterTimelineQueryRepository.countByFactType(workId, characterId, supportedTypes);
        Map<CharacterFactType, Long> countsByType = toCountMap(typeCounts);
        long totalFactCount = supportedTypes.stream().mapToLong(type -> countsByType.getOrDefault(type, 0L)).sum();
        // 상위 유형만 고른 경우에는 이미 조회한 유형별 집계를 재사용한다. 하위 key가
        // 섞인 OR 조건만 별도 count query로 계산해 중복 집계와 불필요한 DB 왕복을 피한다.
        long filteredFactCount = selection.all()
                ? totalFactCount
                : selection.factKeys().isEmpty()
                        ? selection.factTypes().stream()
                                .mapToLong(type -> countsByType.getOrDefault(type, 0L))
                                .sum()
                        : characterTimelineQueryRepository.countFacts(
                                workId,
                                characterId,
                                supportedTypes,
                                selection
                        );

        List<CharacterTimelineEpisodeCount> filteredEpisodeCounts =
                characterTimelineQueryRepository.countByEpisode(workId, characterId, supportedTypes, selection);
        List<CharacterTimelineEpisodeCount> allEpisodeCounts = selection.all()
                ? filteredEpisodeCounts
                : characterTimelineQueryRepository.countByEpisode(
                        workId,
                        characterId,
                        supportedTypes,
                        CharacterTimelineFilterSelection.from(CharacterTimelineFactFilter.ALL, null, null)
                );
        long episodeFactCount = filteredEpisodeCounts.stream()
                .mapToLong(CharacterTimelineEpisodeCount::factCount)
                .sum();
        List<CharacterSettingSchema> schemas = characterSettingSchemaRepository.findAllActiveForWork(workId);

        return new CharacterTimelineSummaryResponse(
                character.getId(),
                character.getName(),
                findFirstAppearanceEpisodeNo(character, workId),
                totalFactCount,
                allEpisodeCounts.size(),
                selection.appliedFactType(),
                selection.explicitFactTypes(),
                selection.factKeys(),
                filteredFactCount,
                characterTimelineMapper.toFactTypeCountResponses(supportedTypes, typeCounts),
                characterTimelineMapper.toFactFacetResponses(
                        supportedTypes,
                        typeCounts,
                        characterTimelineQueryRepository.countByFactKey(workId, characterId, supportedTypes),
                        characterTimelineQueryRepository.findFactDisplaySources(
                                workId,
                                characterId,
                                supportedTypes
                        ),
                        schemas
                ),
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
            List<CharacterTimelineFactFilter> factTypes,
            List<String> factKeys,
            String cursor,
            Integer fromEpisodeNo,
            int size
    ) {
        getOwnedActiveCharacter(memberId, workId, characterId);
        if (cursor != null && fromEpisodeNo != null) {
            throw new AppException(CharacterErrorCode.CHARACTER_TIMELINE_CURSOR_INVALID);
        }
        CharacterTimelineFilterSelection selection = CharacterTimelineFilterSelection.from(
                factType,
                factTypes,
                factKeys
        );
        String filterFingerprint = selection.cursorFingerprint();

        CharacterTimelineCursor decodedCursor = cursor == null
                ? new CharacterTimelineCursor(characterId, filterFingerprint, fromEpisodeNo, 0)
                : characterTimelineCursorCodec.decode(cursor);
        validateCursor(decodedCursor, characterId, filterFingerprint);

        List<CharacterFact> queriedFacts =
                characterTimelineQueryRepository.findTimelineFacts(
                        workId,
                        characterId,
                        CharacterTimelineFactFilter.supportedFactTypes(),
                        selection,
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
                        filterFingerprint,
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
            String filterFingerprint
    ) {
        if (!cursor.characterId().equals(characterId)
                || !cursor.filterFingerprint().equals(filterFingerprint)) {
            throw new AppException(CharacterErrorCode.CHARACTER_TIMELINE_CURSOR_INVALID);
        }
    }
}
