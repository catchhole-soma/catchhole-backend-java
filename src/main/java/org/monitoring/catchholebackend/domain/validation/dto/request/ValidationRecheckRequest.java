package org.monitoring.catchholebackend.domain.validation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.UUID;

@Schema(description = "수정 후 새 검수 접수. 원고 값은 회차 상세(GET /episodes/{episodeId})의 현재 값, 기준 값은 재검수할 report(GET /episode-validations/{reportId})의 값을 그대로 보냅니다.")
public record ValidationRecheckRequest(
        @Schema(description = "START/RECHECK가 공유하는 작품별 멱등 키. 같은 키·다른 입력은 409", example = "validation-recheck-1") @NotBlank @Size(max=100) String idempotencyKey,
        @Schema(description = "재검수할 report의 reportVersion", example = "12") @NotNull @PositiveOrZero Long expectedReportVersion,
        @Schema(description = "회차의 현재 원고 contentHash", example = "0000000000000000000000000000000000000000000000000000000000000000") @NotBlank @Pattern(regexp="[0-9a-f]{64}") String expectedContentHash,
        @Schema(description = "회차의 현재 원고 contentS3Key") @NotBlank @Size(max=512) String expectedContentS3Key,
        @Schema(description = "회차의 현재 원고 contentS3Version. 실제 값이 null이면 null", nullable = true) @Size(max=255) String expectedContentS3Version,
        @Schema(description = "회차의 현재 원고 contentRevision", example = "3") @NotNull @PositiveOrZero Long expectedContentRevision,
        @Schema(description = """
                재검수할 report의 고정 기준 식별값. GET /{reportId}의 baselineFingerprint를 그대로 보냅니다(baselineHash가 아닙니다. 원고 교체 등으로 무효화된 report는 baselineHash가 null이지만 baselineFingerprint는 남습니다).
                correctionReceiptId를 보내지 않으면 사실상 필수이며, 누락하면 409 VALIDATION_SOURCE_VERSION_CONFLICT(context.reason=BASELINE_FINGERPRINT_REQUIRED), 다른 값이면 같은 409(context.reason=BASELINE_FINGERPRINT_MISMATCH)입니다.
                baselineFingerprint 자체가 null인 report(최초 기준 캡처 실패·V91 이전 파기)만 null을 보냅니다. correctionReceiptId와 함께 보내면 그 receipt의 기준과 같아야 합니다.""",
                example = "0000000000000000000000000000000000000000000000000000000000000000", nullable = true)
        @Pattern(regexp="[0-9a-f]{64}") String expectedBaselineHash,
        @Schema(description = "POST /{reportId}/corrections가 반환한 정정 receipt ID. 보내면 expectedBaselineHash를 생략할 수 있습니다.", nullable = true) UUID correctionReceiptId) {}
