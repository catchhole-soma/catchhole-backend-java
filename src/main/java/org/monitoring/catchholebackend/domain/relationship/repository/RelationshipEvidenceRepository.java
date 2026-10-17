package org.monitoring.catchholebackend.domain.relationship.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipEvidence;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RelationshipEvidenceRepository extends JpaRepository<RelationshipEvidence, UUID> {

    @EntityGraph(attributePaths = {"candidate", "sourceFact", "sourceFact.workCharacter", "sourceFact.settingCandidate"})
    List<RelationshipEvidence> findAllByCandidateIdIn(Collection<UUID> candidateIds);

    void deleteAllByCandidateId(UUID candidateId);
}
