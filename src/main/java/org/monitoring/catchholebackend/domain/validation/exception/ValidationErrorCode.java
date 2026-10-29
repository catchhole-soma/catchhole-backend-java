package org.monitoring.catchholebackend.domain.validation.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ValidationErrorCode implements ResultCode {
    VALIDATION_RUNTIME_DISABLED(HttpStatus.CONFLICT, "검수 실행이 활성화되어 있지 않습니다."),
    VALIDATION_LEASE_CONFLICT(HttpStatus.CONFLICT, "검수 실행 권한이 만료되었거나 다른 실행입니다."),
    VALIDATION_PUBLICATION_CONFLICT(HttpStatus.CONFLICT, "저장된 검수 단계 또는 검사 항목 버전이 다릅니다."),
    VALIDATION_RESULT_INVALID(HttpStatus.BAD_REQUEST, "검수 결과의 처리 범위 또는 원문 근거가 올바르지 않습니다."),
    VALIDATION_EXECUTION_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "검수 실행 예산을 초과했습니다."),
    VALIDATION_TARGET_INVALID(HttpStatus.BAD_REQUEST, "마지막 회차 뒤에 단일 업로드한 새 원고만 검수를 시작할 수 있습니다."),
    VALIDATION_COMPARISON_MATERIAL_REQUIRED(HttpStatus.BAD_REQUEST, "첫 회차 검수에는 설정집이나 직접 입력한 설정 등 비교 자료가 필요합니다."),
    VALIDATION_SOURCE_VERSION_CONFLICT(HttpStatus.CONFLICT, "원고 버전이 변경되었습니다. 현재 원고를 다시 확인해 주세요."),
    VALIDATION_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "같은 검수 요청 키에 다른 입력을 사용할 수 없습니다."),
    VALIDATION_REPORT_VERSION_CONFLICT(HttpStatus.CONFLICT, "현재 검수와 버전이 변경되었습니다. 다시 확인해 주세요."),
    VALIDATION_RECHECK_INPUT_UNCHANGED(HttpStatus.CONFLICT, "동일한 입력은 기존 검수의 재시도를 사용해 주세요."),
    VALIDATION_CORRECTION_CONFLICT(HttpStatus.CONFLICT, "기준 항목과 현재 설정을 정확히 연결할 수 없습니다. 다시 확인해 주세요."),
    VALIDATION_ERROR_REVIEW_PENDING(HttpStatus.CONFLICT, "오류 검사와 검토가 완료되기 전에는 설정 추출이나 후보 반영을 시작할 수 없습니다."),
    VALIDATION_JUDGMENT_INVALID(HttpStatus.BAD_REQUEST, "이 항목에 사용할 수 없는 판단입니다."),
    VALIDATION_JUDGMENT_LOCKED(HttpStatus.CONFLICT, "검사가 진행 중이거나 검토를 완료했거나 현재 유효한 검수가 아니어서 판단을 저장할 수 없습니다."),
    VALIDATION_JUDGMENT_STALE(HttpStatus.CONFLICT, "다시 검사되어 바뀐 항목입니다. 최신 항목을 다시 확인해 주세요."),
    VALIDATION_JUDGMENT_VERSION_CONFLICT(HttpStatus.CONFLICT, "판단이 이미 변경되었습니다. 다시 확인해 주세요."),
    VALIDATION_REVIEW_NOT_COMPLETABLE(HttpStatus.CONFLICT, "검사가 정상 완료되지 않았거나 검토할 항목이 남아 있거나 현재 유효한 검수가 아니어서 검토를 완료할 수 없습니다."),
    VALIDATION_REVIEW_ALREADY_COMPLETED(HttpStatus.CONFLICT, "이미 검토를 완료한 검수입니다. 판단 변경이나 재시도 대신 수정한 원고로 새 검수를 접수해 주세요."),
    VALIDATION_FINDING_NOT_FOUND(HttpStatus.NOT_FOUND, "검수 발견 항목을 찾을 수 없습니다."),
    VALIDATION_NOT_FOUND(HttpStatus.NOT_FOUND, "검수 요청을 찾을 수 없습니다.");
    private final HttpStatus status;
    private final String message;
    @Override public String getCode() { return name(); }
}
