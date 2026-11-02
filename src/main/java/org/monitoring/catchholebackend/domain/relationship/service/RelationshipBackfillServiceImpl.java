package org.monitoring.catchholebackend.domain.relationship.service;

import static org.monitoring.catchholebackend.domain.relationship.exception.RelationshipGraphErrorCode.RELATIONSHIP_GRAPH_CONFLICT;
import static org.monitoring.catchholebackend.domain.relationship.exception.RelationshipGraphErrorCode.RELATIONSHIP_GRAPH_INCOMPLETE;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.domain.aitoken.repository.AiTokenUsageRepository;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobFailRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobHeartbeatResponse;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisJobWorkerMapper;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisFailureDiagnosticsSanitizer;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisCandidateSourceGuard;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobLeaseService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeSourcePurgeRequestRepository;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.member.type.MemberStatus;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.AnalysisStatus;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Prepare;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipBackfillCampaign;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipBackfillItem;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipBackfillWork;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphAnalysis;
import org.monitoring.catchholebackend.domain.relationship.mapper.RelationshipGraphMapper;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipBackfillManifest;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipBackfillWorkRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipGraphAnalysisRepository;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipBackfillItemState;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipBackfillWorkState;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkLifecycleStatus;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable, cohort-scoped relation lane. Work locks serialize admission with core completion. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RelationshipBackfillServiceImpl implements RelationshipBackfillService {
    private static final String RECOVERY_CAMPAIGN_KEY = "relationship-backfill-20261030-v1";

    private final EntityManager em;
    private final RelationshipBackfillManifest manifest;
    private final RelationshipBackfillWorkRepository workStates;
    private final WorkRepository works;
    private final EpisodeRepository episodes;
    private final EpisodeSourcePurgeRequestRepository purges;
    private final AnalysisJobRepository jobs;
    private final AnalysisJobLeaseService leases;
    private final AnalysisCandidateSourceGuard sourceGuard;
    private final AnalysisFailureDiagnosticsSanitizer failureDiagnostics;
    private final AnalysisJobWorkerMapper workerMapper;
    private final RelationshipGraphService graph;
    private final RelationshipGraphAnalysisRepository analyses;
    private final RelationshipGraphMapper mapper;
    private final AiTokenService tokens;
    private final AiTokenUsageRepository tokenUsages;

    @Value("${analysis.relationship-backfill.enabled:false}")
    private boolean enabled;

    @Override
    @Transactional
    public void bootstrapRelationshipBackfill() {
        if (!enabled) return;
        var campaign = campaignLock();
        if (campaign.isInitialized()) return;
        for (Work candidate :
                em.createQuery("select w from Work w join fetch w.member order by w.id", Work.class)
                        .getResultList()) {
            if (!eligible(candidate) || !manifest.matches(candidate.getMember())) continue;
            Work work = works.findByIdForUpdate(candidate.getId()).orElseThrow();
            em.refresh(work);
            em.refresh(work.getMember());
            if (eligible(work) && manifest.matches(work.getMember())) initializeWork(work);
        }
        campaign.initialize();
    }

    /** Called under the ordinary analysis Work lock, before advertising inline capability. */
    @Override
    @Transactional
    public boolean deferInlineRelationshipAnalysis(AnalysisJob job) {
        if (job.getJobType() != AnalysisJobType.SETTING_EXTRACTION || job.isSettingBookSource())
            return false;
        Work work = works.findByIdForUpdate(job.getWork().getId()).orElseThrow();
        var state = workStates.findById(work.getId()).orElse(null);
        if (enabled && state == null && manifest.matches(work.getMember()) && eligible(work)) {
            var campaign = em.find(RelationshipBackfillCampaign.class, manifest.campaignKey());
            if (campaign == null || !campaign.isInitialized()) state = initializeWork(work);
        }
        if (state != null && state.active()) {
            job.deferRelationshipAnalysis();
            return true;
        }
        return false;
    }

    @Override
    public long getPublishedRelationshipGeneration(UUID workId) {
        return workStates
                .findById(workId)
                .map(RelationshipBackfillWork::getPublishedGeneration)
                .orElse(0L);
    }

    /**
     * Core settings have already committed within the same transaction; no LLM work occurs here.
     */
    @Override
    @Transactional
    public void enqueueCompletedCoreAnalysis(AnalysisJob job) {
        if (job.getJobType() != AnalysisJobType.SETTING_EXTRACTION
                || job.isSettingBookSource()
                || job.getEpisode() == null) return;
        works.findByIdForUpdate(job.getWork().getId()).orElseThrow();
        var state = workStates.findById(job.getWork().getId()).orElse(null);
        if (state == null || !state.active()) return;
        var episode = job.getEpisode();
        // A rolling inline worker may already have reserved or reported relationship usage.
        // Even an unknown/released call can settle later, so do not charge the rebuild again.
        boolean alreadyAnalyzed =
                tokenUsages.existsByAnalysisSourceAndPurposeIn(
                        job.getWork().getId(),
                        episode.getId(),
                        episode.getEpisodeNo(),
                        episode.getContentHash(),
                        episode.getContentS3Key(),
                        episode.getContentS3Version(),
                        List.of(
                                AiTokenPurpose.RELATIONSHIP_EXTRACTION,
                                AiTokenPurpose.RELATIONSHIP_COMPARISON));
        enqueue(state, episode, alreadyAnalyzed);
    }

    private RelationshipBackfillWork initializeWork(Work work) {
        var existing = workStates.findById(work.getId()).orElse(null);
        if (existing != null) return existing;
        if (work.getCreatedAt() != null && work.getCreatedAt().isAfter(manifest.selectedAt()))
            return null;
        List<Episode> initial =
                episodes
                        .findAllByWorkIdAndStatusNotOrderByEpisodeNoDesc(
                                work.getId(), EpisodeStatus.ARCHIVED)
                        .stream()
                        .filter(
                                e ->
                                        !e.getCreatedAt().isAfter(manifest.selectedAt())
                                                && e.getContentHash() != null
                                                && e.getContentS3Key() != null
                                                && !purges.existsByEpisodeId(e.getId()))
                        .toList();
        if (initial.isEmpty()) return null;
        var state =
                workStates.save(
                        RelationshipBackfillWork.create(work.getId(), manifest.campaignKey()));
        initial.forEach(e -> enqueue(state, e, true));
        // A newly finished core job between cohort selection and API rollout must join the tail.
        // Query only episodes: historical Job JSON snapshots must not enter this persistence context.
        for (Episode episode :
                em.createQuery(
                                "select e from Episode e where e.createdAt>=:selectedAt and exists"
                                    + " (select j.id from AnalysisJob j where j.episode=e and"
                                    + " j.work.id=:work and j.jobType=:type and j.status=:status and"
                                    + " (j.sourceContentHash=e.contentHash or (j.sourceContentHash is"
                                    + " null and e.contentHash is null)) and"
                                    + " (j.sourceContentS3Key=e.contentS3Key or"
                                    + " (j.sourceContentS3Key is null and e.contentS3Key is null)) and"
                                    + " (j.sourceContentS3Version=e.contentS3Version or"
                                    + " (j.sourceContentS3Version is null and e.contentS3Version is"
                                    + " null)))",
                                Episode.class)
                        .setParameter("selectedAt", manifest.selectedAt())
                        .setParameter("work", work.getId())
                        .setParameter("type", AnalysisJobType.SETTING_EXTRACTION)
                        .setParameter("status", AnalysisJobStatus.SUCCEEDED)
                        .getResultList())
            enqueue(state, episode, true);
        return state;
    }

    private void enqueue(RelationshipBackfillWork state, Episode episode, boolean paid) {
        if (episode.getStatus() == EpisodeStatus.ARCHIVED
                || episode.getContentHash() == null
                || episode.getContentS3Key() == null) return;
        var items = items(state.getWorkId());
        var existing =
                items.stream()
                        .filter(
                                i ->
                                        i.getEpisodeId().equals(episode.getId())
                                                && sameSource(i, episode))
                        .findFirst();
        if (existing.isPresent()) {
            if (paid) existing.get().promoteToServicePaid();
            return;
        }
        // A replacement is a new source, not permission to silently reinterpret a paid snapshot.
        if (items.stream().anyMatch(i -> i.getEpisodeId().equals(episode.getId()))) {
            state.block("SOURCE_REPLACED");
            return;
        }
        em.persist(RelationshipBackfillItem.create(episode, state.getBuildingGeneration(), paid));
        // A missing middle chapter can arrive while a later relation is being built. Reuse its
        // frozen observations, but redo comparison after the inserted chapter before publication.
        for (var item : items) {
            if (item.getEpisodeNo() <= episode.getEpisodeNo() || item.getJobId() == null) continue;
            if (item.isFinished())
                item.requeueForRebuild();
            else if (item.getState() == RelationshipBackfillItemState.WAITING
                    || item.getState() == RelationshipBackfillItemState.RUNNING)
                item.markForRebuild();
        }
    }

    @Override
    @Transactional
    public Optional<WorkerAnalysisJobPayload> claimRelationshipJob(WorkerAnalysisJobClaimRequest request) {
        if (!enabled
                || !request.allowedJobTypes().contains(AnalysisJobType.RELATIONSHIP_EXTRACTION)
                || !Boolean.TRUE.equals(request.supportsRelationshipAnalysis())
                || !request.effectiveSupportedSourceKinds().contains(AnalysisSourceKind.EPISODE)
                || !request.effectiveSupportedAnalysisModes().contains(AnalysisMode.CONFIRMED_ONLY))
            return Optional.empty();
        // All API replicas share this campaign lock. The database partial unique index is a second
        // guard.
        var campaign = campaignLock();
        recoverExpired();
        if (em.createQuery(
                                "select count(j) from AnalysisJob j where j.jobType=:type and"
                                        + " j.status=:status",
                                Long.class)
                        .setParameter("type", AnalysisJobType.RELATIONSHIP_EXTRACTION)
                        .setParameter("status", AnalysisJobStatus.RUNNING)
                        .getSingleResult()
                > 0) return Optional.empty();
        for (var candidate :
                workStates.findAll().stream()
                        .sorted(Comparator.comparing(RelationshipBackfillWork::getWorkId))
                        .toList()) {
            Work work = works.findByIdForUpdate(candidate.getWorkId()).orElse(null);
            if (work == null) continue;
            em.refresh(work);
            em.refresh(work.getMember());
            if (!eligible(work)) continue;
            var state = workStates.findById(work.getId()).orElseThrow();
            // A core completion can change this row while claim waits for the Work lock.
            em.refresh(state);
            if (!Boolean.TRUE.equals(request.supportsRelationshipPartialResults())
                    && items(work.getId()).stream().anyMatch(i -> i.getState() == RelationshipBackfillItemState.PARTIAL))
                continue;
            if (campaign.isInitialized()
                    && state.getState() == RelationshipBackfillWorkState.BLOCKED
                    && Boolean.TRUE.equals(request.supportsRelationshipRecovery())) {
                var recovered = claimRecoveredRelationshipJob(request, work, state);
                if (recovered.isPresent()) return recovered;
            }
            if (state.getState() != RelationshipBackfillWorkState.BUILDING) continue;
            for (var item : items(work.getId())) {
                if (item.getState() == RelationshipBackfillItemState.SKIPPED
                        || item.isFinished()) continue;
                // The same recovery Job can be reclaimed after lease expiry during a rolling deploy.
                if (item.getRecoveryAttemptCount() > 0
                        && !Boolean.TRUE.equals(request.supportsRelationshipRecovery())) break;
                if (item.getState() == RelationshipBackfillItemState.RUNNING) {
                    var stale =
                            item.getJobId() == null
                                    ? null
                                    : jobs.findById(item.getJobId()).orElse(null);
                    if (stale == null
                            || (stale.getStatus() != AnalysisJobStatus.RUNNING
                                    && stale.getStatus() != AnalysisJobStatus.PENDING)) {
                        item.fail("JOB_TERMINATED");
                        state.block("JOB_TERMINATED");
                    }
                }
                if (item.getState() != RelationshipBackfillItemState.WAITING) break;
                var episode = episodes.findById(item.getEpisodeId()).orElse(null);
                if (episode == null || episode.getStatus() == EpisodeStatus.ARCHIVED) {
                    if (item.getJobId() != null) {
                        item.fail("SOURCE_ARCHIVED_AFTER_START");
                        state.block("SOURCE_ARCHIVED_AFTER_START");
                        break;
                    }
                    item.skip("SOURCE_ARCHIVED");
                    continue;
                }
                if (!sameSource(item, episode) || purges.existsByEpisodeId(episode.getId())) {
                    item.fail("SOURCE_CHANGED");
                    state.block("SOURCE_CHANGED");
                    break;
                }
                if (jobs.existsValidationStagingForEpisodes(List.of(episode.getId()))
                        || coreActiveAtOrBefore(work.getId(), item.getEpisodeNo())) break;
                AnalysisJob previous =
                        item.getJobId() == null
                                ? null
                                : jobs.findByIdForUpdate(item.getJobId()).orElse(null);
                AnalysisJob job = previous;
                if (job != null && job.isRelationshipPartialResultsEnabled()
                        && !Boolean.TRUE.equals(request.supportsRelationshipPartialResults())) break;
                if (job != null
                        && job.getStatus() == AnalysisJobStatus.PENDING
                        && (item.requiresFreshComparison()
                                || (item.isServicePaid() && !job.isRelationshipServicePaid()))) {
                    tokens.releaseReservedForAnalysisJob(
                            job.getId(), AiTokenUsageOutcome.WORKER_LEASE_EXPIRED);
                    job.supersedeRelationshipAnalysis();
                }
                if (job == null || job.getStatus() != AnalysisJobStatus.PENDING) {
                    job =
                            AnalysisJob.create(
                                    work, null, episode, AnalysisJobType.RELATIONSHIP_EXTRACTION);
                    job.configureRelationship(state.getBuildingGeneration(), item.isServicePaid());
                    if (Boolean.TRUE.equals(request.supportsRelationshipPartialResults())) job.enableRelationshipPartialResults();
                    job = jobs.saveAndFlush(job);
                }
                job.claim(request.modelName(), "인물 관계 분석", LocalDateTime.now().plusSeconds(60));
                item.running(job.getId(), job.getLeaseToken());
                graph.beginRelationshipAnalysis(job);
                reuseObservations(previous, job);
                // Cached preparation runs under the claim lock and can consume the initial lease.
                job.renewLease(LocalDateTime.now().plusSeconds(60));
                return Optional.of(
                        workerMapper.toResponse(job, episode, List.of(), List.of(), List.of()));
            }
            publishIfReady(state);
        }
        return Optional.empty();
    }

    private Optional<WorkerAnalysisJobPayload> claimRecoveredRelationshipJob(
            WorkerAnalysisJobClaimRequest request, Work work, RelationshipBackfillWork state) {
        if (!RECOVERY_CAMPAIGN_KEY.equals(manifest.campaignKey())
                || !RECOVERY_CAMPAIGN_KEY.equals(state.getCampaignKey())
                || !manifest.matches(work.getMember())
                || work.getCreatedAt() == null
                || work.getCreatedAt().isAfter(manifest.selectedAt())
                || !"ANALYSIS_FAILED".equals(state.getBlockedReason())
                || state.getPublishedGeneration() >= state.getBuildingGeneration())
            return Optional.empty();
        var all = items(work.getId());
        var first = all.stream()
                .filter(i -> !i.isFinished()
                        && i.getState() != RelationshipBackfillItemState.SKIPPED)
                .findFirst();
        if (first.isEmpty()) return Optional.empty();
        var item = first.get();
        if (item.getState() != RelationshipBackfillItemState.FAILED
                || !"ANALYSIS_FAILED".equals(item.getReason())
                || !item.isServicePaid()
                || item.getRecoveryAttemptCount() != 0
                || item.getJobId() == null
                || item.getGeneration() != state.getBuildingGeneration()
                // beginRelationshipAnalysis invalidates later graphs. Never leave a successful
                // later item pointing to an invalidated graph after this narrow recovery.
                || all.subList(all.indexOf(item) + 1, all.size()).stream()
                        .anyMatch(RelationshipBackfillItem::isFinished)
                || coreActiveAtOrBefore(work.getId(), Integer.MAX_VALUE))
            return Optional.empty();
        var episode = episodes.findById(item.getEpisodeId()).orElse(null);
        if (episode == null
                || episode.getStatus() == EpisodeStatus.ARCHIVED
                || !Objects.equals(episode.getWork().getId(), work.getId())
                || !sameSource(item, episode)
                || purges.existsByEpisodeId(episode.getId())
                || jobs.existsValidationStagingForEpisodes(List.of(episode.getId())))
            return Optional.empty();
        var previous = jobs.findByIdForUpdate(item.getJobId()).orElse(null);
        if (previous == null) return Optional.empty();
        em.refresh(previous);
        if (previous.getJobType() != AnalysisJobType.RELATIONSHIP_EXTRACTION
                || previous.getStatus() != AnalysisJobStatus.FAILED
                || previous.getFailureCode() != AnalysisFailureCode.COMPARISON_VALIDATION_FAILED
                || !previous.isRelationshipServicePaid()
                || previous.getSourceKind() != AnalysisSourceKind.EPISODE
                || previous.getEpisode() == null
                || !Objects.equals(previous.getWork().getId(), work.getId())
                || !Objects.equals(previous.getEpisode().getId(), episode.getId())
                || !Objects.equals(previous.getSourceEpisodeNo(), item.getEpisodeNo())
                || !Objects.equals(previous.getSourceContentHash(), item.getSourceHash())
                || !Objects.equals(previous.getSourceContentS3Key(), item.getSourceKey())
                || !Objects.equals(previous.getSourceContentS3Version(), item.getSourceVersion())
                || previous.getRelationshipGeneration() != item.getGeneration()
                || previous.getLeaseToken() != null
                || previous.getLeaseExpiresAt() != null)
            return Optional.empty();
        var cached = analyses.findById(previous.getId()).orElse(null);
        var prepared = recoveryObservations(cached, item);
        if (prepared.isEmpty() || !graph.canPrepareCachedObservations(previous, prepared.get()))
            return Optional.empty();

        // Admission, durable budget, new Job and mandatory cached preparation commit together.
        // Keep the old Job and its immutable usage ledger as the history of the failed attempt.
        item.requeueForRecovery();
        state.resumeRecovery();
        var job = AnalysisJob.create(work, null, episode, AnalysisJobType.RELATIONSHIP_EXTRACTION);
        job.configureRelationship(state.getBuildingGeneration(), true);
        if (Boolean.TRUE.equals(request.supportsRelationshipPartialResults())) job.enableRelationshipPartialResults();
        job = jobs.saveAndFlush(job);
        job.claim(request.modelName(), "인물 관계 비교 복구", LocalDateTime.now().plusSeconds(60));
        item.running(job.getId(), job.getLeaseToken());
        graph.beginRelationshipAnalysis(job);
        graph.prepareCachedObservations(job, prepared.get());
        if (analyses.findById(job.getId()).orElseThrow().getStatus() != AnalysisStatus.PREPARED)
            throw new AppException(RELATIONSHIP_GRAPH_INCOMPLETE);
        job.renewLease(LocalDateTime.now().plusSeconds(60));
        return Optional.of(workerMapper.toResponse(job, episode, List.of(), List.of(), List.of()));
    }

    private Optional<Prepare> recoveryObservations(
            RelationshipGraphAnalysis cached, RelationshipBackfillItem item) {
        if (cached == null
                || cached.getStatus() != AnalysisStatus.FAILED
                || !("COMPARISON_FAILED".equals(cached.getFailureCode())
                        || "INPUT_TOO_LARGE".equals(cached.getFailureCode()))
                || cached.getContext() == null
                || cached.getBindings() == null
                || cached.getRequestHash() == null
                || cached.getContextToken() == null
                || cached.isEvidencePurged()
                || !Objects.equals(cached.getWorkId(), item.getWorkId())
                || !Objects.equals(cached.getEpisodeId(), item.getEpisodeId())
                || cached.getEpisodeNo() != item.getEpisodeNo()
                || cached.getGeneration() != item.getGeneration()
                || !Objects.equals(cached.getSourceHash(), item.getSourceHash())
                || cached.getContext().path("formatVersion").asInt(-1) != 1)
            return Optional.empty();
        try {
            var context = mapper.toContext(cached);
            if (!Objects.equals(context.sourceHash(), item.getSourceHash())
                    || context.sourceEpisodeNo() != item.getEpisodeNo()
                    || !Objects.equals(context.contextToken(), cached.getContextToken())
                    || context.observations() == null || context.observations().isEmpty()
                    || context.observations().size() > 384
                    || context.nodes() == null || context.nodes().size() > 1536
                    || context.relationships() == null || context.relationships().size() > 1536
                    || context.heldObservations() == null || context.heldObservations().size() > 384
                    || context.observations().stream().anyMatch(o -> o == null
                            || o.source() == null || o.target() == null || o.evidence() == null))
                return Optional.empty();
            return Optional.of(mapper.toPrepareCachedObservations(
                    item.getSourceHash(), context, cached.getRejectedObservationCount()));
        } catch (IllegalArgumentException malformedContext) {
            // A malformed historical cache is ineligible, not permission to extract the source again.
            return Optional.empty();
        }
    }

    private void reuseObservations(AnalysisJob previous, AnalysisJob job) {
        if (previous == null || previous.getId().equals(job.getId())) return;
        var old = analyses.findById(previous.getId()).orElse(null);
        if (old == null
                || old.getContext() == null
                || !Objects.equals(old.getSourceHash(), job.getSourceContentHash())
                || old.isEvidencePurged()) return;
        var request =
                mapper.toPrepareCachedObservations(
                        job.getSourceContentHash(),
                        mapper.toContext(old),
                        old.getRejectedObservationCount());
        // Source-bound observations were already verified. build a fresh comparison context using
        // the same normal guard, outside a provider call. A changed dependency never reuses
        // decisions.
        graph.prepareCachedObservations(job, request);
    }

    @Override
    @Transactional
    public WorkerAnalysisJobHeartbeatResponse renewRelationshipJobLease(UUID id, UUID lease) {
        var job = running(id, lease);
        var expires = LocalDateTime.now().plusSeconds(60);
        job.renewLease(expires);
        return workerMapper.toHeartbeatResponse(job);
    }

    @Override
    @Transactional
    public void completeRelationshipJob(UUID id, UUID lease, WorkerAnalysisJobCompleteRequest request) {
        leases.lockWorkForUpdate(id);
        var receipt = item(id);
        if (receipt.isFinished()
                && Objects.equals(receipt.getLeaseToken(), lease)
                && jobs.findById(id)
                        .map(j -> j.getStatus() == AnalysisJobStatus.SUCCEEDED)
                        .orElse(false)) return;
        var job = running(id, lease);
        var item = item(id);
        var state = workStates.findById(item.getWorkId()).orElseThrow();
        sourceGuard.assertCurrent(job, job.getEpisode());
        if (!sameSource(item, job.getEpisode()) || purges.existsByEpisodeId(item.getEpisodeId()))
            throw new AppException(RELATIONSHIP_GRAPH_CONFLICT);
        graph.finalizeRelationshipAnalysis(job);
        if (analyses.findById(id).orElseThrow().getStatus() != AnalysisStatus.APPLIED)
            throw new AppException(RELATIONSHIP_GRAPH_INCOMPLETE);
        long[] usage = tokens.getAnalysisJobTokenTotals(id);
        job.succeed(
                request == null ? null : request.summaryJson(),
                Math.toIntExact(usage[0]),
                Math.toIntExact(usage[1]));
        if (analyses.findById(id).orElseThrow().hasFailures()) item.partial();
        else item.succeeded();
        if (items(item.getWorkId()).stream()
                .anyMatch(
                        i ->
                                i.getEpisodeNo() < item.getEpisodeNo()
                                        && i.getState() == RelationshipBackfillItemState.WAITING))
            item.requeueForRebuild();
        publishIfReady(state);
    }

    @Override
    @Transactional
    public void failRelationshipJob(UUID id, UUID lease, WorkerAnalysisJobFailRequest request) {
        leases.lockWorkForUpdate(id);
        var receipt = item(id);
        if (receipt.getState() == RelationshipBackfillItemState.FAILED
                && Objects.equals(receipt.getLeaseToken(), lease)) return;
        var job = running(id, lease);
        var item = item(id);
        var state = workStates.findById(item.getWorkId()).orElseThrow();
        tokens.releaseReservedForAnalysisJob(id, AiTokenUsageOutcome.WORKER_LEASE_EXPIRED);
        long[] usage = tokens.getAnalysisJobTokenTotals(id);
        job.fail(
                request.failureCode(),
                "관계 전용 분석을 완료하지 못했습니다.",
                Math.toIntExact(usage[0]),
                Math.toIntExact(usage[1]));
        job.recordFailureDiagnostics(
                failureDiagnostics.sanitize(request.failureDiagnostics(), job.getWork().getId()));
        item.fail("ANALYSIS_FAILED");
        state.block("ANALYSIS_FAILED");
    }

    private void recoverExpired() {
        var running =
                em.createQuery(
                                "select j from AnalysisJob j where j.jobType=:type and"
                                        + " j.status=:status order by j.id",
                                AnalysisJob.class)
                        .setParameter("type", AnalysisJobType.RELATIONSHIP_EXTRACTION)
                        .setParameter("status", AnalysisJobStatus.RUNNING)
                        .getResultList();
        for (var candidate : running) {
            if (!candidate.isLeaseExpired(LocalDateTime.now())) continue;
            works.findByIdForUpdate(candidate.getWork().getId());
            var job = jobs.findByIdForUpdate(candidate.getId()).orElseThrow();
            // 잠금을 기다리는 동안 heartbeat가 갱신했을 수 있으므로 1차 캐시도 새로 읽는다.
            em.refresh(job);
            if (job.getStatus() != AnalysisJobStatus.RUNNING
                    || !job.isLeaseExpired(LocalDateTime.now())) continue;
            tokens.releaseReservedForAnalysisJob(
                    job.getId(), AiTokenUsageOutcome.WORKER_LEASE_EXPIRED);
            var item = item(job.getId());
            if (job.getClaimAttemptCount() >= 3) {
                job.fail(AnalysisFailureCode.WORKER_LEASE_EXPIRED, "관계 분석 lease 회수 한도를 초과했습니다.");
                item.fail("LEASE_EXPIRED");
                workStates.findById(item.getWorkId()).orElseThrow().block("LEASE_EXPIRED");
            } else {
                job.requeueExpiredLease();
                item.requeue();
            }
        }
    }

    private void publishIfReady(RelationshipBackfillWork state) {
        if (state.getState() != RelationshipBackfillWorkState.BUILDING) return;
        var all = items(state.getWorkId());
        if (all.isEmpty()
                || all.stream()
                        .anyMatch(
                                i ->
                                        !i.isFinished()
                                                && i.getState()
                                                        != RelationshipBackfillItemState.SKIPPED)
                || coreActiveAtOrBefore(state.getWorkId(), Integer.MAX_VALUE)) return;
        for (var item : all)
            if (item.isFinished()) {
                var episode = episodes.findById(item.getEpisodeId()).orElse(null);
                if (episode == null
                        || !sameSource(item, episode)
                        || episode.getStatus() == EpisodeStatus.ARCHIVED
                        || purges.existsByEpisodeId(item.getEpisodeId())) {
                    state.block("SOURCE_CHANGED");
                    return;
                }
                if (analyses.findById(item.getJobId())
                        .map(a -> a.getStatus() != AnalysisStatus.APPLIED)
                        .orElse(true)) {
                    state.block("STALE_BUILD");
                    return;
                }
            }
        state.publish();
    }

    private AnalysisJob running(UUID id, UUID lease) {
        leases.lockWorkForUpdate(id);
        var job = leases.getRunningAnalysisJobForUpdate(id, lease);
        if (job.getJobType() != AnalysisJobType.RELATIONSHIP_EXTRACTION || !eligible(job.getWork()))
            throw new AppException(RELATIONSHIP_GRAPH_CONFLICT);
        var item = item(id);
        var state = workStates.findById(item.getWorkId()).orElseThrow();
        if (state.getState() != RelationshipBackfillWorkState.BUILDING
                || item.getState() != RelationshipBackfillItemState.RUNNING)
            throw new AppException(RELATIONSHIP_GRAPH_CONFLICT);
        return job;
    }

    private boolean coreActiveAtOrBefore(UUID workId, int cutoff) {
        return em.createQuery(
                                "select count(j) from AnalysisJob j where j.work.id=:work and"
                                        + " j.jobType=:type and j.status in :states and"
                                        + " j.sourceEpisodeNo<=:cutoff",
                                Long.class)
                        .setParameter("work", workId)
                        .setParameter("type", AnalysisJobType.SETTING_EXTRACTION)
                        .setParameter(
                                "states",
                                List.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.RUNNING))
                        .setParameter("cutoff", cutoff)
                        .getSingleResult()
                > 0;
    }

    private RelationshipBackfillCampaign campaignLock() {
        var c =
                em.find(
                        RelationshipBackfillCampaign.class,
                        manifest.campaignKey(),
                        LockModeType.PESSIMISTIC_WRITE);
        if (c == null) {
            c = RelationshipBackfillCampaign.create(manifest.campaignKey());
            em.persist(c);
            em.flush();
        }
        return c;
    }

    private RelationshipBackfillItem item(UUID id) {
        return em.createQuery(
                        "select i from RelationshipBackfillItem i where i.jobId=:id",
                        RelationshipBackfillItem.class)
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new AppException(RELATIONSHIP_GRAPH_CONFLICT));
    }

    private List<RelationshipBackfillItem> items(UUID workId) {
        return em.createQuery(
                        "select i from RelationshipBackfillItem i where i.workId=:work order by"
                                + " i.episodeNo,i.id",
                        RelationshipBackfillItem.class)
                .setParameter("work", workId)
                .getResultList();
    }

    private boolean eligible(Work w) {
        return w.getLifecycleStatus() == WorkLifecycleStatus.ACTIVE
                && w.getMember().getStatus() == MemberStatus.ACTIVE;
    }

    private boolean sameSource(RelationshipBackfillItem i, Episode e) {
        return i.getEpisodeNo() == e.getEpisodeNo()
                && Objects.equals(i.getSourceHash(), e.getContentHash())
                && Objects.equals(i.getSourceKey(), e.getContentS3Key())
                && Objects.equals(i.getSourceVersion(), e.getContentS3Version());
    }
}
