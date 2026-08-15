package org.monitoring.catchholebackend.domain.character.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CharacterErrorCode implements ResultCode {

    CHARACTER_NOT_FOUND(HttpStatus.NOT_FOUND, "캐릭터 정보를 찾을 수 없습니다."),
    CHARACTER_FACT_NOT_FOUND(HttpStatus.NOT_FOUND, "캐릭터 설정 정보를 찾을 수 없습니다."),
    CHARACTER_NAME_DUPLICATED(HttpStatus.CONFLICT, "이미 같은 이름의 캐릭터가 있습니다."),
    CHARACTER_SETTING_KEY_INVALID(HttpStatus.BAD_REQUEST, "캐릭터 설정 key가 설정 유형과 일치하지 않습니다."),
    CHARACTER_SETTING_KEY_DUPLICATED(HttpStatus.BAD_REQUEST, "캐릭터 설정 key가 중복되었습니다."),
    CHARACTER_SETTING_VALUE_INVALID(HttpStatus.BAD_REQUEST, "캐릭터 설정 값이 지정한 값 타입과 일치하지 않습니다."),
    CHARACTER_SETTING_VALUE_TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "캐릭터 설정 값 타입이 schema와 일치하지 않습니다."),
    CHARACTER_SNAPSHOT_SOURCE_INVALID(HttpStatus.CONFLICT, "현재 캐릭터 설정의 원문 근거 연결이 올바르지 않습니다."),
    CHARACTER_TIMELINE_FILTER_INVALID(HttpStatus.BAD_REQUEST, "캐릭터 타임라인 필터가 올바르지 않습니다."),
    CHARACTER_TIMELINE_CURSOR_INVALID(HttpStatus.BAD_REQUEST, "캐릭터 타임라인 cursor가 올바르지 않습니다."),
    SETTING_CANDIDATE_BATCH_NOT_FOUND(HttpStatus.NOT_FOUND, "설정 후보 검토 묶음을 찾을 수 없습니다."),
    SETTING_CANDIDATE_NOT_FOUND(HttpStatus.NOT_FOUND, "설정 후보를 찾을 수 없습니다."),
    SETTING_CANDIDATE_NOT_EDITABLE(HttpStatus.CONFLICT, "검토 대기 상태의 설정 후보만 수정할 수 있습니다."),
    SETTING_CANDIDATE_CONTENT_NOT_EDITABLE(HttpStatus.BAD_REQUEST, "캐릭터 발견 후보는 설정 내용을 수정할 수 없습니다."),
    SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT(HttpStatus.CONFLICT, "설정 후보 검토 상태 전이가 올바르지 않습니다."),
    SETTING_CANDIDATE_MATCH_STATUS_CONFLICT(HttpStatus.CONFLICT, "설정 후보 캐릭터 매칭 상태가 올바르지 않습니다."),
    SETTING_CANDIDATE_MATCHED_CHARACTER_REQUIRED(HttpStatus.BAD_REQUEST, "기존 캐릭터 연결에는 matchedCharacterId가 필요합니다."),
    SETTING_CANDIDATE_NEW_CHARACTER_NAME_REQUIRED(HttpStatus.BAD_REQUEST, "새 캐릭터 이름은 필수입니다."),
    SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED(HttpStatus.CONFLICT, "이미 같은 이름의 캐릭터가 있습니다."),
    SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID(HttpStatus.CONFLICT, "설정 후보에 연결된 캐릭터가 유효하지 않습니다."),
    SETTING_CANDIDATE_ATTRIBUTE_NAME_NOT_EDITABLE(
            HttpStatus.BAD_REQUEST,
            "고정 schema에 매칭된 설정 후보의 설정명은 수정할 수 없습니다."
    ),
    SETTING_CANDIDATE_ATTRIBUTE_NAME_INVALID(
            HttpStatus.BAD_REQUEST,
            "동적 설정 후보의 설정명은 기존 schema pattern 안에서만 수정할 수 있습니다."
    ),
    SETTING_CANDIDATE_EDIT_VALUE_INVALID(
            HttpStatus.BAD_REQUEST,
            "수정한 설정값이 설정 후보의 값 타입과 일치하지 않습니다."
    ),
    SETTING_CANDIDATE_SCHEMA_NOT_MATCHED(HttpStatus.BAD_REQUEST, "설정 후보 속성과 일치하는 활성 schema가 없습니다."),
    SETTING_CANDIDATE_SCHEMA_MATCH_AMBIGUOUS(HttpStatus.CONFLICT, "설정 후보 속성과 일치하는 schema가 여러 개입니다."),
    SETTING_CANDIDATE_VALUE_TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "설정 후보 값 타입이 schema와 일치하지 않습니다."),
    SETTING_CANDIDATE_VALUE_INVALID(HttpStatus.BAD_REQUEST, "나이와 레벨 설정 후보 값은 0 이상의 정수여야 합니다."),
    SETTING_CANDIDATE_VALUE_FORMAT_INVALID(
            HttpStatus.BAD_REQUEST,
            "설정 후보의 표시값이 값 타입과 일치하지 않습니다."
    ),
    SETTING_CANDIDATE_VALUE_MISMATCH(
            HttpStatus.BAD_REQUEST,
            "설정 후보의 표시값과 구조화 값이 서로 일치하지 않습니다."
    ),
    SETTING_CANDIDATE_VALUE_JSON_INVALID(
            HttpStatus.BAD_REQUEST,
            "설정 후보의 구조화 값에 유효하지 않은 속성 key 또는 값 형식이 있습니다."
    ),
    SETTING_CANDIDATE_COMPARISON_NOT_READY(HttpStatus.CONFLICT, "비교가 완료된 캐릭터 설정 후보만 확정할 수 있습니다."),
    SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT(HttpStatus.CONFLICT, "캐릭터 설정 후보의 비교 상태 전이가 올바르지 않습니다."),
    SETTING_CANDIDATE_COMPARISON_STALE(HttpStatus.CONFLICT, "캐릭터 현재 설정이 변경되어 비교 문맥을 다시 조회해야 합니다."),
    SETTING_CANDIDATE_COMPARISON_TARGET_INVALID(HttpStatus.BAD_REQUEST, "캐릭터 설정 비교 대상 또는 변경 제안이 올바르지 않습니다."),
    SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID(HttpStatus.CONFLICT, "현재 확정할 수 없는 캐릭터 설정 비교 결과입니다."),
    SETTING_CANDIDATE_GROUP_DECISION_DEPENDENCY_CONFLICT(
            HttpStatus.CONFLICT,
            "앞선 동일 설정을 현재값에 반영하지 않으면 뒤 후보의 AI 제안을 그대로 적용할 수 없습니다. "
                    + "뒤 후보도 이력에만 저장하거나 앞 후보를 현재 설정에 반영해 주세요."
    ),
    SETTING_CANDIDATE_WORKER_JOB_INVALID(HttpStatus.CONFLICT, "캐릭터 설정 비교 Worker 작업 범위가 올바르지 않습니다."),
    SETTING_CANDIDATE_MERGE_POLICY_UNSUPPORTED(HttpStatus.CONFLICT, "현재 지원하지 않는 설정 병합 정책입니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
