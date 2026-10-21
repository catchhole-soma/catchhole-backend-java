package org.monitoring.catchholebackend.domain.relationship.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.dto.response.FamilyTreeResponse;

public interface FamilyTreeService {
    FamilyTreeResponse getFamilyTree(Long memberId, UUID workId, boolean includeHistorical, UUID focusCharacterId, int limit);
}
