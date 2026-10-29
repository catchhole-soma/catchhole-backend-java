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
    private final SettingCandidateRepository characters;
    private final List<AnalysisStateSource> stateSources;
    private final EntityManager em;
    private final ValidationCorrectionRepository corrections;
    private final ApplicationEventPublisher events;

    public void prepare(ValidationReport report) {
        if (!report.isCurrent() || report.getBaselineSeed() != null || report.getStatus() == ValidationReportStatus.INVALIDATED) return;
        try {
            capture(report);
            if (report.getBaselineSeed() == null) return;
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
        if (report.getMode() == ValidationReportMode.DIAGNOSTIC_ONLY) {
            JsonNode recorded = diagnosticInput(report);
            if (recorded == null) { report.hold("DIAGNOSTIC_BASELINE_UNAVAILABLE"); return; }
            for (String domain : List.of("characters", "worldSettings")) { confirmed.set(domain, recorded.path(domain).deepCopy()); captured.add(domain); }
            if (!applyDiagnosticCorrections(confirmed, report)) { report.hold("DIAGNOSTIC_BASELINE_UNAVAILABLE"); return; }
        } else {
            for (AnalysisStateSource source : stateSources) {
                if (!List.of("characters", "worldSettings").contains(source.domain())) continue;
                JsonNode state = source.capture(report.getWork());
                if (!captured.add(source.domain()) || state == null || !state.isObject()) throw new org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException();
                confirmed.set(source.domain(), state);
            }
        }
        if (!captured.containsAll(List.of("characters", "worldSettings"))) throw new org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException();
        var seed = JsonNodeFactory.instance.objectNode();
        seed.put("formatVersion", 1).put("workId", report.getWork().getId().toString()).put("memberId", report.getMember().getId());
        var target = seed.putObject("target");
        target.put("episodeId", report.getEpisode().getId().toString()).put("episodeNo", report.getSourceEpisodeNo())
                .put("contentRevision", report.getSourceContentRevision())
                .put("contentHash", report.getSourceContentHash()).put("contentS3Key", report.getSourceContentS3Key())
                .put("contentS3Version", report.getSourceContentS3Version());
        seed.set("characters", sanitizeEvidence(confirmed.path("characters"), report));
        seed.set("worldSettings", confirmed.path("worldSettings").deepCopy());
        seed.set("worldHistory", captureWorldHistory(report));
        var authorCorrections = seed.putArray("explicitAuthorCorrections");
        authorCorrections.addAll(correctionScopes(report));
        ArrayNode manifests = seed.putArray("sources");
        for (var episode : episodes.findAllByWorkIdAndStatusNotOrderByEpisodeNoDesc(report.getWork().getId(), EpisodeStatus.ARCHIVED)) {
            if (episode.getEpisodeNo() >= report.getSourceEpisodeNo() || episode.getContentHash() == null
                    || episode.getContentS3Key() == null || purges.blocksSource(episode.getId(),episode.getContentS3Key())) continue;
            var manifest = manifests.addObject().put("sourceKind", "EPISODE").put("episodeId", episode.getId().toString())
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
            var manifest = manifests.addObject().put("sourceKind", "SETTING_BOOK").put("settingBookId", revision.getSettingBook().getId().toString())
                    .put("revisionId", revision.getId().toString()).put("revisionNo", revision.getRevisionNo())
                    .put("contentHash", revision.getContentHash()).put("contentS3Key", revision.getContentS3Key())
                    .put("contentS3Version", revision.getContentS3Version()).put("referenceTimeKind", revision.getReferenceTimeKind().name())
                    .put("referenceEpisodeNo", revision.getReferenceEpisodeNo()).put("sourcePreparationStatus", "NOT_PREPARED");
            sources.save(ValidationBaselineSource.create(report, null, revision, manifest));
        }
        report.captureSeed(seed, journal.hash(seed), journal.hash(confirmed));
    }

    /** 확정 전 불변 입력을 입증할 수 없는 legacy 기록에 live 현재값을 대신 사용하지 않는다. */
    private JsonNode diagnosticInput(ValidationReport report) {
        if (report.getPreviousApplicationJobId() == null) return null;
        var job = em.find(AnalysisJob.class, report.getPreviousApplicationJobId());
        if (job == null || !job.getWork().getId().equals(report.getWork().getId()) || !job.isOrderedProvisional()
                || job.getJournalStatus() != org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.SEALED) return null;
        JsonNode recorded = job.isAutomaticReview() ? job.getAutomaticInputState()
                : Objects.equals(job.getRunSequence(), 0) ? job.getRunBaseState() : null;
        if (recorded == null || !recorded.path("characters").isObject() || !recorded.path("worldSettings").isObject()
                || !Objects.equals(job.getInputStateHash(), journal.hash(recorded)) || hasTargetEvidence(recorded.path("characters"), report.getSourceEpisodeNo())
                || hasTargetEvidence(recorded.path("worldSettings"), report.getSourceEpisodeNo())
                || !recordedSourcesAvailable(recorded.path("characters"), "characters", report)
                || !recordedSourcesAvailable(recorded.path("worldSettings"), "worldSettings", report)) return null;
        return recorded;
    }

    /** Work 잠금 아래 과거 입력 자체의 출처를 확인한다. 현재 manifest로 옛 출처를 재연결하지 않는다. */
    private boolean recordedSourcesAvailable(JsonNode node, String domain, ValidationReport report) {
        if (node == null || !node.isContainerNode()) return true;
        if (node.isObject()) {
            try {
                if (node.path("sourceAnalysisJobId").isTextual()
                        && !recordedJobAvailable(em.find(AnalysisJob.class, UUID.fromString(node.path("sourceAnalysisJobId").asText())), report)) return false;
                if (node.path("sourceCandidateId").isTextual() && !recordedCandidateAvailable(node.path("sourceCandidateId").asText(), domain, report)) return false;
                for (JsonNode id : node.path("sourceCandidateIds")) if (!id.isTextual() || !recordedCandidateAvailable(id.asText(), domain, report)) return false;
                if (node.path("sourceKind").isTextual() && (node.has("sourceHash") || node.has("contentHash") || node.has("sourceEpisodeId")
                        || node.has("episodeId") || node.has("sourceSettingBookRevisionId") || node.has("revisionId"))
                        && !recordedSourceAvailable(node, report)) return false;
            } catch (IllegalArgumentException malformedIdentity) { return false; }
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                // 작품 설정의 임의 JSON key를 서버 provenance로 해석하지 않는다.
                if (!List.of("valueJson", "propertiesJson").contains(field.getKey()) && !recordedSourcesAvailable(field.getValue(), domain, report)) return false;
            }
        } else for (JsonNode child : node) if (!recordedSourcesAvailable(child, domain, report)) return false;
        return true;
    }

    private boolean recordedCandidateAvailable(String id, String domain, ValidationReport report) {
        AnalysisJob source;
        if (domain.equals("characters")) {
            var candidate = characters.findByIdAndWorkId(UUID.fromString(id), report.getWork().getId()).orElse(null);
            source = candidate == null ? null : candidate.getAnalysisJob();
        } else {
            var candidate = em.find(WorldSettingCandidate.class, UUID.fromString(id));
            source = candidate == null || !candidate.getWork().getId().equals(report.getWork().getId()) ? null : candidate.getAnalysisJob();
        }
        return recordedJobAvailable(source, report);
    }

    private boolean recordedJobAvailable(AnalysisJob source, ValidationReport report) {
        if (source == null || !source.getWork().getId().equals(report.getWork().getId()) || source.getSourceContentS3Key() == null
                || source.isSettingBookSource() && (source.getSourceSettingBook() == null || source.getSourceSettingBookRevision() == null)) return false;
        var identity = JsonNodeFactory.instance.objectNode(); AnalysisSourceMetadata.put(identity, source);
        identity.put("contentS3Key", source.getSourceContentS3Key()).put("contentS3Version", source.getSourceContentS3Version());
        return recordedSourceAvailable(identity, report);
    }

    private boolean recordedSourceAvailable(JsonNode source, ValidationReport report) {
        String hash = source.has("sourceHash") ? nullableText(source, "sourceHash") : nullableText(source, "contentHash");
        if (hash == null) return false;
        Object[] actual;
        if ("EPISODE".equals(source.path("sourceKind").asText())) {
            String id = source.has("sourceEpisodeId") ? nullableText(source, "sourceEpisodeId") : nullableText(source, "episodeId");
            if (id == null) return false;
            var episodeId = UUID.fromString(id);
            // 정리 중인 retained 원문도 이전 application input의 수명을 보증하지 않는다.
            if (purges.existsByEpisodeId(episodeId)) return false;
            actual = em.createQuery("select e.contentHash,e.contentS3Key,e.contentS3Version,e.episodeNo,cast(e.status as string) from Episode e where e.id=:id and e.work.id=:work", Object[].class)
                    .setParameter("id", episodeId).setParameter("work", report.getWork().getId()).getResultStream().findFirst().orElse(null);
            JsonNode number = source.has("sourceEpisodeNo") ? source.path("sourceEpisodeNo") : source.path("episodeNo");
            if (actual == null || "ARCHIVED".equals(actual[4]) || !number.isIntegralNumber() || !Objects.equals(actual[3], number.asInt())
                    || number.asInt() >= report.getSourceEpisodeNo()) return false;
        } else if ("SETTING_BOOK".equals(source.path("sourceKind").asText())) {
            String id = source.has("sourceSettingBookRevisionId") ? nullableText(source, "sourceSettingBookRevisionId") : nullableText(source, "revisionId");
            if (id == null) return false;
            actual = em.createQuery("select r.contentHash,r.contentS3Key,r.contentS3Version,cast(r.referenceTimeKind as string),r.referenceEpisodeNo from SettingBookRevision r where r.id=:id and r.settingBook.batch.work.id=:work", Object[].class)
                    .setParameter("id", UUID.fromString(id)).setParameter("work", report.getWork().getId()).getResultStream().findFirst().orElse(null);
            if (actual == null || "EPISODE".equals(actual[3]) && (int) actual[4] >= report.getSourceEpisodeNo()) return false;
        } else return false;
        return hash.equals(actual[0]) && (!source.has("contentS3Key") || Objects.equals(actual[1], nullableText(source, "contentS3Key")))
                && (!source.has("contentS3Version") || Objects.equals(actual[2], nullableText(source, "contentS3Version")));
    }

    private boolean hasTargetEvidence(JsonNode node, int targetNo) {
        if (node == null || !node.isContainerNode()) return false;
        if (node.isObject()) {
            for (String field : List.of("sourceEpisodeNo", "latestSourceEpisodeNo")) if (node.path(field).isNumber() && node.path(field).asInt() >= targetNo) return true;
        }
        for (JsonNode child : node) if (hasTargetEvidence(child, targetNo)) return true;
        return false;
    }

    /** 실제 편집 영수증의 단일 slot/path만 확정 전 입력에 정정한다. 다른 live 현재값을 혼합하지 않는다. */
    private boolean applyDiagnosticCorrections(ObjectNode confirmed, ValidationReport report) {
        for (var scope : correctionScopes(report)) {
            String domain = scope.path("domain").asText();
            String ref = (domain.equals("characters") ? "character:" : "world:") + scope.path("targetId").asText();
            JsonNode value = confirmed.path(domain).path(ref);
            if (!value.isObject()) return false;
            ObjectNode state = (ObjectNode) value;
            if (domain.equals("characters")) {
                String key = scope.path("factType").asText() + ":" + scope.path("factKey").asText();
                var slot = state.path("slots").path(key);
                if (!slot.isObject() || !slot.path("factValue").equals(scope.path("beforeValue"))
                        || !slot.path("valueJson").equals(scope.path("beforeValueJson"))) return false;
                if (scope.path("afterValue").isNull()) ((ObjectNode) state.path("slots")).remove(key);
                else {
                    var updated = (ObjectNode) slot;
                    updated.set("factValue", scope.path("afterValue").deepCopy()); updated.set("valueJson", scope.path("afterValueJson").deepCopy());
                    updated.set("provenance", manualCorrectionProvenance()); updated.set("supportingSources", JsonNodeFactory.instance.arrayNode());
                }
                state.put("snapshotVersion", scope.path("entityVersion").asLong());
            } else if (domain.equals("worldSettings")) {
                String oldScope = nullableText(scope, "currentScopeName"), oldName = scope.path("currentSettingName").asText();
                String newScope = nullableText(scope, "scopeName"), newName = scope.path("settingName").asText();
                ObjectNode properties = (ObjectNode) state.path("propertiesJson");
                JsonNode before = oldScope == null ? properties.path(oldName) : properties.path(oldScope).path(oldName);
                if (!before.equals(scope.path("beforeValue"))) return false;
                if (oldScope == null) properties.remove(oldName);
                else { ObjectNode parent = (ObjectNode) properties.path(oldScope); parent.remove(oldName); if (parent.isEmpty()) properties.remove(oldScope); }
                if (newScope == null) properties.set(newName, scope.path("afterValue").deepCopy());
                else {
                    if (properties.has(newScope) && !properties.path(newScope).isObject()) return false;
                    properties.withObjectProperty(newScope).set(newName, scope.path("afterValue").deepCopy());
                }
                ObjectNode provenance = (ObjectNode) state.path("provenanceByPath");
                provenance.remove(org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingAnalysisStateMapper.pathKey(oldScope, oldName));
                provenance.set(org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingAnalysisStateMapper.pathKey(newScope, newName), manualCorrectionProvenance());
                state.put("version", scope.path("entityVersion").asLong());
            } else return false;
        }
        return true;
    }
    private ArrayNode correctionScopes(ValidationReport report) {
        var scopes = new LinkedHashMap<String, JsonNode>();
        for (var correction : corrections.findAllByWorkIdOrderByCreatedAtAscIdAsc(report.getWork().getId())) {
            ObjectNode scope = correction.getScope().deepCopy();
            for (var prior : scope.path("priorCorrections")) scopes.putIfAbsent(prior.path("correctionId").asText(), prior.deepCopy());
            scope.remove("priorCorrections");
            scope.put("kind", "AUTHOR_CORRECTION").put("correctionId", correction.getId().toString()).put("baselineId", correction.getReport().getId().toString())
                    .put("baselineRevision", correction.getBaselineRevision()).put("entryRef", correction.getEntryRef()).put("entryHash", correction.getEntryHash());
            scopes.put(correction.getId().toString(), scope);
        }
        var result = JsonNodeFactory.instance.arrayNode(); scopes.values().forEach(result::add); return result;
    }
    private ObjectNode manualCorrectionProvenance() {
        var value = JsonNodeFactory.instance.objectNode().put("confirmationStatus", "CONFIRMED").put("reviewSource", "HUMAN").put("currentValueProtected", true);
        value.putArray("sourceCandidateIds"); return value;
    }
    private String nullableText(JsonNode node, String field) { return node.path(field).isTextual() ? node.path(field).asText() : null; }

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
        return episode != null && episode.getStatus() != EpisodeStatus.ARCHIVED && !purges.blocksSource(episode.getId(),source.getSourceContentS3Key())
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
        reports.findAllByWorkId(event.workId()).forEach(this::purge);
    }
    /** Work 잠금 아래 호출한다. 결과 저장소도 같은 트랜잭션의 ReportInvalidated를 소비한다. */
    public void retire(ValidationReport report) { report.retire(); purge(report); }
    private void purge(ValidationReport report) {
        sources.deleteAllByReportId(report.getId()); report.purge();
        events.publishEvent(new ValidationStagingEvents.ReportInvalidated(report.getWork().getId(), report.getId()));
    }
    private JsonNode copy(JsonNode value) { return value == null ? NullNode.instance : value.deepCopy(); }
}
