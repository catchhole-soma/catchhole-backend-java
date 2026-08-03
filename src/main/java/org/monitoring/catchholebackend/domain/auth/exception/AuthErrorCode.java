package org.monitoring.catchholebackend.domain.auth.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ResultCode {

    AUTH_EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    AUTH_PHONE_NUMBER_DUPLICATED(HttpStatus.CONFLICT, "이미 가입된 휴대폰 번호입니다."),
    AUTH_PHONE_VERIFICATION_CODE_INVALID(HttpStatus.BAD_REQUEST, "인증번호가 올바르지 않습니다."),
    AUTH_PHONE_VERIFICATION_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 휴대폰 인증 토큰입니다."),
    AUTH_PHONE_VERIFICATION_EXPIRED(HttpStatus.GONE, "휴대폰 인증이 만료되었습니다."),
    AUTH_PHONE_VERIFICATION_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 요청해주세요."),
    AUTH_PHONE_VERIFICATION_ATTEMPTS_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "인증번호 입력 가능 횟수를 초과했습니다."),
    AUTH_PHONE_VERIFICATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "휴대폰 인증을 일시적으로 사용할 수 없습니다."),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    AUTH_REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "리프레시 토큰이 없습니다."),
    AUTH_REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
