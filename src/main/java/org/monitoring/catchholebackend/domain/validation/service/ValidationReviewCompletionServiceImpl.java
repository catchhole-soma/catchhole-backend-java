package org.monitoring.catchholebackend.domain.validation.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobService;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationReviewCompletionRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationReviewCompletionResponse;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationReviewMapper;
import org.monitoring.catchholebackend.domain.validation.processor.*;
import org.monitoring.catchholebackend.domain.validation.repository.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static org.monitoring.catchholebackend.domain.validation.processor.ValidationExecutionAccess.error;
import static org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode.*;

/** Work → report CAS → 검토 gate → 추출 Job → receipt 순서. 부분 상태를 남기지 않도록 실패하면 트랜잭션 전체를 되돌린다. */
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class ValidationReviewCompletionServiceImpl implements ValidationReviewCompletionService {
    private static final Set<ValidationReviewCompletionMode> EXPLICIT_COMPLETABLE=EnumSet.of(ValidationReviewCompletionMode.EXPLICIT_REQUIRED,ValidationReviewCompletionMode.AUTOMATIC_ELIGIBLE);
    private final WorkRepository works;
    private final ValidationReportRepository reports;
    private final ValidationJobRepository jobs;
    private final ValidationReviewCompletionRepository completions;
    private final ValidationReviewGate gate;
    private final ValidationExecutionAccess access;
    private final ValidationReviewMapper mapper;
    private final AnalysisJobService analysisJobs;
    private final AnalysisStateJournal journal;
    private final EntityManager em;

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Optional<ValidationReviewCompletionResponse> completeValidationReviewAutomatically(ValidationJob job) {
        // 호출자(/complete)가 이미 Work → ValidationJob을 잠그고 terminal 상태를 반영했다. 마지막 판단 저장은 이 경로를 호출하지 않는다.
        var r=job.getReport();
        if(completions.existsByReportId(r.getId())||gate.evaluate(r).completionMode()!=ValidationReviewCompletionMode.AUTOMATIC_ELIGIBLE)return Optional.empty();
        return Optional.of(mapper.toCompletionResponse(release(r,ValidationReviewTrigger.AUTOMATIC,null,null)));
    }

    @Override @Transactional
    public ValidationReviewCompletionResponse completeValidationReview(Long memberId,UUID workId,UUID reportId,ValidationReviewCompletionRequest request) {
        works.getOwnedWorkForUpdate(workId,memberId);
        String hash=journal.hash(JsonNodeFactory.instance.objectNode().put("reportId",reportId.toString()).put("expectedReportVersion",request.expectedReportVersion()));
        var keyed=completions.findByWorkIdAndIdempotencyKey(workId,request.idempotencyKey());
        if(keyed.isPresent()) {
            if(!hash.equals(keyed.get().getRequestHash()))throw error(VALIDATION_IDEMPOTENCY_CONFLICT);
            return mapper.toCompletionResponse(keyed.get());
        }
        var r=reports.findByIdAndWorkId(reportId,workId).orElseThrow(()->error(VALIDATION_NOT_FOUND));em.flush();em.refresh(r);
        if(completions.existsByReportId(reportId))throw error(VALIDATION_REVIEW_ALREADY_COMPLETED);
        if(r.getVersion()!=request.expectedReportVersion())throw error(VALIDATION_REPORT_VERSION_CONFLICT);
        jobs.findByReportId(reportId).ifPresent(em::refresh);
        var review=gate.evaluate(r);
        // AUTOMATIC_ELIGIBLE은 /complete의 자동 종료가 추출 생성 조건에 막혀 receipt 없이 남은 경우다. 같은 판정을 명시 요청으로 다시 시도한다.
        if(!EXPLICIT_COMPLETABLE.contains(review.completionMode()))
            throw new AppException(VALIDATION_REVIEW_NOT_COMPLETABLE,Map.of("completionMode",review.completionMode().name(),"holdReasons",review.holdReasons().stream().map(Enum::name).toList()));
        access.current(r);
        return mapper.toCompletionResponse(release(r,ValidationReviewTrigger.EXPLICIT,request.idempotencyKey(),hash));
    }

    /** 실제 공개 생성 경로로 Job을 만든 뒤 receipt를 저장하고 report version을 한 번 올린다. receipt는 올리기 전 version을 기록한다. */
    private ValidationReviewCompletion release(ValidationReport r,ValidationReviewTrigger trigger,String key,String hash) {
        var job=analysisJobs.createReviewReleaseExtractionJob(r.getMember().getId(),r.getWork().getId(),r.getBatch().getId(),r.getEpisode().getId());
        var completion=completions.save(ValidationReviewCompletion.complete(r,trigger,key,hash,job));
        r.advanceVersion();em.flush();return completion;
    }
}
