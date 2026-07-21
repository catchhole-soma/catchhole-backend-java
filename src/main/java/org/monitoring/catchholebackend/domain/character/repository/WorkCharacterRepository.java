package org.monitoring.catchholebackend.domain.character.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkCharacterRepository extends JpaRepository<WorkCharacter, UUID> {

    Optional<WorkCharacter> findByIdAndWorkId(UUID id, UUID workId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select character
            from WorkCharacter character
            where character.id = :id
              and character.work.id = :workId
            """)
    Optional<WorkCharacter> findByIdAndWorkIdForUpdate(
            @Param("id") UUID id,
            @Param("workId") UUID workId
    );

    Optional<WorkCharacter> findByWorkIdAndName(UUID workId, String name);

    List<WorkCharacter> findAllByWorkIdOrderByCreatedAtDesc(UUID workId);
}
