package org.monitoring.catchholebackend.domain.character.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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

    boolean existsBySourceFactId(UUID sourceFactId);

    List<CharacterSnapshotSource> findAllBySourceFactIdIn(Collection<UUID> sourceFactIds);
}
