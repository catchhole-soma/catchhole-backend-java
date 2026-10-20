package org.monitoring.catchholebackend.domain.relationship.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipCandidate;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipCandidateStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RelationshipCandidateRepository extends JpaRepository<RelationshipCandidate, UUID> {

    @EntityGraph(attributePaths = {"subjectCharacter", "objectCharacter", "mentionGroup"})
    List<RelationshipCandidate> findAllByWorkIdAndMentionGroupId(UUID workId, UUID mentionGroupId);

    long countByWorkIdAndStatus(UUID workId, RelationshipCandidateStatus status);

    @EntityGraph(attributePaths = {"subjectCharacter", "objectCharacter", "confirmedRelationship", "mentionGroup"})
    Optional<RelationshipCandidate> findByWorkIdAndId(UUID workId, UUID id);

    @EntityGraph(attributePaths = {"subjectCharacter", "objectCharacter", "confirmedRelationship", "mentionGroup"})
    Optional<RelationshipCandidate> findByWorkIdAndRequestId(UUID workId, UUID requestId);

    @EntityGraph(attributePaths = {"subjectCharacter", "objectCharacter", "confirmedRelationship", "mentionGroup"})
    Page<RelationshipCandidate> findAllByWorkId(UUID workId, Pageable pageable);

    @EntityGraph(attributePaths = {"subjectCharacter", "objectCharacter", "confirmedRelationship", "mentionGroup"})
    Page<RelationshipCandidate> findAllByWorkIdAndStatus(UUID workId, RelationshipCandidateStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"subjectCharacter", "objectCharacter", "confirmedRelationship", "mentionGroup"})
    List<RelationshipCandidate> findAllByWorkId(UUID workId);

    @EntityGraph(attributePaths = {"subjectCharacter", "objectCharacter", "confirmedRelationship", "mentionGroup"})
    List<RelationshipCandidate> findAllByConfirmedRelationshipIdIn(Collection<UUID> relationshipIds);
}
