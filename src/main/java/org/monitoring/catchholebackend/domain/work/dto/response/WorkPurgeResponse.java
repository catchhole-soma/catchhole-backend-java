package org.monitoring.catchholebackend.domain.work.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.work.type.WorkPurgeStatus;

@Schema(description = "작품 영구 삭제 요청 상태")
public record WorkPurgeResponse(
        @Schema(description = "영구 삭제 요청 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID requestId,

        @Schema(description = "삭제 대상 작품 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID workId,

        @Schema(description = "영구 삭제 상태", example = "REQUESTED", requiredMode = Schema.RequiredMode.REQUIRED)
        WorkPurgeStatus status,

        @Schema(description = "요청 시각", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime requestedAt,

        @Schema(description = "처리 시작 시각", nullable = true)
        LocalDateTime processingStartedAt,

        @Schema(description = "완료 시각", nullable = true)
        LocalDateTime completedAt,

        @Schema(description = "처리 시도 횟수", example = "1")
        int attemptCount,

        @Schema(description = "사용자가 다시 시도할 수 있는지 여부")
        boolean retryable,

        @Schema(description = "정규화된 마지막 실패 code", nullable = true)
        String lastErrorCode,

        @Schema(description = "S3 객체/version/delete marker 삭제 결과")
        WorkPurgeStoreResultResponse objectStorage,

        @Schema(description = "DB 원문 파생 데이터 삭제 결과")
        WorkPurgeStoreResultResponse database,

        @Schema(description = "요청 후 24시간 처리 SLA 초과 여부")
        boolean slaBreached
) {
}
