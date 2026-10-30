package org.monitoring.catchholebackend.domain.validation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.*;
import org.monitoring.catchholebackend.domain.validation.type.*;

@Schema(description = "회차 전체 확정 receipt. APPLIED는 캐릭터·세계관 후보를 한 트랜잭션에서 모두 반영했다는 뜻이고, HELD_REVIEW는 이번 시도에서 아무것도 반영하지 않았다는 뜻입니다.")
public record ValidationSettingsApplicationResponse(UUID applicationId, ValidationApplicationStatus status,
        @Schema(description = "이 receipt를 마지막으로 기록한 경로. AUTOMATIC은 추출 완료, EXPLICIT은 작가 요청") ValidationReviewTrigger trigger,
        @Schema(description = "receipt를 기록한 평가 횟수. 같은 키 재전송과 APPLIED 뒤 요청은 늘리지 않습니다.", example = "2") long attempt,
        List<ValidationApplicationHoldReason> holdReasons,
        @Schema(description = "APPLIED 시각. 보류면 null", nullable = true) LocalDateTime appliedAt,
        UUID extractionJobId, long extractionJobGeneration,
        @Schema(description = "반영 트랜잭션을 되돌린 오류 코드. 없으면 null", nullable = true) String lastErrorCode,
        @Schema(description = "이 시도가 올린 뒤의 reportVersion") long reportVersion) {}
