package org.monitoring.catchholebackend.domain.relationship.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.CharacterFactRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipCandidateCreateRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipCorrectionRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipDecisionRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipDraft;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipEvidenceRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipCandidateResponse;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipEvidenceResponse;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipResponse;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipSourceResponse;
import org.monitoring.catchholebackend.domain.relationship.entity.CharacterRelationship;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipCandidate;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipEvidence;
import org.monitoring.catchholebackend.domain.relationship.exception.RelationshipErrorCode;
import org.monitoring.catchholebackend.domain.relationship.mapper.RelationshipMapper;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphReader;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipRules;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipSourceContext;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipSourceResolver;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipCandidateRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipEvidenceRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipRepository;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipAssertion;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipCandidateStatus;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RelationshipServiceImpl implements RelationshipService {

    private final WorkRepository workRepository;
    private final WorkCharacterRepository characterRepository;
    private final CharacterFactRepository factRepository;
    private final RelationshipRepository relationshipRepository;
    private final RelationshipCandidateRepository candidateRepository;
    private final RelationshipEvidenceRepository evidenceRepository;
    private final RelationshipSourceResolver sourceResolver;
    private final RelationshipGraphReader graphReader;
    private final RelationshipRules rules;
    private final RelationshipMapper mapper;

    @Override
    public List<RelationshipSourceResponse> getRelationshipSources(Long memberId, UUID workId, UUID characterId) {
        workRepository.getOwnedWork(workId, memberId);
        getActiveCharacter(workId, characterId);
        return sourceResolver.findCurrentSources(workId, characterId).stream().map(mapper::toResponse).toList();
    }

    @Override
    public PageResponse<RelationshipCandidateResponse> getRelationshipCandidates(Long memberId, UUID workId, int page, int size, RelationshipCandidateStatus status) {
        workRepository.getOwnedWork(workId, memberId);
        var candidates = status == null ? candidateRepository.findAllByWorkId(workId, pageRequest(page, size))
                : candidateRepository.findAllByWorkIdAndStatus(workId, status, pageRequest(page, size));
        var evidence = candidates.isEmpty() ? List.<RelationshipEvidence>of()
                : evidenceRepository.findAllByCandidateIdIn(candidates.stream().map(RelationshipCandidate::getId).toList());
        var sources = resolveSources(workId, evidence);
        var byCandidate = evidence.stream().collect(Collectors.groupingBy(e -> e.getCandidate().getId()));
        return PageResponse.from(candidates, candidates.stream().map(candidate -> {
            var support = byCandidate.getOrDefault(candidate.getId(), List.of());
            return mapper.toResponse(candidate, support, sources, reviewReasons(candidate, support, sources));
        }).toList());
    }

    @Override
    public RelationshipCandidateResponse getRelationshipCandidate(Long memberId, UUID workId, UUID candidateId) {
        workRepository.getOwnedWork(workId, memberId);
        return candidateResponse(getCandidate(workId, candidateId));
    }

    @Override
    @Transactional
    public RelationshipCandidateResponse createRelationshipCandidate(Long memberId, UUID workId, RelationshipCandidateCreateRequest request) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        String hash = rules.requestHash(request, null, null);
        var replay = findReplay(workId, request.requestId(), hash);
        if (replay != null) {
            return candidateResponse(replay);
        }
        return candidateResponse(createCandidate(work, request.requestId(), hash, request, null));
    }

    @Override
    @Transactional
    public RelationshipCandidateResponse updateRelationshipCandidate(Long memberId, UUID workId, UUID candidateId,
            RelationshipCandidateUpdateRequest request) {
        workRepository.getOwnedWorkForUpdate(workId, memberId);
        RelationshipCandidate candidate = getCandidate(workId, candidateId);
        requireVersion(candidate.getVersion(), request.expectedVersion());
        requirePending(candidate);
        rules.validateDraft(request);
        WorkCharacter subject = getActiveCharacter(workId, request.subjectCharacterId());
        WorkCharacter object = request.objectCharacterId() == null ? null : getActiveCharacter(workId, request.objectCharacterId());
        validateSources(workId, request);
        candidate.revise(subject, object, RelationshipRules.clean(request.objectMention()), request.kind(),
                request.subtype(), request.temporalScope(), RelationshipRules.clean(request.timeLabel()), request.assertion());
        evidenceRepository.deleteAllByCandidateId(candidateId);
        evidenceRepository.flush();
        saveEvidence(candidate, request.evidence());
        return candidateResponse(candidate);
    }

    @Override
    @Transactional
    public RelationshipCandidateResponse confirmRelationshipCandidate(Long memberId, UUID workId, UUID candidateId,
            RelationshipDecisionRequest request) {
        workRepository.getOwnedWorkForUpdate(workId, memberId);
        RelationshipCandidate candidate = getCandidate(workId, candidateId);
        if (isDecisionReplay(candidate, RelationshipCandidateStatus.CONFIRMED, request.expectedVersion())) {
            return candidateResponse(candidate);
        }
        requireVersion(candidate.getVersion(), request.expectedVersion());
        requirePending(candidate);
        confirmCandidate(candidate);
        return candidateResponse(candidate);
    }

    @Override
    @Transactional
    public RelationshipCandidateResponse dismissRelationshipCandidate(Long memberId, UUID workId, UUID candidateId,
            RelationshipDecisionRequest request) {
        workRepository.getOwnedWorkForUpdate(workId, memberId);
        RelationshipCandidate candidate = getCandidate(workId, candidateId);
        if (isDecisionReplay(candidate, RelationshipCandidateStatus.DISMISSED, request.expectedVersion())) {
            return candidateResponse(candidate);
        }
        requireVersion(candidate.getVersion(), request.expectedVersion());
        requirePending(candidate);
        candidate.dismiss();
        return candidateResponse(candidate);
    }

    @Override
    public PageResponse<RelationshipResponse> getRelationships(Long memberId, UUID workId, int page, int size) {
        workRepository.getOwnedWork(workId, memberId);
        var relationships = relationshipRepository.findAllByWorkId(workId, pageRequest(page, size));
        var graph = graphReader.readGraph(workId);
        return PageResponse.from(relationships, relationships.stream().map(r -> mapper.toResponse(r, graph)).toList());
    }

    @Override
    public RelationshipResponse getRelationship(Long memberId, UUID workId, UUID relationshipId) {
        workRepository.getOwnedWork(workId, memberId);
        return relationshipResponse(getRelation(workId, relationshipId));
    }

    @Override
    public List<RelationshipEvidenceResponse> getRelationshipEvidence(Long memberId, UUID workId, UUID relationshipId) {
        return getRelationship(memberId, workId, relationshipId).evidence();
    }

    @Override
    @Transactional
    public RelationshipResponse correctRelationship(Long memberId, UUID workId, UUID relationshipId,
            RelationshipCorrectionRequest request) {
        Work work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        String hash = rules.requestHash(request, relationshipId, request.expectedVersion());
        RelationshipCandidate replay = findReplay(workId, request.requestId(), hash);
        if (replay != null) {
            return relationshipResponse(replay.getConfirmedRelationship());
        }
        CharacterRelationship previous = getRelation(workId, relationshipId);
        requireVersion(previous.getVersion(), request.expectedVersion());
        if (previous.isWithdrawn()) {
            throw new AppException(RelationshipErrorCode.RELATIONSHIP_INVALID_CANDIDATE_STATE);
        }
        RelationshipCandidate replacement = createCandidate(work, request.requestId(), hash, request, relationshipId);
        // 기존 근거를 새 명제에 재사용하지 않는다. 실패하면 철회와 새 후보를 함께 롤백한다.
        previous.withdraw();
        relationshipRepository.flush();
        return relationshipResponse(confirmCandidate(replacement));
    }

    @Override
    @Transactional
    public RelationshipResponse withdrawRelationship(Long memberId, UUID workId, UUID relationshipId,
            RelationshipDecisionRequest request) {
        workRepository.getOwnedWorkForUpdate(workId, memberId);
        CharacterRelationship relation = getRelation(workId, relationshipId);
        if (relation.isWithdrawn() && relation.getVersion() - 1 == request.expectedVersion()) {
            return relationshipResponse(relation);
        }
        requireVersion(relation.getVersion(), request.expectedVersion());
        relation.withdraw();
        return relationshipResponse(relation);
    }

    private RelationshipCandidate createCandidate(Work work, UUID requestId, String hash,
            RelationshipDraft request, UUID replacedId) {
        rules.validateDraft(request);
        WorkCharacter subject = getActiveCharacter(work.getId(), request.subjectCharacterId());
        WorkCharacter object = request.objectCharacterId() == null ? null : getActiveCharacter(work.getId(), request.objectCharacterId());
        validateSources(work.getId(), request);
        RelationshipCandidate candidate = candidateRepository.save(mapper.toEntity(work, requestId, hash, request, subject, object, replacedId));
        saveEvidence(candidate, request.evidence());
        return candidate;
    }

    private CharacterRelationship confirmCandidate(RelationshipCandidate candidate) {
        var evidence = evidenceRepository.findAllByCandidateIdIn(List.of(candidate.getId()));
        var sources = resolveSources(candidate.getWork().getId(), evidence);
        var reasons = reviewReasons(candidate, evidence, sources);
        if (!reasons.isEmpty()) {
            throw new AppException(reasons.contains("SOURCE_CHANGED") ? RelationshipErrorCode.RELATIONSHIP_STALE_SOURCE
                    : RelationshipErrorCode.RELATIONSHIP_REVIEW_REQUIRED, Map.of("reviewReasons", reasons));
        }
        var canonical = rules.canonicalize(candidate);
        if (canonical.kind() == RelationshipKind.PARENT_OF && graphReader.wouldCreateCycle(
                graphReader.readGraph(candidate.getWork().getId()), canonical.subjectId(), canonical.objectId())) {
            throw new AppException(RelationshipErrorCode.RELATIONSHIP_ANCESTRY_CYCLE);
        }
        CharacterRelationship relationship = relationshipRepository
                .findByWorkIdAndCanonicalKeyAndWithdrawnFalse(candidate.getWork().getId(), canonical.key())
                .orElseGet(() -> relationshipRepository.save(mapper.toEntity(candidate, canonical)));
        candidate.confirm(relationship);
        relationship.recordSupportChange();
        return relationship;
    }

    private void validateSources(UUID workId, RelationshipDraft request) {
        var sources = sourceResolver.resolve(workId, request.evidence().stream().map(RelationshipEvidenceRequest::sourceFactId).toList());
        for (var evidence : request.evidence()) {
            var source = sources.get(evidence.sourceFactId());
            if (source == null || (!source.characterId().equals(request.subjectCharacterId())
                    && !source.characterId().equals(request.objectCharacterId()))) {
                throw new AppException(RelationshipErrorCode.RELATIONSHIP_INVALID_SOURCE);
            }
            if (!source.contextToken().equals(evidence.sourceToken())) {
                throw new AppException(RelationshipErrorCode.RELATIONSHIP_STALE_SOURCE);
            }
        }
    }

    private void saveEvidence(RelationshipCandidate candidate, List<RelationshipEvidenceRequest> requests) {
        evidenceRepository.saveAll(requests.stream().map(e -> RelationshipEvidence.create(candidate,
                factRepository.getReferenceById(e.sourceFactId()), e.sourceToken())).toList());
    }

    private List<String> reviewReasons(RelationshipCandidate candidate, List<RelationshipEvidence> evidence,
            Map<UUID, RelationshipSourceContext> sources) {
        List<String> reasons = new ArrayList<>();
        if (candidate.getAutomaticReviewReason() != null) {
            reasons.add(candidate.getAutomaticReviewReason());
        }
        if (candidate.getObjectCharacter() == null) {
            reasons.add("UNRESOLVED_TARGET");
        }
        if (candidate.getSubjectCharacter().getStatus() != CharacterStatus.ACTIVE
                || (candidate.getObjectCharacter() != null && candidate.getObjectCharacter().getStatus() != CharacterStatus.ACTIVE)) {
            reasons.add("ARCHIVED_ENDPOINT");
        }
        if (candidate.getAssertion() != RelationshipAssertion.ASSERTED) {
            reasons.add(candidate.getAssertion().name());
        }
        if (candidate.getTemporalScope() == RelationshipTemporalScope.UNKNOWN) {
            reasons.add("UNKNOWN_TEMPORAL_SCOPE");
        }
        if (evidence.isEmpty() || evidence.stream().anyMatch(e -> !RelationshipGraphReader.isCurrent(e, sources))) {
            reasons.add("SOURCE_CHANGED");
        }
        return reasons.stream().distinct().toList();
    }

    private Map<UUID, RelationshipSourceContext> resolveSources(UUID workId, List<RelationshipEvidence> evidence) {
        return sourceResolver.resolve(workId, evidence.stream().map(e -> e.getSourceFact().getId()).toList());
    }

    private RelationshipCandidateResponse candidateResponse(RelationshipCandidate candidate) {
        var evidence = evidenceRepository.findAllByCandidateIdIn(List.of(candidate.getId()));
        var sources = resolveSources(candidate.getWork().getId(), evidence);
        return mapper.toResponse(candidate, evidence, sources, reviewReasons(candidate, evidence, sources));
    }

    private RelationshipResponse relationshipResponse(CharacterRelationship relationship) {
        return mapper.toResponse(relationship, graphReader.readGraph(relationship.getWork().getId()));
    }

    private RelationshipCandidate findReplay(UUID workId, UUID requestId, String hash) {
        var candidate = candidateRepository.findByWorkIdAndRequestId(workId, requestId).orElse(null);
        if (candidate != null && !candidate.getRequestHash().equals(hash)) {
            throw new AppException(RelationshipErrorCode.RELATIONSHIP_IDEMPOTENCY_CONFLICT);
        }
        return candidate;
    }

    private RelationshipCandidate getCandidate(UUID workId, UUID candidateId) {
        return candidateRepository.findByWorkIdAndId(workId, candidateId)
                .orElseThrow(() -> new AppException(RelationshipErrorCode.RELATIONSHIP_CANDIDATE_NOT_FOUND));
    }

    private CharacterRelationship getRelation(UUID workId, UUID relationshipId) {
        return relationshipRepository.findByWorkIdAndId(workId, relationshipId)
                .orElseThrow(() -> new AppException(RelationshipErrorCode.RELATIONSHIP_NOT_FOUND));
    }

    private WorkCharacter getActiveCharacter(UUID workId, UUID id) {
        return characterRepository.findByIdAndWorkIdAndStatus(id, workId, CharacterStatus.ACTIVE)
                .orElseThrow(() -> new AppException(RelationshipErrorCode.RELATIONSHIP_INVALID_RELATIONSHIP));
    }

    private boolean isDecisionReplay(RelationshipCandidate candidate, RelationshipCandidateStatus status, Long expectedVersion) {
        return candidate.getStatus() == status && Objects.equals(candidate.getDecisionFromVersion(), expectedVersion);
    }

    private void requireVersion(long actual, Long expected) {
        if (expected == null || actual != expected) {
            throw new AppException(RelationshipErrorCode.RELATIONSHIP_STALE_VERSION);
        }
    }

    private void requirePending(RelationshipCandidate candidate) {
        if (candidate.getStatus() != RelationshipCandidateStatus.PENDING) {
            throw new AppException(RelationshipErrorCode.RELATIONSHIP_INVALID_CANDIDATE_STATE);
        }
    }

    private PageRequest pageRequest(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
