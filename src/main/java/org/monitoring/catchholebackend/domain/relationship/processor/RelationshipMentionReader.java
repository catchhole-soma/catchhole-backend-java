package org.monitoring.catchholebackend.domain.relationship.processor;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipCandidate;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipEvidence;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipCandidateRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipEvidenceRepository;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipAssertion;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipCandidateOrigin;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipCandidateStatus;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;
import org.springframework.stereotype.Component;

/** 후보 상태를 바꾸지 않고 현재 설정으로 뒷받침되는 미등록 인물만 읽는다. 이름으로 인물을 합치지 않는다. */
@Component
@RequiredArgsConstructor
public class RelationshipMentionReader {
    private final RelationshipCandidateRepository candidateRepository;
    private final RelationshipEvidenceRepository evidenceRepository;
    private final WorkCharacterRepository characterRepository;
    private final RelationshipSourceResolver sourceResolver;
    private final ExplicitFamilyRelationshipParser parser;
    private final RelationshipGraphReader graphReader;
    private final RelationshipMentionGroupValidator groupValidator;

    public Snapshot readMentions(UUID workId) {
        return readMentions(workId, null);
    }

    public Snapshot readMentions(UUID workId, RelationshipGraphReader.Graph graph) {
        var pending = candidateRepository.findAllByWorkId(workId).stream()
                .filter(candidate -> candidate.getStatus() == RelationshipCandidateStatus.PENDING).toList();
        var unresolved = pending.stream().filter(candidate -> candidate.getObjectCharacter() == null).toList();
        if (unresolved.isEmpty()) return new Snapshot(List.of(), pending.size());
        var evidence = evidenceRepository.findAllByCandidateIdIn(unresolved.stream().map(RelationshipCandidate::getId).toList());
        var byCandidate = evidence.stream().collect(Collectors.groupingBy(item -> item.getCandidate().getId()));
        var currentSources = sourceResolver.findCurrentSources(workId);
        var sources = currentSources.stream().collect(Collectors.toMap(RelationshipSourceContext::factId,
                Function.identity(), (first, ignored) -> first));
        var archivedNames = characterRepository.findAllByWorkIdAndStatusOrderByCreatedAtDesc(workId, CharacterStatus.ARCHIVED)
                .stream().map(character -> character.getName().strip()).collect(Collectors.toSet());
        var activeNames = characterRepository.findAllByWorkIdAndStatusOrderByCreatedAtDesc(workId, CharacterStatus.ACTIVE)
                .stream().collect(Collectors.toMap(character -> character.getId(), character -> character.getName()));
        // 한 슬롯에 여러 provenance Fact가 있어도 문장은 한 번만 검사한다.
        var claims = currentSources.stream().collect(Collectors.toMap(source ->
                source.characterId() + ":" + source.factKey() + ":" + source.contextToken(),
                Function.identity(), (first, ignored) -> first)).values().stream()
                .flatMap(source -> parser.parse(source.factKey(), source.currentValue(), activeNames.get(source.characterId())).relationships().stream()
                        .map(claim -> new Observation(source.characterId(), claim))).toList();
        var displayable = unresolved.stream().filter(candidate -> eligible(candidate,
                byCandidate.getOrDefault(candidate.getId(), List.of()), sources, archivedNames, claims)).toList();
        var grouped = displayable.stream().anyMatch(candidate -> candidate.getMentionGroup() != null);
        Map<UUID, List<String>> groupReasons = grouped ? groupValidator.reviewReasons(
                graph == null ? graphReader.readGraph(workId) : graph, displayable) : Map.of();
        return new Snapshot(displayable.stream().filter(candidate -> candidate.getMentionGroup() == null
                || !groupReasons.containsKey(candidate.getMentionGroup().getId())).toList(),
                pending.size(), byCandidate, sources, groupReasons);
    }

    private boolean eligible(RelationshipCandidate candidate, List<RelationshipEvidence> evidence,
            Map<UUID, RelationshipSourceContext> sources, Set<String> archivedNames, List<Observation> claims) {
        if (candidate.getOrigin() != RelationshipCandidateOrigin.CURRENT_SETTING_RULE
                || candidate.getSubjectCharacter().getStatus() != CharacterStatus.ACTIVE
                || candidate.getAssertion() != RelationshipAssertion.ASSERTED
                || candidate.getTemporalScope() == RelationshipTemporalScope.UNKNOWN
                || !parser.isExplicitNamedMention(candidate.getObjectMention())
                || candidate.getAutomaticReviewReason() != null
                    && !candidate.getAutomaticReviewReason().equals("UNRESOLVED_TARGET")
                || evidence.isEmpty() || evidence.size() > 20
                || evidence.stream().anyMatch(item -> !RelationshipGraphReader.isCurrent(item, sources))) return false;
        String mention = candidate.getObjectMention().strip();
        if (mention.equals(candidate.getSubjectCharacter().getName().strip()) || archivedNames.contains(mention)) return false;
        return claims.stream().noneMatch(observation -> conflicts(candidate, observation));
    }

    private boolean conflicts(RelationshipCandidate candidate, Observation observation) {
        var claim = observation.claim();
        if (!observation.characterId().equals(candidate.getSubjectCharacter().getId())
                || !claim.objectMention().equals(candidate.getObjectMention().strip())
                || claim.temporalScope() != candidate.getTemporalScope()
                || !Objects.equals(claim.timeLabel(), candidate.getTimeLabel())) return false;
        boolean compatibleSubtype = claim.subtype() == candidate.getSubtype()
                || claim.subtype() == RelationshipSubtype.UNSPECIFIED || candidate.getSubtype() == RelationshipSubtype.UNSPECIFIED;
        boolean compatibleGap = claim.generationGap() == null || candidate.getGenerationGap() == null
                || claim.generationGap().equals(candidate.getGenerationGap());
        if (claim.kind() == candidate.getKind() && compatibleSubtype && compatibleGap
                && (claim.assertion() == RelationshipAssertion.NEGATED || claim.assertion() == RelationshipAssertion.CONFLICTING)) return true;
        if (claim.assertion() != RelationshipAssertion.ASSERTED) return false;
        if (claim.kind() == candidate.getKind() && claim.kind().isAncestry() && compatibleSubtype && !compatibleGap) return true;
        return claim.kind().isCanonicalLineage() && candidate.getKind().isInverseLineage()
                || claim.kind().isInverseLineage() && candidate.getKind().isCanonicalLineage();
    }

    private record Observation(UUID characterId, ParsedFamilyRelationship claim) {}

    public record Snapshot(List<RelationshipCandidate> candidates, long pendingCandidateCount,
                           Map<UUID, List<RelationshipEvidence>> evidence,
                           Map<UUID, RelationshipSourceContext> sources,
                           Map<UUID, List<String>> groupReviewReasons) {
        public Snapshot(List<RelationshipCandidate> candidates, long pendingCandidateCount,
                Map<UUID, List<RelationshipEvidence>> evidence, Map<UUID, RelationshipSourceContext> sources) {
            this(candidates, pendingCandidateCount, evidence, sources, Map.of());
        }

        public Snapshot(List<RelationshipCandidate> candidates, long pendingCandidateCount) {
            this(candidates, pendingCandidateCount, Map.of(), Map.of());
        }

        public boolean contains(UUID candidateId) {
            return candidates.stream().anyMatch(candidate -> candidate.getId().equals(candidateId));
        }
    }
}
