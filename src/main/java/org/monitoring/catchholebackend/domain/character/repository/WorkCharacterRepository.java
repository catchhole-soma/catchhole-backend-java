package org.monitoring.catchholebackend.domain.character.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkCharacterRepository extends JpaRepository<WorkCharacter, UUID> {

    Optional<WorkCharacter> findByWorkIdAndName(UUID workId, String name);

    List<WorkCharacter> findAllByWorkIdOrderByCreatedAtDesc(UUID workId);
}
