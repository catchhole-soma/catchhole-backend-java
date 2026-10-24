package org.monitoring.catchholebackend.domain.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonReviewReason;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType;
import org.springframework.stereotype.Component;

/** 업로드 묶음과 무관하게 앞 회차의 대기 내용을 실제 설정과 구분해 수집한다. */
@Component
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class AnalysisPendingReferenceSource {
    private final SettingCandidateRepository characterCandidates;
    private final WorldSettingCandidateRepository worldCandidates;
    private final AnalysisJobRepository jobs;

    private final org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository schemas;
    private final org.monitoring.catchholebackend.domain.character.processor.SettingCandidateSchemaResolver schemaResolver;

    public AnalysisPendingReferenceSource(SettingCandidateRepository characters,
            WorldSettingCandidateRepository worlds, AnalysisJobRepository jobs) {
        this(characters, worlds, jobs, null, new org.monitoring.catchholebackend.domain.character.processor.SettingCandidateSchemaResolver());
    }

    public ObjectNode capture(AnalysisJob current) {
        ObjectNode references = JsonNodeFactory.instance.objectNode();
        var activeSchemas = schemas == null ? java.util.List.<org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema>of()
                : schemas.findAllActiveForWork(current.getWork().getId());
        Set<UUID> seenBooks=new HashSet<>();
        for (AnalysisJob earlier : jobs.findAllByWorkIdAndSourceKindAndJobTypeOrderByCreatedAtDescIdDesc(current.getWork().getId(),
                org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.SETTING_BOOK, AnalysisJobType.SETTING_EXTRACTION)) {
            UUID bookId=earlier.getSourceSettingBook().getId();
            // A newer attempt replaces pending references even when it analyzes the same revision.
            if (!seenBooks.add(bookId) || current.isSettingBookSource() && bookId.equals(current.getSourceSettingBook().getId())
                    || !hasCurrentSource(earlier) || !earlier.isCompletedOrderedAnalysis()) continue;
            captureCharacters(references,earlier,true,activeSchemas);
            captureWorlds(references,earlier,true);
        }
        if (current.getSourceEpisodeNo() == null || current.getSourceEpisodeNo() < 1) return references;
        Set<UUID> seenEpisodes = new HashSet<>();
        // 최신 분석이 무효/실패했을 때 오래된 분석으로 돌아가 참고하지 않는다.
        for (AnalysisJob earlier : jobs.findEarlierExtractionJobsForReferences(
                current.getWork().getId(), current.getSourceEpisodeNo())) {
            Episode episode = earlier.getEpisode();
            if (episode == null || !Objects.equals(earlier.getWork().getId(), current.getWork().getId())
                    || earlier.getJobType() != AnalysisJobType.SETTING_EXTRACTION
                    || episode.getEpisodeNo() < 1 || episode.getEpisodeNo() >= current.getSourceEpisodeNo()
                    || !seenEpisodes.add(episode.getId()) || !hasCurrentSource(earlier)
                    || earlier.getStatus() != AnalysisJobStatus.SUCCEEDED) continue;
            boolean validAnalysis = !earlier.isOrderedProvisional()
                    || earlier.getJournalStatus() == AnalysisJournalStatus.SEALED;
            boolean editedAnalysis = earlier.isOrderedProvisional()
                    && earlier.getJournalStatus() == AnalysisJournalStatus.INVALIDATED;
            if (!validAnalysis && !editedAnalysis) continue;
            captureCharacters(references, earlier, validAnalysis, activeSchemas);
            captureWorlds(references, earlier, validAnalysis);
        }
        return references;
    }

    private void captureCharacters(ObjectNode references, AnalysisJob earlier, boolean validAnalysis,
            java.util.List<org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema> activeSchemas) {
        for (var candidate : characterCandidates.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(earlier.getId())) {
            if (candidate.getReviewStatus() != SettingCandidateReviewStatus.PENDING_REVIEW
                    || !validAnalysis && !candidate.isUserModified()
                    || !sameSource(earlier, candidate.getWork().getId(), candidate.getEpisode(),candidate.getSourceSettingBook(),candidate.getSourceSettingBookRevision())
                    || candidate.getSourceContentS3Key() != null
                        && !Objects.equals(candidate.getSourceContentS3Key(), earlier.getSourceContentS3Key())
                    || !hasEvidence(candidate.getEvidenceSpans())
                    || candidate.getMatchedCharacter() != null
                        && candidate.getMatchedCharacter().getStatus() == CharacterStatus.ARCHIVED
                    || !candidate.isCharacterDiscovery()
                        && (!hasText(candidate.getAttributeName()) || !hasText(candidate.getAttributeValue()))
                    || candidate.getComparisonStatus() == CharacterFactComparisonStatus.PROCESSING
                    || candidate.getComparisonStatus() == CharacterFactComparisonStatus.PENDING) continue;
            String subjectName = candidate.isUserModified() && candidate.getMatchedCharacter() != null
                    ? candidate.getMatchedCharacter().getName() : candidate.getEntityName();
            ObjectNode value = reference("characters", subjectName, earlier,
                    candidate.getAttributeName(), candidate.getAttributeValue(), candidate.getEvidenceSpans(),
                    candidate.isUserModified());
            ObjectNode selection = value.putObject("selection");
            var actualIds = selection.putArray("actualSubjectIds");
            var provisionalKeys = selection.putArray("provisionalSubjectKeys");
            boolean validSelection = candidate.isUserModified() || validAnalysis
                    && candidate.getComparisonStatus() == CharacterFactComparisonStatus.COMPLETED;
            // Identity resolution precedes Fact comparison; a failed Fact does not
            // invalidate an independently resolved owner. Failed operation targets
            // (P/Q slots, proposals) are never copied into this advisory linkage.
            boolean linkedSubject = (candidate.isUserModified() || validAnalysis)
                    && (candidate.getMatchStatus() == org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.MATCHED
                    || candidate.getMatchStatus() == org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus.AUTO_MATCHED_BY_NAME);
            if (linkedSubject) {
                if (candidate.getMatchedCharacterId() != null) actualIds.add(candidate.getMatchedCharacterId().toString());
                else if (hasText(candidate.getProvisionalSubjectKey())) provisionalKeys.add(candidate.getProvisionalSubjectKey());
            }
            selection.put("rawSubjectMention", candidate.getRawEntityMention());
            if (candidate.getCandidateKind() != null) selection.put("candidateKind", candidate.getCandidateKind().name());
            if (!candidate.isCharacterDiscovery()) {
                // A held/failed comparison can still have a valid original STATUS schema.
                // Never infer its type from the display label or a failed comparison.
                if (validSelection && candidate.getComparisonTargetFactType() != null
                        && hasText(candidate.getComparisonTargetFactKey())) {
                    selection.put("factType", candidate.getComparisonTargetFactType().name());
                    selection.put("canonicalFactKey", candidate.getComparisonTargetFactKey());
                } else if (schemas != null) {
                    try {
                        var match = schemaResolver.resolve(candidate.getAttributeName(), candidate.getValueType(),
                                activeSchemas);
                        selection.put("factType", match.matchedSchema().getFactType().name());
                        selection.put("canonicalFactKey", match.factKey());
                    } catch (org.monitoring.catchholebackend.global.exception.AppException ignored) {
                        // Unknown/ambiguous original schema stays untyped advisory data.
                    }
                }
                if (candidate.getValueJson() != null && candidate.getValueJson().isObject())
                    selection.set("valueJson", candidate.getValueJson().deepCopy());
            }
            references.set("pending-character:" + candidate.getId(), value);
        }
    }

    private void captureWorlds(ObjectNode references, AnalysisJob earlier, boolean validAnalysis) {
        for (var candidate : worldCandidates.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(earlier.getId())) {
            boolean edited = candidate.getFinalOperation() != null;
            if (candidate.getReviewStatus() != WorldSettingReviewStatus.PENDING_REVIEW
                    || candidate.getFinalOperation() == WorldSettingOperation.EXCLUDE
                    || !validAnalysis && !edited
                    || edited && (!hasText(candidate.getFinalSubjectName()) || !hasText(candidate.getFinalSettingName())
                        || !hasText(candidate.getFinalValue()))
                    || !sameSource(earlier, candidate.getWork().getId(), candidate.getSourceEpisode(),candidate.getSourceSettingBook(),candidate.getSourceSettingBookRevision())
                    || !hasEvidence(candidate.getEvidenceSpans())
                    || candidate.getComparisonStatus() == WorldSettingComparisonStatus.PROCESSING
                    || candidate.getComparisonStatus() == WorldSettingComparisonStatus.PENDING) continue;
            ObjectNode value = reference("worldSettings",
                    edited ? candidate.getFinalSubjectName() : candidate.getSubjectName(), earlier,
                    edited ? candidate.getFinalSettingName() : candidate.getSettingName(),
                    edited ? candidate.getFinalValue() : candidate.getExtractedValue(), candidate.getEvidenceSpans(), edited);
            value.put("scopeName", edited ? candidate.getFinalScopeName() : candidate.getScopeName());
            // 실패한 비교의 대상 선택이나 수정 전 판단을 다음 분석의 근거로 넘기지 않는다.
            if (!edited && validAnalysis && candidate.getComparisonStatus() == WorldSettingComparisonStatus.COMPLETED
                    && (candidate.getComparisonReviewReason() == WorldSettingComparisonReviewReason.SCOPE_MISMATCH
                    || candidate.getComparisonReviewReason() == WorldSettingComparisonReviewReason.SCOPE_UNRESOLVED)) {
                value.put("matchedScopeName", candidate.getMatchedScopeName());
                value.put("matchedPropertyName", candidate.getMatchedPropertyName());
                value.put("reason", candidate.getComparisonReviewReason() == WorldSettingComparisonReviewReason.SCOPE_MISMATCH
                        ? "원문과 기존 설정의 적용 범위가 달라 확인이 필요한 미확정 참고 정보입니다."
                        : "원문에서 적용 범위를 알 수 없어 기존 설정과 같은 범위인지 확인이 필요한 미확정 참고 정보입니다.");
            }
            if (!edited && validAnalysis && candidate.getComparisonStatus() == WorldSettingComparisonStatus.COMPLETED
                    && candidate.getComparisonReviewReason() == WorldSettingComparisonReviewReason.GENERAL_UNCERTAINTY) {
                value.put("comparisonReviewReason", WorldSettingComparisonReviewReason.GENERAL_UNCERTAINTY.name());
                value.put("reason", "앞 회차에서 대상이나 내용을 확정하지 못한 참고 정보입니다. 원문을 확인해 주세요.");
            }
            ObjectNode selection = value.putObject("selection");
            var actualIds = selection.putArray("actualSubjectIds");
            var provisionalKeys = selection.putArray("provisionalSubjectKeys");
            var category = edited ? candidate.getFinalCategory() : candidate.getCategory();
            if (category != null) selection.put("worldCategory", category.name());
            selection.put("rawSubjectMention", edited ? candidate.getFinalSubjectName() : candidate.getSubjectName());
            if (!edited && validAnalysis) {
                var resolutionType = candidate.getSubjectResolutionType();
                boolean linkedSubject = candidate.hasSubjectResolution()
                        && (resolutionType == WorldSettingSubjectResolutionType.NEW
                        || resolutionType == WorldSettingSubjectResolutionType.EXISTING);
                if (linkedSubject) {
                    if (candidate.getResolvedTargetWorldSettingIds() != null)
                        candidate.getResolvedTargetWorldSettingIds().forEach(id -> actualIds.add(id.deepCopy()));
                    if (candidate.getResolvedProvisionalSubjectKeys() != null)
                        candidate.getResolvedProvisionalSubjectKeys().forEach(key -> provisionalKeys.add(key.deepCopy()));
                }
                // Legacy completed comparisons may predate the typed subject resolution.
                // Operation-selected targets from failed content comparisons stay out.
                if (candidate.getComparisonStatus() == WorldSettingComparisonStatus.COMPLETED
                        && (resolutionType == null || linkedSubject)) {
                    if (actualIds.isEmpty() && candidate.getTargetWorldSetting() != null)
                        actualIds.add(candidate.getTargetWorldSetting().getId().toString());
                    if (provisionalKeys.isEmpty() && hasText(candidate.getProvisionalSubjectKey()))
                        provisionalKeys.add(candidate.getProvisionalSubjectKey());
                }
            } else if (edited && candidate.getTargetWorldSetting() != null
                    && Objects.equals(candidate.getFinalSubjectName(), candidate.getTargetWorldSetting().getSubjectName())
                    && category == candidate.getTargetWorldSetting().getCategory()) {
                actualIds.add(candidate.getTargetWorldSetting().getId().toString());
            }
            references.set("pending-world:" + candidate.getId(), value);
        }
    }

    private boolean hasCurrentSource(AnalysisJob job) {
        if (job.isSettingBookSource()) return job.hasCurrentSourceVersion();
        Episode episode = job.getEpisode();
        // CONFIRMED_ONLY도 명시적으로 검사한다. 그 모드의 hasCurrentSourceVersion은 항상 true다.
        return episode.getStatus() != EpisodeStatus.ARCHIVED
                && job.getSourceContentHash() != null && !job.getSourceContentHash().isBlank()
                && job.getSourceContentS3Key() != null && !job.getSourceContentS3Key().isBlank()
                && Objects.equals(job.getSourceEpisodeNo(), episode.getEpisodeNo())
                && Objects.equals(job.getSourceContentHash(), episode.getContentHash())
                && Objects.equals(job.getSourceContentS3Key(), episode.getContentS3Key())
                && Objects.equals(job.getSourceContentS3Version(), episode.getContentS3Version());
    }

    private boolean sameSource(AnalysisJob job, UUID workId, Episode episode,
            org.monitoring.catchholebackend.domain.upload.entity.UploadFile book,
            org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision revision) {
        if (!Objects.equals(job.getWork().getId(),workId)) return false;
        if (job.isSettingBookSource()) return episode == null && book != null && revision != null
                && book.getId().equals(job.getSourceSettingBook().getId())
                && revision.getId().equals(job.getSourceSettingBookRevision().getId());
        return episode != null
                && Objects.equals(job.getEpisode().getId(), episode.getId());
    }

    private boolean hasEvidence(JsonNode evidence) {
        if (evidence == null || !evidence.isArray()) return false;
        for (JsonNode span : evidence) {
            if (span.path("quote").isTextual() && !span.path("quote").asText().isBlank()) return true;
        }
        return false;
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }

    private ObjectNode reference(String domain, String subject, AnalysisJob source, String setting, String value,
            JsonNode evidence, boolean edited) {
        ObjectNode reference = JsonNodeFactory.instance.objectNode();
        reference.put("domain", domain);
        reference.put("subjectName", subject);
        org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisSourceMetadata.put(reference, source);
        reference.put("confirmationStatus", "UNCONFIRMED");
        reference.put("reason", edited
                ? "사용자가 내용을 수정했지만 아직 작품 설정에 반영하지 않은 참고 정보입니다. 인용은 수정 전 원문의 근거입니다."
                : source.isSettingBookSource() ? "설정집에서 추출했지만 아직 작품 설정에 반영하지 않은 참고 정보입니다."
                : "앞 회차에서 추출했지만 아직 작품 설정에 반영하지 않은 참고 정보입니다.");
        reference.put("settingName", setting);
        reference.put("value", value);
        reference.set("evidenceSpans", evidence.deepCopy());
        return reference;
    }
}
