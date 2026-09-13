package org.monitoring.catchholebackend.domain.worldsetting.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum WorldSettingErrorCode implements ResultCode {

    WORLD_SETTING_ORDERED_COMPARISON_FAILED(
            HttpStatus.CONFLICT, "누적 세계관 비교에 실패한 후보가 있어 후속 처리를 중단했습니다. 원래 분석 작업에서 재시도해주세요."),
    WORLD_SETTING_COMPARISON_INPUT_LIMIT_EXCEEDED(
            HttpStatus.UNPROCESSABLE_ENTITY, "누적 비교의 전체 입력이 상한을 초과했습니다. 설정을 생략하지 않고 비교를 중단합니다."),
    WORLD_SETTING_NOT_FOUND(HttpStatus.NOT_FOUND, "세계관 설정을 찾을 수 없습니다."),
    WORLD_SETTING_SUBJECT_DUPLICATED(HttpStatus.CONFLICT, "같은 분류에 동일한 세계관 대상이 있습니다."),
    WORLD_SETTING_PROPERTY_DUPLICATED(HttpStatus.CONFLICT, "같은 대상에 동일한 설정명이 있습니다."),
    WORLD_SETTING_PROPERTY_PATH_CONFLICT(HttpStatus.CONFLICT, "같은 이름을 설정 항목과 여러 설정을 묶는 이름으로 동시에 사용할 수 없습니다."),
    WORLD_SETTING_PROPERTY_NOT_FOUND(HttpStatus.NOT_FOUND, "세계관 설정 속성을 찾을 수 없습니다."),
    WORLD_SETTING_VERSION_CONFLICT(HttpStatus.CONFLICT, "세계관 설정이 먼저 변경되었습니다. 최신값을 확인해 주세요."),
    WORLD_SETTING_INPUT_INVALID(HttpStatus.BAD_REQUEST, "세계관 설정 입력값이 올바르지 않습니다."),
    WORLD_SETTING_PROPERTIES_INVALID(HttpStatus.BAD_REQUEST, "설정 이름과 내용을 글자로 입력해 주세요."),
    WORLD_SETTING_CANDIDATE_BATCH_NOT_FOUND(HttpStatus.NOT_FOUND, "세계관 설정 후보 검토 묶음을 찾을 수 없습니다."),
    WORLD_SETTING_CANDIDATE_NOT_FOUND(HttpStatus.NOT_FOUND, "세계관 설정 후보를 찾을 수 없습니다."),
    WORLD_SETTING_CANDIDATE_NOT_EDITABLE(HttpStatus.CONFLICT, "검토 대기 상태의 세계관 설정 후보만 수정할 수 있습니다."),
    WORLD_SETTING_CANDIDATE_COMPARISON_NOT_READY(HttpStatus.CONFLICT, "비교 완료된 세계관 설정 후보만 확정할 수 있습니다."),
    WORLD_SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT(HttpStatus.CONFLICT, "지금은 이 설정의 비교 상태를 변경할 수 없습니다. 화면을 새로고침해 주세요."),
    WORLD_SETTING_CANDIDATE_COMPARISON_CONTEXT_STALE(HttpStatus.CONFLICT, "세계관 설정 비교 대상이 변경되어 다시 비교해야 합니다."),
    WORLD_SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT(HttpStatus.CONFLICT, "지금은 이 설정을 검토할 수 없습니다. 화면을 새로고침해 주세요."),
    WORLD_SETTING_CANDIDATE_SELECTION_INVALID(HttpStatus.BAD_REQUEST, "선택한 설정을 처리할 수 없습니다. 화면을 새로고침한 뒤 다시 선택해 주세요."),
    WORLD_SETTING_CANDIDATE_GROUP_INVALID(HttpStatus.BAD_REQUEST, "서로 다른 세계관 대상의 설정은 한 번에 처리할 수 없습니다. 같은 대상의 설정만 선택해 주세요."),
    WORLD_SETTING_CANDIDATE_SETTING_NAME_DUPLICATED(HttpStatus.BAD_REQUEST, "같은 범위와 설정명이 여러 번 포함되어 있습니다. 내용을 하나로 합치거나 중복 후보를 제외해 주세요."),
    WORLD_SETTING_CANDIDATE_ADD_PATH_DUPLICATED(HttpStatus.CONFLICT, "추가하려는 범위와 설정명이 이미 존재합니다. 설정명을 바꾸거나 수정·병합 방식을 선택해 주세요."),
    WORLD_SETTING_CANDIDATE_UPDATE_PATH_NOT_FOUND(HttpStatus.CONFLICT, "수정·병합하려는 범위와 설정명이 존재하지 않습니다. 설정 경로를 확인하거나 추가 방식을 선택해 주세요."),
    WORLD_SETTING_CANDIDATE_CONFLICT_UNRESOLVED(HttpStatus.CONFLICT, "원문 내용이 서로 다른 설정은 최종 내용을 수정한 뒤 반영해 주세요."),
    WORLD_SETTING_CANDIDATE_RECOMPARISON_REQUIRED(HttpStatus.CONFLICT, "확정본이 변경되어 세계관 설정 후보를 다시 비교해야 합니다."),
    WORLD_SETTING_CANDIDATE_OPERATION_INVALID(HttpStatus.BAD_REQUEST, "세계관 설정 후보의 최종 반영 방식이 올바르지 않습니다."),
    WORLD_SETTING_COMPARISON_BATCH_NOT_FOUND(HttpStatus.NOT_FOUND, "세계관 설정 비교 묶음을 찾을 수 없습니다."),
    WORLD_SETTING_COMPARISON_BATCH_STATUS_CONFLICT(HttpStatus.CONFLICT, "세계관 설정 비교 묶음의 상태 전이가 올바르지 않습니다."),
    WORLD_SETTING_COMPARISON_BATCH_COMPLETION_CONFLICT(HttpStatus.CONFLICT, "이미 완료된 세계관 설정 비교 묶음과 다른 결과를 저장할 수 없습니다."),
    WORLD_SETTING_SUBJECT_RESOLUTION_REQUIRED(HttpStatus.CONFLICT, "이 설정이 어떤 대상을 설명하는지 먼저 확인해 주세요."),
    WORLD_SETTING_SUBJECT_RESOLUTION_INVALID(HttpStatus.BAD_REQUEST, "이 설정을 연결할 대상을 확인하지 못했습니다."),
    WORLD_SETTING_SUBJECT_RESOLUTION_STALE(HttpStatus.CONFLICT, "연결하려던 대상이 변경되었습니다. 대상을 다시 확인해 주세요."),
    WORLD_SETTING_WORKER_JOB_INVALID(HttpStatus.CONFLICT, "현재 분석에서 처리할 수 없는 설정입니다. 분석 상태를 확인해 주세요."),
    WORLD_SETTING_COMPARISON_TARGET_INVALID(HttpStatus.BAD_REQUEST, "세계관 설정 비교 대상 또는 속성이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
