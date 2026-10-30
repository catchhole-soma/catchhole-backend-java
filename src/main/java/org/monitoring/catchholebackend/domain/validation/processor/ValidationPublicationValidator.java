package org.monitoring.catchholebackend.domain.validation.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.*;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationDataRequest;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationStage;
import org.monitoring.catchholebackend.domain.validation.service.ValidationDataService;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.springframework.stereotype.Component;
import static org.monitoring.catchholebackend.domain.validation.processor.ValidationExecutionAccess.error;
import static org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode.*;

/** No Work lock or write transaction spans source I/O. Only trusted source refs are readable. */
@Component @RequiredArgsConstructor
public class ValidationPublicationValidator {
    public static final int MAX_PAYLOAD_BYTES=131072;
    private final ValidationDataService data;
    private final ValidationDecisionValidator decisions;
    private final ValidationClueCollector clues;
    private final org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper mapper;
    public record Context(UUID jobId,UUID lease,Scope scope,Long member,UUID work,String baselineHash,String targetHash,JsonNode configuration,JsonNode entries,Map<String,JsonNode> sourceReferences) {}
    public record Prepared(Context context,ValidationBranch branch,JsonNode grid,Map<String,JsonNode> sources,int entryCount) {}

    public Prepared prepare(Context c,ValidationBranch branch,JsonNode savedGrid,String requestedBook) {
        var input=read(c,"INPUT",null,null,null,null,null);var target=read(c,"READ_SOURCE","T1",c.targetHash(),nullable(input.path("target"),"contentS3Version"),null,null);
        if(target.path("text").asText().isEmpty())throw error(VALIDATION_RESULT_INVALID);
        var limits=c.configuration().path("budgets").path(branch==ValidationBranch.SETTING?"setting":"typo");
        int core=limits.path("segmentChars").asInt(),context=limits.path("contextChars").asInt();
        var grid=JsonNodeFactory.instance.objectNode().put("gridVersion",1);var units=grid.putArray("units");
        ArrayNode segments=segments(target.path("totalCodePoints").asInt(),core,context);grid.set("segments",segments);
        var sources=new LinkedHashMap<String,JsonNode>();sources.put("T1",target);
        if(branch==ValidationBranch.TYPO)for(var s:segments)unit(units,"TYPO_SEGMENT",s.path("ref").asText());
        else {
            grid.put("clueVersion",1).set("modelClues",clues.collectValidationClues(target.path("text").asText(),mapper.toValidationNames(c.entries())));
            int count=input.path("entryCount").asInt(),page=limits.path("cataloguePageSize").asInt();
            for(var s:segments) {
                String ref=s.path("ref").asText();unit(units,"PLANNER",ref);unit(units,"COVERAGE",ref);
                for(int offset=0,index=1;offset<count;offset+=page,index++)unit(units,"RELATED_SELECTION",ref+":PAGE"+index);
            }
            var books=grid.putArray("books");
            for(var source:input.path("sources"))if("SETTING_BOOK".equals(source.path("sourceKind").asText())) {
                String ref=source.path("sourceRef").asText();var previous=bookOrNull(savedGrid,ref);var b=books.addObject().put("sourceRef",ref);
                if(previous!=null&&previous.has("totalCodePoints"))b.setAll((ObjectNode)previous.deepCopy());
                else if(ref.equals(requestedBook)) {
                    var book=read(c,"READ_SOURCE",ref,source.path("contentHash").asText(),nullable(source,"contentS3Version"),null,null);sources.put(ref,book);
                    var parts=segments(book.path("totalCodePoints").asInt(),core,context);b.put("totalCodePoints",book.path("totalCodePoints").asInt()).put("plannedChecks",(long)parts.size()*segments.size()).set("segments",parts);
                }
            }
            bookUnits(grid);
        }
        return new Prepared(c,branch,grid,sources,input.path("entryCount").asInt());
    }
    /** Merge concurrent source discoveries under Work without shrinking the server-owned required grid. */
    public Prepared extendGrid(Prepared prepared,JsonNode current) {
        if(current==null)return prepared;
        if(prepared.branch()==ValidationBranch.TYPO) {if(!decisions.same(current,prepared.grid()))throw error(VALIDATION_SOURCE_VERSION_CONFLICT);return prepared;}
        if(!decisions.same(gridBase(current),gridBase(prepared.grid())))throw error(VALIDATION_SOURCE_VERSION_CONFLICT);
        var merged=(ObjectNode)prepared.grid().deepCopy();var books=(ArrayNode)merged.path("books");
        if(current.path("books").size()!=books.size())throw error(VALIDATION_SOURCE_VERSION_CONFLICT);
        for(int i=0;i<books.size();i++) {
            var proposed=books.path(i);var old=current.path("books").path(i);
            if(!old.path("sourceRef").equals(proposed.path("sourceRef")))throw error(VALIDATION_SOURCE_VERSION_CONFLICT);
            if(old.has("totalCodePoints")) {
                if(proposed.has("totalCodePoints")&&!decisions.same(old,proposed))throw error(VALIDATION_SOURCE_VERSION_CONFLICT);
                books.set(i,old.deepCopy());
            }
        }
        bookUnits(merged);return new Prepared(prepared.context(),prepared.branch(),merged,prepared.sources(),prepared.entryCount());
    }
    private ObjectNode gridBase(JsonNode grid) {
        var base=(ObjectNode)grid.deepCopy();base.remove(List.of("books","pendingBookRefs"));var units=base.putArray("units");
        for(var row:grid.path("units"))if(!Set.of("BOOK_SOURCE","BOOK_COVERAGE","MERGED_PLAN").contains(row.path("stage").asText()))units.add(row);
        return base;
    }
    private void bookUnits(ObjectNode grid) {
        var base=gridBase(grid);var units=grid.putArray("units");units.addAll((ArrayNode)base.path("units"));var pending=grid.putArray("pendingBookRefs");
        for(var book:grid.path("books")) {
            String ref=book.path("sourceRef").asText();unit(units,"BOOK_SOURCE",ref);
            if(!book.has("totalCodePoints")) {pending.add(ref);continue;}
            for(var segment:grid.path("segments"))for(int i=0;i<book.path("segments").size();i++)unit(units,"BOOK_COVERAGE",segment.path("ref").asText()+":"+ref+"SEG"+(i+1));
        }
        unit(units,"MERGED_PLAN","PLAN");
    }
    public static boolean sourcesReady(JsonNode grid) {return grid!=null&&grid.path("pendingBookRefs").isEmpty();}
    public void recheck(Context c) {read(c,"VALIDATE",null,null,null,null,null);}
    public void payload(Prepared p,JsonNode payload,List<Citation> citations) {
        if(payload.toString().getBytes(StandardCharsets.UTF_8).length>MAX_PAYLOAD_BYTES||citations.size()>1000)throw error(VALIDATION_RESULT_INVALID);
        Set<String> exact=new HashSet<>();
        for(var c:citations) {
            if(c.start()<0||c.end()<=c.start()||c.quote()==null||c.quote().isEmpty()||!exact.add(signature(c.sourceRef(),c.start(),c.end(),c.quote())))throw error(VALIDATION_RESULT_INVALID);
            var full=p.sources().get(c.sourceRef());
            if(full==null) { full=read(p.context(),"READ_SOURCE",c.sourceRef(),c.sourceHash(),c.sourceVersion(),null,null);p.sources().put(c.sourceRef(),full); }
            if(!c.sourceHash().equals(full.path("contentHash").asText()))throw error(VALIDATION_RESULT_INVALID);
            var actual=read(p.context(),"READ_SOURCE",c.sourceRef(),c.sourceHash(),c.sourceVersion(),c.start(),c.end());
            if(!c.quote().equals(actual.path("text").asText())||actual.path("start").asInt()!=c.start()||actual.path("end").asInt()!=c.end())throw error(VALIDATION_RESULT_INVALID);
        }
        validateQuotes(p,payload,exact,0);
    }
    private void validateQuotes(Prepared p,JsonNode n,Set<String> citations,int depth) {
        if(depth>50)throw error(VALIDATION_RESULT_INVALID);
        if(n.isArray()) {for(var child:n)validateQuotes(p,child,citations,depth+1);return;}
        if(!n.isObject())return;
        for(var field:n.properties())switch(field.getKey()) {
            case "target_spans","spans","evidence" -> {
                if(!field.getValue().isArray())throw error(VALIDATION_RESULT_INVALID);
                for(var span:field.getValue())validateCopy(p,span,citations,depth+1,false);
            }
            case "target_context","additional_target_context" -> {
                if(!field.getValue().isArray())throw error(VALIDATION_RESULT_INVALID);
                for(var context:field.getValue())validateCopy(p,context,citations,depth+1,true);
            }
            case "span" -> validateCopy(p,field.getValue(),citations,depth+1,false);
            case "settings" -> {
                if(!field.getValue().isArray())throw error(VALIDATION_RESULT_INVALID);
                var known=mapper.toValidationSettings(p.context().entries(),p.context().sourceReferences());
                for(var setting:field.getValue()) {
                    var actual=known.get(setting.path("entry_ref").asText());
                    if(actual==null||!decisions.same(actual,setting))throw error(VALIDATION_RESULT_INVALID);
                    validateDepth(setting,depth+2);
                }
            }
            // Copies outside the typed span/context paths cannot hide in arbitrary checkpoint JSON.
            case "quote","text" -> throw error(VALIDATION_RESULT_INVALID);
            default -> validateQuotes(p,field.getValue(),citations,depth+1);
        }
    }
    private void validateCopy(Prepared p,JsonNode n,Set<String> citations,int depth,boolean context) {
        if(depth>50||!n.isObject()||!n.path("start").isIntegralNumber())throw error(VALIDATION_RESULT_INVALID);
        String field=context?"text":"quote",ref=n.path(n.has("source_ref")?"source_ref":"sourceRef").asText(context?"T1":"");
        if(!n.path(field).isTextual()||!context&&!n.path("end").isIntegralNumber())throw error(VALIDATION_RESULT_INVALID);
        String value=n.path(field).asText();int start=n.path("start").asInt(),end=context?start+value.codePointCount(0,value.length()):n.path("end").asInt();
        if(!citations.contains(signature(ref,start,end,value)))throw error(VALIDATION_RESULT_INVALID);
        for(var extra:n.properties())if(!Set.of(field,"source_ref","sourceRef","start","end").contains(extra.getKey())) {
            if(Set.of("text","quote").contains(extra.getKey()))throw error(VALIDATION_RESULT_INVALID);
            validateQuotes(p,extra.getValue(),citations,depth+1);
        }
    }
    private void validateDepth(JsonNode n,int depth) {
        if(depth>50)throw error(VALIDATION_RESULT_INVALID);
        if(n.isContainerNode())for(var child:n)validateDepth(child,depth+1);
    }
    public void stage(Prepared p,ValidationStageKind kind,String ref,JsonNode body,List<ValidationStage> stored) {
        if(!contains(p.grid(),kind,ref))throw error(VALIDATION_RESULT_INVALID);
        if(kind==ValidationStageKind.MERGED_PLAN) return;
        if(kind==ValidationStageKind.BOOK_SOURCE) {
            var book=findBook(p.grid(),ref);
            if(!"READY".equals(body.path("source_read_status").asText())||body.path("planned_checks").asLong(-1)!=book.path("plannedChecks").asLong())throw error(VALIDATION_RESULT_INVALID);
            return;
        }
        String segment=ref.split(":")[0];
        if(kind==ValidationStageKind.RELATED_SELECTION) {
            int page=Integer.parseInt(ref.substring(ref.lastIndexOf("PAGE")+4));int size=p.context().configuration().path("budgets").path("setting").path("cataloguePageSize").asInt();
            var expected=new HashSet<String>();for(int i=(page-1)*size;i<Math.min(page*size,p.entryCount());i++)expected.add("B"+(i+1));
            var actual=new HashSet<String>();if(!body.path("entries").isArray())throw error(VALIDATION_RESULT_INVALID);
            for(var e:body.path("entries"))if(!e.path("selected").isBoolean()||e.path("reason").asText().isBlank()||!actual.add(e.path("entry_ref").asText()))throw error(VALIDATION_RESULT_INVALID);
            if(!actual.equals(expected))throw error(VALIDATION_RESULT_INVALID);return;
        }
        if(kind==ValidationStageKind.TYPO_SEGMENT) {batchSegments(body,segment);return;}
        batchSegments(body,segment);
        if(!body.path("questions").isArray()||!body.path("rules").isArray())throw error(VALIDATION_RESULT_INVALID);
        Set<String> expected=new HashSet<>();
        if(kind==ValidationStageKind.COVERAGE)for(var required:p.grid().path("units"))if("RELATED_SELECTION".equals(required.path("stage").asText())&&required.path("ref").asText().startsWith(segment+":"))
            if(stored.stream().noneMatch(s->s.isSuccessful()&&s.getStage()==ValidationStageKind.RELATED_SELECTION&&s.getRef().equals(required.path("ref").asText())))throw error(VALIDATION_RESULT_INVALID);
        if(kind==ValidationStageKind.BOOK_COVERAGE) {
            String source=ref.substring(ref.indexOf(':')+1,ref.indexOf("SEG",ref.indexOf(':')));
            if(stored.stream().noneMatch(s->s.isSuccessful()&&s.getStage()==ValidationStageKind.BOOK_SOURCE&&s.getRef().equals(source)))throw error(VALIDATION_RESULT_INVALID);
        }
        if(kind==ValidationStageKind.COVERAGE)for(var s:stored)if(s.isSuccessful()&&s.getStage()==ValidationStageKind.RELATED_SELECTION&&s.getRef().startsWith(segment+":"))
            for(var e:s.getPayload().path("entries"))if(e.path("selected").asBoolean())expected.add(e.path("entry_ref").asText());
        if(kind==ValidationStageKind.BOOK_COVERAGE)expected.add(ref.substring(ref.indexOf(':')+1));
        var actual=new HashSet<String>();for(var r:body.path("rules"))if(!Set.of("CHECKED","NOT_APPLICABLE").contains(r.path("status").asText())||r.path("reason").asText().isBlank()||!actual.add(r.path("entry_ref").asText()))throw error(VALIDATION_RESULT_INVALID);
        if(!actual.equals(expected))throw error(VALIDATION_RESULT_INVALID);
        if(kind==ValidationStageKind.COVERAGE) {
            var expectedClues=new HashSet<String>();var bounds=segment(p.grid(),segment);
            for(var clue:p.grid().path("modelClues"))if(clue.path("span").path("start").asInt()>=bounds.path("start").asInt()&&clue.path("span").path("start").asInt()<bounds.path("end").asInt())expectedClues.add(clue.path("ref").asText());
            var actualClues=new HashSet<String>();for(var clue:body.path("clue_checks"))if(!actualClues.add(clue.path("entry_ref").asText()))throw error(VALIDATION_RESULT_INVALID);
            if(!expectedClues.equals(actualClues))throw error(VALIDATION_RESULT_INVALID);
        }
        for(var clue:body.path("clue_checks"))if(!Set.of("CHECKED","NOT_APPLICABLE").contains(clue.path("status").asText())||clue.path("reason").asText().isBlank())throw error(VALIDATION_RESULT_INVALID);
        Set<String> locals=new HashSet<>();var bounds=segment(p.grid(),segment);boolean any=false;
        for(var q:body.path("questions")) {
            decisions.draft(q,decisions.subjects(p));
            if(q.path("local_ref").asText().isBlank()||!locals.add(q.path("local_ref").asText())||!q.path("target_spans").isArray()||q.path("target_spans").isEmpty())throw error(VALIDATION_RESULT_INVALID);
            for(var span:q.path("target_spans")) {
                if(!"T1".equals(span.path("source_ref").asText())||span.path("start").asInt()<bounds.path("contextStart").asInt()||span.path("end").asInt()>bounds.path("contextEnd").asInt())throw error(VALIDATION_RESULT_INVALID);
                any|=span.path("start").asInt()<bounds.path("end").asInt()&&span.path("end").asInt()>bounds.path("start").asInt();
            }
        }
        if("PLANNED".equals(body.path("segments").path(0).path("status").asText())!=any)throw error(VALIDATION_RESULT_INVALID);
    }
    public static void batchSegments(JsonNode body,String ref) {
        var s=body.path("segments");if(!s.isArray()||s.size()!=1||!ref.equals(s.path(0).path("segment_ref").asText())
                ||!Set.of("PLANNED","NO_CHECK").contains(s.path(0).path("status").asText())||s.path(0).path("reason").asText().isBlank())throw error(VALIDATION_RESULT_INVALID);
    }
    public static boolean contains(JsonNode grid,ValidationStageKind kind,String ref) {
        for(var row:grid.path("units"))if(kind.name().equals(row.path("stage").asText())&&ref.equals(row.path("ref").asText()))return true;return false;
    }
    public static JsonNode segment(JsonNode grid,String ref) {for(var row:grid.path("segments"))if(ref.equals(row.path("ref").asText()))return row;throw error(VALIDATION_RESULT_INVALID);}
    private static JsonNode bookOrNull(JsonNode grid,String ref) {if(grid!=null)for(var row:grid.path("books"))if(ref.equals(row.path("sourceRef").asText()))return row;return null;}
    private static JsonNode findBook(JsonNode grid,String ref) {var book=bookOrNull(grid,ref);if(book==null||!book.has("totalCodePoints"))throw error(VALIDATION_RESULT_INVALID);return book;}
    private static ArrayNode segments(int length,int core,int context) {
        var a=JsonNodeFactory.instance.arrayNode();for(int start=0,index=1;start<length;start+=core,index++)a.addObject().put("ref","TSEG"+index).put("start",start).put("end",Math.min(length,(long)start+core)).put("contextStart",Math.max(0,start-context)).put("contextEnd",Math.min(length,(long)start+core+context));return a;
    }
    private static void unit(ArrayNode units,String stage,String ref) {units.addObject().put("stage",stage).put("ref",ref);}
    private JsonNode read(Context c,String operation,String ref,String hash,String version,Integer start,Integer end) {
        var r=data.readValidationData(c.member(),c.work(),c.scope().reportId(),new ValidationDataRequest(c.baselineHash(),c.targetHash(),operation,0,100,ref,hash,version,start,end),c.jobId(),c.lease());
        if(!"READY".equals(r.path("status").asText()))throw error(VALIDATION_SOURCE_VERSION_CONFLICT);return r;
    }
    private String signature(String ref,int start,int end,String quote) {return ref+":"+start+":"+end+":"+quote;}
    private static String nullable(JsonNode n,String key) {return n.path(key).isTextual()?n.path(key).asText():null;}
}
