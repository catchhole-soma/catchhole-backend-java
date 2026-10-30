package org.monitoring.catchholebackend.domain.validation.service;
import jakarta.persistence.EntityManager;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationJudgmentRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.*;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationReviewMapper;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationReviewGate;
import org.monitoring.catchholebackend.domain.validation.repository.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.monitoring.catchholebackend.global.exception.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static org.monitoring.catchholebackend.domain.validation.processor.ValidationExecutionAccess.error;
import static org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode.*;

/** 판단 쓰기는 Work → report → 판단 순서로 잠근 한 트랜잭션이며 검토 종료·추출 Job·후보·확정은 만들지 않는다. */
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class ValidationReviewServiceImpl implements ValidationReviewService {
    private static final int MAX_PAGE_SIZE=100;
    private final WorkRepository works;
    private final ValidationReportRepository reports;
    private final ValidationJobRepository jobs;
    private final ValidationJudgmentRepository judgments;
    private final ValidationJudgmentHistoryRepository histories;
    private final ValidationReviewRepository reviews;
    private final ValidationReviewGate gate;
    private final ValidationReviewCompletionRepository completions;
    private final ValidationReviewMapper mapper;
    private final EntityManager em;

    @Override public PageResponse<ValidationFindingSummaryResponse> getValidationFindings(Long memberId,UUID workId,UUID reportId,ValidationBranch branch,ValidationJudgmentValue judgment,String outcome,int page,int size) {
        report(memberId,workId,reportId);if(page<0||size<1||size>MAX_PAGE_SIZE)throw new AppException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
        var rows=reviews.currentFindings(reportId,branch,judgment,outcome==null||outcome.isBlank()?null:outcome,PageRequest.of(page,size));var revised=reviews.revisedCheckIds(reportId);
        return PageResponse.from(rows,rows.getContent().stream().map(x->mapper.toSummaryResponse(x.finding(),x.judgment(),revised.contains(x.finding().getCheckId()))).toList());
    }
    @Override public ValidationFindingDetailResponse getValidationFinding(Long memberId,UUID workId,UUID reportId,UUID findingId) {
        report(memberId,workId,reportId);var f=reviews.finding(reportId,findingId).orElseThrow(()->error(VALIDATION_FINDING_NOT_FOUND));
        List<ValidationFinding> related=List.of(f);List<ValidationCheckHistory> checkHistory=List.of();Long current=null;
        if(f.getCheckId()!=null) {
            current=reviews.checkRevision(f.getCheckId()).orElse(null);checkHistory=reviews.checkHistory(f.getCheckId());
            related=reviews.checkFindings(f.getCheckId()).stream().filter(x->x.getCheckRevision()<=f.getCheckRevision()).toList();
        }
        var byFinding=judgments.findAllByFindingIdIn(related.stream().map(ValidationFinding::getId).toList()).stream().collect(Collectors.toMap(j->j.getFinding().getId(),Function.identity()));
        Map<Long,String> reasons=new HashMap<>();var prior=new ArrayList<ValidationFindingDetailResponse.PriorRevision>();
        for(var h:checkHistory) {
            reasons.put(h.getRevision(),h.getChangeReason());if(h.getRevision()>=f.getCheckRevision())continue;
            var old=related.stream().filter(x->x.getCheckRevision()==h.getRevision()).findFirst().orElse(null);
            prior.add(new ValidationFindingDetailResponse.PriorRevision(h.getRevision(),h.getOutcome(),h.getChangeReason(),old==null?null:old.getId(),old==null?null:mapper.toJudgmentResponse(byFinding.get(old.getId()))));
        }
        var evidence=reviews.evidence(reportId,f.getBranch()==ValidationBranch.SETTING?"CHECK:"+f.getCheckId()+":"+f.getCheckRevision():"TYPO:"+f.getUnitRef());
        if(f.getBranch()==ValidationBranch.TYPO) {
            // 오탈자 인용은 segment 단위로 저장되므로 이 제안의 위치와 겹치는 인용만 보여준다.
            var span=f.getPayload().path("span");
            evidence=evidence.stream().filter(e->e.getSourceRef().equals(span.path("source_ref").asText())&&e.getStart()<span.path("end").asInt()&&e.getEnd()>span.path("start").asInt()).toList();
        }
        return mapper.toDetailResponse(f,byFinding.get(f.getId()),current,!checkHistory.isEmpty(),evidence,
                histories.findAllByFindingIdInOrderByCreatedAtAscIdAsc(related.stream().map(ValidationFinding::getId).toList()),reasons,List.copyOf(prior));
    }
    @Override @Transactional
    public ValidationFindingSummaryResponse saveValidationJudgment(Long memberId,UUID workId,UUID reportId,UUID findingId,ValidationJudgmentRequest request) {
        works.getOwnedWorkForUpdate(workId,memberId);
        var r=reports.findByIdAndWorkId(reportId,workId).orElseThrow(()->error(VALIDATION_NOT_FOUND));em.flush();em.refresh(r);
        var f=reviews.finding(reportId,findingId).orElseThrow(()->error(VALIDATION_FINDING_NOT_FOUND));
        if(!request.judgment().allowedIn(f.getBranch()))throw error(VALIDATION_JUDGMENT_INVALID);
        var job=jobs.findByReportId(reportId).orElse(null);if(job!=null)em.refresh(job);
        // GH312: 검토 종료 receipt 뒤의 판단 변경은 release된 추출 입력과 어긋나므로 잠근다.
        if(ValidationReviewGate.lockReason(r.isCurrent(),r.getBaselineStatus(),r.getStatus(),job==null?ValidationExecutionStatus.NOT_STARTED:job.getStatus(),completions.existsByReportId(reportId))!=null)throw error(VALIDATION_JUDGMENT_LOCKED);
        if(!Objects.equals(request.expectedCheckRevision(),f.getCheckRevision())||f.getCheckId()!=null&&!reviews.checkRevision(f.getCheckId()).map(c->c.equals(f.getCheckRevision())).orElse(false))throw error(VALIDATION_JUDGMENT_STALE);
        var existing=judgments.findByFindingId(findingId);
        long expected=request.expectedJudgmentVersion()==null?0:request.expectedJudgmentVersion();
        if(expected!=existing.map(ValidationJudgment::getVersion).orElse(0L))throw error(VALIDATION_JUDGMENT_VERSION_CONFLICT);
        var j=existing.orElse(null);var from=j==null?ValidationJudgmentValue.PENDING:j.getJudgment();
        // 같은 값 재전송은 행·이력·version을 바꾸지 않는다. 판단이 없던 항목의 PENDING도 같은 값이다.
        if(from!=request.judgment()) {
            if(j==null)j=judgments.save(ValidationJudgment.decide(f,request.judgment()));else j.change(request.judgment());
            histories.save(ValidationJudgmentHistory.author(j,existing.isPresent()?from:null));r.advanceVersion();em.flush();
        }
        return mapper.toSummaryResponse(f,j,f.getCheckId()!=null&&reviews.revisedCheckIds(reportId).contains(f.getCheckId()));
    }
    @Override public ValidationReviewSummaryResponse getValidationReview(Long memberId,UUID workId,UUID reportId) {
        var r=report(memberId,workId,reportId);return mapper.toReviewResponse(r,gate.evaluate(r));
    }
    @Override public PageResponse<ValidationReportHistoryResponse> getValidationHistory(Long memberId,UUID workId,UUID episodeId,int page,int size) {
        works.getOwnedWork(workId,memberId);if(page<0||size<1||size>MAX_PAGE_SIZE)throw new AppException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
        var rows=reviews.history(workId,episodeId,PageRequest.of(page,size));
        // 항목 수는 report별 count 집계 한 번으로 구한다. 항목·근거 행은 읽지 않는다.
        var counts=reviews.currentFindingCounts(rows.getContent().stream().map(ValidationReportHistoryRow::reportId).toList());
        return PageResponse.from(rows,rows.getContent().stream().map(row->mapper.toHistoryResponse(row,counts.getOrDefault(row.reportId(),new long[2]))).toList());
    }
    private ValidationReport report(Long memberId,UUID workId,UUID reportId) {
        works.getOwnedWork(workId,memberId);return reports.findByIdAndWorkId(reportId,workId).orElseThrow(()->error(VALIDATION_NOT_FOUND));
    }
}
