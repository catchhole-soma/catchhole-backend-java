package org.monitoring.catchholebackend.domain.validation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.validation.type.ValidationReviewTrigger;

@Schema(description = "검토 종료 receipt. 같은 트랜잭션에서 만든 설정 추출 Job을 가리킵니다. 후보 확정은 별도 단계입니다.")
public record ValidationReviewCompletionResponse(UUID completionId, ValidationReviewTrigger trigger, UUID reportId,
        @Schema(description = "검토 종료를 판정한 reportVersion. 종료로 증가하기 전 값입니다.") long reportVersion,
        @Schema(description = "검토 종료가 만든 SETTING_EXTRACTION Job. 공개 재시도가 새 Job으로 바꾸면 그 Job입니다.") UUID extractionJobId,
        LocalDateTime completedAt) {}
