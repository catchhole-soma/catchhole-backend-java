package org.monitoring.catchholebackend.domain.validation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.validation.type.*;

@Schema(description = "회차 검수 이력의 report 한 건 요약(GH312 S4). 과거 응답은 요약만 제공하며 인용·근거·판정 payload·판단 목록은 포함하지 않습니다. 검토 종료·회차 전체 확정 receipt는 퇴역한 report도 이력으로 보여 줍니다.")
public record ValidationReportHistoryResponse(
        UUID reportId,
        @Schema(description = "회차 안의 검수 revision. 재검수마다 1씩 증가합니다.", example = "2") long revision,
        @Schema(description = "회차의 현재 report 여부") boolean current,
        ValidationReportMode mode,
        ValidationReportStatus status,
        ValidationPreparationStatus baselineStatus,
        @Schema(description = "접수 시각") LocalDateTime requestedAt,
        @Schema(description = "전용 검사 실행 상태. Job이 없으면 NOT_STARTED") ValidationExecutionStatus executionStatus,
        Counts counts,
        @Schema(description = "검토 종료 receipt. 없으면 null", nullable = true) Completion completion,
        @Schema(description = "회차 전체 확정 receipt. 시도 전이면 null", nullable = true) Application application,
        @Schema(description = "자동 검토 종료 보류 사유. 없거나 receipt가 생겨 비워졌으면 null", nullable = true) ReleaseHold releaseHold,
        @Schema(description = "처음 무효화(원고·기준 변경, 파기, 재검수 퇴역)된 시각. 유효한 report와 기록 이전(V100 전) 무효화는 null", nullable = true) LocalDateTime invalidatedAt,
        @Schema(description = "이 report가 대체한 이전 report. 최초 접수는 null", nullable = true) UUID previousReportId,
        @Schema(description = "파생 검수 단계. 퇴역·무효 report는 INVALIDATED", example = "INVALIDATED") ValidationReviewStage reviewStage) {

    @Schema(name = "ValidationReportHistoryCounts", description = "현재 revision 항목 수(/review counts의 total과 같은 규칙). 근거·항목이 파기된 report는 0")
    public record Counts(long settingFindings, long typoFindings) {}

    @Schema(name = "ValidationReportHistoryCompletion", description = "검토 종료 receipt 요약")
    public record Completion(ValidationReviewTrigger trigger, LocalDateTime completedAt) {}

    @Schema(name = "ValidationReportHistoryApplication", description = "회차 전체 확정 receipt 요약. HELD_REVIEW는 확정 설정 쓰기 0")
    public record Application(ValidationApplicationStatus status, @Schema(nullable = true) LocalDateTime appliedAt) {}

    @Schema(name = "ValidationReportHistoryReleaseHold", description = "자동 검토 종료 보류")
    public record ReleaseHold(@Schema(example = "AI_TOKEN_QUOTA_EXHAUSTED") String code, LocalDateTime at) {}
}
