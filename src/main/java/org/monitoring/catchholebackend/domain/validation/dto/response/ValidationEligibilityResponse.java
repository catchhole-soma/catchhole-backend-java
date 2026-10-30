package org.monitoring.catchholebackend.domain.validation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationIntakeEligibility;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationNextEpisodeAdmission;
import org.monitoring.catchholebackend.domain.validation.type.*;

@Schema(description = "신규 회차 검수 접수 전 자격 조회(#314 B1). 접수(POST /episode-validations)와 같은 판정 코드를 읽기만 하며 report·Job을 만들지 않습니다. 접수 자격의 최종 권위는 접수 요청입니다. episodeId 없이 조회하면 작품 단위 다음 회차 등록 차단(nextEpisodeAdmission)만 채웁니다.")
public record ValidationEligibilityResponse(
        @Schema(description = "조회한 회차. 작품 단위 조회면 null", nullable = true) UUID episodeId,
        @Schema(description = "지금 이 회차의 검수를 새로 접수할 수 있는지. episodeId가 없으면 null", nullable = true, example = "false") Boolean eligible,
        @Schema(description = "접수 자격 판정 사유. 접수가 같은 순서로 판정합니다. episodeId가 없으면 null", nullable = true, example = "NOT_LATEST_EPISODE") ValidationEligibilityReason reasonCode,
        @Schema(description = "판정 안내 문구. 거절 사유는 접수 오류와 같은 문구입니다. episodeId가 없으면 null", nullable = true) String message,
        @Schema(description = "조회한 회차가 작품의 최신 활성 회차인지. episodeId가 없으면 null", nullable = true) Boolean latestEpisode,
        @Schema(description = "조회한 회차의 현재 검수 report. 없으면 null", nullable = true) CurrentReport currentReport,
        @Schema(description = "작품 단위 다음 회차 등록 가능 여부. 항상 채웁니다.") NextEpisodeAdmission nextEpisodeAdmission) {

    @Schema(name = "ValidationEligibilityCurrentReport", description = "회차의 현재(is_current) 검수 report 요약. 새로 접수하지 말고 이 report를 사용합니다.")
    public record CurrentReport(UUID reportId, @Schema(example = "1") long revision, ValidationReportMode mode,
            @Schema(description = "파생 검수 단계", example = "AWAITING_REVIEW") ValidationReviewStage reviewStage) {}

    @Schema(name = "ValidationEligibilityNextEpisodeAdmission", description = "다음 회차 등록 차단. 작품의 활성 회차에 확정 전 검수(BEFORE_CONFIRMATION) 현재 report가 있고 검수 단계가 APPLIED가 아니면(INVALIDATED 포함, 재검수 뒤 APPLIED까지) allowed=false이며 모든 업로드 방식이 409 VALIDATION_NEXT_EPISODE_BLOCKED입니다. 진단 재검수(DIAGNOSTIC_ONLY)·재검수로 퇴역한 report·삭제된 회차의 report는 막지 않습니다.")
    public record NextEpisodeAdmission(
            @Schema(example = "false") boolean allowed,
            @Schema(description = "등록을 막는 report. 허용이면 null", nullable = true) UUID blockingReportId,
            @Schema(nullable = true) UUID blockingEpisodeId,
            @Schema(nullable = true, example = "101") Integer blockingEpisodeNo,
            @Schema(nullable = true, example = "AWAITING_REVIEW") ValidationReviewStage blockingReviewStage,
            @Schema(description = "차단 안내 문구(409와 같은 문구). 허용이면 null", nullable = true) String message) {
        public static NextEpisodeAdmission from(Optional<ValidationNextEpisodeAdmission.Blocking> blocking) {
            return blocking.map(b -> new NextEpisodeAdmission(false, b.reportId(), b.episodeId(), b.episodeNo(), b.reviewStage(),
                    org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode.VALIDATION_NEXT_EPISODE_BLOCKED.getMessage()))
                    .orElseGet(() -> new NextEpisodeAdmission(true, null, null, null, null, null));
        }
    }

    public static ValidationEligibilityResponse workOnly(Optional<ValidationNextEpisodeAdmission.Blocking> blocking) {
        return new ValidationEligibilityResponse(null, null, null, null, null, null, NextEpisodeAdmission.from(blocking));
    }

    public static ValidationEligibilityResponse of(UUID episodeId, ValidationIntakeEligibility.Judgement intake, CurrentReport currentReport) {
        return new ValidationEligibilityResponse(episodeId, intake.eligible(), intake.reason(), intake.message(), intake.latestEpisode(), currentReport,
                NextEpisodeAdmission.from(intake.nextEpisodeBlock()));
    }
}
