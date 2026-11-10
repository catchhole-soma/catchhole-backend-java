package org.monitoring.catchholebackend.domain.relationship.service;

import java.util.UUID;
import java.util.List;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipExplorerContract.SelectionExploration;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipExplorerContract.Exploration;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipExplorerContract.Person;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Detail;
import org.monitoring.catchholebackend.global.common.response.PageResponse;

public interface RelationshipExplorerService {
    Exploration exploreRelationships(Long member, UUID work, Integer episodeNo, UUID focus, int page);
    PageResponse<Person> searchRelationshipPeople(Long member, UUID work, Integer episodeNo, String query, int page, int size);
    SelectionExploration exploreSelectedRelationships(Long member, UUID work, Integer episodeNo, UUID focus,
            List<UUID> counterparts, int page);
    PageResponse<Person> searchRelationshipCounterparts(Long member, UUID work, Integer episodeNo, UUID focus,
            String query, int page, int size);
    Detail getRelationshipDetail(Long member, UUID work, UUID edge, Integer episodeNo);
}
