package org.monitoring.catchholebackend.domain.analysis.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AnalysisJobErrorCode implements ResultCode {

    ANALYSIS_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "분석 작업을 찾을 수 없습니다."),
    ANALYSIS_JOB_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "분석 대상 리소스를 찾을 수 없습니다."),
    ANALYSIS_JOB_ALREADY_IN_PROGRESS(HttpStatus.CONFLICT, "같은 대상의 분석 작업이 이미 진행 중입니다."),
    ANALYSIS_JOB_TYPE_INVALID(HttpStatus.BAD_REQUEST, "외부에서 생성하거나 조회할 수 없는 분석 작업 유형입니다."),
    ANALYSIS_JOB_STATUS_CONFLICT(HttpStatus.CONFLICT, "분석 작업 상태가 올바르지 않습니다."),
    ANALYSIS_AUTOMATIC_APPLICATION_PENDING(HttpStatus.CONFLICT, "이 회차의 설정을 자동으로 반영하고 있습니다. 완료된 뒤 다시 시도해 주세요."),
    ANALYSIS_ORDERED_JOB_RETRY_REQUIRED(HttpStatus.CONFLICT, "누적 분석의 실패 후보는 원래 분석 작업에서 재시도해야 합니다."),
    ANALYSIS_JOB_LEASE_CONFLICT(HttpStatus.CONFLICT, "분석 작업의 처리 시간이 만료되었거나 담당 작업이 변경되었습니다."),
    ANALYSIS_JOB_CHECKPOINT_INCOMPLETE(HttpStatus.CONFLICT, "분석 작업의 필수 처리 단계가 완료되지 않았습니다."),
    ANALYSIS_JOB_EPISODE_STATUS_REQUIRED(HttpStatus.BAD_REQUEST, "회차 분석 작업의 진행 상태는 필수입니다."),
    ANALYSIS_RUN_STATE_CONFLICT(HttpStatus.CONFLICT, "누적 분석의 실행·입력 상태 또는 원문 버전이 변경되었습니다."),
    ANALYSIS_RUN_PREDECESSOR_INCOMPLETE(HttpStatus.CONFLICT, "앞 회차의 변경 기록이 완성되지 않았습니다."),
    ANALYSIS_RUN_MODE_INVALID(HttpStatus.BAD_REQUEST, "요청한 분석 모드와 대상이 일치하지 않습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
