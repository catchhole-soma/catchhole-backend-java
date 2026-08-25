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
    AI_TOKEN_RESERVATION_CONFLICT(HttpStatus.CONFLICT, "AI 토큰 예약 상태가 올바르지 않습니다."),
    AI_TOKEN_EXTENSION_FEEDBACK_INVALID(HttpStatus.BAD_REQUEST, "피드백은 공백을 제외하고 35자 이상 1,000자 이하로 입력해 주세요."),
    AI_TOKEN_EXTENSION_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "추가 사용량 요청을 찾을 수 없습니다."),
    AI_TOKEN_EXTENSION_REVIEW_CONFLICT(HttpStatus.CONFLICT, "이미 처리된 추가 사용량 요청입니다."),
    AI_TOKEN_EXTENSION_REJECTION_REASON_INVALID(HttpStatus.BAD_REQUEST, "거절 사유를 1자 이상 500자 이하로 입력해 주세요."),
    AI_TOKEN_EXTENSION_GRANT_DISABLED(HttpStatus.CONFLICT, "현재 추가 사용량 지급 설정이 비활성화되어 있습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
