package org.monitoring.catchholebackend.domain.character.repository;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CharacterFactRepository extends JpaRepository<CharacterFact, UUID> {

    List<CharacterFact> findAllByWorkCharacterIdOrderByCreatedAtDesc(UUID characterId);

    List<CharacterFact> findAllByWorkCharacterIdAndIsCurrentTrueOrderByFactTypeAscFactKeyAsc(UUID characterId);

    List<CharacterFact> findAllByWorkCharacterIdAndFactTypeAndFactKeyOrderByEffectiveFromEpisodeNoDescCreatedAtDesc(
            UUID characterId,
            CharacterFactType factType,
            String factKey
    );
}
