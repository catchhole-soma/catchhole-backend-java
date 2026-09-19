package org.monitoring.catchholebackend.domain.character.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CharacterErrorCode implements ResultCode {

    SETTING_CANDIDATE_ORDERED_COMPARISON_FAILED(HttpStatus.CONFLICT, "누적 비교 후보의 처리 실패로 후속 분석을 중단했습니다. 원래 분석 작업에서 재시도해 주세요."),

    CHARACTER_NOT_FOUND(HttpStatus.NOT_FOUND, "캐릭터 정보를 찾을 수 없습니다."),
    CHARACTER_FACT_NOT_FOUND(HttpStatus.NOT_FOUND, "캐릭터 설정 정보를 찾을 수 없습니다."),
    CHARACTER_NAME_DUPLICATED(HttpStatus.CONFLICT, "이미 같은 이름의 캐릭터가 있습니다."),
    CHARACTER_SETTING_KEY_INVALID(HttpStatus.BAD_REQUEST, "설정 이름이 선택한 분류와 맞지 않습니다."),
    CHARACTER_SETTING_KEY_DUPLICATED(HttpStatus.BAD_REQUEST, "같은 이름의 설정이 이미 있습니다."),
    CHARACTER_SETTING_VALUE_INVALID(HttpStatus.BAD_REQUEST, "캐릭터 설정을 저장할 수 없는 형식입니다. 입력한 내용을 확인해 주세요."),
    CHARACTER_SETTING_VALUE_TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "이 캐릭터 설정에 맞는 형식으로 내용을 입력해 주세요."),
    CHARACTER_SNAPSHOT_SOURCE_INVALID(HttpStatus.CONFLICT, "현재 캐릭터 설정의 원문 근거 연결이 올바르지 않습니다."),
    CHARACTER_TIMELINE_FILTER_INVALID(HttpStatus.BAD_REQUEST, "캐릭터 타임라인 필터가 올바르지 않습니다."),
    CHARACTER_TIMELINE_CURSOR_INVALID(HttpStatus.BAD_REQUEST, "다음 이력을 불러오지 못했습니다. 화면을 새로고침해 주세요."),
    SETTING_CANDIDATE_BATCH_NOT_FOUND(HttpStatus.NOT_FOUND, "설정 후보 검토 묶음을 찾을 수 없습니다."),
    SETTING_CANDIDATE_NOT_FOUND(HttpStatus.NOT_FOUND, "설정 후보를 찾을 수 없습니다."),
    SETTING_CANDIDATE_NOT_EDITABLE(HttpStatus.CONFLICT, "검토 대기 상태의 설정 후보만 수정할 수 있습니다."),
    SETTING_CANDIDATE_CONTENT_NOT_EDITABLE(HttpStatus.BAD_REQUEST, "캐릭터 발견 후보는 설정 내용을 수정할 수 없습니다."),
    SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT(HttpStatus.CONFLICT, "지금은 이 설정을 검토할 수 없습니다. 화면을 새로고침해 주세요."),
    SETTING_CANDIDATE_MATCH_STATUS_CONFLICT(HttpStatus.CONFLICT, "설정 후보 캐릭터 매칭 상태가 올바르지 않습니다."),
    SETTING_CANDIDATE_MATCHED_CHARACTER_REQUIRED(HttpStatus.BAD_REQUEST, "연결할 기존 캐릭터를 선택해 주세요."),
    SETTING_CANDIDATE_NEW_CHARACTER_NAME_REQUIRED(HttpStatus.BAD_REQUEST, "새 캐릭터 이름은 필수입니다."),
    SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED(HttpStatus.CONFLICT, "이미 같은 이름의 캐릭터가 있습니다."),
    SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID(HttpStatus.CONFLICT, "설정 후보에 연결된 캐릭터가 유효하지 않습니다."),
    SETTING_CANDIDATE_ATTRIBUTE_NAME_NOT_EDITABLE(
            HttpStatus.BAD_REQUEST,
            "이 항목은 이름이 정해져 있어 설정명을 바꿀 수 없습니다."
    ),
    SETTING_CANDIDATE_ATTRIBUTE_NAME_INVALID(
            HttpStatus.BAD_REQUEST,
            "설정명은 현재 선택한 분류에 맞게 입력해 주세요."
    ),
    SETTING_CANDIDATE_EDIT_VALUE_INVALID(
            HttpStatus.BAD_REQUEST,
            "수정한 내용을 이 설정에 맞는 형식으로 입력해 주세요."
    ),
    SETTING_CANDIDATE_EDIT_APPLICATION_REQUIRED(
            HttpStatus.CONFLICT,
            "수정한 내용을 현재 설정에 반영하려면 직접 확인한 값으로 확정해 주세요."
    ),
    SETTING_CANDIDATE_SCHEMA_NOT_MATCHED(HttpStatus.BAD_REQUEST, "이 설정의 입력 형식을 확인하지 못했습니다."),
    SETTING_CANDIDATE_SCHEMA_MATCH_AMBIGUOUS(HttpStatus.CONFLICT, "이 설정에 적용할 입력 형식을 하나로 정하지 못했습니다."),
    SETTING_CANDIDATE_VALUE_TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "이 설정에 맞는 형식으로 내용을 입력해 주세요."),
    SETTING_CANDIDATE_VALUE_INVALID(HttpStatus.BAD_REQUEST, "나이와 레벨 설정 후보 값은 0 이상의 정수여야 합니다."),
    SETTING_CANDIDATE_VALUE_FORMAT_INVALID(
            HttpStatus.BAD_REQUEST,
            "설정 내용을 저장할 수 없는 형식입니다. 내용을 확인해 주세요."
    ),
    SETTING_CANDIDATE_VALUE_MISMATCH(
            HttpStatus.BAD_REQUEST,
            "화면에 표시된 내용과 저장할 내용이 다릅니다. 내용을 확인해 주세요."
    ),
    SETTING_CANDIDATE_VALUE_JSON_INVALID(
            HttpStatus.BAD_REQUEST,
            "설정 이름이나 내용이 올바르지 않습니다. 내용을 확인해 주세요."
    ),
    SETTING_CANDIDATE_COMPARISON_NOT_READY(HttpStatus.CONFLICT, "비교가 완료된 캐릭터 설정 후보만 확정할 수 있습니다."),
    SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT(HttpStatus.CONFLICT, "지금은 이 설정의 비교 상태를 변경할 수 없습니다. 화면을 새로고침해 주세요."),
    SETTING_CANDIDATE_COMPARISON_INPUT_LIMIT_EXCEEDED(
            HttpStatus.UNPROCESSABLE_ENTITY, "누적 비교의 전체 입력이 상한을 초과했습니다. 설정을 생략하지 않고 비교를 중단합니다."),
    SETTING_CANDIDATE_COMPARISON_BATCH_NOT_FOUND(HttpStatus.NOT_FOUND, "캐릭터 설정 비교 묶음을 찾을 수 없습니다."),
    SETTING_CANDIDATE_COMPARISON_BATCH_RESPONSE_INVALID(
            HttpStatus.BAD_REQUEST,
            "요청한 설정 중 일부를 비교하지 못했습니다."
    ),
    SETTING_CANDIDATE_COMPARISON_STALE(HttpStatus.CONFLICT, "현재 캐릭터 설정이 변경되었습니다. 최신 내용을 다시 확인해 주세요."),
    SETTING_CANDIDATE_COMPARISON_TARGET_INVALID(HttpStatus.BAD_REQUEST, "캐릭터 설정 비교 대상 또는 변경 제안이 올바르지 않습니다."),
    SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID(HttpStatus.CONFLICT, "현재 확정할 수 없는 캐릭터 설정 비교 결과입니다."),
    SETTING_CANDIDATE_GROUP_DECISION_DEPENDENCY_CONFLICT(
            HttpStatus.CONFLICT,
            "앞선 동일 설정을 현재값에 반영하지 않으면 뒤 후보의 AI 제안을 그대로 적용할 수 없습니다. "
                    + "뒤 후보도 이력에만 저장하거나 앞 후보를 현재 설정에 반영해 주세요."
    ),
    SETTING_CANDIDATE_WORKER_JOB_INVALID(HttpStatus.CONFLICT, "현재 분석에서 처리할 수 없는 설정입니다. 분석 상태를 확인해 주세요."),
    SETTING_CANDIDATE_MERGE_POLICY_UNSUPPORTED(HttpStatus.CONFLICT, "현재 지원하지 않는 설정 병합 정책입니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
