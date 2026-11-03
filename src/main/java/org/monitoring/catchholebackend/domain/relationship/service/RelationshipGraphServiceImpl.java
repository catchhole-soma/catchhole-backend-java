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

import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceRepository;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisCandidateSourceGuard;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobLeaseService;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
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
        linkConfirmedCharacters(job, analysis, context);
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
            AnalysisJob job, RelationshipGraphAnalysis analysis, Context context) {
        var confirmed =
                candidates.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(job.getId()).stream()
                        .filter(
                                c ->
                                        c.getReviewStatus()
                                                        == SettingCandidateReviewStatus.CONFIRMED
                                                && c.getMatchedCharacterId() != null)
                        .toList();
        Map<UUID, RelationshipGraphNode> currentNodes =
                nodes.findAllByWorkIdOrderByFirstEpisodeNoAscIdAsc(job.getWork().getId()).stream()
                        .filter(n -> n.getFirstEpisodeNo() <= analysis.getEpisodeNo())
                        .collect(Collectors.toMap(RelationshipGraphNode::getId, n -> n));
        Map<String, Long> nameCounts =
                currentNodes.values().stream()
                        .collect(
                                Collectors.groupingBy(
                                        RelationshipGraphNode::getName, Collectors.counting()));
        Map<UUID, UUID> links = new HashMap<>();
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
                    if (owned.contains(characterId))
                        currentNodes.get(nodeId).link(characterId, analysis.getEpisodeNo());
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
        Map<String, List<RelationshipGraphNode>> allByName =
                allNodes.stream().collect(Collectors.groupingBy(RelationshipGraphNode::getName));
        List<RelationshipGraphNode> existing =
                allNodes.stream().filter(n -> n.getFirstEpisodeNo() <= cutoff).toList();
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
        if (job.getJobType() == AnalysisJobType.RELATIONSHIP_EXTRACTION) {
            // Only episode-provenance names known by the selected reader cutoff. Current names,
            // book facts with unknown narrative time, and future chapters never enter this input.
            var historical =
                    entityManager
                            .createQuery(
                                    "select c from SettingCandidate c join fetch c.episode e where"
                                        + " c.work.id=:work and c.reviewStatus=:confirmed and"
                                        + " c.matchedCharacterId is not null and"
                                        + " e.episodeNo<=:cutoff and e.status<>:archived and exists"
                                        + " (select ch.id from WorkCharacter ch where"
                                        + " ch.id=c.matchedCharacterId and ch.work.id=:work)",
                                    org.monitoring.catchholebackend.domain.character.entity
                                            .SettingCandidate.class)
                            .setParameter("work", workId)
                            .setParameter("confirmed", SettingCandidateReviewStatus.CONFIRMED)
                            .setParameter("cutoff", cutoff)
                            .setParameter("archived", EpisodeStatus.ARCHIVED)
                            .getResultList();
            for (var candidate : historical) {
                if (candidate.getAnalysisJob() == null
                        || candidate.getEntityName() == null
                        || candidate.getAnalysisJob().getEpisode() == null
                        || !Objects.equals(candidate.getAnalysisJob().getWork().getId(), workId)
                        || !Objects.equals(
                                candidate.getAnalysisJob().getEpisode().getId(),
                                candidate.getEpisode().getId())
                        || !Objects.equals(
                                candidate.getAnalysisJob().getSourceEpisodeNo(),
                                candidate.getEpisode().getEpisodeNo())
                        || !Objects.equals(
                                candidate.getAnalysisJob().getSourceContentHash(),
                                candidate.getEpisode().getContentHash())
                        || !Objects.equals(
                                candidate.getAnalysisJob().getSourceContentS3Key(),
                                candidate.getEpisode().getContentS3Key())
                        || !Objects.equals(
                                candidate.getAnalysisJob().getSourceContentS3Version(),
                                candidate.getEpisode().getContentS3Version())
                        || purges.existsByEpisodeId(candidate.getEpisode().getId())) continue;
                known.computeIfAbsent(candidate.getEntityName(), x -> new ArrayList<>())
                        .add(candidate.getMatchedCharacterId());
            }
        }
        known.forEach(
                (name, identities) -> {
                    List<UUID> distinct = identities.stream().distinct().toList();
                    if (conflictsWithKnownIdentity(byName.getOrDefault(name, List.of()), distinct))
                        byName.put(name, List.of());
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
                // An earlier manual job may see a name first prepared by a later job. Reuse
                // only that explicitly observed name, without exposing other future identities
                // or moving the independently recorded profile-link episode into the past.
                List<RelationshipGraphNode> future =
                        allByName.getOrDefault(endpoint.name(), List.of());
                if (!future.isEmpty()) {
                    if (future.size() != 1 || conflictsWithKnownIdentity(future, identities)) {
                        byName.put(endpoint.name(), List.of());
                        continue;
                    }
                    RelationshipGraphNode node = future.getFirst();
                    if (persistNodes) node.observeAt(cutoff);
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
                List<UUID> identities =
                        known.getOrDefault(node.getName(), List.of()).stream().distinct().toList();
                if (group.size() == 1 && identities.size() == 1)
                    binding.put("knownCharacterId", identities.getFirst().toString());
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
            if (source == null
                    || target == null
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
        Map<String, Integer> failedPairs = failedPairEpisodes(allAnalyses, cutoff - 1, validPriorIds);
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
        if (job.getJobType() == AnalysisJobType.SETTING_EXTRACTION) {
            bindings.put("manualDependencyVersion", 1);
            bindings.put("manualDependencyRevision", manualDependencyRevision(workId,
                    manualDependencyPairs(context, bindings)));
            var sourceIds = bindings.putArray("sourceDependencies");
            sourceDependencies.stream().sorted().forEach(id -> sourceIds.add(id.toString()));
        }
        return new BuiltContext(context, bindings);
    }

    /** Persisted N refs are local to a context. Cross-episode dependencies use actual node IDs. */
    private Map<String, Integer> failedPairEpisodes(Map<Integer, RelationshipGraphAnalysis> all,
            int cutoff, Set<UUID> valid) {
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
                    result.merge(RelationshipGraphRules.pair(source, target), analysis.getEpisodeNo(), Math::min);
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
        Map<String, Integer> failedPairs = failedPairEpisodes(read.analyses(), verificationCutoff, read.validIds());
        List<Edge> output = new ArrayList<>();
        Map<UUID, Node> outputNodes = new LinkedHashMap<>();
        List<RelationshipGraphEvent> visible =
                latest.values().stream()
                        .filter(e -> !read.edges().get(e.getEdgeId()).isSuppressed())
                        .toList();
        for (var event : visible.stream().limit(200).toList()) {
            var edge = read.edges().get(event.getEdgeId());
            var progress = pairProgress(read, edge, event, verificationCutoff, failedPairs);
            output.add(
                    mapper.toEdge(
                            edge, event, evidence.getOrDefault(edge.getId(), List.of()).size(),
                            progress.lastVerifiedEpisodeNo(), progress.updateIncomplete()));
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

    private PairProgress pairProgress(ReadState read, RelationshipGraphEdge edge,
            RelationshipGraphEvent event, int cutoff, Map<String, Integer> failedPairs) {
        String pair = RelationshipGraphRules.pair(edge.getSourceNodeId().toString(), edge.getTargetNodeId().toString());
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
        if (edge == null || edge.isSuppressed())
            throw new AppException(RELATIONSHIP_GRAPH_NOT_FOUND);
        int cutoff = graph.selectedEpisode().episodeNo();
        var event = latestEvents(read, cutoff).get(edgeId);
        if (event == null) throw new AppException(RELATIONSHIP_GRAPH_NOT_FOUND);
        List<PublicEvidence> evidence = evidence(read, cutoff).getOrDefault(edgeId, List.of());
        int verificationCutoff = episodeNo != null ? episodeNo : read.analyses().keySet().stream()
                .mapToInt(Integer::intValue).max().orElse(cutoff);
        var progress = pairProgress(read, edge, event, verificationCutoff,
                failedPairEpisodes(read.analyses(), verificationCutoff, read.validIds()));
        return new Detail(
                mapper.toEdge(edge, event, evidence.size(), progress.lastVerifiedEpisodeNo(), progress.updateIncomplete()),
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
}
