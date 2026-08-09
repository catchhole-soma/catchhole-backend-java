package org.monitoring.catchholebackend.domain.character.repository;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.springframework.stereotype.Repository;

/**
 * CharacterFact 검색과 다른 타임라인 전용 집계·정렬 조회를 담당한다.
 * 설정 검색 Repository에 cursor와 회차 그룹 로직을 섞지 않아 각 조회 계약을 독립적으로 유지한다.
 */
@Repository
@RequiredArgsConstructor
public class CharacterTimelineQueryRepository {

    private static final String BASE_CONDITION = """
            character.id = :characterId
            and character.work.id = :workId
            and character.status = :characterStatus
            and fact.factType in :factTypes
            """;

    private final EntityManager entityManager;

    public List<CharacterTimelineFactTypeCount> countByFactType(
            UUID workId,
            UUID characterId,
            List<CharacterFactType> factTypes
    ) {
        return entityManager.createQuery("""
                        select new org.monitoring.catchholebackend.domain.character.repository.CharacterTimelineFactTypeCount(
                            fact.factType,
                            count(fact)
                        )
                        from CharacterFact fact
                        join fact.workCharacter character
                        where %s
                        group by fact.factType
                        """.formatted(BASE_CONDITION), CharacterTimelineFactTypeCount.class)
                .setParameter("workId", workId)
                .setParameter("characterId", characterId)
                .setParameter("characterStatus", CharacterStatus.ACTIVE)
                .setParameter("factTypes", factTypes)
                .getResultList();
    }

    public List<CharacterTimelineEpisodeCount> countByEpisode(
            UUID workId,
            UUID characterId,
            List<CharacterFactType> factTypes
    ) {
        return entityManager.createQuery("""
                        select new org.monitoring.catchholebackend.domain.character.repository.CharacterTimelineEpisodeCount(
                            coalesce(sourceEpisode.id, candidateEpisode.id),
                            coalesce(sourceEpisode.episodeNo, candidateEpisode.episodeNo),
                            count(fact)
                        )
                        from CharacterFact fact
                        join fact.workCharacter character
                        left join fact.sourceEpisode sourceEpisode
                        left join fact.settingCandidate candidate
                        left join candidate.episode candidateEpisode
                        where %s
                          and coalesce(sourceEpisode.id, candidateEpisode.id) is not null
                        group by coalesce(sourceEpisode.id, candidateEpisode.id),
                                 coalesce(sourceEpisode.episodeNo, candidateEpisode.episodeNo)
                        order by coalesce(sourceEpisode.episodeNo, candidateEpisode.episodeNo) asc,
                                 coalesce(sourceEpisode.id, candidateEpisode.id) asc
                        """.formatted(BASE_CONDITION), CharacterTimelineEpisodeCount.class)
                .setParameter("workId", workId)
                .setParameter("characterId", characterId)
                .setParameter("characterStatus", CharacterStatus.ACTIVE)
                .setParameter("factTypes", factTypes)
                .getResultList();
    }

    public List<CharacterFact> findTimelineFacts(
            UUID workId,
            UUID characterId,
            List<CharacterFactType> factTypes,
            Integer fromEpisodeNo,
            int offset,
            int limit
    ) {
        return entityManager.createQuery("""
                        select fact
                        from CharacterFact fact
                        join fetch fact.workCharacter character
                        left join fetch fact.sourceEpisode sourceEpisode
                        left join fetch fact.settingCandidate candidate
                        left join fetch candidate.episode candidateEpisode
                        where %s
                          and (
                              :fromEpisodeNo is null
                              or coalesce(sourceEpisode.id, candidateEpisode.id) is null
                              or coalesce(sourceEpisode.episodeNo, candidateEpisode.episodeNo) >= :fromEpisodeNo
                          )
                        order by
                            case when coalesce(sourceEpisode.id, candidateEpisode.id) is null then 1 else 0 end asc,
                            coalesce(sourceEpisode.episodeNo, candidateEpisode.episodeNo) asc,
                            case when coalesce(
                                json_value(candidate.evidenceSpans, '$[0].startOffset' returning Integer),
                                json_value(candidate.evidenceSpans, '$[0].start_offset' returning Integer)
                            ) is null then 1 else 0 end asc,
                            coalesce(
                                json_value(candidate.evidenceSpans, '$[0].startOffset' returning Integer),
                                json_value(candidate.evidenceSpans, '$[0].start_offset' returning Integer)
                            ) asc,
                            fact.createdAt asc,
                            fact.id asc
                        """.formatted(BASE_CONDITION), CharacterFact.class)
                .setParameter("workId", workId)
                .setParameter("characterId", characterId)
                .setParameter("characterStatus", CharacterStatus.ACTIVE)
                .setParameter("factTypes", factTypes)
                .setParameter("fromEpisodeNo", fromEpisodeNo)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }
}
