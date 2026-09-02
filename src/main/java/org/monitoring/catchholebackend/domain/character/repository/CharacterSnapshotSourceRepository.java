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

    @EntityGraph(attributePaths = {"workCharacter", "sourceFact"})
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

    boolean existsBySourceFactId(UUID sourceFactId);

    List<CharacterSnapshotSource> findAllBySourceFactIdIn(Collection<UUID> sourceFactIds);
}
