package org.monitoring.catchholebackend.domain.worldsetting.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum WorldSettingErrorCode implements ResultCode {

    WORLD_SETTING_NOT_FOUND(HttpStatus.NOT_FOUND, "세계관 설정을 찾을 수 없습니다."),
    WORLD_SETTING_SUBJECT_DUPLICATED(HttpStatus.CONFLICT, "같은 분류에 동일한 세계관 대상이 있습니다."),
    WORLD_SETTING_PROPERTY_DUPLICATED(HttpStatus.CONFLICT, "같은 대상에 동일한 설정명이 있습니다."),
    WORLD_SETTING_PROPERTY_NOT_FOUND(HttpStatus.NOT_FOUND, "세계관 설정 속성을 찾을 수 없습니다."),
    WORLD_SETTING_VERSION_CONFLICT(HttpStatus.CONFLICT, "세계관 설정이 먼저 변경되었습니다. 최신값을 확인해 주세요."),
    WORLD_SETTING_INPUT_INVALID(HttpStatus.BAD_REQUEST, "세계관 설정 입력값이 올바르지 않습니다."),
    WORLD_SETTING_PROPERTIES_INVALID(HttpStatus.BAD_REQUEST, "세계관 설정 속성은 문자열 key와 문자열 value 객체여야 합니다."),
    WORLD_SETTING_CANDIDATE_BATCH_NOT_FOUND(HttpStatus.NOT_FOUND, "세계관 설정 후보 검토 묶음을 찾을 수 없습니다."),
    WORLD_SETTING_CANDIDATE_NOT_FOUND(HttpStatus.NOT_FOUND, "세계관 설정 후보를 찾을 수 없습니다."),
    WORLD_SETTING_CANDIDATE_NOT_EDITABLE(HttpStatus.CONFLICT, "검토 대기 상태의 세계관 설정 후보만 수정할 수 있습니다."),
    WORLD_SETTING_CANDIDATE_COMPARISON_NOT_READY(HttpStatus.CONFLICT, "비교 완료된 세계관 설정 후보만 확정할 수 있습니다."),
    WORLD_SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT(HttpStatus.CONFLICT, "세계관 설정 후보의 비교 상태 전이가 올바르지 않습니다."),
    WORLD_SETTING_CANDIDATE_COMPARISON_CONTEXT_STALE(HttpStatus.CONFLICT, "세계관 설정 비교 대상이 변경되어 다시 비교해야 합니다."),
    WORLD_SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT(HttpStatus.CONFLICT, "세계관 설정 후보의 검토 상태 전이가 올바르지 않습니다."),
    WORLD_SETTING_CANDIDATE_SELECTION_INVALID(HttpStatus.BAD_REQUEST, "선택한 설정을 처리할 수 없습니다. 화면을 새로고침한 뒤 다시 선택해 주세요."),
    WORLD_SETTING_CANDIDATE_GROUP_INVALID(HttpStatus.BAD_REQUEST, "서로 다른 세계관 대상의 설정은 한 번에 처리할 수 없습니다. 같은 대상의 설정만 선택해 주세요."),
    WORLD_SETTING_CANDIDATE_SETTING_NAME_DUPLICATED(HttpStatus.BAD_REQUEST, "같은 설정명이 여러 번 포함되어 있습니다. 내용을 하나로 합치거나 하나만 선택해 주세요."),
    WORLD_SETTING_CANDIDATE_CONFLICT_UNRESOLVED(HttpStatus.CONFLICT, "원문 내용이 서로 다른 설정은 최종 내용을 수정한 뒤 반영해 주세요."),
    WORLD_SETTING_CANDIDATE_RECOMPARISON_REQUIRED(HttpStatus.CONFLICT, "확정본이 변경되어 세계관 설정 후보를 다시 비교해야 합니다."),
    WORLD_SETTING_CANDIDATE_OPERATION_INVALID(HttpStatus.BAD_REQUEST, "세계관 설정 후보의 최종 반영 방식이 올바르지 않습니다."),
    WORLD_SETTING_WORKER_JOB_INVALID(HttpStatus.CONFLICT, "세계관 설정 Worker 작업 범위가 올바르지 않습니다."),
    WORLD_SETTING_COMPARISON_TARGET_INVALID(HttpStatus.BAD_REQUEST, "세계관 설정 비교 대상 또는 속성이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
