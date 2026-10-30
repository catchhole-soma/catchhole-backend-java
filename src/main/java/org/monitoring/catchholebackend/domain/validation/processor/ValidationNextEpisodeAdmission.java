package org.monitoring.catchholebackend.domain.validation.processor;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationAdmissionState;
import org.monitoring.catchholebackend.domain.validation.repository.ValidationReportRepository;
import org.monitoring.catchholebackend.domain.validation.type.ValidationReportMode;
import org.monitoring.catchholebackend.domain.validation.type.ValidationReviewStage;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;

/**
 * 다음 회차 등록 차단(#314 B1)의 유일한 판정. 작품에 {@code mode=BEFORE_CONFIRMATION}·현재(is_current) report가 있고
 * 그 검수 단계가 APPLIED/INVALIDATED가 아니면 새 회차 등록을 막는다. DIAGNOSTIC_ONLY(확정 뒤 진단 재검수)는 AWAITING_REVIEW에서 멈추는 설계라 막지 않는다.
 * 검수 단계는 {@link ValidationReviewStages#resolve} scalar 계산을 그대로 쓴다.
 *
 * <p>호출자는 Work 행을 먼저 잠근 트랜잭션에서 호출한다. 회차 업로드와 {@code EpisodeValidationService.start}가 같은 Work 잠금으로 직렬화되어
 * "다음 회차 업로드"와 "최신 회차 검수 접수"가 엇갈리지 않는다. 이 컴포넌트는 Work보다 먼저 다른 행을 잠그지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ValidationNextEpisodeAdmission {
    /** 차단하지 않는 검수 단계. 그 밖의 모든 단계(PREPARING_BASELINE~HELD_INCOMPLETE)는 다음 회차 등록을 막는다. */
    public static final Set<ValidationReviewStage> ADMITTING_STAGES = EnumSet.of(ValidationReviewStage.APPLIED, ValidationReviewStage.INVALIDATED);

    private final ValidationReportRepository reports;

    /** 등록을 막는 report. Front는 reportId로 현재 검수 화면에 이동한다. */
    public record Blocking(UUID reportId, UUID episodeId, int episodeNo, ValidationReviewStage reviewStage) {
        public AppException toException() {
            return new AppException(ValidationErrorCode.VALIDATION_NEXT_EPISODE_BLOCKED, Map.of("reportId", reportId.toString(), "episodeId", episodeId.toString(),
                    "episodeNo", episodeNo, "reviewStage", reviewStage.name()));
        }
    }

    /** 차단 규칙. mode와 파생 검수 단계만 본다. */
    public static boolean blocks(ValidationReportMode mode, ValidationReviewStage stage) {
        return mode == ValidationReportMode.BEFORE_CONFIRMATION && !ADMITTING_STAGES.contains(stage);
    }

    /** 작품의 가장 앞선 회차의 차단 report. 없으면 새 회차를 등록할 수 있다. 쓰기 없음. */
    public Optional<Blocking> blocking(UUID workId) {
        return reports.findAdmissionStatesByWorkId(workId, ValidationReportMode.BEFORE_CONFIRMATION).stream()
                .filter(state -> blocks(ValidationReportMode.BEFORE_CONFIRMATION, state.reviewStage())).findFirst()
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
