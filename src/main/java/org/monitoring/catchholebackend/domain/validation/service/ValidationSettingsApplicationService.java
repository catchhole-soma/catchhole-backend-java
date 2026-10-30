package org.monitoring.catchholebackend.domain.validation.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationSettingsApplicationRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationSettingsApplicationResponse;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationApplicationGate;
import org.monitoring.catchholebackend.global.exception.AppException;

/**
 * GH312 S3 회차 전체 확정. release 회차의 캐릭터·세계관 후보를 Work 잠금 아래 한 트랜잭션에서 모두 반영하거나,
 * 하나라도 반영할 수 없으면 아무것도 쓰지 않고 HELD_REVIEW receipt를 남긴다. release 회차의 확정 설정을 쓰는 유일한 경로다.
 */
public interface ValidationSettingsApplicationService {
    /** 상태를 바꾸지 않는 평가. 현재 report의 검토 종료 receipt가 없으면 VALIDATION_APPLICATION_UNAVAILABLE이다. */
    ValidationApplicationGate.Decision evaluate(ValidationReport report);

    /** 현재 report의 검토 종료 receipt가 이 Job을 가리키면 true다. 추출 완료가 기존 자동 반영 대신 이 서비스를 호출할지 정한다. */
    boolean isReleaseJob(AnalysisJob job);

    /**
     * 추출 완료 트랜잭션(Work → Job 잠금, SEALED 직후) 안의 자동 경로. READY면 두 도메인을 반영하고 APPLIED와 automaticAppliedAt을 기록하며,
     * HELD면 보류 receipt만 남긴다. 반영 중 실패는 {@link ValidationErrorCode#VALIDATION_APPLICATION_ROLLED_BACK}을 던져 완료 트랜잭션 전체를
     * 되돌리게 하고, 호출자는 같은 완료를 rolledBack과 함께 다시 실행해 반영 없이 그 사유를 기록한다.
     */
    void applyAutomatically(AnalysisJob job, AppException rolledBack);

    /** 작가의 명시 반영. 같은 키·같은 입력은 같은 receipt, APPLIED 뒤의 다른 키도 같은 APPLIED receipt를 반환한다. */
    ValidationSettingsApplicationResponse applySettings(Long memberId, UUID workId, UUID reportId, ValidationSettingsApplicationRequest request);

    /** 반영 트랜잭션을 되돌리라는 내부 신호인지 확인한다. */
    static boolean isRolledBack(AppException exception) {
        return exception.getResultCode() == ValidationErrorCode.VALIDATION_APPLICATION_ROLLED_BACK;
    }
}
