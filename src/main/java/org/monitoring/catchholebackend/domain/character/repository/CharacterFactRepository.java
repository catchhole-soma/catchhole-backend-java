package org.monitoring.catchholebackend.domain.character.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CharacterFactRepository extends JpaRepository<CharacterFact, UUID> {

    List<CharacterFact> findAllByWorkCharacterIdOrderByCreatedAtDesc(UUID characterId);

    List<CharacterFact> findAllByWorkCharacterIdAndFactTypeAndFactKeyOrderByEffectiveFromEpisodeNoDescCreatedAtDesc(
            UUID characterId,
            CharacterFactType factType,
            String factKey
    );

    @Query(
            value = """
                    select fact
                    from CharacterFact fact
                    join fetch fact.workCharacter character
                    left join fetch fact.sourceEpisode sourceEpisode
                    left join fetch fact.settingCandidate candidate
                    left join fetch candidate.episode candidateEpisode
                    where character.work.id = :workId
                      and character.status = :characterStatus
                      and fact.factType in :factTypes
                      and (
                          :query = ''
                          or lower(fact.factKey) like lower(concat('%', :query, '%')) escape '\\'
                          or (
                              :factKeyQuery <> ''
                              and replace(replace(lower(fact.factKey), ' ', ''), '_', '')
                                  like lower(concat('%', :factKeyQuery, '%')) escape '\\'
                          )
                          or fact.factKey in :displayNameSchemaKeys
                          or lower(coalesce(fact.factValue, '')) like lower(concat('%', :query, '%')) escape '\\'
                      )
                      and (
                          :allScopes = true
                          or (:currentScope = true and exists (
                              select snapshotSource.id
                              from CharacterSnapshotSource snapshotSource
                              where snapshotSource.sourceFact = fact
                          ))
                          or (:currentScope = false and not exists (
                              select snapshotSource.id
                              from CharacterSnapshotSource snapshotSource
                              where snapshotSource.sourceFact = fact
                          ))
                      )
                    order by case when exists (
                                 select snapshotSource.id
                                 from CharacterSnapshotSource snapshotSource
                                 where snapshotSource.sourceFact = fact
                             ) then 0 else 1 end asc,
                             case when fact.effectiveFromEpisodeNo is null then 1 else 0 end asc,
                             fact.effectiveFromEpisodeNo desc,
                             fact.createdAt desc,
                             fact.id asc
                    """,
            countQuery = """
                    select count(fact)
                    from CharacterFact fact
                    join fact.workCharacter character
                    where character.work.id = :workId
                      and character.status = :characterStatus
                      and fact.factType in :factTypes
                      and (
                          :query = ''
                          or lower(fact.factKey) like lower(concat('%', :query, '%')) escape '\\'
                          or (
                              :factKeyQuery <> ''
                              and replace(replace(lower(fact.factKey), ' ', ''), '_', '')
                                  like lower(concat('%', :factKeyQuery, '%')) escape '\\'
                          )
                          or fact.factKey in :displayNameSchemaKeys
                          or lower(coalesce(fact.factValue, '')) like lower(concat('%', :query, '%')) escape '\\'
                      )
                      and (
                          :allScopes = true
                          or (:currentScope = true and exists (
                              select snapshotSource.id
                              from CharacterSnapshotSource snapshotSource
                              where snapshotSource.sourceFact = fact
                          ))
                          or (:currentScope = false and not exists (
                              select snapshotSource.id
                              from CharacterSnapshotSource snapshotSource
                              where snapshotSource.sourceFact = fact
                          ))
                      )
                    """
    )
    Page<CharacterFact> search(
            @Param("workId") UUID workId,
            @Param("characterStatus") CharacterStatus characterStatus,
            @Param("factTypes") List<CharacterFactType> factTypes,
            @Param("query") String query,
            @Param("factKeyQuery") String factKeyQuery,
            @Param("displayNameSchemaKeys") List<String> displayNameSchemaKeys,
            @Param("allScopes") boolean allScopes,
            @Param("currentScope") boolean currentScope,
            Pageable pageable
    );

    @Query("""
            select fact
            from CharacterFact fact
            join fetch fact.workCharacter character
            left join fetch fact.sourceEpisode sourceEpisode
            left join fetch fact.settingCandidate candidate
            left join fetch candidate.episode candidateEpisode
            where fact.id = :characterFactId
              and character.work.id = :workId
              and character.status = :characterStatus
            """)
    Optional<CharacterFact> findActiveByIdAndWorkId(
            @Param("characterFactId") UUID characterFactId,
            @Param("workId") UUID workId,
            @Param("characterStatus") CharacterStatus characterStatus
    );
}
