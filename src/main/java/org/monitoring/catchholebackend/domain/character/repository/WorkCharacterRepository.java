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

    Optional<WorkCharacter> findByWorkIdAndNameAndStatus(
            UUID workId,
            String name,
            CharacterStatus status
    );

    boolean existsByWorkIdAndName(UUID workId, String name);

    List<WorkCharacter> findAllByWorkIdAndStatusOrderByCreatedAtDesc(
            UUID workId,
            CharacterStatus status
    );

    List<WorkCharacter> findAllByWorkIdOrderByCreatedAtDesc(UUID workId);

    Page<WorkCharacter> findAllByWorkIdAndStatusOrderByUpdatedAtDescIdDesc(
            UUID workId,
            CharacterStatus status,
            Pageable pageable
    );

    /**
     * 이름 부분 일치 검색이다. 호출자는 {@code LikePatternEscaper}로 escape한 검색어를 넘긴다.
     * 여기의 이름순은 DB collation을 따르므로 화면 정렬 기준이 아니다. 서비스가 일치 결과를 한국어 Collator로
     * 다시 정렬하며, 일치 수가 메모리 정렬 상한을 넘을 때만 이 순서로 DB 페이지를 그대로 사용한다.
     */
    @Query("""
            select character
            from WorkCharacter character
            where character.work.id = :workId
              and character.status = :status
              and lower(character.name) like lower(concat('%', :pattern, '%')) escape '\\'
            order by character.name asc, character.id asc
            """)
    Page<WorkCharacter> searchByWorkIdAndStatusAndName(
            @Param("workId") UUID workId,
            @Param("status") CharacterStatus status,
            @Param("pattern") String escapedPattern,
            Pageable pageable
    );

    boolean existsByWorkIdAndNameAndStatusAndIdNot(
            UUID workId,
            String name,
            CharacterStatus status,
            UUID id
    );
}
