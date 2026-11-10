package org.monitoring.catchholebackend.domain.relationship.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.dto.response.FamilyTreeExplorationResponse;
import org.monitoring.catchholebackend.domain.relationship.dto.response.FamilyTreeResponse;

public interface FamilyTreeService {
    FamilyTreeExplorationResponse exploreFamilyTree(Long memberId, UUID workId, boolean includeHistorical, UUID focusCharacterId, String query, int page, int size);
    FamilyTreeResponse getFamilyTree(Long memberId, UUID workId, boolean includeHistorical, UUID focusCharacterId, int limit);
}
