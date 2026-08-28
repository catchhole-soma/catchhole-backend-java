package org.monitoring.catchholebackend.domain.feedback.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum FeedbackErrorCode implements ResultCode {

    FEEDBACK_CONTENT_INVALID(HttpStatus.BAD_REQUEST, "의견은 공백을 제외하고 35자 이상 1,000자 이하로 입력해 주세요."),
    FEEDBACK_PAGE_PATH_INVALID(HttpStatus.BAD_REQUEST, "의견을 작성한 화면 경로가 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
