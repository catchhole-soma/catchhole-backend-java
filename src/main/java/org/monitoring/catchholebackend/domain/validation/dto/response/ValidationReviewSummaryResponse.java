package org.monitoring.catchholebackend.domain.validation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.*;
import org.monitoring.catchholebackend.domain.validation.type.*;

@Schema(description = "작가 검토 요약. canCompleteReview/AUTOMATIC_ELIGIBLE도 설정 추출을 시작하지 않으며 검토 완료 API는 후속입니다.")
public record ValidationReviewSummaryResponse(UUID reportId, long reportVersion, ValidationExecutionStatus executionStatus, ValidationReportMode mode,
        Counts counts, boolean judgmentLocked, ValidationReviewLockReason lockReason, boolean canCompleteReview,
        ValidationReviewCompletionMode completionMode, List<ValidationReviewHoldReason> holdReasons,
        @Schema(description = "화면 안내용 행동. 실제 요청은 서버가 다시 검증합니다.") List<ValidationReviewAction> allowedActions) {
    @Schema(description = "현재 revision 항목 수. 판단을 저장한 적 없는 항목은 Pending")
    public record Counts(long settingTotal, long settingPending, long settingNeedsFix, long settingNotConflict,
            long typoTotal, long typoPending, long typoNeedsFix, long typoKeepOriginal) {}
}
