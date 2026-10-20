package org.monitoring.catchholebackend.domain.relationship.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipDecisionRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipMentionGroupCreateRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipMentionGroupResponse;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipCandidate;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipMentionGroup;
import org.monitoring.catchholebackend.domain.relationship.exception.RelationshipErrorCode;
import org.monitoring.catchholebackend.domain.relationship.mapper.RelationshipMentionGroupMapper;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphReader;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipMentionGroupValidator;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipMentionReader;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipRules;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipCandidateRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipMentionGroupRepository;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RelationshipMentionGroupServiceImpl implements RelationshipMentionGroupService {
    private final WorkRepository workRepository;
    private final RelationshipMentionGroupRepository groupRepository;
    private final RelationshipCandidateRepository candidateRepository;
    private final RelationshipMentionReader mentionReader;
    private final RelationshipGraphReader graphReader;
    private final RelationshipMentionGroupValidator validator;
    private final RelationshipMentionGroupMapper mapper;
    private final RelationshipRules rules;

    @Override
    @Transactional
    public RelationshipMentionGroupResponse createMentionGroup(Long memberId, UUID workId,
            RelationshipMentionGroupCreateRequest request) {
        var work = workRepository.getOwnedWorkForUpdate(workId, memberId);
        String hash = rules.mentionGroupRequestHash(request);
        var replay = groupRepository.findByWorkIdAndRequestId(workId, request.requestId()).orElse(null);
        if (replay != null) {
            if (!replay.getRequestHash().equals(hash)) throw new AppException(RelationshipErrorCode.RELATIONSHIP_IDEMPOTENCY_CONFLICT);
            return response(replay, mentionReader.readMentions(workId));
        }
        if (new HashSet<>(request.candidates().stream().map(item -> item.candidateId()).toList()).size() != request.candidates().size()) {
            throw new AppException(RelationshipErrorCode.RELATIONSHIP_INVALID_RELATIONSHIP);
        }
        var graph = graphReader.readGraph(workId);
        var snapshot = mentionReader.readMentions(workId, graph);
        var displayable = snapshot.candidates().stream().collect(Collectors.toMap(RelationshipCandidate::getId, item -> item));
        List<RelationshipCandidate> selected = new ArrayList<>();
        for (var item : request.candidates()) {
            var candidate = candidateRepository.findByWorkIdAndId(workId, item.candidateId())
                    .orElseThrow(() -> new AppException(RelationshipErrorCode.RELATIONSHIP_CANDIDATE_NOT_FOUND));
            requireVersion(candidate.getVersion(), item.expectedVersion());
            if (candidate.getMentionGroup() != null) failReview("MENTION_ALREADY_GROUPED");
            if (!displayable.containsKey(candidate.getId())) failReview("MENTION_NOT_DISPLAYABLE");
            selected.add(candidate);
        }
        var group = groupRepository.save(RelationshipMentionGroup.create(work, request.requestId(), hash, request.name().strip()));
        selected.forEach(candidate -> candidate.assignMentionGroup(group));
        var reasons = validator.reviewReasons(graph, snapshot.candidates()).getOrDefault(group.getId(), List.of());
        if (!reasons.isEmpty()) throw new AppException(RelationshipErrorCode.RELATIONSHIP_REVIEW_REQUIRED, Map.of("reviewReasons", reasons));
        return mapper.toResponse(group, selected, List.of());
    }

    @Override
    public List<RelationshipMentionGroupResponse> getMentionGroups(Long memberId, UUID workId) {
        workRepository.getOwnedWork(workId, memberId);
        var snapshot = mentionReader.readMentions(workId);
        return groupRepository.findAllByWorkIdAndActiveTrueOrderByCreatedAtAscIdAsc(workId).stream()
                .map(group -> response(group, snapshot)).toList();
    }

    @Override
    public RelationshipMentionGroupResponse getMentionGroup(Long memberId, UUID workId, UUID groupId) {
        workRepository.getOwnedWork(workId, memberId);
        return response(group(workId, groupId), mentionReader.readMentions(workId));
    }

    @Override
    @Transactional
    public RelationshipMentionGroupResponse ungroupMentions(Long memberId, UUID workId, UUID groupId,
            RelationshipDecisionRequest request) {
        workRepository.getOwnedWorkForUpdate(workId, memberId);
        var group = group(workId, groupId);
        if (!group.isActive() && Objects.equals(group.getDisbandedFromVersion(), request.expectedVersion())) {
            return mapper.toResponse(group, List.of(), List.of());
        }
        requireVersion(group.getVersion(), request.expectedVersion());
        if (!group.isActive()) throw new AppException(RelationshipErrorCode.RELATIONSHIP_INVALID_CANDIDATE_STATE);
        var members = candidateRepository.findAllByWorkIdAndMentionGroupId(workId, groupId);
        group.disband();
        members.forEach(RelationshipCandidate::clearDisbandedMentionGroup);
        return mapper.toResponse(group, List.of(), List.of());
    }

    private RelationshipMentionGroupResponse response(RelationshipMentionGroup group, RelationshipMentionReader.Snapshot snapshot) {
        var members = candidateRepository.findAllByWorkIdAndMentionGroupId(group.getWork().getId(), group.getId());
        var reasons = new ArrayList<>(snapshot.groupReviewReasons().getOrDefault(group.getId(), List.of()));
        if (members.stream().anyMatch(member -> !snapshot.contains(member.getId())) && reasons.isEmpty()) {
            reasons.add("MENTION_GROUP_SOURCE_CHANGED");
        }
        return mapper.toResponse(group, members, reasons);
    }

    private RelationshipMentionGroup group(UUID workId, UUID groupId) {
        return groupRepository.findByWorkIdAndId(workId, groupId)
                .orElseThrow(() -> new AppException(RelationshipErrorCode.RELATIONSHIP_MENTION_GROUP_NOT_FOUND));
    }

    private void requireVersion(long actual, Long expected) {
        if (expected == null || expected != actual) throw new AppException(RelationshipErrorCode.RELATIONSHIP_STALE_VERSION);
    }

    private void failReview(String reason) {
        throw new AppException(RelationshipErrorCode.RELATIONSHIP_REVIEW_REQUIRED, Map.of("reviewReasons", List.of(reason)));
    }
}
