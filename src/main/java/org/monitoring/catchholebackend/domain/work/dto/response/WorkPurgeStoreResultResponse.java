package org.monitoring.catchholebackend.domain.work.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "영구 삭제 저장소별 처리 결과")
public record WorkPurgeStoreResultResponse(
        @Schema(description = "확인한 삭제 대상 수", example = "12")
        int targetCount,

        @Schema(description = "삭제 성공 수", example = "12")
        int deletedCount,

        @Schema(description = "삭제 실패 수", example = "0")
        int failedCount
) {
}
