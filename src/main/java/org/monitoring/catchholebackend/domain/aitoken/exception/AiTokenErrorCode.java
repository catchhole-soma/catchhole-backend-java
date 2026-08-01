package org.monitoring.catchholebackend.domain.aitoken.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AiTokenErrorCode implements ResultCode {

    AI_TOKEN_QUOTA_EXHAUSTED(HttpStatus.CONFLICT, "기본 AI 토큰을 모두 사용했습니다."),
    AI_TOKEN_USAGE_INVALID(HttpStatus.BAD_REQUEST, "AI 토큰 사용량 값이 올바르지 않습니다."),
    AI_TOKEN_RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "AI 토큰 예약을 찾을 수 없습니다."),
    AI_TOKEN_RESERVATION_CONFLICT(HttpStatus.CONFLICT, "AI 토큰 예약 상태가 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
