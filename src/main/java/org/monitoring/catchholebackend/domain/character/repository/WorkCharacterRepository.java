package org.monitoring.catchholebackend.domain.character.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkCharacterRepository extends JpaRepository<WorkCharacter, UUID> {

    Optional<WorkCharacter> findByIdAndWorkId(UUID id, UUID workId);

    Optional<WorkCharacter> findByIdAndWorkIdAndStatus(UUID id, UUID workId, CharacterStatus status);

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select character
            from WorkCharacter character
            where character.id = :id
              and character.work.id = :workId
              and character.status = :status
            """)
    Optional<WorkCharacter> findByIdAndWorkIdAndStatusForUpdate(
            @Param("id") UUID id,
            @Param("workId") UUID workId,
            @Param("status") CharacterStatus status
    );

    Optional<WorkCharacter> findByWorkIdAndName(UUID workId, String name);

    List<WorkCharacter> findAllByWorkIdAndStatusOrderByCreatedAtDesc(
            UUID workId,
            CharacterStatus status
    );

    List<WorkCharacter> findAllByWorkIdOrderByCreatedAtDesc(UUID workId);

    Page<WorkCharacter> findAllByWorkIdAndStatusOrderByCreatedAtDescIdDesc(
            UUID workId,
            CharacterStatus status,
            Pageable pageable
    );

    boolean existsByWorkIdAndNameAndIdNot(UUID workId, String name, UUID id);
}
