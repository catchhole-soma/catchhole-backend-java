package org.monitoring.catchholebackend.domain.relationship.repository;

import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RelationshipGraphEventRepository
        extends JpaRepository<RelationshipGraphEvent, UUID> {
    List<RelationshipGraphEvent>
            findAllByWorkIdAndEpisodeNoLessThanEqualOrderByEpisodeNoAscCreatedAtAscIdAsc(
                    UUID workId, int episodeNo);
}
