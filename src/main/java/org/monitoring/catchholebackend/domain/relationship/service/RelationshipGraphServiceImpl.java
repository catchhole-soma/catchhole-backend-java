package org.monitoring.catchholebackend.domain.relationship.service;

import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.AnalysisStatus;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.BlockedPair;
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
import lombok.extern.slf4j.Slf4j;

import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceRepository;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisCandidateSourceGuard;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobLeaseService;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.repository.ConfirmedCharacterIdentityRow;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterNameRow;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateKind;
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
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipHoldClosureRepository;
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

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RelationshipGraphServiceImpl implements RelationshipGraphService {
    private final RelationshipGraphAnalysisRepository analyses;
    private final RelationshipHoldClosureRepository holdClosures;
    private final RelationshipGraphNodeRepository nodes;
    private final RelationshipGraphEdgeRepository edges;
    private final RelationshipGraphEventRepository events;
    private final AnalysisJobLeaseService leases;
    private final AnalysisCandidateSourceGuard sourceGuard;
    private final AnalysisRunStateService runState;
    private final AnalysisJobRepository jobs;
    private final AnalysisJobSourceRepository jobSources;
    private final SettingCandidateRepository candidates;
    private final WorkCharacterRepository characters;
    private final EpisodeRepository episodes;
    private final EpisodeSourcePurgeRequestRepository purges;
    private final WorkRepository works;
    private final ObjectStorageService storage;
    private final RelationshipGraphRules rules;
    private final RelationshipGraphMapper mapper;
    private final org.monitoring.catchholebackend.domain.analysis.processor.AnalysisFailureDiagnosticsSanitizer failureDiagnosticsSanitizer;
    private final TransactionTemplate transactions;
    private final org.monitoring.catchholebackend.domain.relationship.repository
                    .RelationshipBackfillWorkRepository
            backfillWorks;
    private final jakarta.persistence.EntityManager entityManager;

    @Override
    public boolean hasRelationshipAnalysis(UUID jobId) { return analyses.existsById(jobId); }

    @Override
    @Transactional
    public void beginRelationshipAnalysis(AnalysisJob job) {
        if (!job.isRelationshipAnalysisEnabled() || analyses.existsById(job.getId())) return;
        requireEligible(job);
        // Ordinary episode decisions remain independent records. A replacement supersedes only
        // its own episode; private backfill retains its strictly ordered rebuild contract.
        for (var previous :
                analyses.findAllByWorkIdOrderByEpisodeNoAscCreatedAtAscIdAsc(
                        job.getWork().getId())) {
            if (previous.getGeneration() == job.getRelationshipGeneration()
                    && (previous.getEpisodeNo() == job.getSourceEpisodeNo()
                        || job.getJobType() == AnalysisJobType.RELATIONSHIP_EXTRACTION
                            && previous.getEpisodeNo() > job.getSourceEpisodeNo())) previous.invalidate();
        }
        var pending =
                RelationshipGraphAnalysis.pending(
                        job.getId(),
                        job.getWork().getId(),
                        job.getEpisode().getId(),
                        job.getSourceEpisodeNo(),
                        job.getSourceContentHash());
        pending.assignGeneration(job.getRelationshipGeneration());
        analyses.save(pending);
    }

    @Override
    @Transactional
    public Optional<Context> getRelationshipAnalysis(UUID jobId, UUID lease) {
        AnalysisJob job = running(jobId, lease);
        return analyses.findById(jobId)
                .flatMap(
                        a -> {
                            prepareOrdinaryResume(a, job);
                            requireCurrent(a, job);
                            if (a.getStatus() == AnalysisStatus.PENDING) return Optional.empty();
                            assertResumableDependency(a);
                            return Optional.of(mapper.toContext(a, job.isRelationshipPartialResultsEnabled()));
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
                    if (exceedsContextLimit(built.context()))
                        analysis.fail(FailureCode.INPUT_TOO_LARGE.name());
                    return mapper.toContext(analysis);
                });
    }

    @Override
    public boolean canPrepareCachedObservations(AnalysisJob job, Prepare request) {
        requireEligible(job);
        if (job.getJobType() != AnalysisJobType.RELATIONSHIP_EXTRACTION) invalid();
        sourceGuard.assertCurrent(job, job.getEpisode());
        try {
            // Recovery admission holds the Work lock. Build the same current dependencies without
            // saving nodes or moving an existing node's first observation into the past.
            return !exceedsContextLimit(buildContext(job, request, false).context());
        } catch (IllegalArgumentException | NullPointerException malformedContext) {
            // Historical malformed context must not starve unrelated works or trigger extraction.
            // Database/source-guard failures still propagate and roll back the entire claim.
            return false;
        }
    }

    @Override
    @Transactional
    public void prepareCachedObservations(AnalysisJob job, Prepare request) {
        requireEligible(job);
        if (job.getJobType() != AnalysisJobType.RELATIONSHIP_EXTRACTION) invalid();
        sourceGuard.assertCurrent(job, job.getEpisode());
        var analysis = requiredAnalysis(job);
        if (analysis.getStatus() != AnalysisStatus.PENDING) conflict();
        BuiltContext built = buildContext(job, request);
        analysis.prepareContext(
                hash(mapper.toJson(request)),
                built.context().contextToken(),
                mapper.toJson(built.context()),
                built.bindings(),
                request.rejectedObservationCount() == null
                        ? 0
                        : request.rejectedObservationCount());
        if (exceedsContextLimit(built.context()))
            analysis.fail(FailureCode.INPUT_TOO_LARGE.name());
    }

    private boolean exceedsContextLimit(Context context) {
        return context.heldObservations().size() > 384
                || context.nodes().size() > 1536
                || context.relationships().size() > 1536;
    }

    private Context preparedRetry(RelationshipGraphAnalysis analysis, Prepare request) {
        assertResumableDependency(analysis);
        if (analysis.getStatus() == AnalysisStatus.PENDING) return null;
        if (!Objects.equals(hash(mapper.toJson(request)), analysis.getRequestHash())) conflict();
        return mapper.toContext(analysis);
    }

    /** Only a worker's initial load may replace a legacy token; an in-flight complete must not. */
    private void prepareOrdinaryResume(RelationshipGraphAnalysis analysis, AnalysisJob job) {
        if (job.getJobType() != AnalysisJobType.SETTING_EXTRACTION
                || analysis.getStatus() == AnalysisStatus.PENDING
                || analysis.getStatus() == AnalysisStatus.APPLIED
                || analysis.getStatus() == AnalysisStatus.FAILED) return;
        boolean invalidated = analysis.getStatus() == AnalysisStatus.INVALIDATED;
        boolean legacy = analysis.getBindings() == null
                || analysis.getBindings().path("manualDependencyVersion").isMissingNode();
        if (!invalidated && !legacy) return;
        assertOrdinaryResumeSource(analysis, job);
        if (events.existsByAnalysisId(analysis.getId())) conflict();

        if (analysis.getContext() == null) {
            // A pending marker can be invalidated before extraction has stored any observations.
            // A purged or partially lost prepared context must never be treated as fresh input.
            if (!invalidated || analysis.getBindings() != null || analysis.getContextToken() != null
                    || analysis.getRequestHash() != null || analysis.getCompletionHash() != null
                    || analysis.getDecisions() != null || analysis.getFailures() != null) conflict();
            analysis.resumeInvalidated(analysis.getFailureCode() == null
                    ? AnalysisStatus.PENDING : AnalysisStatus.FAILED);
            return;
        }
        Context saved = checkedCachedContext(analysis, job);
        AnalysisStatus phase = savedPhase(analysis);
        if (!invalidated && phase != analysis.getStatus()) conflict();
        if (phase == AnalysisStatus.FAILED) {
            if (invalidated) analysis.resumeInvalidated(phase);
            return;
        }
        if (!legacy) {
            assertDependencyRevision(analysis);
            if (invalidated) analysis.resumeInvalidated(phase);
            return;
        }

        // Old hashes mix all predecessors with user edits. Do not overwrite that opaque baseline
        // with the current value: check visible user edits, then rebuild from already verified O.
        assertLegacyManualEditsUnchanged(analysis, saved);
        Prepare request = mapper.toPrepareCachedObservations(
                analysis.getSourceHash(), saved, analysis.getRejectedObservationCount());
        BuiltContext rebuilt = buildContext(job, request);
        analysis.prepareContext(hash(mapper.toJson(request)), rebuilt.context().contextToken(),
                mapper.toJson(rebuilt.context()), rebuilt.bindings(), analysis.getRejectedObservationCount());
        if (exceedsContextLimit(rebuilt.context())) analysis.fail(FailureCode.INPUT_TOO_LARGE.name());
    }

    private void assertOrdinaryResumeSource(RelationshipGraphAnalysis analysis, AnalysisJob job) {
        if (analysis.isEvidencePurged()
                || !Objects.equals(analysis.getWorkId(), job.getWork().getId())
                || !Objects.equals(analysis.getEpisodeId(), job.getEpisode().getId())
                || analysis.getEpisodeNo() != job.getSourceEpisodeNo()
                || analysis.getGeneration() != job.getRelationshipGeneration()
                || !Objects.equals(analysis.getSourceHash(), job.getSourceContentHash())
                || !sourceAvailable(analysis)) conflict();
        sourceGuard.assertCurrent(job, job.getEpisode());
        var latestGraphs = analyses.findLatestIdsForEpisode(analysis.getWorkId(), analysis.getGeneration(),
                analysis.getEpisodeNo(), org.springframework.data.domain.PageRequest.of(0, 1));
        if (latestGraphs.isEmpty() || !latestGraphs.getFirst().equals(analysis.getId())) conflict();
        // A newer, not-yet-claimed Job has no graph row yet. It still supersedes this retry.
        var latestJobs = entityManager.createQuery("""
                select j.id from AnalysisJob j where j.work.id=:work and j.episode.id=:episode
                  and j.jobType=:type order by j.createdAt desc,j.id desc
                """, UUID.class).setParameter("work", analysis.getWorkId())
                .setParameter("episode", analysis.getEpisodeId())
                .setParameter("type", AnalysisJobType.SETTING_EXTRACTION).setMaxResults(1).getResultList();
        if (latestJobs.isEmpty() || !latestJobs.getFirst().equals(job.getId())) conflict();
    }

    private Context checkedCachedContext(RelationshipGraphAnalysis analysis, AnalysisJob job) {
        if (analysis.getBindings() == null || !analysis.getBindings().isObject()
                || analysis.getRequestHash() == null || analysis.getContextToken() == null
                || !analysis.getContext().path("formatVersion").isInt()
                || analysis.getContext().path("formatVersion").intValue() != 1) conflict();
        Context saved;
        try { saved = mapper.toContext(analysis); }
        catch (IllegalArgumentException | NullPointerException malformed) { throw new AppException(RELATIONSHIP_GRAPH_CONFLICT); }
        if (!Objects.equals(saved.sourceHash(), analysis.getSourceHash())
                || saved.sourceEpisodeNo() != analysis.getEpisodeNo()
                || !Objects.equals(saved.contextToken(), analysis.getContextToken())
                || saved.supportsPartialResults() != job.isRelationshipPartialResultsEnabled()
                || saved.observations() == null || saved.observations().size() > 384
                || saved.nodes() == null || saved.relationships() == null || saved.heldObservations() == null)
            conflict();
        Set<String> refs = new HashSet<>();
        for (var observation : saved.observations()) {
            if (observation == null || observation.ref() == null || !observation.ref().matches("O[1-9][0-9]*")
                    || !refs.add(observation.ref()) || observation.source() == null || observation.target() == null
                    || observation.summary() == null || observation.evidence() == null
                    || observation.evidence().isEmpty()
                    || !Objects.equals(observation.sourceEpisodeNo(), analysis.getEpisodeNo())) conflict();
            for (var evidence : observation.evidence()) {
                if (evidence == null || evidence.startOffset() < 0 || evidence.endOffset() <= evidence.startOffset()
                        || evidence.quote() == null || evidence.quote().isBlank()) conflict();
            }
        }
        return saved;
    }

    private AnalysisStatus savedPhase(RelationshipGraphAnalysis analysis) {
        if (analysis.getCompletionHash() != null) {
            if (analysis.getDecisions() == null || !analysis.getDecisions().isArray()
                    || analysis.getFailures() != null && !analysis.getFailures().isArray()) conflict();
            Complete saved = new Complete(1, analysis.getContextToken(), mapper.decisions(analysis), mapper.failures(analysis));
            if (!Objects.equals(analysis.getCompletionHash(), hash(mapper.toJson(saved)))) conflict();
            return AnalysisStatus.COMPLETED;
        }
        if (analysis.getDecisions() != null || analysis.getFailures() != null) conflict();
        if (analysis.getFailureCode() != null) return AnalysisStatus.FAILED;
        return AnalysisStatus.PREPARED;
    }

    private void assertLegacyManualEditsUnchanged(RelationshipGraphAnalysis analysis, Context saved) {
        var bound = analysis.getBindings().path("relationships");
        Map<UUID, RelationshipGraphEdge> owned = edges.findAllByWorkId(analysis.getWorkId()).stream()
                .collect(Collectors.toMap(RelationshipGraphEdge::getId, edge -> edge));
        Set<UUID> frozenEdges = new HashSet<>();
        for (var relationship : saved.relationships()) {
            UUID edgeId;
            try { edgeId = UUID.fromString(bound.path(relationship.ref()).asText()); }
            catch (IllegalArgumentException malformed) { throw new AppException(RELATIONSHIP_GRAPH_CONFLICT); }
            var edge = owned.get(edgeId);
            if (edge == null || edge.isSuppressed()
                    || edge.getCorrectedSummary() != null && !edge.getCorrectedSummary().equals(relationship.summary()))
                conflict();
            frozenEdges.add(edgeId);
        }
        Set<String> pairs = manualDependencyPairs(saved, analysis.getBindings());
        for (var edge : owned.values()) {
            // Legacy O-only context has no baseline for a later-created, manually changed edge.
            // Never silently adopt that edit while upgrading its opaque predecessor hash.
            if (!frozenEdges.contains(edge.getId())
                    && (edge.isSuppressed() || edge.getCorrectedSummary() != null)
                    && pairs.contains(RelationshipGraphRules.pair(
                            edge.getSourceNodeId().toString(), edge.getTargetNodeId().toString()))) conflict();
        }
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
        analysis.complete(mapper.toJson(request.decisions()), mapper.toJson(request.failures()), completionHash);
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
        var diagnostics = failureDiagnosticsSanitizer.sanitize(request.failureDiagnostics(), job.getWork().getId());
        analysis.fail(request.failureCode().name(), diagnostics == null ? null : mapper.toJson(diagnostics));
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
        // One node load serves event-name orientation, alias-slot suppression and profile linking.
        NodeIndex index =
                new NodeIndex(nodes.findAllByWorkIdOrderByFirstEpisodeNoAscIdAsc(job.getWork().getId()));
        // Apply this job's confirmed links/merges before its decisions. Linking reads only the
        // frozen context and node rows, never edges, so the outcome is unchanged; but an ADD on a
        // node that this job merges into a profile must already see that profile's deletions.
        linkConfirmedCharacters(job, analysis, context, index);
        Set<String> suppressedKeys = null;
        Map<String, ContextNode> contextNodes =
                context.nodes().stream().collect(Collectors.toMap(ContextNode::ref, n -> n));
        Map<String, ContextObservation> observations = new LinkedHashMap<>();
        context.observations().forEach(o -> observations.put(o.ref(), o));
        context.heldObservations().forEach(o -> observations.put(o.ref(), o));
        for (Decision decision : mapper.decisions(analysis)) {
            if (decision.operation() == Operation.HOLD || decision.operation() == Operation.EXCLUDE)
                continue;
            // Frozen bindings are applied as recorded (a stale alias-node id is not rewritten).
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
                var found = edges.findByWorkIdAndCanonicalKey(job.getWork().getId(), key);
                if (found.isEmpty() && index.hasAliases(source, target)) {
                    // A suppressed edge is absent from the context, so the model may ADD it again
                    // between the profile node and a partner. A user deletion recorded on a
                    // redirected node's edge must still block that same slot.
                    if (suppressedKeys == null)
                        suppressedKeys =
                                edges.findAllByWorkId(job.getWork().getId()).stream()
                                        .filter(RelationshipGraphEdge::isSuppressed)
                                        .map(RelationshipGraphEdge::getCanonicalKey)
                                        .collect(Collectors.toSet());
                    Set<String> deleted = suppressedKeys;
                    if (aliasSlotKeys(index, source, target, decision).stream()
                            .anyMatch(deleted::contains)) continue;
                }
                edge =
                        found.orElseGet(
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
            // An UPDATE may target an edge recorded on a redirected node while the decision
            // refers to its profile node; orient event names by the resolved endpoints.
            UUID edgeSource = index.canonicalOf(edge.getSourceNodeId()),
                    edgeTarget = index.canonicalOf(edge.getTargetNodeId());
            events.save(
                    mapper.toEvent(
                            analysis,
                            edge,
                            decision,
                            contextNodes
                                    .get(
                                            index.canonicalOf(source).equals(edgeSource)
                                                    ? decision.sourceNodeRef()
                                                    : decision.targetNodeRef())
                                    .name(),
                            contextNodes
                                    .get(
                                            index.canonicalOf(target).equals(edgeTarget)
                                                    ? decision.targetNodeRef()
                                                    : decision.sourceNodeRef())
                                    .name(),
                            evidence));
            edge.changed();
        }
        analysis.apply();
        // A late ordinary episode adds its own dated event. Later PRESENT decisions (including
        // MERGE) remain the decisions made for their episodes, not materialized predecessor state.
        if (job.getJobType() == AnalysisJobType.RELATIONSHIP_EXTRACTION) {
            for (var later : analyses.findAllByWorkIdOrderByEpisodeNoAscCreatedAtAscIdAsc(
                    job.getWork().getId())) {
                if (later.getGeneration() == analysis.getGeneration()
                        && later.getEpisodeNo() > analysis.getEpisodeNo()) later.invalidate();
            }
        }
    }

    private void linkConfirmedCharacters(
            AnalysisJob job, RelationshipGraphAnalysis analysis, Context context, NodeIndex index) {
        var confirmed =
                candidates.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(job.getId()).stream()
                        .filter(
                                c ->
                                        c.getReviewStatus()
                                                        == SettingCandidateReviewStatus.CONFIRMED
                                                && c.getMatchedCharacterId() != null)
                        .toList();
        // A redirected node is never linked again. A profile node counts from its earliest name.
        Map<UUID, RelationshipGraphNode> currentNodes =
                index.nodes().stream()
                        .filter(
                                n ->
                                        !n.isMerged()
                                                && index.visibleFrom(n.getId())
                                                        <= analysis.getEpisodeNo())
                        .collect(Collectors.toMap(RelationshipGraphNode::getId, n -> n));
        Map<String, Long> nameCounts =
                currentNodes.values().stream()
                        .collect(
                                Collectors.groupingBy(
                                        RelationshipGraphNode::getName, Collectors.counting()));
        Map<UUID, UUID> links = new LinkedHashMap<>();
        for (ContextNode visible : context.nodes()) {
            var node = currentNodes.get(nodeId(analysis, visible.ref()));
            if (node == null
                    || node.getCharacterId() != null
                    || nameCounts.getOrDefault(node.getName(), 0L) != 1) continue;
            Set<UUID> matches =
                    confirmed.stream()
                            .filter(c -> node.getName().equals(c.getEntityName()))
                            .map(c -> c.getMatchedCharacterId())
                            .collect(Collectors.toSet());
            JsonNode frozen =
                    analysis.getBindings()
                            .path("nodes")
                            .path(visible.ref())
                            .path("knownCharacterId");
            if (frozen.isTextual()) matches.add(UUID.fromString(frozen.asText()));
            if (matches.size() == 1) links.put(node.getId(), matches.iterator().next());
        }
        // Validate ownership in one query; never attach a stale/cross-work frozen identity.
        Set<UUID> owned =
                characters.findAllById(new HashSet<>(links.values())).stream()
                        .filter(c -> c.getWork().getId().equals(job.getWork().getId()))
                        .map(c -> c.getId())
                        .collect(Collectors.toSet());
        links.forEach(
                (nodeId, characterId) -> {
                    if (!owned.contains(characterId)) return;
                    RelationshipGraphNode node = currentNodes.get(nodeId);
                    List<RelationshipGraphNode> profile = index.canonicalNodesOf(characterId);
                    if (profile.isEmpty()) {
                        node.link(characterId, analysis.getEpisodeNo());
                    } else if (profile.size() == 1) {
                        // A second confirmed name of the same profile redirects to its node
                        // instead of linking a parallel node. Edges, events and names stay.
                        if (node.mergeInto(profile.getFirst().getId(), analysis.getEpisodeNo()))
                            index.merged(node);
                        else
                            log.warn("GH382 relationship node not merged workId={} characterId={}",
                                    job.getWork().getId(), characterId);
                    } else {
                        // Already split production data waits for the one-time cleanup.
                        log.warn("GH382 profile has several relationship nodes workId={} characterId={}",
                                job.getWork().getId(), characterId);
                    }
                });
    }

    private BuiltContext buildContext(AnalysisJob job, Prepare request) {
        return buildContext(job, request, true);
    }

    private BuiltContext buildContext(AnalysisJob job, Prepare request, boolean persistNodes) {
        UUID workId = job.getWork().getId();
        int cutoff = job.getSourceEpisodeNo();
        var allAnalyses = latestAnalyses(workId, job.getRelationshipGeneration());
        Map<UUID, RelationshipGraphEvent> prior = latestEvents(workId, cutoff - 1, allAnalyses);
        Map<UUID, RelationshipGraphEdge> allEdges =
                edges.findAllByWorkId(workId).stream()
                        .collect(Collectors.toMap(RelationshipGraphEdge::getId, e -> e));
        // Keep cutoff-safe provisional identities even before they produce an event.
        // Parallel/manual
        // jobs, internal HOLD and reanalysis must not manufacture duplicate same-name node IDs.
        List<RelationshipGraphNode> allNodes =
                nodes.findAllByWorkIdOrderByFirstEpisodeNoAscIdAsc(workId);
        NodeIndex index = new NodeIndex(allNodes);
        // Stored names stay matching keys. A redirected node's name resolves to its profile node,
        // which is therefore visible from the first episode in which any of its names is known.
        Map<String, List<RelationshipGraphNode>> allByName =
                allNodes.stream().collect(Collectors.groupingBy(RelationshipGraphNode::getName));
        Map<String, List<RelationshipGraphNode>> byName = new LinkedHashMap<>();
        for (var node : allNodes)
            if (node.getFirstEpisodeNo() <= cutoff)
                addDistinct(byName, node.getName(), index.canonicalNode(node));
        JsonNode input =
                job.isOrderedProvisional() ? runState.getInputState(job) : mapper.toJson(Map.of());
        // Ordered input contributes only representative names. Its alias strings carry no
        // per-alias episode, so aliases always come from the cutoff-bounded database query.
        Map<String, List<UUID>> known = new HashMap<>();
        for (JsonNode character : input.path("characters")) {
            if (character.path("actualCharacterId").isTextual())
                known.computeIfAbsent(character.path("name").asText(), x -> new ArrayList<>())
                        .add(UUID.fromString(character.path("actualCharacterId").asText()));
        }
        for (var identity : confirmedIdentities(job, request, cutoff)) {
            if (identity.getEntityName() != null)
                known.computeIfAbsent(identity.getEntityName(), x -> new ArrayList<>())
                        .add(identity.getCharacterId());
        }
        known.forEach(
                (name, identities) -> {
                    List<UUID> distinct = identities.stream().distinct().toList();
                    if (conflictsWithKnownIdentity(byName.getOrDefault(name, List.of()), distinct))
                        byName.put(name, List.of());
                });
        // A node reused under another confirmed name keeps that observed name in this context
        // until its own stored name is cutoff-safe. Stored node names are never rewritten.
        Map<UUID, String> contextNames = new HashMap<>();
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
                if (identities.size() == 1) {
                    // A name confirmed for exactly one profile by this cutoff (representative
                    // name or alias) reuses that profile's node instead of starting another one.
                    List<RelationshipGraphNode> profile =
                            index.canonicalNodesOf(identities.getFirst());
                    if (profile.size() > 1) {
                        // Already split data is not guessed into one node here.
                        byName.put(endpoint.name(), List.of());
                        continue;
                    }
                    if (profile.size() == 1) {
                        RelationshipGraphNode node = profile.getFirst();
                        List<RelationshipGraphNode> named =
                                allByName.getOrDefault(endpoint.name(), List.of()).stream()
                                        .filter(n -> index.canonicalOf(n.getId()).equals(node.getId()))
                                        .toList();
                        if (!named.isEmpty()) {
                            // This exact stored name of the profile becomes cutoff-safe here.
                            if (persistNodes) named.forEach(n -> n.observeAt(cutoff));
                        } else if (index.visibleFrom(node.getId()) > cutoff) {
                            // Record the alias as a redirect so later contexts at this cutoff
                            // still include the profile node, without its not-yet-known name.
                            var alias =
                                    RelationshipGraphNode.create(
                                            UUID.randomUUID(), workId, null, endpoint.name(), cutoff);
                            alias.mergeInto(node.getId(), cutoff);
                            if (persistNodes) alias = nodes.save(alias);
                            index.add(alias);
                        }
                        contextNames.putIfAbsent(node.getId(), endpoint.name());
                        byName.put(endpoint.name(), List.of(node));
                        continue;
                    }
                }
                // An earlier manual job may see a name first prepared by a later job. Reuse
                // only that explicitly observed name, without exposing other future identities
                // or moving the independently recorded profile-link episode into the past.
                List<RelationshipGraphNode> future =
                        allByName.getOrDefault(endpoint.name(), List.of());
                if (!future.isEmpty()) {
                    List<RelationshipGraphNode> resolved =
                            future.stream().map(index::canonicalNode).distinct().toList();
                    if (resolved.size() != 1 || conflictsWithKnownIdentity(resolved, identities)) {
                        byName.put(endpoint.name(), List.of());
                        continue;
                    }
                    RelationshipGraphNode node = resolved.getFirst();
                    // Move the stored rows carrying this name, never a profile node's other name.
                    if (persistNodes) future.forEach(n -> n.observeAt(cutoff));
                    contextNames.putIfAbsent(node.getId(), endpoint.name());
                    byName.put(endpoint.name(), List.of(node));
                    continue;
                }
                var node =
                        RelationshipGraphNode.create(
                                UUID.randomUUID(),
                                workId,
                                identities.size() == 1 ? identities.getFirst() : null,
                                endpoint.name(),
                                cutoff);
                if (persistNodes) node = nodes.save(node);
                index.add(node);
                byName.put(endpoint.name(), List.of(node));
            }
        Map<UUID, String> refs = new LinkedHashMap<>();
        Map<String, ContextNode> contextNodes = new LinkedHashMap<>();
        ObjectNode bindings = (ObjectNode) mapper.toJson(Map.of());
        ObjectNode nodeBindings = bindings.putObject("nodes");
        Map<String, Set<UUID>> knownByRef = new LinkedHashMap<>();
        for (var group : byName.entrySet())
            for (var node : group.getValue()) {
                // A profile node can be reached through several confirmed names; it gets one ref.
                String ref = refs.get(node.getId());
                if (ref == null) {
                    ref = "N" + (contextNodes.size() + 1);
                    refs.put(node.getId(), ref);
                    // Prior edges recorded on a redirected node project onto the same ref.
                    for (var member : index.members(node.getId()))
                        refs.putIfAbsent(member.getId(), ref);
                    String name =
                            node.getFirstEpisodeNo() <= cutoff
                                    ? node.getName()
                                    : contextNames.get(node.getId());
                    if (name == null)
                        name = Objects.requireNonNullElse(index.nameAt(node, cutoff), node.getName());
                    contextNodes.put(
                            ref,
                            new ContextNode(
                                    ref,
                                    name,
                                    node.getCharacterId() != null
                                            && node.getCharacterLinkedEpisodeNo() != null
                                            && node.getCharacterLinkedEpisodeNo() <= cutoff));
                    nodeBindings.putObject(ref).put("id", node.getId().toString());
                }
                List<UUID> identities =
                        known.getOrDefault(group.getKey(), List.of()).stream().distinct().toList();
                if (group.getValue().size() == 1 && identities.size() == 1)
                    knownByRef.computeIfAbsent(ref, x -> new java.util.LinkedHashSet<>())
                            .add(identities.getFirst());
            }
        knownByRef.forEach(
                (ref, identities) -> {
                    if (identities.size() == 1)
                        ((ObjectNode) nodeBindings.get(ref))
                                .put("knownCharacterId", identities.iterator().next().toString());
                });
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
        Set<String> relevantPairs = observations.stream().map(RelationshipGraphRules::pair)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        boolean pairScoped = job.isRelationshipPartialResultsEnabled();
        ObjectNode relationBindings = bindings.putObject("relationships");
        List<ContextRelationship> relationships = new ArrayList<>();
        Set<UUID> sourceDependencies = new HashSet<>();
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
            // An old edge between a redirected node and its own profile node is not a pair.
            if (source == null
                    || target == null
                    || source.equals(target)
                    || (pairScoped
                            ? !relevantPairs.contains(RelationshipGraphRules.pair(source, target))
                            : !relevant.contains(source) && !relevant.contains(target))) continue;
            String ref = "R" + (relationships.size() + 1);
            relationBindings.put(ref, edge.getId().toString());
            sourceDependencies.add(event.getAnalysisId());
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
                        new ContextObservation(
                                ref,
                                original.source(),
                                original.target(),
                                original.summary(),
                                original.evidence(),
                                heldEndpointRefs(original.source(), original.sourceNodeRefs(),
                                        previous.getBindings(), byName, refs, index),
                                heldEndpointRefs(original.target(), original.targetNodeRefs(),
                                        previous.getBindings(), byName, refs, index),
                                previous.getEpisodeNo());
                // Unresolved and other-pair HOLDs remain in their original analysis. They do not
                // consume this pair's input budget or become decisions of an unrelated group.
                if (pairScoped) {
                    if (!relevantPairs.contains(RelationshipGraphRules.pair(mapped))) continue;
                } else if (java.util.stream.Stream.concat(
                                mapped.sourceNodeRefs().stream(), mapped.targetNodeRefs().stream())
                        .noneMatch(relevant::contains)) continue;
                held.add(mapped);
                sourceDependencies.add(previous.getId());
                ObjectNode origin = heldBindings.putObject(ref);
                origin.put("episodeId", previous.getEpisodeId().toString());
                origin.put("episodeNo", previous.getEpisodeNo());
                origin.put("analysisId", previous.getId().toString());
                origin.put("observationRef", oldRef);
            }
        }
        // Old servers still read the broad hash during rolling deployment/rollback. New servers
        // use the separately versioned pair hash; existing frozen contexts retain their old scope.
        bindings.put("dependencyRevision", dependencyRevision(workId, cutoff, job.getRelationshipGeneration(), null));
        if (pairScoped) {
            bindings.put("dependencyPairScopeVersion", 1);
            bindings.put("pairDependencyRevision", dependencyRevision(workId, cutoff,
                    job.getRelationshipGeneration(), stableComparisonPairs(observations, bindings)));
        }
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
        List<BlockedPair> blockedPairs = new ArrayList<>();
        Map<String, Integer> failedPairs =
                failedPairEpisodes(allAnalyses, cutoff - 1, validPriorIds, index);
        for (var observation : observations) {
            if (RelationshipGraphRules.pair(observation) == null) continue;
            String sourceRef = observation.sourceNodeRefs().getFirst(), targetRef = observation.targetNodeRefs().getFirst();
            String key = RelationshipGraphRules.pair(nodeBindings.path(sourceRef).path("id").asText(),
                    nodeBindings.path(targetRef).path("id").asText());
            Integer first = failedPairs.get(key);
            if (first != null && blockedPairs.stream().noneMatch(b -> RelationshipGraphRules.pair(
                    b.sourceNodeRef(), b.targetNodeRef()).equals(RelationshipGraphRules.pair(sourceRef, targetRef))))
                blockedPairs.add(new BlockedPair(sourceRef, targetRef, first));
        }
        // An old Worker cannot safely reason over a prefix with a missing pair update.
        if (!job.isRelationshipPartialResultsEnabled() && !blockedPairs.isEmpty()) conflict();
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
                        List.copyOf(held),
                        job.isRelationshipPartialResultsEnabled(),
                        List.copyOf(blockedPairs));
        Set<String> dependencyPairs = Set.of();
        if (job.getJobType() == AnalysisJobType.SETTING_EXTRACTION) {
            dependencyPairs = manualDependencyPairs(context, bindings);
            bindings.put("manualDependencyVersion", 1);
            bindings.put("manualDependencyRevision", manualDependencyRevision(workId, dependencyPairs));
            var sourceIds = bindings.putArray("sourceDependencies");
            sourceDependencies.stream().sorted().forEach(id -> sourceIds.add(id.toString()));
        } else if (pairScoped) {
            dependencyPairs = stableComparisonPairs(observations, bindings);
        }
        captureAliasDependencies(workId, bindings, dependencyPairs, allEdges.values(), index);
        return new BuiltContext(context, bindings);
    }

    /**
     * GH382: a context projects an alias-node edge onto its profile node's ref, so its dependency
     * pairs name the profile node while the existing revisions filter edges by raw endpoints.
     * Capture those raw alias-edge pairs and their user edits in separate fields. The existing
     * fields, hashes frozen before this field existed, and old-server rollback stay unchanged;
     * contexts without a redirected edge get no field at all.
     */
    private void captureAliasDependencies(UUID workId, ObjectNode bindings, Set<String> dependencyPairs,
            java.util.Collection<RelationshipGraphEdge> workEdges, NodeIndex index) {
        if (dependencyPairs.isEmpty()) return;
        Set<String> aliasPairs = new java.util.TreeSet<>();
        for (var edge : workEdges) {
            String source = edge.getSourceNodeId().toString(), target = edge.getTargetNodeId().toString();
            String raw = RelationshipGraphRules.pair(source, target);
            String resolved = RelationshipGraphRules.pair(index.resolvedId(source), index.resolvedId(target));
            if (!raw.equals(resolved) && dependencyPairs.contains(resolved)) aliasPairs.add(raw);
        }
        if (aliasPairs.isEmpty()) return;
        bindings.put("aliasDependencyVersion", 1);
        var captured = bindings.putArray("aliasDependencyPairs");
        aliasPairs.forEach(captured::add);
        bindings.put("aliasDependencyRevision", manualDependencyRevision(workId, aliasPairs));
    }

    /** Rechecks user edits on the captured alias-edge pairs; absent for legacy/alias-free contexts. */
    private void assertAliasDependencyRevision(RelationshipGraphAnalysis analysis) {
        JsonNode bindings = analysis.getBindings();
        JsonNode version = bindings.path("aliasDependencyVersion");
        if (version.isMissingNode()) return;
        JsonNode pairs = bindings.path("aliasDependencyPairs");
        if (!version.isInt() || version.intValue() != 1 || !pairs.isArray() || pairs.isEmpty()
                || !bindings.path("aliasDependencyRevision").isTextual()) conflict();
        Set<String> captured = new HashSet<>();
        for (JsonNode pair : pairs) {
            if (!pair.isTextual()) conflict();
            captured.add(pair.textValue());
        }
        if (!Objects.equals(bindings.path("aliasDependencyRevision").textValue(),
                manualDependencyRevision(analysis.getWorkId(), captured))) conflict();
    }

    /**
     * Confirmed name-to-profile decisions known by the reader cutoff, read as scalars once.
     * Ordinary analysis reads only character-discovery names observed in this request, without
     * per-candidate job/purge hydration. Relationship backfill keeps its historical meaning:
     * every confirmed kind, but only when the creating job's source still matches the episode.
     */
    private List<ConfirmedCharacterIdentityRow> confirmedIdentities(
            AnalysisJob job, Prepare request, int cutoff) {
        UUID workId = job.getWork().getId();
        if (job.getJobType() == AnalysisJobType.RELATIONSHIP_EXTRACTION)
            return candidates.findVerifiedConfirmedIdentities(workId, cutoff);
        Set<String> names = new java.util.LinkedHashSet<>();
        for (Observation observation : request.observations())
            for (Endpoint endpoint : List.of(observation.source(), observation.target()))
                if (endpoint.name() != null) names.add(endpoint.name());
        if (names.isEmpty()) return List.of();
        return candidates.findConfirmedIdentities(
                workId, List.of(SettingCandidateKind.CHARACTER_DISCOVERY), names, cutoff);
    }

    /**
     * Persisted N refs are local to a context. Cross-episode dependencies use actual node IDs,
     * resolved through redirects so a failed alias-node pair still blocks its profile-node pair.
     */
    private Map<String, Integer> failedPairEpisodes(Map<Integer, RelationshipGraphAnalysis> all,
            int cutoff, Set<UUID> valid, NodeIndex index) {
        Map<String, Integer> result = new HashMap<>();
        for (var analysis : all.values()) {
            if (analysis.getEpisodeNo() > cutoff || !valid.contains(analysis.getId()) || !analysis.hasFailures()) continue;
            Context context = mapper.toContext(analysis);
            Map<String, ContextObservation> observations = context.observations().stream()
                    .collect(Collectors.toMap(ContextObservation::ref, value -> value));
            for (var failure : mapper.failures(analysis)) {
                for (String ref : failure.observationRefs()) {
                    var observation = observations.get(ref);
                    if (observation == null || RelationshipGraphRules.pair(observation) == null) conflict();
                    String source = analysis.getBindings().path("nodes")
                            .path(observation.sourceNodeRefs().getFirst()).path("id").asText();
                    String target = analysis.getBindings().path("nodes")
                            .path(observation.targetNodeRefs().getFirst()).path("id").asText();
                    // A damaged persisted binding is not authority to advance the dependent pair.
                    try { UUID.fromString(source); UUID.fromString(target); }
                    catch (IllegalArgumentException malformed) { conflict(); }
                    result.merge(RelationshipGraphRules.pair(index.resolvedId(source), index.resolvedId(target)),
                            analysis.getEpisodeNo(), Math::min);
                }
            }
        }
        return result;
    }

    private boolean conflictsWithKnownIdentity(
            List<RelationshipGraphNode> candidates, List<UUID> identities) {
        return identities.size() > 1
                || identities.size() == 1
                        && candidates.stream()
                                .map(RelationshipGraphNode::getCharacterId)
                                .filter(Objects::nonNull)
                                .anyMatch(id -> !id.equals(identities.getFirst()));
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

    /**
     * A held endpoint first resolves by name, as before. A name this context does not know at all
     * (an alias that reused an already visible profile node, which leaves no stored row and is
     * not re-queried unless observed again) falls back to the node the original analysis bound,
     * when that node is in this context. A name this context deliberately holds stays empty.
     */
    private List<String> heldEndpointRefs(
            Endpoint endpoint,
            List<String> originalRefs,
            JsonNode originalBindings,
            Map<String, List<RelationshipGraphNode>> byName,
            Map<UUID, String> refs,
            NodeIndex index) {
        List<String> named = endpointRefs(endpoint, byName, refs);
        if (!named.isEmpty()
                || endpoint.name() == null
                || byName.containsKey(endpoint.name())
                || originalRefs == null
                || originalRefs.size() != 1
                || originalBindings == null) return named;
        String bound = originalBindings.path("nodes").path(originalRefs.getFirst()).path("id").asText();
        try {
            String ref = refs.get(index.canonicalOf(UUID.fromString(bound)));
            return ref == null ? List.of() : List.of(ref);
        } catch (IllegalArgumentException malformed) {
            return List.of();
        }
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
        int verificationCutoff = episodeNo != null ? episodeNo : read.analyses().keySet().stream()
                .mapToInt(Integer::intValue).max().orElse(cutoff);
        Map<String, Integer> failedPairs =
                failedPairEpisodes(read.analyses(), verificationCutoff, read.validIds(), read.index());
        List<Edge> output = new ArrayList<>();
        Map<UUID, Node> outputNodes = new LinkedHashMap<>();
        // Edges stay physically on their recorded nodes; output endpoints are the resolved
        // profile nodes. An old edge between an alias node and its own profile node is hidden.
        List<RelationshipGraphEvent> visible =
                latest.values().stream()
                        .filter(e -> {
                            var edge = read.edges().get(e.getEdgeId());
                            return !edge.isSuppressed() && !read.index().selfLoop(edge);
                        })
                        .toList();
        for (var event : visible.stream().limit(200).toList()) {
            var edge = read.edges().get(event.getEdgeId());
            UUID source = read.index().canonicalOf(edge.getSourceNodeId()),
                    target = read.index().canonicalOf(edge.getTargetNodeId());
            var progress = pairProgress(read, source, target, verificationCutoff, failedPairs);
            output.add(
                    mapper.toEdge(
                            edge, event, evidence.getOrDefault(edge.getId(), List.of()).size(),
                            progress.lastVerifiedEpisodeNo(), progress.updateIncomplete(),
                            source, target));
            outputNodes.put(
                    source,
                    displayNode(read, source, event.getSourceName(), cutoff));
            outputNodes.put(
                    target,
                    displayNode(read, target, event.getTargetName(), cutoff));
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
                                                    || a.hasFailures()
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
        List<Integer> incompleteEpisodes = considered.stream()
                .filter(a -> a.getStatus() == AnalysisStatus.FAILED || a.getStatus() == AnalysisStatus.INVALIDATED
                        || a.getStatus() == AnalysisStatus.APPLIED && (!read.validIds().contains(a.getId())
                            || a.hasFailures() || a.getRejectedObservationCount() > 0))
                .map(RelationshipGraphAnalysis::getEpisodeNo).distinct().sorted().toList();
        String revision =
                hash(
                        mapper.toJson(
                                List.of(
                                        output,
                                        List.copyOf(outputNodes.values()),
                                        available,
                                        status,
                                        incompleteEpisodes)));
        return new Graph(
                selected,
                available,
                revision,
                List.copyOf(outputNodes.values()),
                List.copyOf(output),
                visible.size() > 200,
                visible.size(),
                status,
                partial,
                !incompleteEpisodes.isEmpty(),
                incompleteEpisodes);
    }

    private record PairProgress(int lastVerifiedEpisodeNo, boolean updateIncomplete) {}

    /** {@code source}/{@code target} are the resolved profile nodes of the edge. */
    private PairProgress pairProgress(ReadState read, UUID source, UUID target, int cutoff,
            Map<String, Integer> failedPairs) {
        String pair = RelationshipGraphRules.pair(source.toString(), target.toString());
        Integer pairFailure = failedPairs.get(pair);
        int verified = 0;
        boolean incomplete = false, advancing = true;
        for (var analysis : read.analyses().values()) {
            if (analysis.getEpisodeNo() > cutoff) continue;
            boolean gap = analysis.getStatus() == AnalysisStatus.FAILED
                    || analysis.getStatus() == AnalysisStatus.INVALIDATED
                    || analysis.getStatus() == AnalysisStatus.APPLIED && (!read.validIds().contains(analysis.getId())
                        || analysis.getRejectedObservationCount() > 0)
                    || pairFailure != null && analysis.getEpisodeNo() >= pairFailure;
            if (gap) { incomplete = true; advancing = false; }
            if (analysis.getStatus() != AnalysisStatus.APPLIED) advancing = false;
            if (advancing) verified = Math.max(verified, analysis.getEpisodeNo());
        }
        return new PairProgress(verified, incomplete);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail getRelationshipEdge(Long memberId, UUID workId, UUID edgeId, Integer episodeNo) {
        works.getOwnedWork(workId, memberId);
        ReadState read = readState(workId);
        Graph graph = graph(read, episodeNo);
        if (graph.selectedEpisode() == null) throw new AppException(RELATIONSHIP_GRAPH_NOT_FOUND);
        var edge = read.edges().get(edgeId);
        if (edge == null || edge.isSuppressed() || read.index().selfLoop(edge))
            throw new AppException(RELATIONSHIP_GRAPH_NOT_FOUND);
        int cutoff = graph.selectedEpisode().episodeNo();
        var event = latestEvents(read, cutoff).get(edgeId);
        if (event == null) throw new AppException(RELATIONSHIP_GRAPH_NOT_FOUND);
        List<PublicEvidence> evidence = evidence(read, cutoff).getOrDefault(edgeId, List.of());
        int verificationCutoff = episodeNo != null ? episodeNo : read.analyses().keySet().stream()
                .mapToInt(Integer::intValue).max().orElse(cutoff);
        UUID source = read.index().canonicalOf(edge.getSourceNodeId()),
                target = read.index().canonicalOf(edge.getTargetNodeId());
        var progress = pairProgress(read, source, target, verificationCutoff,
                failedPairEpisodes(read.analyses(), verificationCutoff, read.validIds(), read.index()));
        return new Detail(
                mapper.toEdge(edge, event, evidence.size(), progress.lastVerifiedEpisodeNo(),
                        progress.updateIncomplete(), source, target),
                displayNode(read, source, event.getSourceName(), cutoff),
                displayNode(read, target, event.getTargetName(), cutoff),
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
        NodeIndex index = new NodeIndex(nodes.findAllByWorkIdOrderByFirstEpisodeNoAscIdAsc(workId));
        return new ReadState(
                all,
                valid,
                events.findAllByWorkIdAndEpisodeNoLessThanEqualOrderByEpisodeNoAscCreatedAtAscIdAsc(
                        workId, Integer.MAX_VALUE),
                edges.findAllByWorkId(workId).stream()
                        .collect(Collectors.toMap(RelationshipGraphEdge::getId, e -> e)),
                index,
                profileNames(workId, index),
                episodeMap);
    }

    /**
     * Current profile names of linked profile nodes, read once as scalars in the same snapshot.
     * Archived or deleted profiles are absent, so those nodes fall back to their observed name.
     */
    private Map<UUID, String> profileNames(UUID workId, NodeIndex index) {
        Set<UUID> linked = index.nodes().stream()
                .filter(n -> !n.isMerged() && n.getCharacterId() != null)
                .map(RelationshipGraphNode::getCharacterId)
                .collect(Collectors.toSet());
        if (linked.isEmpty()) return Map.of();
        return characters.findNamesByWorkIdAndStatusAndIdIn(workId, CharacterStatus.ACTIVE, linked).stream()
                .collect(Collectors.toMap(WorkCharacterNameRow::getId, WorkCharacterNameRow::getName));
    }

    /**
     * A node registered to a profile by the selected episode shows the current profile name;
     * any other node keeps the name observed up to that episode. Stored names are not changed.
     */
    private Node displayNode(ReadState read, UUID nodeId, String observedName, int cutoff) {
        RelationshipGraphNode node = read.index().node(nodeId);
        boolean registered = node.getCharacterId() != null
                && node.getCharacterLinkedEpisodeNo() != null
                && node.getCharacterLinkedEpisodeNo() <= cutoff;
        String profileName = registered ? read.profileNames().get(node.getCharacterId()) : null;
        return mapper.toNode(node, profileName == null ? observedName : profileName, cutoff);
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
            NodeIndex index,
            Map<UUID, String> profileNames,
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
        return latestAnalyses(
                workId,
                backfillWorks
                        .findById(workId)
                        .map(
                                org.monitoring.catchholebackend.domain.relationship.entity
                                                .RelationshipBackfillWork
                                        ::getPublishedGeneration)
                        .orElse(0L));
    }

    private Map<Integer, RelationshipGraphAnalysis> latestAnalyses(UUID workId, long generation) {
        Map<Integer, RelationshipGraphAnalysis> result = new TreeMap<>();
        analyses.findAllByWorkIdOrderByEpisodeNoAscCreatedAtAscIdAsc(workId).stream()
                .filter(a -> a.getGeneration() == generation)
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
        boolean privateBuild = !all.isEmpty() && backfillWorks.findById(all.values().iterator().next().getWorkId())
                .map(state -> state.active()
                        && state.getBuildingGeneration() == all.values().iterator().next().getGeneration()
                        && state.getPublishedGeneration() != state.getBuildingGeneration()).orElse(false);
        int invalidFrom =
                all.values().stream()
                        .filter(
                                a ->
                                        privateBuild && a.getStatus() == AnalysisStatus.INVALIDATED
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
                && jobSources.findSourceById(analysis.getId())
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
        all.values().stream().findFirst().ifPresent(analysis ->
                result.addAll(holdClosures.findClosedKeysByWorkId(analysis.getWorkId())));
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

    private Set<String> stableComparisonPairs(List<ContextObservation> observations, JsonNode bindings) {
        Set<String> result = new HashSet<>();
        for (var observation : observations) {
            if (RelationshipGraphRules.pair(observation) == null) continue;
            String source = bindings.path("nodes").path(observation.sourceNodeRefs().getFirst()).path("id").asText();
            String target = bindings.path("nodes").path(observation.targetNodeRefs().getFirst()).path("id").asText();
            try { UUID.fromString(source); UUID.fromString(target); }
            catch (IllegalArgumentException malformed) { conflict(); }
            result.add(RelationshipGraphRules.pair(source, target));
        }
        return result;
    }

    private String dependencyRevision(UUID workId, int cutoff, long generation, Set<String> comparisonPairs) {
        List<Object> revision = new ArrayList<>();
        for (var a : latestAnalyses(workId, generation).values())
            if (a.getEpisodeNo() < cutoff) {
                revision.add(
                        List.of(
                                a.getId().toString(),
                                a.getStatus() == AnalysisStatus.APPLIED,
                                sourceAvailable(a)));
            }
        // Partial comparison depends only on corrections/deletions of its resolved pairs.
        // Include suppressed/other-generation edges of those pairs too: edge identity and user
        // suppression are shared across generations. Legacy frozen hashes retain the broad scope.
        Set<UUID> relevantEdges =
                events
                        .findAllByWorkIdAndEpisodeNoLessThanEqualOrderByEpisodeNoAscCreatedAtAscIdAsc(
                                workId, cutoff)
                        .stream()
                        .map(RelationshipGraphEvent::getEdgeId)
                        .collect(Collectors.toSet());
        edges.findAllByWorkId(workId).stream()
                .filter(e -> relevantEdges.contains(e.getId()))
                .filter(e -> comparisonPairs == null || comparisonPairs.contains(
                        RelationshipGraphRules.pair(e.getSourceNodeId().toString(), e.getTargetNodeId().toString())))
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
        assertAliasDependencyRevision(analysis);
        JsonNode manualVersion = analysis.getBindings().path("manualDependencyVersion");
        if (!manualVersion.isMissingNode()) {
            if (!manualVersion.isInt() || manualVersion.intValue() != 1
                    || !analysis.getBindings().path("manualDependencyRevision").isTextual()) conflict();
            assertFrozenSourcesCurrent(analysis);
            String actual = manualDependencyRevision(analysis.getWorkId(),
                    manualDependencyPairs(mapper.toContext(analysis), analysis.getBindings()));
            if (!Objects.equals(analysis.getBindings().path("manualDependencyRevision").textValue(), actual))
                conflict();
            return;
        }
        JsonNode scope = analysis.getBindings().path("dependencyPairScopeVersion");
        Set<String> comparisonPairs = null;
        String revisionField = "dependencyRevision";
        if (!scope.isMissingNode()) {
            if (!scope.isInt() || scope.intValue() != 1 || !mapper.toContext(analysis).supportsPartialResults()) conflict();
            revisionField = "pairDependencyRevision";
            if (!analysis.getBindings().path(revisionField).isTextual()) conflict();
            comparisonPairs = stableComparisonPairs(mapper.toContext(analysis).observations(), analysis.getBindings());
        }
        if (!Objects.equals(
                analysis.getBindings().path(revisionField).asText(),
                dependencyRevision(
                        analysis.getWorkId(), analysis.getEpisodeNo(), analysis.getGeneration(), comparisonPairs)))
            conflict();
    }

    private Set<String> manualDependencyPairs(Context context, JsonNode bindings) {
        Set<String> result = stableComparisonPairs(context.observations(), bindings);
        result.addAll(stableComparisonPairs(context.heldObservations(), bindings));
        // Legacy non-partial contexts also contain neighboring relationships, not only O's pair.
        for (var relationship : context.relationships()) {
            String source = bindings.path("nodes").path(relationship.sourceNodeRef()).path("id").asText();
            String target = bindings.path("nodes").path(relationship.targetNodeRef()).path("id").asText();
            try { UUID.fromString(source); UUID.fromString(target); }
            catch (IllegalArgumentException malformed) { throw new AppException(RELATIONSHIP_GRAPH_CONFLICT); }
            result.add(RelationshipGraphRules.pair(source, target));
        }
        return result;
    }

    private String manualDependencyRevision(UUID workId, Set<String> pairs) {
        // Automatic event creation/version increments are intentionally absent. Human corrections
        // and suppression apply to this edge across all generations and all reader cutoffs.
        var revision = edges.findAllByWorkId(workId).stream()
                .filter(edge -> edge.isSuppressed() || edge.getCorrectedSummary() != null)
                .filter(edge -> pairs.contains(RelationshipGraphRules.pair(
                        edge.getSourceNodeId().toString(), edge.getTargetNodeId().toString())))
                .sorted(Comparator.comparing(RelationshipGraphEdge::getId))
                .map(edge -> List.of(edge.getId().toString(),
                        edge.getCorrectedSummary() == null ? "" : edge.getCorrectedSummary(), edge.isSuppressed()))
                .toList();
        return hash(mapper.toJson(revision));
    }

    private void assertFrozenSourcesCurrent(RelationshipGraphAnalysis analysis) {
        JsonNode sourceIds = analysis.getBindings().path("sourceDependencies");
        if (!sourceIds.isArray()) conflict();
        Set<UUID> ids = new HashSet<>();
        for (JsonNode value : sourceIds) {
            try { ids.add(UUID.fromString(value.asText())); }
            catch (IllegalArgumentException malformed) { throw new AppException(RELATIONSHIP_GRAPH_CONFLICT); }
        }
        if (!ids.isEmpty() && analyses.countCurrentSources(ids, analysis.getWorkId(),
                analysis.getGeneration(), analysis.getEpisodeNo()) != ids.size()) conflict();
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
        if ((job.getJobType() != AnalysisJobType.SETTING_EXTRACTION
                        && job.getJobType() != AnalysisJobType.RELATIONSHIP_EXTRACTION)
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
                || analysis.getGeneration() != job.getRelationshipGeneration()
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

    private static void addDistinct(
            Map<String, List<RelationshipGraphNode>> byName, String name, RelationshipGraphNode node) {
        var group = byName.computeIfAbsent(name, x -> new ArrayList<>());
        if (group.stream().noneMatch(n -> n.getId().equals(node.getId()))) group.add(node);
    }

    /** Slot keys of the same decision recorded on any redirected node of either endpoint. */
    private Set<String> aliasSlotKeys(NodeIndex index, UUID source, UUID target, Decision decision) {
        Set<String> keys = new HashSet<>();
        for (var from : index.members(index.canonicalOf(source)))
            for (var to : index.members(index.canonicalOf(target)))
                if (!from.getId().equals(to.getId()))
                    keys.add(
                            storage.contentHash(
                                    RelationshipGraphRules.canonical(
                                            from.getId().toString(),
                                            to.getId().toString(),
                                            decision.axis(),
                                            decision.kind(),
                                            decision.direction())));
        return keys;
    }

    /**
     * A work's nodes with GH382 redirects resolved in memory, built from the single node load the
     * caller already performs. A redirected node keeps its row; reads use its profile node.
     */
    private static final class NodeIndex {
        private static final int MAX_REDIRECT_DEPTH = 8;
        private final Map<UUID, RelationshipGraphNode> byId = new LinkedHashMap<>();
        private final Map<UUID, UUID> canonical = new HashMap<>();
        private final Map<UUID, List<RelationshipGraphNode>> members = new HashMap<>();

        NodeIndex(List<RelationshipGraphNode> nodes) {
            nodes.forEach(node -> byId.put(node.getId(), node));
            byId.values().forEach(this::register);
        }

        java.util.Collection<RelationshipGraphNode> nodes() {
            return byId.values();
        }

        RelationshipGraphNode node(UUID id) {
            return byId.get(id);
        }

        void add(RelationshipGraphNode node) {
            byId.put(node.getId(), node);
            register(node);
        }

        /** Re-resolves a node redirected in this transaction together with anything resolving to it. */
        void merged(RelationshipGraphNode node) {
            List<RelationshipGraphNode> moved = members.remove(node.getId());
            UUID root = resolve(node.getId());
            for (var member : moved == null ? List.of(node) : moved) {
                canonical.put(member.getId(), root);
                members.computeIfAbsent(root, x -> new ArrayList<>()).add(member);
            }
        }

        UUID canonicalOf(UUID id) {
            return canonical.getOrDefault(id, id);
        }

        /** Keeps an unredirected id's original text so legacy dependency keys are unchanged. */
        String resolvedId(String id) {
            UUID raw = UUID.fromString(id);
            UUID resolved = canonicalOf(raw);
            return resolved.equals(raw) ? id : resolved.toString();
        }

        RelationshipGraphNode canonicalNode(RelationshipGraphNode node) {
            return byId.get(canonicalOf(node.getId()));
        }

        boolean selfLoop(RelationshipGraphEdge edge) {
            return canonicalOf(edge.getSourceNodeId()).equals(canonicalOf(edge.getTargetNodeId()));
        }

        boolean hasAliases(UUID source, UUID target) {
            return members(canonicalOf(source)).size() > 1 || members(canonicalOf(target)).size() > 1;
        }

        /** The profile's unredirected nodes across the work; more than one is split data. */
        List<RelationshipGraphNode> canonicalNodesOf(UUID characterId) {
            return byId.values().stream()
                    .filter(n -> characterId.equals(n.getCharacterId()) && !n.isMerged())
                    .toList();
        }

        /** The node itself and every node redirected to it. */
        List<RelationshipGraphNode> members(UUID canonicalId) {
            return members.getOrDefault(canonicalId, List.of());
        }

        /** The first episode in which any name of this profile node is known. */
        int visibleFrom(UUID canonicalId) {
            return members(canonicalId).stream()
                    .mapToInt(RelationshipGraphNode::getFirstEpisodeNo)
                    .min()
                    .orElse(byId.get(canonicalId).getFirstEpisodeNo());
        }

        /** The node's own name once known by the cutoff, else the latest alias known by then. */
        String nameAt(RelationshipGraphNode node, int cutoff) {
            if (node.getFirstEpisodeNo() <= cutoff) return node.getName();
            return members(node.getId()).stream()
                    .filter(n -> n.getFirstEpisodeNo() <= cutoff)
                    .max(Comparator.comparingInt(RelationshipGraphNode::getFirstEpisodeNo)
                            .thenComparing(RelationshipGraphNode::getId, Comparator.reverseOrder()))
                    .map(RelationshipGraphNode::getName)
                    .orElse(null);
        }

        private void register(RelationshipGraphNode node) {
            UUID root = resolve(node.getId());
            canonical.put(node.getId(), root);
            members.computeIfAbsent(root, x -> new ArrayList<>()).add(node);
        }

        private UUID resolve(UUID id) {
            UUID current = id;
            for (int depth = 0; ; depth++) {
                RelationshipGraphNode node = byId.get(current);
                // A redirect is same-work and acyclic; anything else is damaged data, not a guess.
                if (node == null || depth > MAX_REDIRECT_DEPTH) conflict();
                if (node.getMergedIntoNodeId() == null) return current;
                current = node.getMergedIntoNodeId();
            }
        }
    }
}
