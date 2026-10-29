package org.monitoring.catchholebackend.domain.validation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.*;
import org.monitoring.catchholebackend.domain.validation.type.*;

@Schema(description = "작가 검토 요약. 검토 종료 뒤에는 completionMode=COMPLETED이고 completion에 설정 추출 Job, application에 회차 전체 확정 결과가 있습니다.")
public record ValidationReviewSummaryResponse(UUID reportId, long reportVersion, ValidationExecutionStatus executionStatus, ValidationReportMode mode,
        Counts counts, boolean judgmentLocked, ValidationReviewLockReason lockReason, boolean canCompleteReview,
        ValidationReviewCompletionMode completionMode, List<ValidationReviewHoldReason> holdReasons,
        @Schema(description = "화면 안내용 행동. 실제 요청은 서버가 다시 검증합니다.") List<ValidationReviewAction> allowedActions,
        @Schema(description = "현재 report의 검토 종료 receipt. 종료 전이거나 퇴역한 report는 null", nullable = true) Completion completion,
        @Schema(description = "정상 0건 자동 검토 종료가 추출 생성 조건에 막혀 되돌려진 이유. 없으면 null이며 명시 검토 완료로 다시 시도합니다.", nullable = true) ReleaseHold releaseHold,
        @Schema(description = "현재 report의 회차 전체 확정 receipt. 검토 종료 전이거나 아직 평가하지 않았으면 null", nullable = true) Application application,
        @Schema(description = "검토 종료가 만든 설정 추출 Job 상태. 종료 전 NOT_STARTED") ValidationExtractionState extractionState) {
    @Schema(description = "현재 revision 항목 수. 판단을 저장한 적 없는 항목은 Pending")
    public record Counts(long settingTotal, long settingPending, long settingNeedsFix, long settingNotConflict,
            long typoTotal, long typoPending, long typoNeedsFix, long typoKeepOriginal) {}
    @Schema(description = "검토 종료 receipt 요약")
    public record Completion(UUID completionId, ValidationReviewTrigger trigger, LocalDateTime completedAt, UUID extractionJobId) {}
    @Schema(description = "회차 전체 확정 receipt 요약. HELD_REVIEW는 이번 시도에서 아무것도 반영하지 않았다는 뜻입니다.")
    public record Application(ValidationApplicationStatus status, long attempt,
            List<org.monitoring.catchholebackend.domain.validation.dto.response.ValidationApplicationHoldReason> holdReasons,
            @Schema(nullable = true) LocalDateTime appliedAt, long extractionJobGeneration) {}
    @Schema(description = "자동 검토 종료 보류")
    public record ReleaseHold(@Schema(description = "되돌린 오류 코드", example = "AI_TOKEN_QUOTA_EXHAUSTED") String code, LocalDateTime at) {}
}
