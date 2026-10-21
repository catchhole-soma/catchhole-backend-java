package org.monitoring.catchholebackend.domain.relationship.service;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.relationship.dto.response.FamilyTreeResponse;
import org.monitoring.catchholebackend.domain.relationship.mapper.FamilyTreeMapper;
import org.monitoring.catchholebackend.domain.relationship.processor.FamilyTreeProjector;
import org.monitoring.catchholebackend.domain.relationship.processor.FamilyTreeRoleResolver;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphReader;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipMentionReader;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.worldimage.service.CharacterImageService;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class FamilyTreeServiceImpl implements FamilyTreeService {
    private final WorkRepository workRepository;
    private final WorkCharacterRepository characterRepository;
    private final RelationshipMentionReader mentionReader;
    private final RelationshipGraphReader graphReader;
    private final FamilyTreeProjector projector;
    private final FamilyTreeRoleResolver roleResolver;
    private final CharacterImageService characterImageService;
    private final FamilyTreeMapper mapper;

    @Override
    public FamilyTreeResponse getFamilyTree(Long memberId, UUID workId, boolean includeHistorical, UUID focusCharacterId, int limit) {
        var work = workRepository.getOwnedWork(workId, memberId);
        var focus = focusCharacterId == null ? null : characterRepository
                .findByIdAndWorkIdAndStatus(focusCharacterId, workId, CharacterStatus.ACTIVE)
                .orElseThrow(() -> new AppException(CharacterErrorCode.CHARACTER_NOT_FOUND));
        var graph = graphReader.readGraph(workId);
        var mentions = mentionReader.readMentions(workId, graph);
        var roles = roleResolver.resolveRoles(workId, graph, mentions);
        var projection = projector.projectFamilyTree(work, graph, focus, includeHistorical, limit, mentions, roles);
        var visibleIds = projection.nodes().stream().map(FamilyTreeProjector.TreeNode::characterId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        var characters = new LinkedHashMap<UUID, WorkCharacter>();
        graph.relationships().forEach(relation -> {
            characters.put(relation.getSubjectCharacter().getId(), relation.getSubjectCharacter());
            characters.put(relation.getObjectCharacter().getId(), relation.getObjectCharacter());
        });
        mentions.candidates().forEach(candidate -> characters.put(candidate.getSubjectCharacter().getId(), candidate.getSubjectCharacter()));
        if (focus != null) characters.put(focus.getId(), focus);
        var images = characterImageService.getCharacterImages(characters.values().stream()
                .filter(character -> visibleIds.contains(character.getId())).toList());
        return mapper.toResponse(projection, images);
    }
}
