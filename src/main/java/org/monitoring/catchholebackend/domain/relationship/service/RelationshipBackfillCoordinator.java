package org.monitoring.catchholebackend.domain.relationship.service;

import static org.monitoring.catchholebackend.domain.relationship.exception.RelationshipGraphErrorCode.RELATIONSHIP_GRAPH_CONFLICT;
import static org.monitoring.catchholebackend.domain.relationship.exception.RelationshipGraphErrorCode.RELATIONSHIP_GRAPH_INCOMPLETE;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
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
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Observation;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Prepare;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipBackfillCampaign;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipBackfillItem;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipBackfillWork;
import org.monitoring.catchholebackend.domain.relationship.mapper.RelationshipGraphMapper;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipBackfillManifest;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipBackfillWorkRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipGraphAnalysisRepository;
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
@Transactional
public class RelationshipBackfillCoordinator {
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

    @Value("${analysis.relationship-backfill.enabled:false}")
    private boolean enabled;

    public void bootstrap() {
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
    public boolean deferInline(AnalysisJob job) {
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

    public long publishedGeneration(UUID workId) {
        return workStates
                .findById(workId)
                .map(RelationshipBackfillWork::getPublishedGeneration)
                .orElse(0L);
    }

    /**
     * Core settings have already committed within the same transaction; no LLM work occurs here.
     */
    public void coreCompleted(AnalysisJob job) {
        if (job.getJobType() != AnalysisJobType.SETTING_EXTRACTION
                || job.isSettingBookSource()
                || job.getEpisode() == null) return;
        works.findByIdForUpdate(job.getWork().getId()).orElseThrow();
        var state = workStates.findById(job.getWork().getId()).orElse(null);
        if (state == null || !state.active()) return;
        enqueue(state, job.getEpisode(), false);
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
        for (AnalysisJob core :
                em.createQuery(
                                "select j from AnalysisJob j where j.work.id=:work and"
                                    + " j.jobType=:type and j.status=:status and j.episode is not"
                                    + " null",
                                AnalysisJob.class)
                        .setParameter("work", work.getId())
                        .setParameter("type", AnalysisJobType.SETTING_EXTRACTION)
                        .setParameter("status", AnalysisJobStatus.SUCCEEDED)
                        .getResultList())
            if (!core.getEpisode().getCreatedAt().isBefore(manifest.selectedAt())
                    && sameSource(core, core.getEpisode()))
                enqueue(state, core.getEpisode(), false);
        return state;
    }

    private void enqueue(RelationshipBackfillWork state, Episode episode, boolean paid) {
        if (episode.getStatus() == EpisodeStatus.ARCHIVED
                || episode.getContentHash() == null
                || episode.getContentS3Key() == null) return;
        var items = items(state.getWorkId());
        if (items.stream()
                .anyMatch(
                        i ->
                                i.getEpisodeId().equals(episode.getId())
                                        && i.getSourceHash().equals(episode.getContentHash())))
            return;
        // A replacement is a new source, not permission to silently reinterpret a paid snapshot.
        if (items.stream().anyMatch(i -> i.getEpisodeId().equals(episode.getId()))) {
            state.block("SOURCE_REPLACED");
            return;
        }
        em.persist(RelationshipBackfillItem.create(episode, state.getBuildingGeneration(), paid));
        // A missing middle chapter can arrive while a later relation is being built. Reuse its
        // frozen observations, but redo comparison after the inserted chapter before publication.
        items.stream()
                .filter(
                        i ->
                                i.getEpisodeNo() > episode.getEpisodeNo()
                                        && i.getState() == RelationshipBackfillItem.State.SUCCEEDED)
                .forEach(RelationshipBackfillItem::requeue);
    }

    public Optional<WorkerAnalysisJobPayload> claim(WorkerAnalysisJobClaimRequest request) {
        if (!enabled
                || !request.allowedJobTypes().contains(AnalysisJobType.RELATIONSHIP_EXTRACTION)
                || !Boolean.TRUE.equals(request.supportsRelationshipAnalysis())
                || !request.effectiveSupportedSourceKinds().contains(AnalysisSourceKind.EPISODE)
                || !request.effectiveSupportedAnalysisModes().contains(AnalysisMode.CONFIRMED_ONLY))
            return Optional.empty();
        // All API replicas share this campaign lock. The database partial unique index is a second
        // guard.
        campaignLock();
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
            if (state.getState() != RelationshipBackfillWork.State.BUILDING) continue;
            for (var item : items(work.getId())) {
                if (item.getState() == RelationshipBackfillItem.State.SKIPPED
                        || item.getState() == RelationshipBackfillItem.State.SUCCEEDED) continue;
                if (item.getState() == RelationshipBackfillItem.State.RUNNING) {
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
                if (item.getState() != RelationshipBackfillItem.State.WAITING) break;
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
                                : jobs.findById(item.getJobId()).orElse(null);
                AnalysisJob job = previous;
                if (job == null || job.getStatus() != AnalysisJobStatus.PENDING) {
                    job =
                            AnalysisJob.create(
                                    work, null, episode, AnalysisJobType.RELATIONSHIP_EXTRACTION);
                    job.configureRelationship(state.getBuildingGeneration(), item.isServicePaid());
                    job = jobs.saveAndFlush(job);
                }
                job.claim(request.modelName(), "인물 관계 분석", LocalDateTime.now().plusSeconds(60));
                item.running(job.getId(), job.getLeaseToken());
                graph.beginRelationshipAnalysis(job);
                reuseObservations(previous, job);
                return Optional.of(
                        workerMapper.toResponse(job, episode, List.of(), List.of(), List.of()));
            }
            publishIfReady(state);
        }
        return Optional.empty();
    }

    private void reuseObservations(AnalysisJob previous, AnalysisJob job) {
        if (previous == null || previous.getId().equals(job.getId())) return;
        var old = analyses.findById(previous.getId()).orElse(null);
        if (old == null
                || old.getContext() == null
                || !Objects.equals(old.getSourceHash(), job.getSourceContentHash())
                || old.isEvidencePurged()) return;
        var context = mapper.toContext(old);
        var request =
                new Prepare(
                        1,
                        job.getSourceContentHash(),
                        context.observations().stream()
                                .map(
                                        o ->
                                                new Observation(
                                                        o.ref(),
                                                        o.source(),
                                                        o.target(),
                                                        o.summary(),
                                                        o.evidence()))
                                .toList(),
                        old.getRejectedObservationCount());
        // Source-bound observations were already verified. build a fresh comparison context using
        // the same normal guard, outside a provider call. A changed dependency never reuses
        // decisions.
        graph.prepareCachedObservations(job, request);
    }

    public WorkerAnalysisJobHeartbeatResponse heartbeat(UUID id, UUID lease) {
        var job = running(id, lease);
        var expires = LocalDateTime.now().plusSeconds(60);
        job.renewLease(expires);
        return new WorkerAnalysisJobHeartbeatResponse(lease, expires);
    }

    public void complete(UUID id, UUID lease, WorkerAnalysisJobCompleteRequest request) {
        leases.lockWorkForUpdate(id);
        var receipt = item(id);
        if (receipt.getState() == RelationshipBackfillItem.State.SUCCEEDED
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
        item.succeeded();
        if (items(item.getWorkId()).stream()
                .anyMatch(
                        i ->
                                i.getEpisodeNo() < item.getEpisodeNo()
                                        && i.getState() == RelationshipBackfillItem.State.WAITING))
            item.requeue();
        publishIfReady(state);
    }

    public void fail(UUID id, UUID lease, WorkerAnalysisJobFailRequest request) {
        leases.lockWorkForUpdate(id);
        var receipt = item(id);
        if (receipt.getState() == RelationshipBackfillItem.State.FAILED
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
        if (state.getState() != RelationshipBackfillWork.State.BUILDING) return;
        var all = items(state.getWorkId());
        if (all.isEmpty()
                || all.stream()
                        .anyMatch(
                                i ->
                                        i.getState() != RelationshipBackfillItem.State.SUCCEEDED
                                                && i.getState()
                                                        != RelationshipBackfillItem.State.SKIPPED)
                || coreActiveAtOrBefore(state.getWorkId(), Integer.MAX_VALUE)) return;
        for (var item : all)
            if (item.getState() == RelationshipBackfillItem.State.SUCCEEDED) {
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
        if (state.getState() != RelationshipBackfillWork.State.BUILDING
                || item.getState() != RelationshipBackfillItem.State.RUNNING)
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

    private boolean sameSource(AnalysisJob j, Episode e) {
        return Objects.equals(j.getSourceContentHash(), e.getContentHash())
                && Objects.equals(j.getSourceContentS3Key(), e.getContentS3Key())
                && Objects.equals(j.getSourceContentS3Version(), e.getContentS3Version());
    }
}
