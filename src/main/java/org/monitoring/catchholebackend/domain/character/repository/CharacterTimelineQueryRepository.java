package org.monitoring.catchholebackend.domain.character.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.processor.CharacterTimelineFilterSelection;
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
            and fact.factType in :supportedFactTypes
            """;

    private final EntityManager entityManager;

    public List<CharacterTimelineFactTypeCount> countByFactType(
            UUID workId,
            UUID characterId,
            List<CharacterFactType> supportedFactTypes
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
                .setParameter("supportedFactTypes", supportedFactTypes)
                .getResultList();
    }

    public List<CharacterTimelineFactKeyCount> countByFactKey(
            UUID workId,
            UUID characterId,
            List<CharacterFactType> supportedFactTypes
    ) {
        return entityManager.createQuery("""
                        select new org.monitoring.catchholebackend.domain.character.repository.CharacterTimelineFactKeyCount(
                            fact.factType,
                            fact.factKey,
                            count(fact)
                        )
                        from CharacterFact fact
                        join fact.workCharacter character
                        where %s
                        group by fact.factType, fact.factKey
                        order by fact.factType asc, fact.factKey asc
                        """.formatted(BASE_CONDITION), CharacterTimelineFactKeyCount.class)
                .setParameter("workId", workId)
                .setParameter("characterId", characterId)
                .setParameter("characterStatus", CharacterStatus.ACTIVE)
                .setParameter("supportedFactTypes", supportedFactTypes)
                .getResultList();
    }

    /**
     * factKey별 현재 Fact를 우선하고, 현재 Fact가 없으면 가장 최근 이력을 앞에 둔다.
     * Mapper는 첫 항목의 valueJson을 사용해 수동·레거시 설정의 저장된 표시명을 복원한다.
     */
    public List<CharacterTimelineFactDisplaySource> findFactDisplaySources(
            UUID workId,
            UUID characterId,
            List<CharacterFactType> supportedFactTypes
    ) {
        return entityManager.createQuery("""
                        select new org.monitoring.catchholebackend.domain.character.repository.CharacterTimelineFactDisplaySource(
                            fact.factType,
                            fact.factKey,
                            fact.valueJson
                        )
                        from CharacterFact fact
                        join fact.workCharacter character
                        where %s
                        order by fact.factType asc,
                                 fact.factKey asc,
                                 fact.isCurrent desc,
                                 case when fact.effectiveFromEpisodeNo is null then 1 else 0 end asc,
                                 fact.effectiveFromEpisodeNo desc,
                                 fact.createdAt desc,
                                 fact.id asc
                        """.formatted(BASE_CONDITION), CharacterTimelineFactDisplaySource.class)
                .setParameter("workId", workId)
                .setParameter("characterId", characterId)
                .setParameter("characterStatus", CharacterStatus.ACTIVE)
                .setParameter("supportedFactTypes", supportedFactTypes)
                .getResultList();
    }

    public long countFacts(
            UUID workId,
            UUID characterId,
            List<CharacterFactType> supportedFactTypes,
            CharacterTimelineFilterSelection selection
    ) {
        TypedQuery<Long> query = entityManager.createQuery("""
                        select count(fact)
                        from CharacterFact fact
                        join fact.workCharacter character
                        where %s
                        %s
                        """.formatted(BASE_CONDITION, selectionCondition(selection)), Long.class);
        bindCommonParameters(query, workId, characterId, supportedFactTypes);
        bindSelectionParameters(query, selection);
        return query.getSingleResult();
    }

    public List<CharacterTimelineEpisodeCount> countByEpisode(
            UUID workId,
            UUID characterId,
            List<CharacterFactType> supportedFactTypes,
            CharacterTimelineFilterSelection selection
    ) {
        TypedQuery<CharacterTimelineEpisodeCount> query = entityManager.createQuery("""
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
                          %s
                          and coalesce(sourceEpisode.id, candidateEpisode.id) is not null
                        group by coalesce(sourceEpisode.id, candidateEpisode.id),
                                 coalesce(sourceEpisode.episodeNo, candidateEpisode.episodeNo)
                        order by coalesce(sourceEpisode.episodeNo, candidateEpisode.episodeNo) asc,
                                 coalesce(sourceEpisode.id, candidateEpisode.id) asc
                        """.formatted(BASE_CONDITION, selectionCondition(selection)), CharacterTimelineEpisodeCount.class);
        bindCommonParameters(query, workId, characterId, supportedFactTypes);
        bindSelectionParameters(query, selection);
        return query.getResultList();
    }

    public List<CharacterFact> findTimelineFacts(
            UUID workId,
            UUID characterId,
            List<CharacterFactType> supportedFactTypes,
            CharacterTimelineFilterSelection selection,
            Integer fromEpisodeNo,
            int offset,
            int limit
    ) {
        TypedQuery<CharacterFact> query = entityManager.createQuery("""
                        select fact
                        from CharacterFact fact
                        join fetch fact.workCharacter character
                        left join fetch fact.sourceEpisode sourceEpisode
                        left join fetch fact.settingCandidate candidate
                        left join fetch candidate.episode candidateEpisode
                        where %s
                          %s
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
                        """.formatted(BASE_CONDITION, selectionCondition(selection)), CharacterFact.class);
        bindCommonParameters(query, workId, characterId, supportedFactTypes);
        bindSelectionParameters(query, selection);
        return query.setParameter("fromEpisodeNo", fromEpisodeNo)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }

    private String selectionCondition(CharacterTimelineFilterSelection selection) {
        if (selection.all()) {
            return "";
        }
        if (!selection.factTypes().isEmpty() && !selection.factKeys().isEmpty()) {
            return "and (fact.factType in :selectedFactTypes or fact.factKey in :selectedFactKeys)";
        }
        if (!selection.factTypes().isEmpty()) {
            return "and fact.factType in :selectedFactTypes";
        }
        return "and fact.factKey in :selectedFactKeys";
    }

    private void bindCommonParameters(
            TypedQuery<?> query,
            UUID workId,
            UUID characterId,
            List<CharacterFactType> supportedFactTypes
    ) {
        query.setParameter("workId", workId)
                .setParameter("characterId", characterId)
                .setParameter("characterStatus", CharacterStatus.ACTIVE)
                .setParameter("supportedFactTypes", supportedFactTypes);
    }

    private void bindSelectionParameters(
            TypedQuery<?> query,
            CharacterTimelineFilterSelection selection
    ) {
        if (selection.all()) {
            return;
        }
        if (!selection.factTypes().isEmpty()) {
            query.setParameter("selectedFactTypes", selection.factTypes());
        }
        if (!selection.factKeys().isEmpty()) {
            query.setParameter("selectedFactKeys", selection.factKeys());
        }
    }
}
