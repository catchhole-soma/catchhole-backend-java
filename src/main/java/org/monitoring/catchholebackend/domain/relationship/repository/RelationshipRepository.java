package org.monitoring.catchholebackend.domain.relationship.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.entity.CharacterRelationship;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RelationshipRepository extends JpaRepository<CharacterRelationship, UUID> {

    @EntityGraph(attributePaths = {"subjectCharacter", "objectCharacter"})
    Optional<CharacterRelationship> findByWorkIdAndId(UUID workId, UUID id);

    @EntityGraph(attributePaths = {"subjectCharacter", "objectCharacter"})
    Optional<CharacterRelationship> findByWorkIdAndCanonicalKeyAndWithdrawnFalse(UUID workId, String canonicalKey);

    @EntityGraph(attributePaths = {"subjectCharacter", "objectCharacter"})
    List<CharacterRelationship> findAllByWorkId(UUID workId);

    @EntityGraph(attributePaths = {"subjectCharacter", "objectCharacter"})
    Page<CharacterRelationship> findAllByWorkId(UUID workId, Pageable pageable);
}
