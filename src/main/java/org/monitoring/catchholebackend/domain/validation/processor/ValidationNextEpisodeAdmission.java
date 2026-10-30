package org.monitoring.catchholebackend.domain.validation.processor;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationAdmissionState;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationReportRepository;
import org.monitoring.catchholebackend.domain.validation.type.ValidationApplicationStatus;
import org.monitoring.catchholebackend.domain.validation.type.ValidationReportMode;
import org.monitoring.catchholebackend.domain.validation.type.ValidationReviewStage;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;

/**
 * 다음 회차 등록 차단(#314 B1)의 유일한 판정. 작품의 활성(보관되지 않은) 회차에 {@code mode=BEFORE_CONFIRMATION}·현재(is_current) report가 있고
 * 그 검수 단계가 APPLIED가 아니면 새 회차 등록을 막는다. DIAGNOSTIC_ONLY(확정 뒤 진단 재검수)는 AWAITING_REVIEW에서 멈추는 설계라 막지 않는다.
 * 검수 단계는 {@link ValidationReviewStages#resolve} scalar 계산을 그대로 쓴다.
 *
 * <p>회차 전체 확정 receipt가 APPLIED인 report는 이후 상태와 관계없이 막지 않는다. 확정 설정까지 쓴 회차라 더 기다릴 검토·저장이 없다.
 * APPLIED 뒤 다음 회차를 받은 상태에서 앞 회차 원고를 교체하면 그 report는 current인 채 INVALIDATED가 되는데(이력 단계는 그대로 INVALIDATED),
 * 그 회차는 최신이 아니어서 재검수할 수 없으므로 막으면 작품이 회차 삭제 없이는 풀리지 않는다.
 *
 * <p>APPLIED receipt가 없는 INVALIDATED는 막는다. 무효 report는 재검수가 퇴역시킬 때까지 current로 남아 그 회차의 추출을 계속 막는데, 다음 회차를 받으면
 * 그 회차가 최신이 아니어서 재검수가 거절되고 추출할 길이 없어진다. 재검수로 퇴역한(current=false) report와 삭제(ARCHIVED)된 회차의 report는 보지 않는다.
 *
 * <p>호출자는 Work 행을 먼저 잠근 트랜잭션에서 호출한다. 회차 업로드와 {@code EpisodeValidationService.start}가 같은 Work 잠금으로 직렬화되어
 * "다음 회차 업로드"와 "최신 회차 검수 접수"가 엇갈리지 않는다. 이 컴포넌트는 Work보다 먼저 다른 행을 잠그지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ValidationNextEpisodeAdmission {
    /** 차단하지 않는 검수 단계. 회차 전체 확정(APPLIED) 외의 모든 단계(INVALIDATED 포함)는 다음 회차 등록을 막는다. */
    public static final Set<ValidationReviewStage> ADMITTING_STAGES = EnumSet.of(ValidationReviewStage.APPLIED);

    private final ValidationReportRepository reports;

    /** 등록을 막는 report. Front는 reportId로 현재 검수 화면에 이동한다. */
    public record Blocking(UUID reportId, UUID episodeId, int episodeNo, ValidationReviewStage reviewStage) {
        public AppException toException() {
            return new AppException(ValidationErrorCode.VALIDATION_NEXT_EPISODE_BLOCKED, Map.of("reportId", reportId.toString(), "episodeId", episodeId.toString(),
                    "episodeNo", episodeNo, "reviewStage", reviewStage.name()));
        }
    }

    /** 차단 규칙. mode, 파생 검수 단계와 회차 전체 확정 receipt 상태(시도 전 null)만 본다. APPLIED receipt가 있으면 단계와 관계없이 허용한다. */
    public static boolean blocks(ValidationReportMode mode, ValidationReviewStage stage, ValidationApplicationStatus application) {
        return mode == ValidationReportMode.BEFORE_CONFIRMATION && application != ValidationApplicationStatus.APPLIED && !ADMITTING_STAGES.contains(stage);
    }

    /** 작품의 가장 앞선 회차의 차단 report. 없으면 새 회차를 등록할 수 있다. 쓰기 없음. */
    public Optional<Blocking> blocking(UUID workId) {
        return reports.findAdmissionStatesByWorkId(workId, ValidationReportMode.BEFORE_CONFIRMATION).stream()
                .filter(state -> blocks(ValidationReportMode.BEFORE_CONFIRMATION, state.reviewStage(), state.application())).findFirst()
                .map(ValidationNextEpisodeAdmission::blocking);
    }

    /** Work 잠금 아래 새 회차 쓰기 직전에 호출한다. 막히면 아무것도 쓰기 전에 409 VALIDATION_NEXT_EPISODE_BLOCKED. */
    public void assertNextEpisodeAdmitted(UUID workId) {
        blocking(workId).ifPresent(block -> { throw block.toException(); });
    }

    private static Blocking blocking(ValidationAdmissionState state) {
        return new Blocking(state.reportId(), state.episodeId(), state.episodeNo(), state.reviewStage());
    }
}
