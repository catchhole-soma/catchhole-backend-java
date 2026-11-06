package org.monitoring.catchholebackend.domain.character.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CharacterSnapshotSourceRepository extends JpaRepository<CharacterSnapshotSource, UUID> {

    @EntityGraph(attributePaths = {
            "sourceFact",
            "sourceFact.settingCandidate",
            "sourceFact.settingCandidate.episode",
            "sourceFact.sourceEpisode"
    })
    List<CharacterSnapshotSource> findAllByWorkCharacterIdOrderByFactTypeAscFactKeyAscSourceOrderAsc(
            UUID characterId
    );

    @EntityGraph(attributePaths = {"workCharacter", "sourceFact", "sourceFact.settingCandidate"})
    @Query("""
            select source
            from CharacterSnapshotSource source
            where source.workCharacter.id in :characterIds
              and source.factType = :factType
            order by source.workCharacter.id, source.factKey, source.sourceOrder
            """)
    List<CharacterSnapshotSource> findAllByCharacterIdsAndFactType(
            @Param("characterIds") Collection<UUID> characterIds,
            @Param("factType") CharacterFactType factType
    );

    @EntityGraph(attributePaths = {
            "sourceFact",
            "sourceFact.settingCandidate",
            "sourceFact.settingCandidate.episode",
            "sourceFact.sourceEpisode"
    })
    List<CharacterSnapshotSource> findAllByWorkCharacterIdAndFactTypeAndFactKeyOrderBySourceOrderAsc(
            UUID characterId,
            CharacterFactType factType,
            String factKey
    );

    List<CharacterSnapshotSource> findAllByWorkCharacterIdAndFactTypeAndFactKeyIn(
            UUID characterId,
            CharacterFactType factType,
            Collection<String> factKeys
    );

    /** #381 slot별 근거 Fact 표시값을 slot 안 sourceOrder 순으로 읽는다. Fact·후보 엔티티는 읽지 않는다. */
    @Query("""
            select new org.monitoring.catchholebackend.domain.character.repository.CharacterSnapshotSourceValue(
                source.workCharacter.id, source.factType, source.factKey, fact.factValue)
            from CharacterSnapshotSource source
            join source.sourceFact fact
            where source.workCharacter.id in :characterIds
            order by source.workCharacter.id, source.factType, source.factKey, source.sourceOrder
            """)
    List<CharacterSnapshotSourceValue> findSourceFactValues(@Param("characterIds") Collection<UUID> characterIds);

    boolean existsBySourceFactId(UUID sourceFactId);

    List<CharacterSnapshotSource> findAllBySourceFactIdIn(Collection<UUID> sourceFactIds);
}
