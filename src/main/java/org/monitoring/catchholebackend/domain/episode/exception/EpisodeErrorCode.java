package org.monitoring.catchholebackend.domain.episode.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum EpisodeErrorCode implements ResultCode {

    EPISODE_NOT_FOUND(HttpStatus.NOT_FOUND, "회차를 찾을 수 없습니다."),
    EPISODE_DUPLICATED(HttpStatus.CONFLICT, "이미 등록된 회차 번호입니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
