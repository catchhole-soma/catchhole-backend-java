package org.monitoring.catchholebackend.domain.chat.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ChatErrorCode implements ResultCode {
    CHAT_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "채팅 실행이 아직 활성화되지 않았습니다."),
    CHAT_CONFLICT(HttpStatus.CONFLICT, "실행 상태 또는 멱등성 입력이 일치하지 않습니다."),
    CHAT_SCOPE_INVALID(HttpStatus.FORBIDDEN, "허용된 채팅 실행 범위를 벗어났습니다."),
    CHAT_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "채팅 실행 한도를 초과했습니다."),
    CHAT_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅 실행을 찾을 수 없습니다.");
    private final HttpStatus status;
    private final String message;

    public String getCode() {
        return name();
    }
}
