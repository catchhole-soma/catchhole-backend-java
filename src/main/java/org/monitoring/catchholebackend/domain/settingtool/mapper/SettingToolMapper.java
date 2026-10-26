package org.monitoring.catchholebackend.domain.settingtool.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse.Evidence;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse.EvidenceStatus;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse.Item;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse.Origin;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse.Reference;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse.Provenance;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse.SourceProvenance;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse.SourceKind;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse.ApplicationAction;
import org.monitoring.catchholebackend.domain.settingtool.type.SettingTool;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.springframework.stereotype.Component;

@Component
public class SettingToolMapper {
    public static final int MAX_VALUE_LENGTH = 2000;
    public static final int MAX_SOURCES = 20;

    public Item toCharacterResponse(WorkCharacter character) {
        return item("CHARACTER", character.getId(), character.getId(), character.getName(), null, null, null,
                character.getName(), null, null, null, null, null, character.getSnapshotVersion(), null, Origin.UNKNOWN, null, null, List.of(), null);
    }

    public Item toFieldResponse(UUID subjectId, String subjectName, String category, String scope, String key,
                                String displayName, Long version) {
        return item("FIELD", null, subjectId, subjectName, category, scope, key, displayName,
                null, null, null, null, null, version, null, Origin.UNKNOWN, null, null, List.of(), null);
    }

    public Item toWorldResponse(WorldSetting world) {
        return item("WORLD_SETTING", world.getId(), world.getId(), world.getSubjectName(), world.getCategory().name(),
                null, null, world.getSubjectName(), null, null, null, null, null, world.getVersion(), null, Origin.UNKNOWN, null, null, List.of(), null);
    }

    public Item toCharacterCurrentResponse(WorkCharacter character, CharacterSnapshotEntry entry, String displayName, List<CharacterFact> sources) {
        boolean allConfirmed = !sources.isEmpty() && sources.stream().allMatch(fact -> fact.getSettingCandidate() != null);
        return withProvenance(item("CURRENT", null, character.getId(), character.getName(), entry.slot().factType().name(), null,
                entry.slot().factKey(), displayName, entry.factValue(), entry.valueJson(), true, null, false,
                character.getSnapshotVersion(), null, allConfirmed ? Origin.CONFIRMED_ANALYSIS : Origin.UNKNOWN, null, null,
                sources.reversed().stream().map(f -> new Reference(SettingTool.CHARACTER_EVIDENCE, f.getId())).toList(), null), sources.reversed());
    }

    public Item toFactResponse(CharacterFact fact, String displayName, boolean current, Evidence evidence) {
        var candidate = fact.getSettingCandidate();
        return withProvenance(item(evidence == null ? "HISTORY" : "EVIDENCE", fact.getId(), fact.getWorkCharacter().getId(),
                fact.getWorkCharacter().getName(), fact.getFactType().name(), null, fact.getFactKey(), displayName,
                fact.getFactValue(), fact.getValueJson(), current,
                candidate == null || candidate.getConfirmedApplicationMode() == null ? null
                        : candidate.getConfirmedApplicationMode() == CharacterFactConfirmApplicationMode.HISTORY_ONLY ? "HISTORY_ONLY"
                        : candidate.getSuggestedOperation() == null ? null : candidate.getSuggestedOperation().name(),
                candidate == null || candidate.getConfirmedApplicationMode() == null ? null
                        : candidate.getConfirmedApplicationMode() == CharacterFactConfirmApplicationMode.HISTORY_ONLY,
                null, fact.getEffectiveFromEpisodeNo(), candidate == null ? Origin.UNKNOWN : Origin.CONFIRMED_ANALYSIS,
                fact.getSourceEpisode() != null ? fact.getSourceEpisode().getId()
                        : candidate != null && candidate.getEpisode() != null ? candidate.getEpisode().getId() : null,
                sourceEpisodeNo(candidate == null ? fact.getExtractedByJob() : candidate.getAnalysisJob(),
                        fact.getSourceEpisode() != null ? fact.getSourceEpisode() : candidate == null ? null : candidate.getEpisode()),
                List.of(new Reference(SettingTool.CHARACTER_EVIDENCE, fact.getId())), evidence), List.of(fact));
    }

    public Item toWorldCurrentResponse(WorldSetting world, WorldSetting.Property property, List<Reference> sources, boolean truncated) {
        var item = item("CURRENT", null, world.getId(), world.getSubjectName(), world.getCategory().name(), property.scopeName(),
                property.settingName(), property.settingName(), property.value(), null, true, null, false, world.getVersion(), null,
                world.isManuallyEdited(property.scopeName(), property.settingName()) ? Origin.USER_INPUT
                        : sources.isEmpty() ? Origin.UNKNOWN : Origin.CONFIRMED_ANALYSIS, null, null, sources, null);
        return truncated ? withSourcesTruncated(item) : item;
    }

    public Item toWorldHistoryResponse(WorldSettingCandidate candidate, Boolean current, Evidence evidence) {
        var world = candidate.getTargetWorldSetting();
        return item(evidence == null ? "HISTORY" : "EVIDENCE", candidate.getId(), world.getId(), world.getSubjectName(),
                candidate.getFinalCategory().name(), candidate.getFinalScopeName(), candidate.getFinalSettingName(), candidate.getFinalSettingName(),
                candidate.getFinalValue(), null, current, candidate.getFinalOperation().name(), candidate.isHistoryOnly(),
                candidate.getAppliedWorldSettingVersion(), null, Origin.CONFIRMED_ANALYSIS, candidate.getSourceEpisode().getId(),
                sourceEpisodeNo(candidate.getAnalysisJob(), candidate.getSourceEpisode()),
                List.of(new Reference(SettingTool.WORLD_EVIDENCE, candidate.getId())), evidence);
    }

    public Item withSourcesTruncated(Item item) {
        return new Item(item.kind(), item.id(), item.subjectId(), item.subjectName(), item.category(), item.scopeName(), item.settingKey(),
                item.displayName(), item.value(), item.structuredValueJson(), item.valueTruncated(), item.contributesToCurrent(),
                item.operation(), item.historyOnly(), item.settingVersion(), item.effectiveFromEpisodeNo(), item.sourceEpisodeId(), item.sourceEpisodeNo(), item.origin(), item.sources(), true, item.evidence(),
                item.provenance() == null ? null : new Provenance(item.provenance().sources(), true));
    }

    private Item withProvenance(Item item, List<CharacterFact> facts) {
        if (facts.isEmpty() || item.evidence() != null && item.evidence().status() == EvidenceStatus.OUT_OF_RANGE) return item;
        var provenance = new Provenance(facts.stream().limit(MAX_SOURCES).map(this::toSourceProvenance).toList(),
                facts.size() > MAX_SOURCES);
        return new Item(item.kind(), item.id(), item.subjectId(), item.subjectName(), item.category(), item.scopeName(), item.settingKey(),
                item.displayName(), item.value(), item.structuredValueJson(), item.valueTruncated(), item.contributesToCurrent(),
                item.operation(), item.historyOnly(), item.settingVersion(), item.effectiveFromEpisodeNo(), item.sourceEpisodeId(), item.sourceEpisodeNo(),
                item.origin(), item.sources(), item.sourcesTruncated(), item.evidence(), provenance);
    }

    private SourceProvenance toSourceProvenance(CharacterFact fact) {
        var candidate = fact.getSettingCandidate();
        return new SourceProvenance(new Reference(SettingTool.CHARACTER_EVIDENCE, fact.getId()), sourceKind(fact),
                candidate == null ? null : candidate.getUserContentModified(), applicationAction(candidate), fact.getCreatedAt());
    }

    private SourceKind sourceKind(CharacterFact fact) {
        var candidate = fact.getSettingCandidate();
        var kind = candidate != null ? candidate.getSourceKind()
                : fact.getExtractedByJob() == null ? null : fact.getExtractedByJob().getSourceKind();
        if (kind != null) return SourceKind.valueOf(kind.name());
        if (fact.getSourceEpisode() != null) return SourceKind.EPISODE;
        // 후보·Job·회차 없는 Fact는 직접 입력과 레거시를 구분할 저장 표지가 없다.
        return SourceKind.UNKNOWN;
    }

    private ApplicationAction applicationAction(SettingCandidate candidate) {
        if (candidate == null) return ApplicationAction.UNKNOWN;
        if (candidate.isReviewedAutomatically()) return ApplicationAction.AUTOMATIC_CONFIRM;
        if (candidate.getReviewedApplicationMode() != null) {
            return ApplicationAction.USER_CONFIRM;
        }
        // false/userModified나 파기 시 지워지는 rawComparisonJson으로 과거 행위자를 추정하지 않는다.
        return ApplicationAction.UNKNOWN;
    }

    private Item item(String kind, UUID id, UUID subjectId, String subjectName, String category, String scope, String key,
                      String display, String value, JsonNode json, Boolean current, String operation, Boolean historyOnly,
                      Long version, Integer effectiveNo, Origin origin, UUID sourceEpisodeId, Integer sourceEpisodeNo, List<Reference> sources, Evidence evidence) {
        if (evidence != null && evidence.status() == EvidenceStatus.OUT_OF_RANGE) {
            // 회차 범위 밖 Fact의 값이 원문 없는 답변 근거로 우회 사용되지 않게 한다.
            value = null;
            json = null;
            current = null;
            operation = null;
            historyOnly = null;
            version = null;
            effectiveNo = null;
            sources = List.of();
        }
        String structured = json == null ? null : json.toString();
        boolean truncated = overLimit(value) || overLimit(structured);
        return new Item(kind, id, subjectId, subjectName, category, scope, key, display, clipped(value),
                overLimit(structured) ? null : structured, truncated, current, operation, historyOnly, version, effectiveNo,
                sourceEpisodeId, sourceEpisodeNo, origin, sources.stream().limit(MAX_SOURCES).toList(), sources.size() > MAX_SOURCES, evidence, null);
    }
    private Integer sourceEpisodeNo(org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob job,
                                    org.monitoring.catchholebackend.domain.episode.entity.Episode episode) {
        return job != null && job.getSourceEpisodeNo() != null ? job.getSourceEpisodeNo()
                : episode == null ? null : episode.getEpisodeNo();
    }
    private boolean overLimit(String value) { return value != null && value.codePointCount(0, value.length()) > MAX_VALUE_LENGTH; }
    private String clipped(String value) { return overLimit(value) ? value.substring(0, value.offsetByCodePoints(0, MAX_VALUE_LENGTH)) : value; }
}
