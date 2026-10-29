package org.monitoring.catchholebackend.domain.validation.processor;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.processor.*;
import org.monitoring.catchholebackend.domain.character.repository.*;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationApplicationHoldReason;
import org.monitoring.catchholebackend.domain.validation.entity.*;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationReviewCompletionRepository;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingCandidateChronology;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.*;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;
import static org.monitoring.catchholebackend.domain.validation.type.ValidationApplicationHoldCode.*;

/**
 * GH312 S3 회차 전체 확정의 사전 평가. 상태를 바꾸지 않는다. 기존 자동 반영 규칙(contributor)이 그대로 받아들일 후보만 READY로 보고
 * 하나라도 아니면 HELD다. 반영 단계의 실제 보류는 {@link #leftovers}가 같은 트랜잭션에서 다시 확인해 전체를 되돌리게 한다.
 */
@Component @RequiredArgsConstructor
public class ValidationApplicationGate {
    private final ValidationReviewCompletionRepository completions;
    private final ValidationExecutionAccess access;
    private final SettingCandidateRepository characters;
    private final WorldSettingCandidateRepository worlds;
    private final CharacterSettingSchemaRepository schemas;
    private final WorkCharacterRepository workCharacters;
    private final SettingCandidateSchemaResolver schemaResolver;
    private final CharacterSettingValueValidator values;

    public enum Kind { READY, HELD, NOT_READY }
    /** READY의 반영 순서. 캐릭터 발견 → 캐릭터 설정(원문 순서) → 세계관(원문 순서)이며 기존 contributor의 순서와 같다. */
    public record Plan(ValidationReviewCompletion completion,AnalysisJob job,List<UUID> characterIds,List<UUID> worldIds) {}
    public record Decision(Kind kind,Plan plan,List<ValidationApplicationHoldReason> reasons) {
        static Decision notReady(Plan plan) {return new Decision(Kind.NOT_READY,plan,List.of());}
    }

    /** 현재 report의 검토 종료 receipt. 퇴역 report의 receipt는 반영 권한이 아니다. */
    public Optional<ValidationReviewCompletion> completion(ValidationReport r) {return r.isCurrent()?completions.findByReportId(r.getId()):Optional.empty();}
    /** 현재 report의 receipt가 이 Job을 가리키면 release Job이다. */
    public Optional<ValidationReviewCompletion> releaseOf(AnalysisJob job) {return completions.findCurrentByExtractionJobId(job.getId());}

    public Decision evaluate(ValidationReport r) {
        var completion=completion(r).orElseThrow(()->new AppException(org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode.VALIDATION_APPLICATION_UNAVAILABLE));
        var job=completion.getExtractionJob();var reasons=new ArrayList<ValidationApplicationHoldReason>();
        if(r.getStatus()==ValidationReportStatus.INVALIDATED||r.getBaselineStatus()==ValidationPreparationStatus.INVALIDATED)reasons.add(ValidationApplicationHoldReason.episode(REPORT_INVALIDATED,r.getHoldReason()));
        if(!access.currentUploadBatch(r.getEpisode().getId()).map(b->b.getId().equals(r.getBatch().getId())).orElse(false))reasons.add(ValidationApplicationHoldReason.episode(SOURCE_CHANGED,"UPLOAD_BATCH"));
        // 추출 성공은 불변 기록 SEALED다. 완료 트랜잭션 안에서는 아직 RUNNING이고, 그 밖에서는 SUCCEEDED다.
        boolean sealed=job.getJournalStatus()==AnalysisJournalStatus.SEALED&&(job.getStatus()==AnalysisJobStatus.RUNNING||job.getStatus()==AnalysisJobStatus.SUCCEEDED);
        if(!sealed&&(job.getStatus()==AnalysisJobStatus.PENDING||job.getStatus()==AnalysisJobStatus.RUNNING))return Decision.notReady(new Plan(completion,job,List.of(),List.of()));
        if(!sealed)reasons.add(ValidationApplicationHoldReason.episode(EXTRACTION_NOT_FINISHED,job.getStatus()+"/"+job.getJournalStatus()));
        else if(!job.hasCurrentSourceVersion())reasons.add(ValidationApplicationHoldReason.episode(SOURCE_CHANGED,"EXTRACTION_SOURCE"));
        var characterIds=new ArrayList<UUID>();var worldIds=new ArrayList<UUID>();
        if(sealed) {
            var schemaList=schemas.findAllActiveForWork(r.getWork().getId());
            var ordered=SettingCandidateChronology.sorted(characters.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(job.getId()));
            var applicationOrder=new ArrayList<SettingCandidate>();ordered.stream().filter(SettingCandidate::isCharacterDiscovery).forEach(applicationOrder::add);ordered.stream().filter(c->!c.isCharacterDiscovery()).forEach(applicationOrder::add);
            for(var c:applicationOrder) {
                if(!c.isPendingReview())continue;var hold=character(c,schemaList);
                if(hold==null)characterIds.add(c.getId());else reasons.add(hold);
            }
            for(var w:worlds.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(job.getId()).stream().sorted(WorldSettingCandidateChronology.comparator()).toList()) {
                if(!w.isPendingReview())continue;var hold=world(w);
                if(hold==null)worldIds.add(w.getId());else reasons.add(hold);
            }
        }
        var plan=new Plan(completion,job,List.copyOf(characterIds),List.copyOf(worldIds));
        return new Decision(reasons.isEmpty()?Kind.READY:Kind.HELD,plan,List.copyOf(reasons));
    }

    /** 기존 자동 반영 규칙을 실행한 뒤 아직 검토 대기인 후보. 하나라도 있으면 그 트랜잭션은 부분 반영이므로 되돌린다. */
    public List<ValidationApplicationHoldReason> leftovers(AnalysisJob job) {
        var out=new ArrayList<ValidationApplicationHoldReason>();
        for(var c:characters.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(job.getId()))if(c.isPendingReview())
            out.add(new ValidationApplicationHoldReason(CANDIDATE_CHANGED,ValidationApplicationCandidateType.CHARACTER,c.getId(),c.getAutomaticReviewHoldReason()==null?"NOT_APPLIED":c.getAutomaticReviewHoldReason().name()));
        for(var w:worlds.findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(job.getId()))if(w.isPendingReview())
            out.add(new ValidationApplicationHoldReason(CANDIDATE_CHANGED,ValidationApplicationCandidateType.WORLD_SETTING,w.getId(),w.getAutomaticReviewHoldReason()==null?"NOT_APPLIED":w.getAutomaticReviewHoldReason().name()));
        return List.copyOf(out);
    }

    /** SettingCandidateServiceImpl.applyAutomatically의 수용 조건과 같은 순서의 사전 판정. null이면 반영 계획에 넣는다. */
    private ValidationApplicationHoldReason character(SettingCandidate c,List<org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema> schemaList) {
        if(c.isUserModified()||c.getReviewedApplicationMode()!=null)return hold(AUTHOR_EDIT_PENDING,c,null);
        if(unknownName(c.getEntityName())||c.getMatchStatus()==SettingCandidateMatchStatus.AMBIGUOUS
                ||c.getMatchedCharacterId()==null&&c.getProvisionalSubjectKey()==null)return hold(SUBJECT_UNRESOLVED,c,c.getMatchStatus()==null?null:c.getMatchStatus().name());
        // 미연결 발견의 이름이 이미 등록 인물이면 자동 반영은 같은 대상 확인을 요구한다.
        if(c.isCharacterDiscovery()&&c.getMatchedCharacterId()==null&&workCharacters.findAllByWorkIdOrderByCreatedAtDesc(c.getWork().getId()).stream()
                .anyMatch(existing->SettingCandidateGroupNameNormalizer.belongsToSameGroup(existing.getName(),c.getEntityName())))return hold(SUBJECT_UNRESOLVED,c,"NAME_ALREADY_REGISTERED");
        if(c.getAutomaticReviewHoldReason()!=null)return hold(fromAutomaticHold(c.getAutomaticReviewHoldReason()),c,c.getAutomaticReviewHoldReason().name());
        if(c.isCharacterDiscovery())return null;
        try {
            var match=schemaResolver.resolve(c.getAttributeName(),c.getValueType(),schemaList);
            var validation=values.evaluateCandidate(c,match.matchedSchema().getFactType(),match.matchedSchema().getValueType());
            if(validation.isInvalid())return hold(VALUE_INVALID,c,validation.errorCode().name());
        } catch(AppException e) {return hold(VALUE_INVALID,c,e.getResultCode().getCode());}
        var status=c.getComparisonStatus();
        if(status==CharacterFactComparisonStatus.PENDING||status==CharacterFactComparisonStatus.PROCESSING)return hold(COMPARISON_PENDING,c,status.name());
        if(status==CharacterFactComparisonStatus.WAITING_FOR_CHARACTER_MATCH)return hold(SUBJECT_UNRESOLVED,c,status.name());
        if(status==CharacterFactComparisonStatus.FAILED)return hold(c.getComparisonFailureCode()==AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED?TOKEN_QUOTA:COMPARISON_FAILED,c,c.getComparisonFailureCode()==null?null:c.getComparisonFailureCode().name());
        if(status!=CharacterFactComparisonStatus.COMPLETED)return hold(COMPARISON_FAILED,c,status==null?null:status.name());
        if(c.getSuggestedOperation()==null||c.getSuggestedOperation()==CharacterFactOperation.REVIEW_REQUIRED)return hold(REVIEW_REQUIRED,c,c.getSuggestedOperation()==null?null:c.getSuggestedOperation().name());
        return null;
    }

    /** WorldSettingCandidateServiceImpl.isAutomaticCandidate와 같은 조건의 사전 판정. */
    private ValidationApplicationHoldReason world(WorldSettingCandidate w) {
        if(w.getFinalOperation()!=null)return hold(AUTHOR_EDIT_PENDING,w,w.getFinalOperation().name());
        var status=w.getComparisonStatus();
        if(status==WorldSettingComparisonStatus.PENDING||status==WorldSettingComparisonStatus.PROCESSING)return hold(COMPARISON_PENDING,w,status.name());
        if(status==WorldSettingComparisonStatus.FAILED)return hold(w.getComparisonFailureCode()==AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED?TOKEN_QUOTA:COMPARISON_FAILED,w,w.getComparisonFailureCode()==null?null:w.getComparisonFailureCode().name());
        if(status!=WorldSettingComparisonStatus.COMPLETED)return hold(COMPARISON_FAILED,w,status==null?null:status.name());
        if(w.getSubjectResolutionType()!=WorldSettingSubjectResolutionType.NEW&&w.getSubjectResolutionType()!=WorldSettingSubjectResolutionType.EXISTING
                ||w.getComparisonReviewReason()==WorldSettingComparisonReviewReason.SUBJECT_UNRESOLVED)return hold(SUBJECT_UNRESOLVED,w,w.getSubjectResolutionType()==null?null:w.getSubjectResolutionType().name());
        if(w.getComparisonReviewReason()==WorldSettingComparisonReviewReason.SCOPE_UNRESOLVED||w.getComparisonReviewReason()==WorldSettingComparisonReviewReason.SCOPE_MISMATCH)return hold(SCOPE_UNRESOLVED,w,w.getComparisonReviewReason().name());
        if(w.getComparisonReviewReason()!=null)return hold(REVIEW_REQUIRED,w,w.getComparisonReviewReason().name());
        if(w.getAutomaticReviewHoldReason()!=null)return hold(fromAutomaticHold(w.getAutomaticReviewHoldReason()),w,w.getAutomaticReviewHoldReason().name());
        var decision=w.getComparisonDecision();
        if(w.getSuggestedOperation()==null||w.getSuggestedOperation()==WorldSettingSuggestedOperation.REVIEW_REQUIRED)return hold(REVIEW_REQUIRED,w,w.getSuggestedOperation()==null?null:w.getSuggestedOperation().name());
        if(w.getConsolidationStatus()==WorldSettingConsolidationStatus.CONFLICT||decision==null||decision.getSuggestedOperation()!=w.getSuggestedOperation()
                ||decision.getConsolidationStatus()==WorldSettingConsolidationStatus.CONFLICT||decision.isRootPropertyMovesDisabled())return hold(REVIEW_REQUIRED,w,"CONFLICT_OR_DECISION_CHANGED");
        return null;
    }

    private static ValidationApplicationHoldCode fromAutomaticHold(AutomaticReviewHoldReason reason) {
        return switch(reason) {
            case SUBJECT_RESOLUTION_FAILED,SUBJECT_CONFIRMATION_REQUIRED->SUBJECT_UNRESOLVED;
            case SETTING_VALUE_CONFIRMATION_REQUIRED->VALUE_INVALID;
            default->REVIEW_REQUIRED;
        };
    }
    private static boolean unknownName(String name) {var key=SettingCandidateGroupNameNormalizer.toGroupKey(name);return key.isBlank()||key.equals("미상");}
    private static ValidationApplicationHoldReason hold(ValidationApplicationHoldCode code,SettingCandidate c,String detail) {return new ValidationApplicationHoldReason(code,ValidationApplicationCandidateType.CHARACTER,c.getId(),detail);}
    private static ValidationApplicationHoldReason hold(ValidationApplicationHoldCode code,WorldSettingCandidate w,String detail) {return new ValidationApplicationHoldReason(code,ValidationApplicationCandidateType.WORLD_SETTING,w.getId(),detail);}
}
