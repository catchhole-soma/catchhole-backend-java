package org.monitoring.catchholebackend.domain.validation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.*;
import org.monitoring.catchholebackend.domain.validation.type.*;

@Schema(description = "작가 검토 요약. 검토 종료 뒤에는 completionMode=COMPLETED이고 completion에 설정 추출 Job이 있습니다.")
public record ValidationReviewSummaryResponse(UUID reportId, long reportVersion, ValidationExecutionStatus executionStatus, ValidationReportMode mode,
        Counts counts, boolean judgmentLocked, ValidationReviewLockReason lockReason, boolean canCompleteReview,
        ValidationReviewCompletionMode completionMode, List<ValidationReviewHoldReason> holdReasons,
        @Schema(description = "화면 안내용 행동. 실제 요청은 서버가 다시 검증합니다.") List<ValidationReviewAction> allowedActions,
        @Schema(description = "현재 report의 검토 종료 receipt. 종료 전이거나 퇴역한 report는 null", nullable = true) Completion completion) {
    @Schema(description = "현재 revision 항목 수. 판단을 저장한 적 없는 항목은 Pending")
    public record Counts(long settingTotal, long settingPending, long settingNeedsFix, long settingNotConflict,
            long typoTotal, long typoPending, long typoNeedsFix, long typoKeepOriginal) {}
    @Schema(description = "검토 종료 receipt 요약")
    public record Completion(UUID completionId, ValidationReviewTrigger trigger, LocalDateTime completedAt, UUID extractionJobId) {}
}
