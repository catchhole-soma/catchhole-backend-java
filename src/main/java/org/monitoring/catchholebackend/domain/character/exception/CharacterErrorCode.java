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
    SETTING_CANDIDATE_REVIEW_STATUS_CONFLICT(HttpStatus.CONFLICT, "설정 후보 검토 상태 전이가 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
