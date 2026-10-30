package org.monitoring.catchholebackend.domain.validation.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component @RequiredArgsConstructor
public class ValidationDataMapper {
    private final AnalysisStateJournal journal;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Spring Boot 4의 Jackson 3 경계에 Jackson 2 tree 객체 대신 표준 JSON 값을 전달한다. */
    public Map<String, Object> toTransport(JsonNode response) {
        return objectMapper.convertValue(response, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    public ObjectNode toResponse(ValidationReport report) {
        return JsonNodeFactory.instance.objectNode().put("formatVersion", 1)
                .put("reportId", report.getId().toString()).put("baselineRevision", report.getBaselineRevision())
                .put("reportRevision", report.getRevision()).put("reportVersion", report.getVersion()).put("reportMode", report.getMode().name())
                .put("baselineHash", report.getBaselineHash()).put("targetHash", report.getSourceContentHash())
                .put("clueVersion", 1).put("projectionVersion", 1).put("manifestHash", journal.hash(report.getBaselineSeed().path("sources"))).put("status", "READY");
    }

    public LinkedHashMap<String, ObjectNode> toSourceReferences(ValidationReport report) {
        var result = new LinkedHashMap<String, ObjectNode>();
        ObjectNode target = report.getBaselineSeed().path("target").deepCopy();
        target.put("sourceKind", "EPISODE").put("sourceRef", "T1"); result.put("T1", target);
        int index = 1;
        for (JsonNode source : report.getBaselineSeed().path("sources")) {
            ObjectNode copy = source.deepCopy(); String ref = "R" + index++;
            copy.put("sourceRef", ref); result.put(ref, copy);
        }
        return result;
    }

    public ArrayNode toEntries(JsonNode entries, int offset, int limit) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        for (int index = offset; index < Math.min(entries.size(), (long) offset + limit); index++) {
            ObjectNode entry = entries.get(index).deepCopy();
            entry.put("entryRef", "B" + (index + 1)).put("entryHash", journal.hash(entries.get(index))); result.add(entry);
        }
        return result;
    }
    private static final Set<String> PRIVATE=Set.of("actualCharacterId","actualWorldSettingId","provisionalSubjectKey","subjectRef","sourceKey","sourceVersion","sourceHash");
    public Map<String,JsonNode> toValidationSettings(JsonNode entries,Map<String,? extends JsonNode> sources) {
        var result=new LinkedHashMap<String,JsonNode>();var subjects=new LinkedHashMap<String,String>();int index=0;
        for(var row:entries) {
            String ref="B"+(++index);var state=row.path("state");String identity=subjectIdentity(row,ref);
            String subject=subjects.computeIfAbsent(identity,k->"S"+(subjects.size()+1));var item=JsonNodeFactory.instance.objectNode().put("entry_ref",ref).put("subject_ref",subject).put("domain",row.path("domain").asText());
            JsonNode name=state.hasNonNull("name")?state.path("name"):state.path("subjectName");item.set("name",name.isMissingNode()?NullNode.instance:name.deepCopy());item.set("aliases",state.has("aliases")?state.path("aliases").deepCopy():JsonNodeFactory.instance.arrayNode());
            item.set("state",toValidationModelState(state,sources));item.set("author_correction",NullNode.instance);result.put(ref,item);
        }return result;
    }
    /** 고정 기준 항목의 대상 identity. 같은 identity의 항목(세계관 대상과 그 확정 이력)은 같은 subject_ref를 받는다. */
    public static String subjectIdentity(JsonNode row,String entryRef) {
        return row.hasNonNull("subjectRef")?row.path("subjectRef").asText():"world:"+row.path("state").path("targetWorldSettingId").asText(entryRef);
    }
    /** toValidationSettings와 같은 규칙의 entry_ref(B1..) → subject_ref(S1..). 모델 투영 없이 대상 참조만 계산한다(#314 B2). */
    public static LinkedHashMap<String,String> toSubjectRefs(JsonNode entries) {
        var result=new LinkedHashMap<String,String>();var subjects=new LinkedHashMap<String,String>();int index=0;
        if(entries!=null)for(var row:entries) {String ref="B"+(++index);result.put(ref,subjects.computeIfAbsent(subjectIdentity(row,ref),k->"S"+(subjects.size()+1)));}
        return result;
    }
    public JsonNode toValidationModelState(JsonNode node,Map<String,? extends JsonNode> sources) {
        if(node.isObject()) {
            var result=JsonNodeFactory.instance.objectNode();node.properties().forEach(e->{if(!e.getKey().matches(".*(?:Id|Ids|Hash|S3Key|S3Version|Token)$")&&!PRIVATE.contains(e.getKey()))result.set(e.getKey(),toValidationModelState(e.getValue(),sources));});
            String id=first(node,"sourceEpisodeId","episodeId","sourceSettingBookRevisionId","revisionId"),hash=first(node,"sourceHash","contentHash"),version=first(node,"sourceVersion","contentS3Version");
            if(id!=null&&hash!=null&&!"UNAVAILABLE".equals(node.path("availability").asText())&&!"UNAVAILABLE".equals(node.path("sourceAvailability").asText()))for(var source:sources.entrySet()) {
                var s=source.getValue();if((id.equals(first(s,"episodeId"))||id.equals(first(s,"revisionId")))&&hash.equals(s.path("contentHash").asText())&&(version==null||version.equals(first(s,"contentS3Version")))){result.put("source_ref",source.getKey());break;}
            }return result;
        }
        if(node.isArray()) {var result=JsonNodeFactory.instance.arrayNode();for(var child:node)result.add(toValidationModelState(child,sources));return result;}
        return node.deepCopy();
    }
    public JsonNode toValidationSourceCatalogue(Map<String,? extends JsonNode> sources) {
        var result=JsonNodeFactory.instance.arrayNode();for(var source:sources.entrySet())if(!"T1".equals(source.getKey())) {
            var row=result.addObject().put("source_ref",source.getKey()).put("source_kind",source.getValue().path("sourceKind").asText());
            for(String name:List.of("episodeNo","revisionNo","referenceTimeKind","referenceEpisodeNo"))if(source.getValue().has(name))row.set(name,source.getValue().path(name).deepCopy());
        }return result;
    }
    public List<String> toValidationNames(JsonNode entries) {
        var names=new ArrayList<String>();for(var row:entries) {var state=row.path("state");var name=state.hasNonNull("name")?state.path("name"):state.path("subjectName");if(name.isTextual())names.add(name.asText());for(var alias:state.path("aliases"))if(alias.isTextual())names.add(alias.asText());}return names;
    }
    private static String first(JsonNode node,String... names) {for(String name:names)if(node.path(name).isTextual()&&!node.path(name).asText().isEmpty())return node.path(name).asText();return null;}
}
