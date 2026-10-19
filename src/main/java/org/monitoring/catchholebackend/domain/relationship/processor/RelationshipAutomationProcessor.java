package org.monitoring.catchholebackend.domain.relationship.processor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.CharacterFactRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipCandidateCreateRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipEvidenceRequest;
import org.monitoring.catchholebackend.domain.relationship.entity.CharacterRelationship;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipCandidate;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipEvidence;
import org.monitoring.catchholebackend.domain.relationship.mapper.RelationshipMapper;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipCandidateRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipEvidenceRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipRepository;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipAssertion;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipCandidateStatus;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.springframework.stereotype.Component;

/** 호출자는 작품 쓰기 잠금과 별도 처리 트랜잭션을 소유한다. 현재 설정 이외의 문장은 읽지 않는다. */
@Component
@RequiredArgsConstructor
public class RelationshipAutomationProcessor {
    private final RelationshipSourceResolver sourceResolver;
    private final ExplicitFamilyRelationshipParser parser;
    private final WorkCharacterRepository characterRepository;
    private final CharacterFactRepository factRepository;
    private final RelationshipCandidateRepository candidateRepository;
    private final RelationshipEvidenceRepository evidenceRepository;
    private final RelationshipRepository relationshipRepository;
    private final RelationshipGraphReader graphReader;
    private final RelationshipRules rules;
    private final RelationshipMapper mapper;

    public Result process(Work work) {
        var characters = characterRepository.findAllByWorkIdAndStatusOrderByCreatedAtDesc(work.getId(), CharacterStatus.ACTIVE);
        var byId = characters.stream().collect(Collectors.toMap(WorkCharacter::getId, c -> c));
        var byName = characters.stream().collect(Collectors.groupingBy(c -> c.getName().strip()));
        var existing = candidateRepository.findAllByWorkId(work.getId()).stream()
                .collect(Collectors.toMap(RelationshipCandidate::getRequestId, c -> c));
        var slots = sourceResolver.findCurrentSources(work.getId()).stream().collect(Collectors.groupingBy(
                s -> s.characterId() + ":" + s.factKey() + ":" + s.contextToken(), TreeMap::new, Collectors.toList()));
        var observations = new ArrayList<Observation>();
        int unsupported = 0;
        for (var sources : slots.values()) {
            var source = sources.getFirst();
            var parsed = parser.parse(source.factKey(), source.currentValue());
            if (parsed.familyMentioned() && (parsed.relationships().isEmpty() || sources.size() > 20)) {
                unsupported++;
            }
            if (sources.size() > 20) continue;
            var evidence = sources.stream().sorted(Comparator.comparing(s -> s.factId().toString()))
                    .map(s -> new RelationshipEvidenceRequest(s.factId(), s.contextToken())).toList();
            for (var claim : parsed.relationships()) {
                var targets = byName.getOrDefault(claim.objectMention(), List.of());
                var object = targets.size() == 1 ? targets.getFirst() : null;
                String reason = targets.size() > 1 ? "AMBIGUOUS_TARGET" : object == null ? "UNRESOLVED_TARGET" : null;
                if (object != null && object.getId().equals(source.characterId())) {
                    object = null;
                    reason = "SELF_REFERENCE";
                }
                if (reason == null && claim.assertion() != RelationshipAssertion.ASSERTED) reason = claim.assertion().name();
                if (reason == null && claim.temporalScope() == RelationshipTemporalScope.UNKNOWN) reason = "UNKNOWN_TEMPORAL_SCOPE";
                if (reason == null && !claim.reviewReasons().isEmpty()) reason = claim.reviewReasons().getFirst();
                // 상대 등록/동명이인 수로 키를 바꾸지 않아 수정·제외 결정을 재처리로 되살리지 않는다.
                var identity = draft(null, source.characterId(), null, claim, evidence);
                UUID requestId = UUID.nameUUIDFromBytes(("family-rule-v1:" + work.getId() + ":"
                        + rules.requestHash(identity, null, null)).getBytes(StandardCharsets.UTF_8));
                var request = draft(requestId, source.characterId(), object == null ? null : object.getId(), claim, evidence);
                var candidate = existing.get(requestId);
                boolean fresh = candidate == null;
                if (fresh) {
                    rules.validateDraft(request);
                    candidate = mapper.toEntity(work, requestId, rules.requestHash(request, null, null), request,
                            byId.get(source.characterId()), object, null);
                    candidate.markRuleGenerated();
                }
                observations.add(new Observation(candidate, request, fresh, reason));
            }
        }

        var conflictGroups = observations.stream().filter(o -> o.request().objectCharacterId() != null)
                .collect(Collectors.groupingBy(o -> new ConflictScope(canonicalPair(o.request()),
                        o.request().temporalScope(), o.request().timeLabel())));
        var assessed = observations.stream().map(observation -> {
            String reason = observation.reason();
            if (observation.request().objectCharacterId() != null) {
                var scope = new ConflictScope(canonicalPair(observation.request()),
                        observation.request().temporalScope(), observation.request().timeLabel());
                if (conflictGroups.get(scope).stream().anyMatch(other -> conflicts(observation, other))) reason = "CONFLICTING";
            }
            if (observation.candidate().isAutomaticallyConfirmed()
                    && !Objects.equals(observation.candidate().getObjectCharacter().getId(), observation.request().objectCharacterId())) {
                reason = "TARGET_CHANGED";
            }
            return new Observation(observation.candidate(), observation.request(), observation.fresh(), reason);
        }).toList();
        var graph = graphReader.readGraph(work.getId());
        var withdrawnKeys = graph.relationships().stream().filter(CharacterRelationship::isWithdrawn)
                .map(CharacterRelationship::getCanonicalKey).collect(Collectors.toSet());
        var parents = new HashMap<UUID, Set<UUID>>();
        for (var relation : graph.relationships()) {
            if (!relation.isWithdrawn() && relation.getKind() == RelationshipKind.PARENT_OF
                    && relation.getSubjectCharacter().getStatus() == CharacterStatus.ACTIVE
                    && relation.getObjectCharacter().getStatus() == CharacterStatus.ACTIVE
                    && graph.evidence().getOrDefault(relation.getId(), List.of()).stream()
                            .anyMatch(e -> !e.getCandidate().isAutomaticallyConfirmed()
                                    && RelationshipGraphReader.isSupporting(e, graph.sources()))) {
                parents.computeIfAbsent(relation.getSubjectCharacter().getId(), ignored -> new HashSet<>())
                        .add(relation.getObjectCharacter().getId());
            }
        }
        // 자동 근거는 현재 대상·상충 검사를 통과한 명제로 다시 구성해야 가짜 순환을 만들지 않는다.
        for (var observation : assessed) {
            if (observation.reason() == null && observation.request().kind() != RelationshipKind.SPOUSE
                    && observation.request().kind() != RelationshipKind.SIBLING && participates(observation)
                    && !withdrawnKeys.contains(rules.canonicalize(observation.candidate()).key())) {
                var pair = canonicalPair(observation.request());
                parents.computeIfAbsent(pair.subject(), ignored -> new HashSet<>()).add(pair.object());
            }
        }

        var relationsByKey = graph.relationships().stream().filter(r -> !r.isWithdrawn())
                .collect(Collectors.toMap(CharacterRelationship::getCanonicalKey, r -> r));
        int automatic = 0;
        Set<UUID> pending = new HashSet<>();
        Set<UUID> visited = new HashSet<>();
        for (var observation : assessed) {
            var candidate = observation.candidate();
            if (!visited.add(candidate.getRequestId())) continue;
            String reason = observation.reason();
            if (reason == null && observation.request().objectCharacterId() != null) {
                var pair = canonicalPair(observation.request());
                if (pair.kind() == RelationshipKind.PARENT_OF && reaches(parents, pair.object(), pair.subject())) {
                    reason = "ANCESTRY_CYCLE";
                }
            }
            if (!observation.fresh()) {
                // 완료한 사용자 결정을 덮어쓰지 않는다. 자동 확정의 지원만 현재 상태로 다시 검증한다.
                if (candidate.isAutomaticallyConfirmed()) {
                    candidate.holdAutomatically(reason);
                    candidate.validateAutomaticSupport(work.getRelationshipInputVersion());
                }
                if (candidate.getStatus() == RelationshipCandidateStatus.PENDING) pending.add(candidate.getId());
                continue;
            }
            candidateRepository.save(candidate);
            evidenceRepository.saveAll(observation.request().evidence().stream().map(e -> RelationshipEvidence.create(
                    candidate, factRepository.getReferenceById(e.sourceFactId()), e.sourceToken())).toList());
            RelationshipRules.CanonicalRelationship canonical = candidate.getObjectCharacter() == null ? null : rules.canonicalize(candidate);
            if (canonical != null && withdrawnKeys.contains(canonical.key())) reason = "PREVIOUSLY_WITHDRAWN";
            if (reason == null) {
                var relationship = relationsByKey.computeIfAbsent(canonical.key(), ignored ->
                        relationshipRepository.save(mapper.toEntity(candidate, canonical)));
                candidate.confirmAutomatically(relationship);
                candidate.validateAutomaticSupport(work.getRelationshipInputVersion());
                relationship.recordSupportChange();
                automatic++;
            } else {
                candidate.holdAutomatically(reason);
                pending.add(candidate.getId());
            }
        }
        return new Result(automatic, pending.size(), unsupported);
    }

    private boolean participates(Observation o) {
        return o.fresh() || (o.candidate().isAutomaticallyConfirmed()
                && !o.candidate().getConfirmedRelationship().isWithdrawn());
    }

    private boolean conflicts(Observation first, Observation second) {
        var a = first.request();
        var b = second.request();
        if (a.objectCharacterId() == null || b.objectCharacterId() == null) return false;
        if (!canonicalPair(a).equals(canonicalPair(b)) || a.temporalScope() != b.temporalScope()
                || !Objects.equals(a.timeLabel(), b.timeLabel())) return false;
        boolean incompatibleAssertion = (a.assertion() == RelationshipAssertion.ASSERTED && b.assertion() == RelationshipAssertion.NEGATED)
                || (b.assertion() == RelationshipAssertion.ASSERTED && a.assertion() == RelationshipAssertion.NEGATED);
        return incompatibleAssertion && (a.subtype() == b.subtype()
                || a.subtype() == RelationshipSubtype.UNSPECIFIED || b.subtype() == RelationshipSubtype.UNSPECIFIED);
    }

    private Pair canonicalPair(RelationshipCandidateCreateRequest draft) {
        var subject = draft.subjectCharacterId();
        var object = draft.objectCharacterId();
        var kind = draft.kind();
        if (kind == RelationshipKind.CHILD_OF) return new Pair(object, subject, RelationshipKind.PARENT_OF);
        if ((kind == RelationshipKind.SPOUSE || kind == RelationshipKind.SIBLING) && subject.compareTo(object) > 0) {
            return new Pair(object, subject, kind);
        }
        return new Pair(subject, object, kind);
    }

    private boolean reaches(Map<UUID, Set<UUID>> parents, UUID from, UUID target) {
        Set<UUID> visited = new HashSet<>();
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        queue.add(from);
        while (!queue.isEmpty()) {
            UUID next = queue.removeFirst();
            if (next.equals(target)) return true;
            if (visited.add(next)) queue.addAll(parents.getOrDefault(next, Set.of()));
        }
        return false;
    }

    private RelationshipCandidateCreateRequest draft(UUID id, UUID subject, UUID object, ParsedFamilyRelationship claim,
            List<RelationshipEvidenceRequest> evidence) {
        return new RelationshipCandidateCreateRequest(id, subject, object, claim.objectMention(), claim.kind(),
                claim.subtype(), claim.temporalScope(), claim.timeLabel(), claim.assertion(), evidence);
    }

    private record ConflictScope(Pair pair, RelationshipTemporalScope temporalScope, String timeLabel) {}
    private record Pair(UUID subject, UUID object, RelationshipKind kind) {}
    private record Observation(RelationshipCandidate candidate, RelationshipCandidateCreateRequest request, boolean fresh, String reason) {}
    public record Result(int autoConfirmed, int pending, int unsupported) {}
}
