package org.monitoring.catchholebackend.domain.validation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisSourceMetadata;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisStateSource;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.episode.repository.*;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision;
import org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.event.ValidationStagingEvents;
import org.monitoring.catchholebackend.domain.validation.repository.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.springframework.context.event.EventListener;
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
    private final SettingCandidateRepository characters;
    private final List<AnalysisStateSource> stateSources;
    private final EntityManager em;

    public void prepare(ValidationReport report) {
        if (report.getBaselineSeed() != null || report.getStatus() == ValidationReportStatus.INVALIDATED) return;
        try {
            capture(report);
            normalize(report);
        } catch (org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException exception) {
            report.hold("BASELINE_EVIDENCE_INVALID");
        } catch (org.monitoring.catchholebackend.global.exception.AppException exception) {
            if (exception.getResultCode() != org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode.ANALYSIS_CURRENT_EVIDENCE_SOURCE_UNAVAILABLE) throw exception;
            report.hold("BASELINE_SOURCE_UNAVAILABLE");
        }
    }

    private void capture(ValidationReport report) {
        var confirmed = journal.emptyState();
        Set<String> captured = new HashSet<>();
        for (AnalysisStateSource source : stateSources) {
            if (!List.of("characters", "worldSettings").contains(source.domain())) continue;
            JsonNode state = source.capture(report.getWork());
            if (!captured.add(source.domain()) || state == null || !state.isObject()) throw new org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException();
            confirmed.set(source.domain(), state);
        }
        if (!captured.containsAll(List.of("characters", "worldSettings"))) throw new org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException();
        var seed = JsonNodeFactory.instance.objectNode();
        seed.put("formatVersion", 1).put("workId", report.getWork().getId().toString()).put("memberId", report.getMember().getId());
        var target = seed.putObject("target");
        target.put("episodeId", report.getEpisode().getId().toString()).put("episodeNo", report.getSourceEpisodeNo())
                .put("contentHash", report.getSourceContentHash()).put("contentS3Key", report.getSourceContentS3Key())
                .put("contentS3Version", report.getSourceContentS3Version());
        seed.set("characters", sanitizeEvidence(confirmed.path("characters"), report));
        seed.set("worldSettings", confirmed.path("worldSettings").deepCopy());
        seed.set("worldHistory", captureWorldHistory(report));
        ArrayNode manifests = seed.putArray("sources");
        for (var episode : episodes.findAllByWorkIdAndStatusNotOrderByEpisodeNoDesc(report.getWork().getId(), EpisodeStatus.ARCHIVED)) {
            if (episode.getEpisodeNo() >= report.getSourceEpisodeNo() || episode.getContentHash() == null
                    || episode.getContentS3Key() == null || purges.existsByEpisodeId(episode.getId())) continue;
            var manifest = manifests.addObject().put("sourceKind", "EPISODE").put("episodeId", episode.getId().toString())
                    .put("episodeNo", episode.getEpisodeNo()).put("contentHash", episode.getContentHash())
                    .put("contentS3Key", episode.getContentS3Key()).put("contentS3Version", episode.getContentS3Version())
                    .put("sourcePreparationStatus", "NOT_PREPARED");
            sources.save(ValidationBaselineSource.create(report, episode, null, manifest));
        }
        // 확정 Fact가 참조하는 과거 revision도 원본 그대로 허용한다. 현재 포인터로 재연결하지 않는다.
        for (var revision : em.createQuery("select r from SettingBookRevision r where r.settingBook.batch.work.id = :id order by r.settingBook.id, r.revisionNo", SettingBookRevision.class)
                .setParameter("id", report.getWork().getId()).getResultList()) {
            if (revision.getReferenceTimeKind() == SettingBookReferenceTimeKind.EPISODE
                    && revision.getReferenceEpisodeNo() >= report.getSourceEpisodeNo()) continue;
            var manifest = manifests.addObject().put("sourceKind", "SETTING_BOOK").put("settingBookId", revision.getSettingBook().getId().toString())
                    .put("revisionId", revision.getId().toString()).put("revisionNo", revision.getRevisionNo())
                    .put("contentHash", revision.getContentHash()).put("contentS3Key", revision.getContentS3Key())
                    .put("contentS3Version", revision.getContentS3Version()).put("referenceTimeKind", revision.getReferenceTimeKind().name())
                    .put("referenceEpisodeNo", revision.getReferenceEpisodeNo()).put("sourcePreparationStatus", "NOT_PREPARED");
            sources.save(ValidationBaselineSource.create(report, null, revision, manifest));
        }
        report.captureSeed(seed, journal.hash(seed), journal.hash(confirmed));
    }

    private ArrayNode captureWorldHistory(ValidationReport report) {
        var result = JsonNodeFactory.instance.arrayNode();
        for (var candidate : em.createQuery("select c from WorldSettingCandidate c where c.work.id = :id and c.reviewStatus = 'CONFIRMED' order by c.reviewedAt, c.id", WorldSettingCandidate.class)
                .setParameter("id", report.getWork().getId()).getResultList()) {
            var source = candidate.getAnalysisJob();
            if (source != null && !source.isSettingBookSource() && source.getSourceEpisodeNo() != null
                    && source.getSourceEpisodeNo() >= report.getSourceEpisodeNo()) continue;
            var row = result.addObject().put("candidateId", candidate.getId().toString())
                    .put("targetWorldSettingId", candidate.getTargetWorldSetting() == null ? null : candidate.getTargetWorldSetting().getId().toString())
                    .put("category", candidate.getFinalCategory() == null ? null : candidate.getFinalCategory().name())
                    .put("subjectName", candidate.getFinalSubjectName()).put("scopeName", candidate.getFinalScopeName())
                    .put("settingName", candidate.getFinalSettingName()).put("value", candidate.getFinalValue())
                    .put("operation", candidate.getFinalOperation() == null ? null : candidate.getFinalOperation().name())
                    .put("historyOnly", candidate.isHistoryOnly()).put("effectiveFromEpisodeNo", candidate.getEffectiveFromEpisodeNo())
                    .put("temporalScope", candidate.getTemporalScope() == null ? null : candidate.getTemporalScope().name());
            boolean available = accessible(source, report);
            row.put("sourceAvailability", available ? "AVAILABLE" : "UNAVAILABLE");
            row.set("evidenceSpans", available ? copy(candidate.getEvidenceSpans()) : JsonNodeFactory.instance.arrayNode());
            row.set("comparison", available ? copy(candidate.getRawComparisonJson()) : NullNode.instance);
            row.set("narrativeTime", AnalysisCurrentSourceTimeMapper.toJson(AnalysisCurrentSourceTimeMapper.from(source,
                    candidate.getEffectiveFromEpisodeNo(), candidate.getTemporalScope())));
            AnalysisSourceMetadata.put(row, source);
        }
        return result;
    }

    private boolean accessible(AnalysisJob source, ValidationReport target) {
        if (source == null || !source.getWork().getId().equals(target.getWork().getId())) return false;
        if (source.isSettingBookSource()) {
            var revision = source.getSourceSettingBookRevision();
            return revision != null && Objects.equals(source.getSourceContentHash(), revision.getContentHash())
                    && Objects.equals(source.getSourceContentS3Key(), revision.getContentS3Key())
                    && Objects.equals(source.getSourceContentS3Version(), revision.getContentS3Version())
                    && (revision.getReferenceTimeKind() != SettingBookReferenceTimeKind.EPISODE
                        || revision.getReferenceEpisodeNo() < target.getSourceEpisodeNo());
        }
        var episode = source.getEpisode();
        return episode != null && episode.getStatus() != EpisodeStatus.ARCHIVED && !purges.existsByEpisodeId(episode.getId())
                && source.getSourceEpisodeNo() != null && source.getSourceEpisodeNo() < target.getSourceEpisodeNo()
                && Objects.equals(source.getSourceContentHash(), episode.getContentHash())
                && Objects.equals(source.getSourceContentS3Key(), episode.getContentS3Key())
                && Objects.equals(source.getSourceContentS3Version(), episode.getContentS3Version());
    }

    /** 이미 파기 접수된 앞 회차는 후속 비동기 정리 전에도 seed에 인용/저장 위치를 복사하지 않는다. */
    private JsonNode sanitizeEvidence(JsonNode node, ValidationReport target) {
        if (node == null || !node.isContainerNode()) return copy(node);
        JsonNode result = node.deepCopy();
        if (result.isObject()) {
            ObjectNode object = (ObjectNode) result;
            if (object.has("availability") && object.path("source").isObject()
                    && object.path("sourceAnalysisJobId").isTextual()) {
                var source = em.find(AnalysisJob.class, UUID.fromString(object.path("sourceAnalysisJobId").asText()));
                if (!accessible(source, target)) return org.monitoring.catchholebackend.domain.character.processor.CharacterNarrativeTime.purgeEvidence(object);
            }
            var fields = new ArrayList<String>(); object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) object.set(field, sanitizeEvidence(object.get(field), target));
            if ("SOURCE_EVIDENCE_PURGED".equals(object.path("supportingSource").path("unavailableReason").asText())) {
                return org.monitoring.catchholebackend.domain.character.processor.CharacterNarrativeTime.purgeEvidence(object);
            }
            if (object.has("identityEvidence")) {
                for (JsonNode id : object.path("provenance").path("sourceCandidateIds")) {
                    var candidate = characters.findByIdAndWorkId(UUID.fromString(id.asText()), target.getWork().getId()).orElse(null);
                    if (candidate == null || !accessible(candidate.getAnalysisJob(), target)) { object.remove("identityEvidence"); break; }
                }
            }
        } else {
            ArrayNode array = (ArrayNode) result;
            for (int i = 0; i < array.size(); i++) array.set(i, sanitizeEvidence(array.get(i), target));
        }
        return result;
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
        if (report == null || report.getStatus() == ValidationReportStatus.INVALIDATED
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
                row.set("state", subject.getValue().deepCopy());
            });
        }
        for (JsonNode history : seed.path("worldHistory")) {
            entries.addObject().put("domain", "worldHistory").set("state", history.deepCopy());
        }
        report.baselineReady(entries);
    }

    @EventListener
    public void purge(ValidationStagingEvents.EpisodeSourceChanged event) {
        for (var report : reports.findAllByWorkId(event.workId())) {
            if (report.getEpisode().getId().equals(event.episodeId()) || sources.findAllByReportId(report.getId()).stream()
                    .anyMatch(s -> s.getEpisode() != null && s.getEpisode().getId().equals(event.episodeId()))) purge(report);
        }
    }
    @EventListener
    public void purge(ValidationStagingEvents.SettingBookSourceChanged event) {
        for (var report : reports.findAllByWorkId(event.workId())) {
            if (sources.findAllByReportId(report.getId()).stream().anyMatch(s -> s.getSettingBookRevision() != null
                    && s.getSettingBookRevision().getSettingBook().getId().equals(event.settingBookId()))) purge(report);
        }
    }
    @EventListener
    public void purge(ValidationStagingEvents.ConfirmedSettingsChanged event) {
        reports.findAllByWorkId(event.workId()).forEach(this::purge);
    }
    private void purge(ValidationReport report) { sources.deleteAllByReportId(report.getId()); report.purge(); }
    private JsonNode copy(JsonNode value) { return value == null ? NullNode.instance : value.deepCopy(); }
}
