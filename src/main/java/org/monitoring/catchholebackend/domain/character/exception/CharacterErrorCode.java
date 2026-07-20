package org.monitoring.catchholebackend.domain.character.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CharacterErrorCode implements ResultCode {

    SETTING_CANDIDATE_NOT_FOUND(HttpStatus.NOT_FOUND, "설정 후보를 찾을 수 없습니다."),
    SETTING_CANDIDATE_NOT_EDITABLE(HttpStatus.CONFLICT, "검토 대기 상태의 설정 후보만 수정할 수 있습니다."),
    SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT(HttpStatus.CONFLICT, "설정 후보 검토 상태 전이가 올바르지 않습니다."),
    SETTING_CANDIDATE_MATCH_STATUS_CONFLICT(HttpStatus.CONFLICT, "설정 후보 캐릭터 매칭 상태가 올바르지 않습니다."),
    SETTING_CANDIDATE_MATCHED_CHARACTER_REQUIRED(HttpStatus.BAD_REQUEST, "기존 캐릭터 연결에는 matchedCharacterId가 필요합니다."),
    SETTING_CANDIDATE_NEW_CHARACTER_NAME_REQUIRED(HttpStatus.BAD_REQUEST, "새 캐릭터 이름은 필수입니다."),
    SETTING_CANDIDATE_CHARACTER_NAME_DUPLICATED(HttpStatus.CONFLICT, "이미 같은 이름의 캐릭터가 있습니다."),
    SETTING_CANDIDATE_MATCHED_CHARACTER_INVALID(HttpStatus.CONFLICT, "설정 후보에 연결된 캐릭터가 유효하지 않습니다."),
    SETTING_CANDIDATE_SCHEMA_NOT_MATCHED(HttpStatus.BAD_REQUEST, "설정 후보 속성과 일치하는 활성 schema가 없습니다."),
    SETTING_CANDIDATE_SCHEMA_MATCH_AMBIGUOUS(HttpStatus.CONFLICT, "설정 후보 속성과 일치하는 schema가 여러 개입니다."),
    SETTING_CANDIDATE_VALUE_TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "설정 후보 값 타입이 schema와 일치하지 않습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
