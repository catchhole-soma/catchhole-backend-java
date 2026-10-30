package org.monitoring.catchholebackend.domain.validation.mapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.validation.dto.response.*;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationFindingDetailResponse.*;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationFindingSummaryResponse.*;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationReviewGate;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.springframework.stereotype.Component;
/** 공개 판정 내용만 옮긴다. evidence_bundle(고정 설정·catalogue·조회 receipt)은 GH310 공개 계약대로 제외한다. */
@Component @RequiredArgsConstructor
public class ValidationReviewMapper {
    private static final List<String> DETAIL_FIELDS=List.of("reason","basis","impact","impact_reason","target_spans","evidence_refs","disagreements","completion","span","replacement","category");
    private final ValidationExecutionMapper transport;
    public ValidationFindingSummaryResponse toSummaryResponse(ValidationFinding f,ValidationJudgment j,boolean changed) {
        var p=f.getPayload();var spans=new ArrayList<Span>();
        for(var span:f.getBranch()==ValidationBranch.SETTING?p.path("target_spans"):JsonNodeFactory.instance.arrayNode().add(p.path("span")))
            if(span.isObject())spans.add(new Span(text(span,"source_ref"),number(span,"start"),number(span,"end"),text(span,"quote")));
        return new ValidationFindingSummaryResponse(f.getId(),f.getBranch(),f.getUnitRef(),f.getCheckId(),f.getCheckRevision(),f.getOutcome(),toJudgmentResponse(j),changed,
                new Summary(text(p,"impact"),text(p,"reason"),text(p,"replacement"),List.copyOf(spans)),toSubjectResponse(f),f.getSettingName(),f.getSettingKey());
    }
    /** #314 B2: 판정 저장 때 기록한 대상. 다시 계산하지 않는다. */
    public ValidationFindingSummaryResponse.Subject toSubjectResponse(ValidationFinding f) {
        return f.getSubjectType()==null?null:new ValidationFindingSummaryResponse.Subject(f.getSubjectType(),f.getSubjectId(),f.getSubjectName());
    }
    public ValidationFindingSummaryResponse.Judgment toJudgmentResponse(ValidationJudgment j) {
        return j==null?new ValidationFindingSummaryResponse.Judgment(ValidationJudgmentValue.PENDING,0,null):new ValidationFindingSummaryResponse.Judgment(j.getJudgment(),j.getVersion(),j.getDecidedAt());
    }
    public ValidationFindingDetailResponse toDetailResponse(ValidationFinding f,ValidationJudgment j,Long currentRevision,boolean changed,List<ValidationEvidence> evidence,
            List<ValidationJudgmentHistory> history,Map<Long,String> changeReasons,List<PriorRevision> prior) {
        var body=JsonNodeFactory.instance.objectNode();for(String field:DETAIL_FIELDS)if(f.getPayload().has(field))body.set(field,f.getPayload().path(field).deepCopy());
        // 완료 판정의 receipt ref는 비공개 호출 기록을 가리키므로 사유와 근거 ref만 남긴다.
        if(body.path("completion").isObject())for(var assessment:body.path("completion"))if(assessment.isObject())((ObjectNode)assessment).remove("receipt_refs");
        @SuppressWarnings("unchecked") var payload=(Map<String,Object>)transport.toTransportValue(body);
        return new ValidationFindingDetailResponse(f.getId(),f.getBranch(),f.getUnitRef(),f.getCheckId(),f.getCheckRevision(),currentRevision,f.getOutcome(),
                f.getCheckId()!=null&&!Objects.equals(currentRevision,f.getCheckRevision()),changed,payload,
                evidence.stream().map(e->new Evidence(e.getSourceRef(),e.getStart(),e.getEnd(),e.getQuote())).distinct().toList(),toJudgmentResponse(j),ValidationJudgmentValue.allowedFor(f.getBranch()),
                history.stream().map(h->new History(h.getFinding().getId(),h.getCheckRevision(),h.getFromJudgment(),h.getToJudgment(),h.getReason(),
                        h.getReason()==ValidationJudgmentHistoryReason.REVISION_SUPERSEDED?changeReasons.get(h.getCheckRevision()):null,h.getCreatedAt())).toList(),prior,
                toSubjectResponse(f),f.getSettingName(),f.getSettingKey());
    }
    public ValidationReviewSummaryResponse toReviewResponse(ValidationReport r,ValidationReviewGate.Review review) {
        var c=review.counts();
        return new ValidationReviewSummaryResponse(r.getId(),r.getVersion(),review.executionStatus(),r.getMode(),
                new ValidationReviewSummaryResponse.Counts(c.settingTotal(),c.settingPending(),c.settingNeedsFix(),c.settingNotConflict(),c.typoTotal(),c.typoPending(),c.typoNeedsFix(),c.typoKeepOriginal()),
                review.judgmentLocked(),review.lockReason(),review.canCompleteReview(),review.completionMode(),review.holdReasons(),review.allowedActions(),
                review.completion()==null?null:new ValidationReviewSummaryResponse.Completion(review.completion().completionId(),review.completion().trigger(),review.completion().completedAt(),review.completion().extractionJobId()),
                review.releaseHold()==null?null:new ValidationReviewSummaryResponse.ReleaseHold(review.releaseHold().code(),review.releaseHold().at()),
                review.application()==null?null:new ValidationReviewSummaryResponse.Application(review.application().status(),review.application().attempt(),review.application().holdReasons(),
                        review.application().appliedAt(),review.application().extractionJobGeneration()),review.extractionState(),review.reviewStage());
    }
    public ValidationReviewCompletionResponse toCompletionResponse(ValidationReviewCompletion c) {
        return new ValidationReviewCompletionResponse(c.getId(),c.getTrigger(),c.getReport().getId(),c.getReportVersion(),c.getExtractionJob().getId(),c.getCompletedAt());
    }
    /** 회차 전체 확정 receipt. 보류 사유 JSONB(Jackson 2)는 응답 record 목록으로 바꿔 MVC 경계에 노출하지 않는다. */
    public ValidationSettingsApplicationResponse toApplicationResponse(ValidationSettingsApplication a) {
        return new ValidationSettingsApplicationResponse(a.getId(),a.getStatus(),a.getTrigger(),a.getAttempt(),toHoldReasons(a.getHoldReasons()),a.getAppliedAt(),
                a.getExtractionJob().getId(),a.getExtractionJobGeneration(),a.getLastErrorCode(),a.getReportVersion());
    }
    public List<ValidationApplicationHoldReason> toHoldReasons(JsonNode reasons) {
        return reasons==null||reasons.isNull()?List.of():List.copyOf(HOLDS.convertValue(reasons,new com.fasterxml.jackson.core.type.TypeReference<List<ValidationApplicationHoldReason>>(){}));
    }
    public JsonNode toHoldReasonTree(List<ValidationApplicationHoldReason> reasons) {return HOLDS.valueToTree(reasons);}
    /** GH312 S4 이력 요약. 퇴역 report의 receipt도 이력으로 옮기며 단계는 목록과 같은 계산식이다. */
    public ValidationReportHistoryResponse toHistoryResponse(org.monitoring.catchholebackend.domain.validation.repository.ValidationReportHistoryRow row,long[] counts) {
        boolean completed=row.completionId()!=null;
        var execution=row.execution()==null?ValidationExecutionStatus.NOT_STARTED:row.execution();
        var stage=org.monitoring.catchholebackend.domain.validation.processor.ValidationReviewStages.resolve(new org.monitoring.catchholebackend.domain.validation.processor.ValidationReviewStages.Input(
                row.current(),row.status(),row.baselineStatus(),execution,completed,
                completed?org.monitoring.catchholebackend.domain.validation.processor.ValidationReviewStages.extractionState(row.extractionStatus(),row.extractionJournal()):ValidationExtractionState.NOT_STARTED,
                completed?row.application():null));
        return new ValidationReportHistoryResponse(row.reportId(),row.revision(),row.current(),row.mode(),row.status(),row.baselineStatus(),row.requestedAt(),execution,
                new ValidationReportHistoryResponse.Counts(counts[0],counts[1]),
                completed?new ValidationReportHistoryResponse.Completion(row.trigger(),row.completedAt()):null,
                row.application()==null?null:new ValidationReportHistoryResponse.Application(row.application(),row.appliedAt()),
                row.releaseHoldCode()==null?null:new ValidationReportHistoryResponse.ReleaseHold(row.releaseHoldCode(),row.releaseHoldAt()),
                row.invalidatedAt(),row.previousReportId(),stage);
    }
    private static final com.fasterxml.jackson.databind.ObjectMapper HOLDS=new com.fasterxml.jackson.databind.ObjectMapper();
    private static String text(JsonNode n,String field) {return n.path(field).isTextual()?n.path(field).asText():null;}
    private static Integer number(JsonNode n,String field) {return n.path(field).isIntegralNumber()?n.path(field).asInt():null;}
}
