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
    /**
     * 게시 명령 한 건의 UTF-8 상한. #349 후속: MERGED_PLAN은 모든 구간의 질문 초안을 한 번에 담아 30회차 작품의 65개 질문이
     * 131,072 bytes를 넘겼다(운영 2026-10-31). 질문 수에 비례하는 유일한 게시이므로 1 MiB로 올린다. 판정·COVERAGE 게시는 수십 KB다.
     */
    public static final int MAX_PAYLOAD_BYTES=1048576;
    /**
     * SETTING grid 빌더 버전. 저장 grid가 이와 다르면 공개 retry가 SETTING 분기를 버리고 다시 만든다.
     * 4(#349 개편): 원고를 segmentChars(1만) 기준 구간 수로 같은 길이로 나누고 줄바꿈 바로 뒤에서 자르며(Java·Python 공통 gridCases),
     * 구간마다 PLANNER와 MERGED_PLAN만 요구한다. 기계 단서·주체 매칭·SUBJECT_RESOLUTION·COVERAGE·설정집 단위는 없다.
     */
    public static final int SETTING_GRID_VERSION=4;
    private final ValidationDataService data;
    private final ValidationDecisionValidator decisions;
    private final org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper mapper;
    public record Context(UUID jobId,UUID lease,Scope scope,Long member,UUID work,String baselineHash,String targetHash,JsonNode configuration,JsonNode entries,Map<String,JsonNode> sourceReferences) {}
    public record Prepared(Context context,ValidationBranch branch,JsonNode grid,Map<String,JsonNode> sources,int entryCount) {}

    /** INPUT와 전체 T1만 읽어 서버 필수 grid를 만든다. 설정집은 grid에 들어가지 않는다(설정집 RAG 색인은 후속 범위). */
    public Prepared prepare(Context c,ValidationBranch branch) {
        var input=read(c,"INPUT",null,null,null,null,null);var target=read(c,"READ_SOURCE","T1",c.targetHash(),nullable(input.path("target"),"contentS3Version"),null,null);
        if(target.path("text").asText().isEmpty())throw error(VALIDATION_RESULT_INVALID);
        var limits=c.configuration().path("budgets").path(branch==ValidationBranch.SETTING?"setting":"typo");
        int core=limits.path("segmentChars").asInt(),context=limits.path("contextChars").asInt();
        var grid=JsonNodeFactory.instance.objectNode().put("gridVersion",branch==ValidationBranch.SETTING?SETTING_GRID_VERSION:1);var units=grid.putArray("units");
        var sources=new LinkedHashMap<String,JsonNode>();sources.put("T1",target);
        if(branch==ValidationBranch.TYPO) {
            // TYPO grid v1: fixed core segments, unchanged.
            ArrayNode segments=segments(target.path("totalCodePoints").asInt(),core,context);grid.set("segments",segments);
            for(var s:segments)unit(units,"TYPO_SEGMENT",s.path("ref").asText());
        } else {
            ArrayNode segments=settingSegments(target.path("text").asText().codePoints().toArray(),core,context);grid.set("segments",segments);
            for(var s:segments)unit(units,"PLANNER",s.path("ref").asText());
            unit(units,"MERGED_PLAN","PLAN");
        }
        return new Prepared(c,branch,grid,sources,input.path("entryCount").asInt());
    }
    /** Concurrent preparations must compute the identical server grid; any difference is a source/version conflict (both branches are strict). */
    public Prepared extendGrid(Prepared prepared,JsonNode current) {
        if(current!=null&&!decisions.same(current,prepared.grid()))throw error(VALIDATION_SOURCE_VERSION_CONFLICT);
        return prepared;
    }
    /**
     * SETTING grid v4 구간(Java·Python 공통 규칙, 정수 연산만). U=segmentChars, C=contextChars, L=T1 code point 길이.
     * n=ceil(L/U). k=1..n-1마다 목표 t=kL/n, 창 (lo,hi] = ((2k-1)L/(2n), (2k+1)L/(2n)] 안에서 code point[p-1]이 U+000A인 p 중
     * |p-t|가 가장 작은 곳(같으면 작은 p)에서 자른다. 창 안에 줄바꿈이 없으면 max(t, lo+1). 창은 겹치지 않고 hi>lo이므로 자르는 위치는 순증가하고 빈 구간이 없다.
     * \n만 줄바꿈으로 보아 \r\n을 가르지 않는다. 문맥은 [max(0,start-C), min(L,end+C)]이며 읽기 전용이다.
     */
    public static ArrayNode settingSegments(int[] text,int unit,int context) {
        long length=text.length,n=(length+unit-1)/unit;var bounds=new long[(int)n+1];bounds[(int)n]=length;
        for(long k=1;k<n;k++) {
            long t=k*length/n,lo=(2*k-1)*length/(2*n),hi=(2*k+1)*length/(2*n),best=-1;
            for(long p=lo+1;p<=hi;p++)if(text[(int)(p-1)]=='\n'&&(best<0||Math.abs(p-t)<Math.abs(best-t)))best=p;
            bounds[(int)k]=best<0?Math.max(t,lo+1):best;
        }
        var result=JsonNodeFactory.instance.arrayNode();
        for(int i=1;i<=n;i++)result.addObject().put("ref","TSEG"+i).put("start",bounds[i-1]).put("end",bounds[i]).put("contextStart",Math.max(0,bounds[i-1]-context)).put("contextEnd",Math.min(length,bounds[i]+context));
        return result;
    }
    /** 저장 SETTING grid가 현재 빌더(gridVersion SETTING_GRID_VERSION)가 만든 것인지. */
    public static boolean currentSettingGrid(JsonNode grid) {return grid.path("gridVersion").asInt()==SETTING_GRID_VERSION;}
    /** grid v4에는 설정집 의무가 없어 항상 true다. 저장 이력(v3)의 pendingBookRefs가 남은 grid만 false다. */
    public static boolean sourcesReady(JsonNode grid) {return grid!=null&&grid.path("pendingBookRefs").isEmpty();}
    public void recheck(Context c) {read(c,"VALIDATE",null,null,null,null,null);}
    public void payload(Prepared p,JsonNode payload,List<Citation> citations) {
        if(payload.toString().getBytes(StandardCharsets.UTF_8).length>MAX_PAYLOAD_BYTES||citations.size()>1000)throw error(VALIDATION_RESULT_INVALID);
        Set<String> exact=new HashSet<>();
        for(var c:citations) {
            if(c.start()<0||c.end()<=c.start()||c.quote()==null||c.quote().isEmpty()||!exact.add(signature(c.sourceRef(),c.start(),c.end(),c.quote())))throw error(VALIDATION_RESULT_INVALID);
            // The citation must name the frozen source identity; the full read below enforces the same hash/version on the server side.
            var frozen=p.context().sourceReferences().get(c.sourceRef());
            if(frozen==null||!c.sourceHash().equals(frozen.path("contentHash").asText())||!Objects.equals(c.sourceVersion(),nullable(frozen,"contentS3Version")))throw error(VALIDATION_RESULT_INVALID);
            var full=p.sources().get(c.sourceRef());
            if(full==null) { full=read(p.context(),"READ_SOURCE",c.sourceRef(),c.sourceHash(),c.sourceVersion(),null,null);p.sources().put(c.sourceRef(),full); }
            if(!c.sourceHash().equals(full.path("contentHash").asText()))throw error(VALIDATION_RESULT_INVALID);
            // #349 grid v4: slice the already hash-verified full text by code points instead of one ranged READ_SOURCE (a whole storage GET) per citation.
            String text=full.path("text").asText();
            if(!full.path("text").isTextual()||c.end()>text.codePointCount(0,text.length())
                    ||!c.quote().equals(text.substring(text.offsetByCodePoints(0,c.start()),text.offsetByCodePoints(0,c.end()))))throw error(VALIDATION_RESULT_INVALID);
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
                    // GH349: 행은 ref만 요구한다. 서버 canonical 전체 행(이전 Worker)은 원문이 아닌 고정 기준이라 text/quote 속성을 span으로 보지 않고,
                    // 그 밖의 행은 추가 키가 다른 payload처럼 인용 검사를 받아 검증되지 않은 복사본문을 settings에 숨길 수 없다.
                    if(decisions.same(decisions.setting(known,setting),setting))validateDepth(setting,depth+2);else validateQuotes(p,setting,citations,depth+2);
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
    /**
     * grid v4 PLANNER 단계: segments 한 행(이 구간), questions 배열만 둔다. rules·clue_checks와 질문의 lookup_intents는 없어야 한다.
     * 모든 span은 이 구간 핵심 [start,end) 안이어야 하며(앞뒤 문맥은 읽기 전용) status는 질문이 있을 때만 PLANNED다.
     * 다른 단계 종류(이전 grid의 SUBJECT_RESOLUTION·COVERAGE·BOOK_*·RELATED_SELECTION)는 v4 grid에 없어 위 contains()에서 거절된다.
     */
    public void stage(Prepared p,ValidationStageKind kind,String ref,JsonNode body,List<ValidationStage> stored) {
        if(!contains(p.grid(),kind,ref))throw error(VALIDATION_RESULT_INVALID);
        if(kind==ValidationStageKind.MERGED_PLAN) return;
        if(kind==ValidationStageKind.TYPO_SEGMENT) {batchSegments(body,ref);return;}
        if(kind!=ValidationStageKind.PLANNER)throw error(VALIDATION_RESULT_INVALID);
        batchSegments(body,ref);
        if(!body.path("questions").isArray()||body.has("rules")||body.has("clue_checks"))throw error(VALIDATION_RESULT_INVALID);
        var subjects=decisions.subjects(p);Set<String> locals=new HashSet<>();var bounds=segment(p.grid(),ref);
        for(var q:body.path("questions")) {
            decisions.draft(q,subjects);
            if(!locals.add(q.path("local_ref").asText()))throw error(VALIDATION_RESULT_INVALID);
            for(var span:q.path("target_spans"))
                if(span.path("start").asInt()<bounds.path("start").asInt()||span.path("end").asInt()>bounds.path("end").asInt()||span.path("end").asInt()<=span.path("start").asInt())throw error(VALIDATION_RESULT_INVALID);
        }
        if("PLANNED".equals(body.path("segments").path(0).path("status").asText())==body.path("questions").isEmpty())throw error(VALIDATION_RESULT_INVALID);
    }
    public static void batchSegments(JsonNode body,String ref) {
        var s=body.path("segments");if(!s.isArray()||s.size()!=1||!ref.equals(s.path(0).path("segment_ref").asText())
                ||!Set.of("PLANNED","NO_CHECK").contains(s.path(0).path("status").asText())||s.path(0).path("reason").asText().isBlank())throw error(VALIDATION_RESULT_INVALID);
    }
    public static boolean contains(JsonNode grid,ValidationStageKind kind,String ref) {
        for(var row:grid.path("units"))if(kind.name().equals(row.path("stage").asText())&&ref.equals(row.path("ref").asText()))return true;return false;
    }
    public static JsonNode segment(JsonNode grid,String ref) {for(var row:grid.path("segments"))if(ref.equals(row.path("ref").asText()))return row;throw error(VALIDATION_RESULT_INVALID);}
    private static ArrayNode segments(int length,int core,int context) {
        var a=JsonNodeFactory.instance.arrayNode();for(int start=0,index=1;start<length;start+=core,index++)a.addObject().put("ref","TSEG"+index).put("start",start).put("end",Math.min(length,(long)start+core)).put("contextStart",Math.max(0,start-context)).put("contextEnd",Math.min(length,(long)start+core+context));return a;
    }
    private static void unit(ArrayNode units,String stage,String ref) {units.addObject().put("stage",stage).put("ref",ref);}
    private JsonNode read(Context c,String operation,String ref,String hash,String version,Integer start,Integer end) {
        var r=data.readValidationData(c.member(),c.work(),c.scope().reportId(),new ValidationDataRequest(c.baselineHash(),c.targetHash(),operation,0,100,ref,hash,version,start,end,null),c.jobId(),c.lease());
        if(!"READY".equals(r.path("status").asText()))throw error(VALIDATION_SOURCE_VERSION_CONFLICT);return r;
    }
    private String signature(String ref,int start,int end,String quote) {return ref+":"+start+":"+end+":"+quote;}
    private static String nullable(JsonNode n,String key) {return n.path(key).isTextual()?n.path(key).asText():null;}
}
