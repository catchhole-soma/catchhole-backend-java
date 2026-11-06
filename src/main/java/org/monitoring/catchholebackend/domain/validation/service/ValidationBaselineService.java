package org.monitoring.catchholebackend.domain.validation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.episode.repository.*;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision;
import org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.event.ValidationStagingEvents;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationLiveSettings;
import org.monitoring.catchholebackend.domain.validation.repository.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.springframework.context.event.EventListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ValidationBaselineService {
    private final ValidationReportRepository reports;
    private final ValidationBaselineSourceRepository sources;
    private final EpisodeRepository episodes;
    private final EpisodeSourcePurgeRequestRepository purges;
    private final WorkRepository works;
    private final AnalysisStateJournal journal;
    private final EntityManager em;
    private final ValidationCorrectionRepository corrections;
    private final ApplicationEventPublisher events;
    private final ValidationSettingsApplicationRepository applications;
    private final ValidationLiveSettings liveSettings;

    /**
     * #381 접수 준비. 확정 설정을 복사하지 않는다(BEFORE_CONFIRMATION·DIAGNOSTIC_ONLY 모두). 원문 출처 manifest만 저장하고
     * 설정 버전 fingerprint로 READY가 된다. 판정 입력은 현재 확정 설정을 읽으며 검수 중의 일관성은 확정 설정 잠금이 보장한다.
     * 작가가 확정한 값이 기준이므로 DIAGNOSTIC_ONLY도 직전 반영 Job의 기록 입력이 아니라 현재 확정 설정과 대조한다(소유자 결정 2026-11-05).
     * 호출자는 Work 잠금 아래 새 report에 한 번 호출한다.
     */
    public void prepare(ValidationReport report) {
        if (!report.isCurrent() || report.getStatus() == ValidationReportStatus.INVALIDATED
                || report.getBaselineStatus() != ValidationPreparationStatus.PENDING
                || report.getBaselineSeed() != null || report.getBaselineHash() != null) return;
        captureManifests(report);
        report.readyWithoutSeed(liveSettings.fingerprint(report.getWork().getId()));
    }

    /**
     * 판정이 인용할 수 있는 원문 버전 목록. 대상 앞의 활성 회차(회차 번호 내림차순)와 기준 시점이 대상 앞인 설정집 revision((설정집 id, revisionNo) 순)이다.
     * 이 순서가 R 번호이며 ValidationSourceManifests가 같은 순서로 다시 읽는다.
     */
    private void captureManifests(ValidationReport report) {
        var views = new SourceViews();
        for (var episode : episodes.findAllByWorkIdAndStatusNotOrderByEpisodeNoDesc(report.getWork().getId(), EpisodeStatus.ARCHIVED)) {
            if (episode.getEpisodeNo() >= report.getSourceEpisodeNo() || episode.getContentHash() == null
                    || episode.getContentS3Key() == null || views.blocksSource(episode.getId(), episode.getContentS3Key())) continue;
            var manifest = JsonNodeFactory.instance.objectNode().put("sourceKind", "EPISODE").put("episodeId", episode.getId().toString())
                    .put("episodeNo", episode.getEpisodeNo()).put("contentHash", episode.getContentHash())
                    .put("contentRevision", episode.getContentRevision())
                    .put("contentS3Key", episode.getContentS3Key()).put("contentS3Version", episode.getContentS3Version())
                    .put("sourcePreparationStatus", "NOT_PREPARED");
            sources.save(ValidationBaselineSource.create(report, episode, null, manifest));
        }
        // 확정 Fact가 참조하는 과거 revision도 원본 그대로 허용한다. 현재 포인터로 재연결하지 않는다.
        for (var revision : em.createQuery("select r from SettingBookRevision r where r.settingBook.batch.work.id = :id order by r.settingBook.id, r.revisionNo", SettingBookRevision.class)
                .setParameter("id", report.getWork().getId()).getResultList()) {
            if (revision.getReferenceTimeKind() == SettingBookReferenceTimeKind.EPISODE
                    && revision.getReferenceEpisodeNo() >= report.getSourceEpisodeNo()) continue;
            var manifest = JsonNodeFactory.instance.objectNode().put("sourceKind", "SETTING_BOOK").put("settingBookId", revision.getSettingBook().getId().toString())
                    .put("revisionId", revision.getId().toString()).put("revisionNo", revision.getRevisionNo())
                    .put("contentHash", revision.getContentHash()).put("contentS3Key", revision.getContentS3Key())
                    .put("contentS3Version", revision.getContentS3Version()).put("referenceTimeKind", revision.getReferenceTimeKind().name())
                    .put("referenceEpisodeNo", revision.getReferenceEpisodeNo()).put("sourcePreparationStatus", "NOT_PREPARED");
            // 접수 때 색인 채우기(2026-11-02): 접수 시점에 그 설정집의 현재 판이고 보관되지 않은 revision만 RAG 범위다(과거 판은 출처 확인용).
            var book = revision.getSettingBook();
            manifest.put("currentRevision", book.getArchivedAt() == null && book.getCurrentSettingBookRevision() != null
                    && revision.getId().equals(book.getCurrentSettingBookRevision().getId()));
            sources.save(ValidationBaselineSource.create(report, null, revision, manifest));
        }
    }

    /**
     * 접수 한 번 동안의 파기 대기 확인 캐시. (회차, 원고 key)마다 한 번만 묻는다. 접수는 Work 잠금 아래라 그 사이 파기 요청이 바뀌지 않는다.
     */
    private final class SourceViews {
        private final Map<List<Object>, Boolean> blocked = new HashMap<>();

        boolean blocksSource(UUID episodeId, String contentKey) {
            return blocked.computeIfAbsent(Arrays.asList(episodeId, contentKey), key -> purges.blocksSource(episodeId, contentKey));
        }
    }

    /** 반드시 저장한 seed만 정규화한다. Work 잠금 후 최신 원장을 읽어 파기와 늦은 정규화를 직렬화한다. */
    @Transactional
    public void materialize(UUID reportId) {
        UUID workId = reports.findById(reportId).map(r -> r.getWork().getId()).orElse(null);
        if (workId == null || works.findByIdForUpdate(workId).isEmpty()) return;
        ValidationReport report = reports.findById(reportId).orElse(null);
        if (report != null) {
            em.flush(); // 같은 트랜잭션에서 이미 무효화한 원장을 refresh로 되돌리지 않는다.
            em.refresh(report, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        }
        if (report == null || !report.isCurrent() || report.getStatus() == ValidationReportStatus.INVALIDATED
                || report.getBaselineSeed() == null
                || report.getBaselineStatus() == ValidationPreparationStatus.READY) return;
        normalize(report);
    }

    private void normalize(ValidationReport report) {
        JsonNode seed = report.getBaselineSeed();
        if (seed == null || !seed.path("characters").isObject() || !seed.path("worldSettings").isObject()
                || !seed.path("worldHistory").isArray() || !seed.path("sources").isArray()
                || !journal.hash(seed).equals(report.getBaselineHash())) {
            report.hold("BASELINE_SEED_INVALID"); return;
        }
        var entries = JsonNodeFactory.instance.arrayNode();
        for (String domain : List.of("characters", "worldSettings")) {
            seed.path(domain).fields().forEachRemaining(subject -> {
                var row = entries.addObject().put("domain", domain).put("subjectRef", subject.getKey());
                ObjectNode state = subject.getValue().deepCopy();
                var corrected = JsonNodeFactory.instance.arrayNode();
                for (JsonNode scope : seed.path("explicitAuthorCorrections")) {
                    String ref = (domain.equals("characters") ? "character:" : "world:") + scope.path("targetId").asText();
                    if (domain.equals(scope.path("domain").asText()) && subject.getKey().equals(ref)) corrected.add(scope.deepCopy());
                }
                if (!corrected.isEmpty()) state.set("explicitAuthorCorrections", corrected);
                row.set("state", state);
            });
        }
        for (JsonNode history : seed.path("worldHistory")) {
            entries.addObject().put("domain", "worldHistory").set("state", history.deepCopy());
        }
        report.baselineReady(entries);
    }

    @EventListener
    public void purge(ValidationStagingEvents.EpisodeSourceChanged event) {
        boolean invalidatesCurrent = false;
        for (var report : reports.findAllByWorkId(event.workId())) {
            if (report.getEpisode().getId().equals(event.episodeId()) && !Objects.equals(report.getSourceContentS3Key(),event.retainedContentKey())
                    || sources.findAllByReportId(report.getId()).stream().anyMatch(s -> s.getEpisode() != null && s.getEpisode().getId().equals(event.episodeId())
                        && !Objects.equals(s.getSourceManifest().path("contentS3Key").asText(),event.retainedContentKey()))) {
                invalidatesCurrent |= report.isCurrent(); corrections.deleteAllByReportId(report.getId()); purge(report);
            }
        }
        if (invalidatesCurrent) corrections.deleteAllByWorkId(event.workId());
    }
    @EventListener
    public void purge(ValidationStagingEvents.SettingBookSourceChanged event) {
        boolean invalidatesCurrent = false;
        for (var report : reports.findAllByWorkId(event.workId())) {
            if (sources.findAllByReportId(report.getId()).stream().anyMatch(s -> s.getSettingBookRevision() != null
                    && s.getSettingBookRevision().getSettingBook().getId().equals(event.settingBookId()))) {
                invalidatesCurrent |= report.isCurrent(); corrections.deleteAllByReportId(report.getId()); purge(report);
            }
        }
        if (invalidatesCurrent) corrections.deleteAllByWorkId(event.workId());
    }
    @EventListener
    public void purge(ValidationStagingEvents.ConfirmedSettingsChanged event) {
        corrections.deleteAllByWorkId(event.workId());
        // GH312 S3: 회차 전체 확정이 APPLIED로 기록한 report는 그 확정(또는 이후 설정 변경)으로 무효화하지 않는다. 이 report의 기준은
        // 이미 확정 전 검사로 쓰였고 검토 종료·반영까지 끝난 이력이다. APPLIED는 도메인 쓰기보다 먼저 같은 트랜잭션에 기록되며
        // 반영이 실패하면 함께 되돌려진다. 같은 작품의 다른 report는 기존처럼 무효화한다. 원문·설정집 변경 경로는 바꾸지 않는다.
        reports.findAllByWorkId(event.workId()).stream()
                .filter(report -> !applications.existsByReportIdAndStatus(report.getId(), org.monitoring.catchholebackend.domain.validation.type.ValidationApplicationStatus.APPLIED))
                .forEach(this::purge);
    }
    /** Work 잠금 아래 호출한다. 결과 저장소도 같은 트랜잭션의 ReportInvalidated를 소비한다. */
    public void retire(ValidationReport report) { report.retire(); purge(report); }
    private void purge(ValidationReport report) {
        sources.deleteAllByReportId(report.getId()); report.purge();
        events.publishEvent(new ValidationStagingEvents.ReportInvalidated(report.getWork().getId(), report.getId()));
    }
}
