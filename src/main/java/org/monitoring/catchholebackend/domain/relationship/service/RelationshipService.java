package org.monitoring.catchholebackend.domain.relationship.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipCandidateCreateRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipCorrectionRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipDecisionRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipCandidateResponse;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipEvidenceResponse;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipResponse;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipSourceResponse;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipCandidateStatus;

public interface RelationshipService {
    List<RelationshipSourceResponse> getRelationshipSources(Long memberId, UUID workId, UUID characterId);
    PageResponse<RelationshipCandidateResponse> getRelationshipCandidates(Long memberId, UUID workId, int page, int size, RelationshipCandidateStatus status);
    RelationshipCandidateResponse getRelationshipCandidate(Long memberId, UUID workId, UUID candidateId);
    RelationshipCandidateResponse createRelationshipCandidate(Long memberId, UUID workId, RelationshipCandidateCreateRequest request);
    RelationshipCandidateResponse updateRelationshipCandidate(Long memberId, UUID workId, UUID candidateId, RelationshipCandidateUpdateRequest request);
    RelationshipCandidateResponse confirmRelationshipCandidate(Long memberId, UUID workId, UUID candidateId, RelationshipDecisionRequest request);
    RelationshipCandidateResponse dismissRelationshipCandidate(Long memberId, UUID workId, UUID candidateId, RelationshipDecisionRequest request);
    PageResponse<RelationshipResponse> getRelationships(Long memberId, UUID workId, int page, int size);
    RelationshipResponse getRelationship(Long memberId, UUID workId, UUID relationshipId);
    List<RelationshipEvidenceResponse> getRelationshipEvidence(Long memberId, UUID workId, UUID relationshipId);
    RelationshipResponse correctRelationship(Long memberId, UUID workId, UUID relationshipId, RelationshipCorrectionRequest request);
    RelationshipResponse withdrawRelationship(Long memberId, UUID workId, UUID relationshipId, RelationshipDecisionRequest request);
}
