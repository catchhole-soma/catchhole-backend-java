package org.monitoring.catchholebackend.domain.relationship.service;

import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.AnalysisStatus;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Complete;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Context;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.ContextNode;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.ContextObservation;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.ContextRelationship;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Correction;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Decision;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Detail;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Edge;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Endpoint;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.EpisodeRef;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Evidence;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Fail;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.FailureCode;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Graph;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Node;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Observation;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Operation;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Prepare;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.PublicEvidence;
import static org.monitoring.catchholebackend.domain.relationship.exception.RelationshipGraphErrorCode.RELATIONSHIP_GRAPH_CONFLICT;
import static org.monitoring.catchholebackend.domain.relationship.exception.RelationshipGraphErrorCode.RELATIONSHIP_GRAPH_INCOMPLETE;
import static org.monitoring.catchholebackend.domain.relationship.exception.RelationshipGraphErrorCode.RELATIONSHIP_GRAPH_INVALID_INPUT;
import static org.monitoring.catchholebackend.domain.relationship.exception.RelationshipGraphErrorCode.RELATIONSHIP_GRAPH_NOT_FOUND;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisCandidateSourceGuard;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobLeaseService;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeSourcePurgeRequestRepository;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphAnalysis;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEdge;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEvent;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphNode;
import org.monitoring.catchholebackend.domain.relationship.mapper.RelationshipGraphMapper;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphRules;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipGraphAnalysisRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipGraphEdgeRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipGraphEventRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipGraphNodeRepository;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RelationshipGraphServiceImpl implements RelationshipGraphService {
    private final RelationshipGraphAnalysisRepository analyses;
    private final RelationshipGraphNodeRepository nodes;
    private final RelationshipGraphEdgeRepository edges;
    private final RelationshipGraphEventRepository events;
    private final AnalysisJobLeaseService leases;
    private final AnalysisCandidateSourceGuard sourceGuard;
    private final AnalysisRunStateService runState;
    private final AnalysisJobRepository jobs;
    private final SettingCandidateRepository candidates;
    private final EpisodeRepository episodes;
    private final EpisodeSourcePurgeRequestRepository purges;
    private final WorkRepository works;
    private final ObjectStorageService storage;
    private final RelationshipGraphRules rules;
    private final RelationshipGraphMapper mapper;
    private final TransactionTemplate transactions;

    @Override
    @Transactional
    public void beginRelationshipAnalysis(AnalysisJob job) {
        if (!job.isRelationshipAnalysisEnabled() || analyses.existsById(job.getId())) return;
        requireEligible(job);
        // 새 시도는 이전 회차 결과와 그 결과를 읽은 후행 관계 상태를 섞지 않는다.
        for (var previous :
                analyses.findAllByWorkIdOrderByEpisodeNoAscCreatedAtAscIdAsc(
                        job.getWork().getId())) {
            if (previous.getEpisodeNo() >= job.getSourceEpisodeNo()) previous.invalidate();
        }
        analyses.save(
                RelationshipGraphAnalysis.pending(
                        job.getId(),
                        job.getWork().getId(),
                        job.getEpisode().getId(),
                        job.getSourceEpisodeNo(),
                        job.getSourceContentHash()));
    }

    @Override
    @Transactional
    public Optional<Context> getRelationshipAnalysis(UUID jobId, UUID lease) {
        AnalysisJob job = running(jobId, lease);
        return analyses.findById(jobId)
                .filter(a -> a.getStatus() != AnalysisStatus.PENDING)
                .map(
                        a -> {
                            requireCurrent(a, job);
                            assertResumableDependency(a);
                            return mapper.toContext(a);
                        });
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Context prepareRelationshipAnalysis(UUID jobId, UUID lease, Prepare request) {
        // S3 I/O never occupies the Work/Job database locks. Recheck lease and manifest afterwards.
        SourceSnapshot snapshot =
                transactions.execute(
                        status -> {
                            AnalysisJob job = running(jobId, lease);
                            if (request == null
                                    || !Objects.equals(
                                            job.getSourceContentHash(), request.sourceHash()))
                                conflict();
                            RelationshipGraphAnalysis analysis = requiredAnalysis(job);
                            Context saved = preparedRetry(analysis, request);
                            return new SourceSnapshot(
                                    job.getSourceContentS3Key(), job.getSourceContentHash(), saved);
                        });
        if (snapshot.context() != null) return snapshot.context();
        String source = storage.getText(snapshot.key());
        if (source == null || !storage.contentHash(source).equals(snapshot.hash())) conflict();
        rules.validatePrepare(request, source);
        return transactions.execute(
                status -> {
                    AnalysisJob job = running(jobId, lease);
                    if (!Objects.equals(snapshot.hash(), job.getSourceContentHash())
                            || !Objects.equals(snapshot.key(), job.getSourceContentS3Key()))
                        conflict();
                    RelationshipGraphAnalysis analysis = requiredAnalysis(job);
                    Context saved = preparedRetry(analysis, request);
                    if (saved != null) return saved;
                    BuiltContext built = buildContext(job, request);
                    analysis.prepareContext(
                            hash(mapper.toJson(request)),
                            built.context().contextToken(),
                            mapper.toJson(built.context()),
                            built.bindings(),
                            request.rejectedObservationCount() == null
                                    ? 0
                                    : request.rejectedObservationCount());
                    if (built.context().heldObservations().size() > 384
                            || built.context().nodes().size() > 1536
                            || built.context().relationships().size() > 1536)
                        analysis.fail(FailureCode.INPUT_TOO_LARGE.name());
                    return mapper.toContext(analysis);
                });
    }

    private Context preparedRetry(RelationshipGraphAnalysis analysis, Prepare request) {
        assertResumableDependency(analysis);
        if (analysis.getStatus() == AnalysisStatus.PENDING) return null;
        if (!Objects.equals(hash(mapper.toJson(request)), analysis.getRequestHash())) conflict();
        return mapper.toContext(analysis);
    }

    @Override
    @Transactional
    public void completeRelationshipAnalysis(UUID jobId, UUID lease, Complete request) {
        AnalysisJob job = running(jobId, lease);
        RelationshipGraphAnalysis analysis = requiredAnalysis(job);
        if (request == null) invalid();
        assertResumableDependency(analysis);
        String completionHash = hash(mapper.toJson(request));
        if (analysis.getStatus() == AnalysisStatus.COMPLETED) {
            if (!completionHash.equals(analysis.getCompletionHash())) conflict();
            return;
        }
        if (analysis.getStatus() != AnalysisStatus.PREPARED) conflict();
        assertDependencyRevision(analysis);
        rules.validateComplete(mapper.toContext(analysis), request);
        analysis.complete(mapper.toJson(request.decisions()), completionHash);
    }

    @Override
    @Transactional
    public void failRelationshipAnalysis(UUID jobId, UUID lease, Fail request) {
        AnalysisJob job = running(jobId, lease);
        if (request == null
                || request.formatVersion() != 1
                || request.failureCode() == null
                || !Objects.equals(request.sourceHash(), job.getSourceContentHash())) invalid();
        RelationshipGraphAnalysis analysis = requiredAnalysis(job);
        if (!Objects.equals(request.contextToken(), analysis.getContextToken())) conflict();
        if (analysis.getStatus() == AnalysisStatus.FAILED) {
            if (!request.failureCode().name().equals(analysis.getFailureCode())) conflict();
            return;
        }
        if (analysis.getStatus() != AnalysisStatus.PENDING
                && analysis.getStatus() != AnalysisStatus.PREPARED) conflict();
        analysis.fail(request.failureCode().name());
    }

    @Override
    @Transactional
    public void finalizeRelationshipAnalysis(AnalysisJob job) {
        if (!job.isRelationshipAnalysisEnabled()) return;
        RelationshipGraphAnalysis analysis = requiredAnalysis(job);
        if (analysis.getStatus() == AnalysisStatus.FAILED
                || analysis.getStatus() == AnalysisStatus.APPLIED) return;
        if (analysis.getStatus() != AnalysisStatus.COMPLETED)
            throw new AppException(RELATIONSHIP_GRAPH_INCOMPLETE);
        assertDependencyRevision(analysis);
        Context context = mapper.toContext(analysis);
        Map<String, ContextNode> contextNodes =
                context.nodes().stream().collect(Collectors.toMap(ContextNode::ref, n -> n));
        Map<String, ContextObservation> observations = new LinkedHashMap<>();
        context.observations().forEach(o -> observations.put(o.ref(), o));
        context.heldObservations().forEach(o -> observations.put(o.ref(), o));
        for (Decision decision : mapper.decisions(analysis)) {
            if (decision.operation() == Operation.HOLD || decision.operation() == Operation.EXCLUDE)
                continue;
            UUID source = nodeId(analysis, decision.sourceNodeRef()),
                    target = nodeId(analysis, decision.targetNodeRef());
            String key =
                    storage.contentHash(
                            RelationshipGraphRules.canonical(
                                    source.toString(),
                                    target.toString(),
                                    decision.axis(),
                                    decision.kind(),
                                    decision.direction()));
            RelationshipGraphEdge edge;
            if (decision.relationshipRef() == null) {
                edge =
                        edges.findByWorkIdAndCanonicalKey(job.getWork().getId(), key)
                                .orElseGet(
                                        () ->
                                                edges.save(
                                                        RelationshipGraphEdge.create(
                                                                job.getWork().getId(),
                                                                source,
                                                                target,
                                                                decision.axis(),
                                                                decision.kind(),
                                                                key)));
            } else {
                edge =
                        edges.findById(
                                        UUID.fromString(
                                                analysis.getBindings()
                                                        .path("relationships")
                                                        .path(decision.relationshipRef())
                                                        .asText()))
                                .orElseThrow(() -> new AppException(RELATIONSHIP_GRAPH_CONFLICT));
            }
            // 사람의 오류 수정/삭제를 자동 분석이 되돌리지 않는다.
            if (edge.isSuppressed()) continue;
            var evidence = mapper.toJson(new ArrayList<>());
            var array = (com.fasterxml.jackson.databind.node.ArrayNode) evidence;
            for (String ref : decision.observationRefs()) {
                ContextObservation observation = observations.get(ref);
                JsonNode held = analysis.getBindings().path("heldSources").path(ref);
                UUID episodeId =
                        held.isMissingNode()
                                ? analysis.getEpisodeId()
                                : UUID.fromString(held.path("episodeId").asText());
                int no =
                        held.isMissingNode()
                                ? analysis.getEpisodeNo()
                                : held.path("episodeNo").asInt();
                for (Evidence quote : observation.evidence()) {
                    ObjectNode entry = (ObjectNode) mapper.toJson(quote);
                    entry.put("episodeId", episodeId.toString());
                    entry.put("episodeNo", no);
                    array.add(entry);
                }
            }
            events.save(
                    mapper.toEvent(
                            analysis,
                            edge,
                            decision,
                            contextNodes
                                    .get(
                                            source.equals(edge.getSourceNodeId())
                                                    ? decision.sourceNodeRef()
                                                    : decision.targetNodeRef())
                                    .name(),
                            contextNodes
                                    .get(
                                            target.equals(edge.getTargetNodeId())
                                                    ? decision.targetNodeRef()
                                                    : decision.sourceNodeRef())
                                    .name(),
                            evidence));
            edge.changed();
        }
        linkNewlyConfirmedCharacters(job, analysis);
        analysis.apply();
        // A later manual job may have prepared before this earlier episode completed.
        for (var later :
                analyses.findAllByWorkIdOrderByEpisodeNoAscCreatedAtAscIdAsc(
                        job.getWork().getId())) {
            if (later.getEpisodeNo() > analysis.getEpisodeNo()) later.invalidate();
        }
    }

    private void linkNewlyConfirmedCharacters(AnalysisJob job, RelationshipGraphAnalysis analysis) {
        var confirmed =
                candidates.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(job.getId()).stream()
                        .filter(
                                c ->
                                        c.getReviewStatus()
                                                        == SettingCandidateReviewStatus.CONFIRMED
                                                && c.getMatchedCharacterId() != null)
                        .toList();
        for (JsonNode bound : analysis.getBindings().path("nodes")) {
            var node = nodes.findById(UUID.fromString(bound.path("id").asText())).orElseThrow();
            if (node.getCharacterId() != null
                    || node.getFirstEpisodeNo() != analysis.getEpisodeNo()) continue;
            var matches =
                    confirmed.stream()
                            .filter(c -> node.getName().equals(c.getEntityName()))
                            .map(c -> c.getMatchedCharacterId())
                            .distinct()
                            .toList();
            if (matches.size() == 1) node.link(matches.getFirst(), analysis.getEpisodeNo());
        }
    }

    private BuiltContext buildContext(AnalysisJob job, Prepare request) {
        UUID workId = job.getWork().getId();
        int cutoff = job.getSourceEpisodeNo();
        var allAnalyses = latestAnalyses(workId);
        Map<UUID, RelationshipGraphEvent> prior = latestEvents(workId, cutoff - 1, allAnalyses);
        Map<UUID, RelationshipGraphEdge> allEdges =
                edges.findAllByWorkId(workId).stream()
                        .collect(Collectors.toMap(RelationshipGraphEdge::getId, e -> e));
        // Keep cutoff-safe provisional identities even before they produce an event.
        // Parallel/manual
        // jobs, internal HOLD and reanalysis must not manufacture duplicate same-name node IDs.
        List<RelationshipGraphNode> existing =
                nodes.findAllByWorkIdOrderByFirstEpisodeNoAscIdAsc(workId).stream()
                        .filter(n -> n.getFirstEpisodeNo() <= cutoff)
                        .toList();
        Map<String, List<RelationshipGraphNode>> byName = new LinkedHashMap<>();
        existing.forEach(n -> byName.computeIfAbsent(n.getName(), x -> new ArrayList<>()).add(n));
        JsonNode input =
                job.isOrderedProvisional() ? runState.getInputState(job) : mapper.toJson(Map.of());
        Map<String, List<UUID>> known = new HashMap<>();
        for (JsonNode character : input.path("characters")) {
            if (character.path("actualCharacterId").isTextual())
                known.computeIfAbsent(character.path("name").asText(), x -> new ArrayList<>())
                        .add(UUID.fromString(character.path("actualCharacterId").asText()));
        }
        known.forEach(
                (name, identities) -> {
                    if (identities.stream().distinct().count() > 1) byName.put(name, List.of());
                });
        // 새 이름은 현재 원문에서 확인한 것만 독립 도형 후보가 된다. 역할/대명사는 생성하지 않는다.
        for (Observation observation : request.observations())
            for (Endpoint endpoint : List.of(observation.source(), observation.target())) {
                if (endpoint.name() == null || byName.containsKey(endpoint.name())) continue;
                List<UUID> identities =
                        known.getOrDefault(endpoint.name(), List.of()).stream().distinct().toList();
                if (identities.size() > 1) {
                    byName.put(endpoint.name(), List.of());
                    continue;
                }
                var node =
                        nodes.save(
                                RelationshipGraphNode.create(
                                        UUID.randomUUID(),
                                        workId,
                                        identities.size() == 1 ? identities.getFirst() : null,
                                        endpoint.name(),
                                        cutoff));
                byName.put(endpoint.name(), List.of(node));
            }
        Map<UUID, String> refs = new LinkedHashMap<>();
        Map<String, ContextNode> contextNodes = new LinkedHashMap<>();
        ObjectNode bindings = (ObjectNode) mapper.toJson(Map.of());
        ObjectNode nodeBindings = bindings.putObject("nodes");
        for (var group : byName.values())
            for (var node : group) {
                String ref = "N" + (refs.size() + 1);
                refs.put(node.getId(), ref);
                contextNodes.put(
                        ref,
                        new ContextNode(
                                ref,
                                node.getName(),
                                node.getCharacterId() != null
                                        && node.getCharacterLinkedEpisodeNo() != null
                                        && node.getCharacterLinkedEpisodeNo() <= cutoff));
                ObjectNode binding = nodeBindings.putObject(ref);
                binding.put("id", node.getId().toString());
            }
        List<ContextObservation> observations =
                request.observations().stream()
                        .map(o -> contextObservation(o.ref(), o, byName, refs, cutoff))
                        .toList();
        Set<String> relevant =
                observations.stream()
                        .flatMap(
                                o ->
                                        java.util.stream.Stream.concat(
                                                o.sourceNodeRefs().stream(),
                                                o.targetNodeRefs().stream()))
                        .collect(Collectors.toSet());
        ObjectNode relationBindings = bindings.putObject("relationships");
        List<ContextRelationship> relationships = new ArrayList<>();
        Set<UUID> validPriorIds = validIds(allAnalyses, cutoff - 1);
        Map<UUID, Integer> firstKnown = new HashMap<>();
        events
                .findAllByWorkIdAndEpisodeNoLessThanEqualOrderByEpisodeNoAscCreatedAtAscIdAsc(
                        workId, cutoff - 1)
                .stream()
                .filter(e -> validPriorIds.contains(e.getAnalysisId()))
                .forEach(e -> firstKnown.putIfAbsent(e.getEdgeId(), e.getEpisodeNo()));
        for (var event : prior.values()) {
            var edge = allEdges.get(event.getEdgeId());
            if (edge == null || edge.isSuppressed()) continue;
            String source = refs.get(edge.getSourceNodeId()),
                    target = refs.get(edge.getTargetNodeId());
            if (source == null
                    || target == null
                    || (!relevant.contains(source) && !relevant.contains(target))) continue;
            String ref = "R" + (relationships.size() + 1);
            relationBindings.put(ref, edge.getId().toString());
            relationships.add(
                    new ContextRelationship(
                            ref,
                            source,
                            target,
                            edge.getAxis(),
                            edge.getKind(),
                            event.getLabel(),
                            edge.getCorrectedSummary() == null
                                    ? event.getSummary()
                                    : edge.getCorrectedSummary(),
                            event.getDirection(),
                            event.getStatus(),
                            firstKnown.get(edge.getId()),
                            event.getEpisodeNo()));
        }
        Set<String> consumedHolds = consumedHolds(allAnalyses, cutoff - 1);
        List<ContextObservation> held = new ArrayList<>();
        ObjectNode heldBindings = bindings.putObject("heldSources");
        for (var previous : allAnalyses.values()) {
            if (previous.getEpisodeNo() >= cutoff
                    || !validPriorIds.contains(previous.getId())
                    || previous.getStatus() != AnalysisStatus.APPLIED
                    || previous.isEvidencePurged()
                    || !sourceAvailable(previous)) continue;
            Context old = mapper.toContext(previous);
            Map<String, ContextObservation> originals =
                    old.observations().stream()
                            .collect(Collectors.toMap(ContextObservation::ref, o -> o));
            Set<String> heldRefs =
                    mapper.decisions(previous).stream()
                            .filter(d -> d.operation() == Operation.HOLD)
                            .flatMap(d -> d.observationRefs().stream())
                            .collect(Collectors.toSet());
            for (String oldRef : heldRefs) {
                ContextObservation original = originals.get(oldRef);
                if (original == null || consumedHolds.contains(previous.getId() + ":" + oldRef))
                    continue;
                String ref = "H" + (held.size() + 1);
                var mapped =
                        contextObservation(
                                ref,
                                new Observation(
                                        ref,
                                        original.source(),
                                        original.target(),
                                        original.summary(),
                                        original.evidence()),
                                byName,
                                refs,
                                previous.getEpisodeNo());
                if (java.util.stream.Stream.concat(
                                mapped.sourceNodeRefs().stream(), mapped.targetNodeRefs().stream())
                        .noneMatch(relevant::contains)) continue;
                held.add(mapped);
                ObjectNode origin = heldBindings.putObject(ref);
                origin.put("episodeId", previous.getEpisodeId().toString());
                origin.put("episodeNo", previous.getEpisodeNo());
                origin.put("analysisId", previous.getId().toString());
                origin.put("observationRef", oldRef);
            }
        }
        bindings.put("dependencyRevision", dependencyRevision(workId, cutoff));
        String token =
                storage.contentHash(
                        job.getId() + "|" + request.sourceHash() + "|" + UUID.randomUUID());
        Set<String> neededRefs = new HashSet<>(relevant);
        relationships.forEach(
                r -> {
                    neededRefs.add(r.sourceNodeRef());
                    neededRefs.add(r.targetNodeRef());
                });
        held.forEach(
                o -> {
                    neededRefs.addAll(o.sourceNodeRefs());
                    neededRefs.addAll(o.targetNodeRefs());
                });
        List<ContextNode> neededNodes =
                contextNodes.values().stream().filter(n -> neededRefs.contains(n.ref())).toList();
        Context context =
                new Context(
                        1,
                        request.sourceHash(),
                        token,
                        AnalysisStatus.PREPARED,
                        cutoff,
                        observations,
                        neededNodes,
                        relationships,
                        List.copyOf(held));
        return new BuiltContext(context, bindings);
    }

    private ContextObservation contextObservation(
            String ref,
            Observation o,
            Map<String, List<RelationshipGraphNode>> byName,
            Map<UUID, String> refs,
            int episodeNo) {
        return new ContextObservation(
                ref,
                o.source(),
                o.target(),
                o.summary(),
                o.evidence(),
                endpointRefs(o.source(), byName, refs),
                endpointRefs(o.target(), byName, refs),
                episodeNo);
    }

    private List<String> endpointRefs(
            Endpoint endpoint,
            Map<String, List<RelationshipGraphNode>> byName,
            Map<UUID, String> refs) {
        if (endpoint.name() == null) return List.of();
        return byName.getOrDefault(endpoint.name(), List.of()).stream()
                .map(n -> refs.get(n.getId()))
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Graph getRelationshipGraph(Long memberId, UUID workId, Integer episodeNo) {
        works.getOwnedWork(workId, memberId);
        return graph(readState(workId), episodeNo);
    }

    private Graph graph(ReadState read, Integer episodeNo) {
        List<EpisodeRef> available =
                read.analyses().values().stream()
                        .filter(a -> read.validIds().contains(a.getId()))
                        .map(
                                a ->
                                        new EpisodeRef(
                                                a.getEpisodeId(),
                                                a.getEpisodeNo(),
                                                read.episodes().get(a.getEpisodeId()).getTitle()))
                        .toList();
        EpisodeRef selected;
        if (episodeNo != null) {
            if (episodeNo < 1) invalid();
            selected =
                    available.stream()
                            .filter(e -> e.episodeNo() == episodeNo)
                            .findFirst()
                            .orElseThrow(() -> new AppException(RELATIONSHIP_GRAPH_NOT_FOUND));
        } else selected = available.isEmpty() ? null : available.getLast();
        int cutoff = selected == null ? 0 : selected.episodeNo();
        Map<UUID, RelationshipGraphEvent> latest = latestEvents(read, cutoff);
        Map<UUID, List<PublicEvidence>> evidence = evidence(read, cutoff);
        List<Edge> output = new ArrayList<>();
        Map<UUID, Node> outputNodes = new LinkedHashMap<>();
        List<RelationshipGraphEvent> visible =
                latest.values().stream()
                        .filter(e -> !read.edges().get(e.getEdgeId()).isSuppressed())
                        .toList();
        for (var event : visible.stream().limit(200).toList()) {
            var edge = read.edges().get(event.getEdgeId());
            output.add(
                    mapper.toEdge(
                            edge, event, evidence.getOrDefault(edge.getId(), List.of()).size()));
            outputNodes.put(
                    edge.getSourceNodeId(),
                    mapper.toNode(
                            read.nodes().get(edge.getSourceNodeId()),
                            event.getSourceName(),
                            cutoff));
            outputNodes.put(
                    edge.getTargetNodeId(),
                    mapper.toNode(
                            read.nodes().get(edge.getTargetNodeId()),
                            event.getTargetName(),
                            cutoff));
        }
        var considered =
                read.analyses().values().stream()
                        .filter(a -> episodeNo == null || a.getEpisodeNo() <= episodeNo)
                        .toList();
        Set<String> consumed =
                consumedHolds(
                        read.analyses(),
                        episodeNo == null ? Integer.MAX_VALUE : episodeNo,
                        read.validIds());
        String status = "NOT_ANALYZED";
        boolean partial = false;
        if (!considered.isEmpty()) {
            var last = considered.getLast();
            status =
                    switch (last.getStatus()) {
                        case PENDING, PREPARED, COMPLETED -> "PROCESSING";
                        case FAILED -> "FAILED";
                        case INVALIDATED -> "INVALIDATED";
                        case APPLIED ->
                                read.validIds().contains(last.getId())
                                        ? "COMPLETED"
                                        : "INVALIDATED";
                    };
            partial =
                    considered.stream()
                            .anyMatch(
                                    a ->
                                            a.getStatus() != AnalysisStatus.APPLIED
                                                    || !read.validIds().contains(a.getId())
                                                    || a.getRejectedObservationCount() > 0
                                                    || mapper.decisions(a).stream()
                                                            .filter(
                                                                    d ->
                                                                            d.operation()
                                                                                    == Operation
                                                                                            .HOLD)
                                                            .flatMap(
                                                                    d ->
                                                                            d
                                                                                    .observationRefs()
                                                                                    .stream())
                                                            .filter(ref -> ref.startsWith("O"))
                                                            .anyMatch(
                                                                    ref ->
                                                                            !consumed.contains(
                                                                                    a.getId() + ":"
                                                                                            + ref)));
            if (status.equals("COMPLETED") && partial) status = "PARTIAL";
        }
        String revision =
                hash(
                        mapper.toJson(
                                List.of(
                                        output,
                                        List.copyOf(outputNodes.values()),
                                        available,
                                        status)));
        return new Graph(
                selected,
                available,
                revision,
                List.copyOf(outputNodes.values()),
                List.copyOf(output),
                visible.size() > 200,
                visible.size(),
                status,
                partial);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail getRelationshipEdge(Long memberId, UUID workId, UUID edgeId, Integer episodeNo) {
        works.getOwnedWork(workId, memberId);
        ReadState read = readState(workId);
        Graph graph = graph(read, episodeNo);
        if (graph.selectedEpisode() == null) throw new AppException(RELATIONSHIP_GRAPH_NOT_FOUND);
        var edge = read.edges().get(edgeId);
        if (edge == null || edge.isSuppressed())
            throw new AppException(RELATIONSHIP_GRAPH_NOT_FOUND);
        int cutoff = graph.selectedEpisode().episodeNo();
        var event = latestEvents(read, cutoff).get(edgeId);
        if (event == null) throw new AppException(RELATIONSHIP_GRAPH_NOT_FOUND);
        List<PublicEvidence> evidence = evidence(read, cutoff).getOrDefault(edgeId, List.of());
        return new Detail(
                mapper.toEdge(edge, event, evidence.size()),
                mapper.toNode(
                        read.nodes().get(edge.getSourceNodeId()), event.getSourceName(), cutoff),
                mapper.toNode(
                        read.nodes().get(edge.getTargetNodeId()), event.getTargetName(), cutoff),
                evidence,
                edge.getVersion());
    }

    private ReadState readState(UUID workId) {
        Map<Integer, RelationshipGraphAnalysis> all = latestAnalyses(workId);
        Set<UUID> valid = validIds(all, Integer.MAX_VALUE);
        Map<UUID, Episode> episodeMap =
                episodes
                        .findAllById(
                                all.values().stream()
                                        .map(RelationshipGraphAnalysis::getEpisodeId)
                                        .distinct()
                                        .toList())
                        .stream()
                        .collect(Collectors.toMap(Episode::getId, e -> e));
        return new ReadState(
                all,
                valid,
                events.findAllByWorkIdAndEpisodeNoLessThanEqualOrderByEpisodeNoAscCreatedAtAscIdAsc(
                        workId, Integer.MAX_VALUE),
                edges.findAllByWorkId(workId).stream()
                        .collect(Collectors.toMap(RelationshipGraphEdge::getId, e -> e)),
                nodes.findAllByWorkIdOrderByFirstEpisodeNoAscIdAsc(workId).stream()
                        .collect(Collectors.toMap(RelationshipGraphNode::getId, n -> n)),
                episodeMap);
    }

    private Map<UUID, RelationshipGraphEvent> latestEvents(ReadState read, int cutoff) {
        Map<UUID, RelationshipGraphEvent> result = new LinkedHashMap<>();
        read.events().stream()
                .filter(
                        e ->
                                e.getEpisodeNo() <= cutoff
                                        && read.validIds().contains(e.getAnalysisId()))
                .forEach(e -> result.put(e.getEdgeId(), e));
        return result;
    }

    private Map<UUID, List<PublicEvidence>> evidence(ReadState read, int cutoff) {
        Map<UUID, Map<String, PublicEvidence>> grouped = new HashMap<>();
        for (var event : read.events()) {
            if (event.getEpisodeNo() > cutoff
                    || !read.validIds().contains(event.getAnalysisId())
                    || event.getEvidence() == null) continue;
            for (JsonNode quote : event.getEvidence()) {
                UUID episodeId = UUID.fromString(quote.path("episodeId").asText());
                int no = quote.path("episodeNo").asInt();
                var analysis = read.analyses().get(no);
                var episode = read.episodes().get(episodeId);
                if (no > cutoff
                        || episode == null
                        || analysis == null
                        || !read.validIds().contains(analysis.getId())
                        || !analysis.getEpisodeId().equals(episodeId)) continue;
                String text = quote.path("quote").asText();
                if (text.isBlank()) continue;
                int start = quote.path("startOffset").asInt(),
                        end = quote.path("endOffset").asInt();
                grouped.computeIfAbsent(event.getEdgeId(), x -> new LinkedHashMap<>())
                        .putIfAbsent(
                                episodeId + ":" + start + ":" + end,
                                new PublicEvidence(
                                        episodeId, no, episode.getTitle(), text, start, end));
            }
        }
        Map<UUID, List<PublicEvidence>> result = new HashMap<>();
        grouped.forEach((id, entries) -> result.put(id, List.copyOf(entries.values())));
        return result;
    }

    private record ReadState(
            Map<Integer, RelationshipGraphAnalysis> analyses,
            Set<UUID> validIds,
            List<RelationshipGraphEvent> events,
            Map<UUID, RelationshipGraphEdge> edges,
            Map<UUID, RelationshipGraphNode> nodes,
            Map<UUID, Episode> episodes) {}

    @Override
    @Transactional
    public void correctRelationshipEdge(
            Long memberId, UUID workId, UUID edgeId, Correction request) {
        works.getOwnedWorkForUpdate(workId, memberId);
        runState.assertSettingMutationAllowed(workId);
        var edge = ownedEdge(workId, edgeId);
        if (request == null || !RelationshipGraphRules.text(request.summary(), 500)) invalid();
        if (edge.getVersion() != request.expectedVersion() || edge.isSuppressed()) conflict();
        edge.correct(request.summary());
    }

    @Override
    @Transactional
    public void deleteRelationshipEdge(Long memberId, UUID workId, UUID edgeId, long version) {
        works.getOwnedWorkForUpdate(workId, memberId);
        runState.assertSettingMutationAllowed(workId);
        var edge = ownedEdge(workId, edgeId);
        if (edge.getVersion() != version) conflict();
        edge.suppress();
    }

    private RelationshipGraphEdge ownedEdge(UUID workId, UUID id) {
        return edges.findById(id)
                .filter(e -> e.getWorkId().equals(workId))
                .orElseThrow(() -> new AppException(RELATIONSHIP_GRAPH_NOT_FOUND));
    }

    private Map<Integer, RelationshipGraphAnalysis> latestAnalyses(UUID workId) {
        Map<Integer, RelationshipGraphAnalysis> result = new TreeMap<>();
        analyses.findAllByWorkIdOrderByEpisodeNoAscCreatedAtAscIdAsc(workId)
                .forEach(a -> result.put(a.getEpisodeNo(), a));
        return result;
    }

    private Map<UUID, RelationshipGraphEvent> latestEvents(
            UUID workId, int cutoff, Map<Integer, RelationshipGraphAnalysis> all) {
        Set<UUID> valid = validIds(all, cutoff);
        Map<UUID, RelationshipGraphEvent> result = new LinkedHashMap<>();
        events
                .findAllByWorkIdAndEpisodeNoLessThanEqualOrderByEpisodeNoAscCreatedAtAscIdAsc(
                        workId, cutoff)
                .stream()
                .filter(e -> valid.contains(e.getAnalysisId()))
                .forEach(e -> result.put(e.getEdgeId(), e));
        return result;
    }

    private Set<UUID> validIds(Map<Integer, RelationshipGraphAnalysis> all, int cutoff) {
        int invalidFrom =
                all.values().stream()
                        .filter(
                                a ->
                                        a.getStatus() == AnalysisStatus.INVALIDATED
                                                || !sourceAvailable(a))
                        .mapToInt(RelationshipGraphAnalysis::getEpisodeNo)
                        .min()
                        .orElse(Integer.MAX_VALUE);
        return all.values().stream()
                .filter(
                        a ->
                                a.getEpisodeNo() <= cutoff
                                        && a.getEpisodeNo() < invalidFrom
                                        && a.getStatus() == AnalysisStatus.APPLIED)
                .map(RelationshipGraphAnalysis::getId)
                .collect(Collectors.toSet());
    }

    private boolean sourceAvailable(RelationshipGraphAnalysis analysis) {
        Episode episode = episodes.findById(analysis.getEpisodeId()).orElse(null);
        return episode != null
                && episode.getStatus() != EpisodeStatus.ARCHIVED
                && !analysis.isEvidencePurged()
                && !purges.existsByEpisodeId(episode.getId())
                && episode.getEpisodeNo() == analysis.getEpisodeNo()
                && Objects.equals(episode.getContentHash(), analysis.getSourceHash())
                && jobs.findById(analysis.getId())
                        .map(
                                job ->
                                        Objects.equals(
                                                        job.getSourceContentS3Key(),
                                                        episode.getContentS3Key())
                                                && Objects.equals(
                                                        job.getSourceContentS3Version(),
                                                        episode.getContentS3Version()))
                        .orElse(false);
    }

    private Set<String> consumedHolds(Map<Integer, RelationshipGraphAnalysis> all, int cutoff) {
        return consumedHolds(all, cutoff, validIds(all, cutoff));
    }

    private Set<String> consumedHolds(
            Map<Integer, RelationshipGraphAnalysis> all, int cutoff, Set<UUID> valid) {
        Set<String> result = new HashSet<>();
        for (var analysis : all.values())
            if (analysis.getEpisodeNo() <= cutoff && valid.contains(analysis.getId())) {
                for (var decision : mapper.decisions(analysis))
                    if (decision.operation() != Operation.HOLD) {
                        for (String ref : decision.observationRefs())
                            if (ref.startsWith("H")) {
                                JsonNode held =
                                        analysis.getBindings().path("heldSources").path(ref);
                                result.add(
                                        held.path("analysisId").asText()
                                                + ":"
                                                + held.path("observationRef").asText());
                            }
                    }
            }
        return result;
    }

    private String dependencyRevision(UUID workId, int cutoff) {
        List<Object> revision = new ArrayList<>();
        for (var a : latestAnalyses(workId).values())
            if (a.getEpisodeNo() < cutoff) {
                revision.add(
                        List.of(
                                a.getId().toString(),
                                a.getStatus() == AnalysisStatus.APPLIED,
                                sourceAvailable(a)));
            }
        // User corrections/deletions between prepare and completion make the frozen comparison
        // stale.
        Set<UUID> relevantEdges =
                events
                        .findAllByWorkIdAndEpisodeNoLessThanEqualOrderByEpisodeNoAscCreatedAtAscIdAsc(
                                workId, cutoff)
                        .stream()
                        .map(RelationshipGraphEvent::getEdgeId)
                        .collect(Collectors.toSet());
        edges.findAllByWorkId(workId).stream()
                .filter(e -> relevantEdges.contains(e.getId()))
                .sorted(Comparator.comparing(RelationshipGraphEdge::getId))
                .forEach(
                        e ->
                                revision.add(
                                        List.of(
                                                e.getId().toString(),
                                                e.getCorrectedSummary() == null
                                                        ? ""
                                                        : e.getCorrectedSummary(),
                                                e.isSuppressed())));
        return hash(mapper.toJson(revision));
    }

    private void assertResumableDependency(RelationshipGraphAnalysis analysis) {
        if (analysis.getStatus() == AnalysisStatus.PREPARED
                || analysis.getStatus() == AnalysisStatus.COMPLETED)
            assertDependencyRevision(analysis);
    }

    private void assertDependencyRevision(RelationshipGraphAnalysis analysis) {
        if (!Objects.equals(
                analysis.getBindings().path("dependencyRevision").asText(),
                dependencyRevision(analysis.getWorkId(), analysis.getEpisodeNo()))) conflict();
    }

    private EpisodeRef episodeRef(UUID id, int no) {
        return new EpisodeRef(id, no, episodes.findById(id).map(Episode::getTitle).orElse(null));
    }

    private AnalysisJob running(UUID id, UUID lease) {
        leases.lockWorkForUpdate(id);
        AnalysisJob job = leases.getRunningAnalysisJobForUpdate(id, lease);
        requireEligible(job);
        if (!job.isRelationshipAnalysisEnabled()) conflict();
        sourceGuard.assertCurrent(job, job.getEpisode());
        return job;
    }

    private void requireEligible(AnalysisJob job) {
        if (job.getJobType() != AnalysisJobType.SETTING_EXTRACTION
                || job.isSettingBookSource()
                || job.getEpisode() == null
                || job.getSourceEpisodeNo() == null) invalid();
    }

    private RelationshipGraphAnalysis requiredAnalysis(AnalysisJob job) {
        var analysis =
                analyses.findById(job.getId())
                        .orElseThrow(() -> new AppException(RELATIONSHIP_GRAPH_INCOMPLETE));
        requireCurrent(analysis, job);
        return analysis;
    }

    private void requireCurrent(RelationshipGraphAnalysis analysis, AnalysisJob job) {
        if (analysis.getStatus() == AnalysisStatus.INVALIDATED
                || analysis.isEvidencePurged()
                || !analysis.getSourceHash().equals(job.getSourceContentHash())) conflict();
        sourceGuard.assertCurrent(job, job.getEpisode());
    }

    private UUID nodeId(RelationshipGraphAnalysis a, String ref) {
        return UUID.fromString(a.getBindings().path("nodes").path(ref).path("id").asText());
    }

    private String hash(JsonNode json) {
        return storage.contentHash(json.toString());
    }

    private static void invalid() {
        throw new AppException(RELATIONSHIP_GRAPH_INVALID_INPUT);
    }

    private static void conflict() {
        throw new AppException(RELATIONSHIP_GRAPH_CONFLICT);
    }

    private record SourceSnapshot(String key, String hash, Context context) {}

    private record BuiltContext(Context context, ObjectNode bindings) {}
}
