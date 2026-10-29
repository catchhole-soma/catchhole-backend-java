package org.monitoring.catchholebackend.domain.validation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.character.processor.*;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.service.CharacterService;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationCorrectionRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationCorrectionReceipt;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationCorrection;
import org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode;
import org.monitoring.catchholebackend.domain.validation.repository.*;
import org.monitoring.catchholebackend.domain.validation.type.ValidationPreparationStatus;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingRepository;
import org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingService;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class ValidationCorrectionServiceImpl implements ValidationCorrectionService {
    private final WorkRepository works;
    private final ValidationReportRepository reports;
    private final ValidationCorrectionRepository corrections;
    private final WorkCharacterRepository characters;
    private final WorldSettingRepository worldSettings;
    private final CharacterService characterEdits;
    private final WorldSettingService worldEdits;
    private final CharacterSnapshotAccessor snapshots;
    private final CharacterSnapshotSourceManager snapshotSources;
    private final AnalysisStateJournal journal;
    private final EntityManager em;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    @Override @Transactional
    public ValidationCorrectionReceipt correctSettingAndLinkBaseline(Long memberId,UUID workId,UUID reportId,ValidationCorrectionRequest request) {
        works.getOwnedWorkForUpdate(workId,memberId);
        String hash=journal.hash(json.valueToTree(request));
        var existing=corrections.findByWorkIdAndRequestKey(workId,request.idempotencyKey());
        if(existing.isPresent()) {
            if(!existing.get().getReport().getId().equals(reportId) || !existing.get().getRequestHash().equals(hash)) throw new AppException(ValidationErrorCode.VALIDATION_IDEMPOTENCY_CONFLICT);
            return ValidationCorrectionReceipt.from(existing.get());
        }
        var report=reports.findByIdAndWorkId(reportId,workId).orElseThrow(()->new AppException(ValidationErrorCode.VALIDATION_NOT_FOUND));
        em.flush(); em.refresh(report);
        if(!report.isCurrent() || report.getVersion()!=request.expectedReportVersion()) throw new AppException(ValidationErrorCode.VALIDATION_REPORT_VERSION_CONFLICT);
        var episode=report.getEpisode(); em.refresh(episode);
        if(episode.getContentRevision()!=report.getSourceContentRevision() || !Objects.equals(episode.getContentHash(),report.getSourceContentHash())
                || !Objects.equals(episode.getContentS3Key(),report.getSourceContentS3Key()) || !Objects.equals(episode.getContentS3Version(),report.getSourceContentS3Version())) throw conflict();
        if((request.character()==null)==(request.worldSetting()==null) || !report.getId().equals(request.baselineId())
                || report.getBaselineStatus()!=ValidationPreparationStatus.READY || report.getBaselineRevision()!=request.expectedBaselineRevision()
                || !Objects.equals(report.getBaselineHash(),request.expectedBaselineHash())) throw conflict();
        JsonNode entry;
        try {
            int index=Integer.parseInt(request.entryRef().substring(1))-1;
            entry=report.getBaselineEntries().get(index);
        } catch(IndexOutOfBoundsException | NumberFormatException exception) {throw conflict();}
        if(entry==null || !journal.hash(entry).equals(request.expectedEntryHash())) throw conflict();
        JsonNode priorScopes=report.getBaselineSeed().path("explicitAuthorCorrections").deepCopy();
        ObjectNode scope;
        if(request.character()!=null) scope=correctCharacter(memberId,workId,entry,request.character());
        else scope=correctWorldSetting(memberId,workId,entry,request.worldSetting());
        scope.put("sourceKind","AUTHOR_MANUAL_EDIT").put("scopeKind","CURRENT_VALUE");
        if(priorScopes.isArray() && !priorScopes.isEmpty()) scope.set("priorCorrections",priorScopes);
        // Existing edit services synchronously invalidate the baseline. This receipt is saved only after that real edit succeeds.
        em.flush();
        var correction=corrections.save(ValidationCorrection.create(report,request.idempotencyKey(),hash,request.entryRef(),request.expectedEntryHash(),
                request.expectedBaselineRevision(),request.expectedBaselineHash(),scope.path("entityVersion").asLong(),scope));
        em.flush(); return ValidationCorrectionReceipt.from(correction);
    }

    private ObjectNode correctCharacter(Long memberId,UUID workId,JsonNode entry,ValidationCorrectionRequest.CharacterCorrection command) {
        if(!"characters".equals(entry.path("domain").asText()) || !entry.path("subjectRef").asText().equals("character:"+command.targetId())) throw conflict();
        var character=characters.findByIdAndWorkIdAndStatusForUpdate(command.targetId(),workId,CharacterStatus.ACTIVE).orElseThrow(this::conflict);
        em.refresh(character);
        JsonNode state=entry.path("state");
        if(character.getSnapshotVersion()!=command.expectedSnapshotVersion() || state.path("snapshotVersion").asLong(-1)!=command.expectedSnapshotVersion()
                || !character.getName().equals(state.path("name").asText())) throw conflict();
        var current=snapshots.read(character,snapshotSources.findSourceFactsBySlot(character));
        if(current.size()!=state.path("slots").size()) throw conflict();
        for(var slot:current.entrySet()) {
            JsonNode frozen=state.path("slots").path(slot.getKey().factType().name()+":"+slot.getKey().factKey());
            if(!Objects.equals(slot.getValue().factValue(),nullable(frozen,"factValue")) || !Objects.equals(value(slot.getValue().valueJson()),value(frozen.get("valueJson")))) throw conflict();
        }
        var key=new CharacterSnapshotSlot(command.factType(),command.factKey());
        var before=current.get(key); if(before==null) throw conflict();
        characterEdits.correctCharacterSetting(memberId,workId,command.targetId(),command.edit(),command.expectedSnapshotVersion(),command.factType(),command.factKey());
        var after=snapshots.read(character,snapshotSources.findSourceFactsBySlot(character)).get(key);
        var scope=JsonNodeFactory.instance.objectNode().put("domain","characters").put("targetId",command.targetId().toString())
                .put("factType",command.factType().name()).put("factKey",command.factKey()).put("entityVersion",character.getSnapshotVersion())
                .put("beforeValue",before.factValue()).put("afterValue",after==null?null:after.factValue());
        scope.set("beforeValueJson",value(before.valueJson())); scope.set("afterValueJson",after==null?NullNode.instance:value(after.valueJson())); return scope;
    }

    private ObjectNode correctWorldSetting(Long memberId,UUID workId,JsonNode entry,ValidationCorrectionRequest.WorldSettingCorrection command) {
        if(!"worldSettings".equals(entry.path("domain").asText()) || !entry.path("subjectRef").asText().equals("world:"+command.targetId())) throw conflict();
        var setting=worldSettings.findByIdAndWorkIdForUpdate(command.targetId(),workId).orElseThrow(this::conflict);
        em.refresh(setting);
        var edit=command.edit(); var state=entry.path("state");
        if(setting.getVersion()!=command.expectedVersion() || !Objects.equals(edit.version(),command.expectedVersion())
                || state.path("version").asLong(-1)!=command.expectedVersion() || !setting.getPropertiesJson().equals(state.path("propertiesJson"))
                || !setting.getCategory().name().equals(state.path("category").asText()) || !setting.getSubjectName().equals(state.path("subjectName").asText())) throw conflict();
        String before=setting.getPropertyValue(edit.currentScopeName(),edit.currentSettingName()); if(before==null) throw conflict();
        var original=setting.getPropertiesJson().deepCopy();
        worldEdits.updateWorldSettingProperty(memberId,workId,command.targetId(),edit);
        if(original.equals(setting.getPropertiesJson())) throw conflict();
        return JsonNodeFactory.instance.objectNode().put("domain","worldSettings").put("targetId",command.targetId().toString())
                .put("currentScopeName",edit.currentScopeName()).put("currentSettingName",edit.currentSettingName())
                .put("scopeName",edit.scopeName()).put("settingName",edit.settingName()).put("beforeValue",before)
                .put("afterValue",setting.getPropertyValue(edit.scopeName(),edit.settingName())).put("entityVersion",setting.getVersion());
    }
    private String nullable(JsonNode node,String field) {return node.path(field).isTextual()?node.path(field).asText():null;}
    private JsonNode value(JsonNode node) {return node==null?NullNode.instance:node.deepCopy();}
    private AppException conflict() {return new AppException(ValidationErrorCode.VALIDATION_CORRECTION_CONFLICT);}
}
