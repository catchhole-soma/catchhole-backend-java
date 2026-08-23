package org.monitoring.catchholebackend.domain.episode.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum EpisodeErrorCode implements ResultCode {

    EPISODE_NOT_FOUND(HttpStatus.NOT_FOUND, "회차를 찾을 수 없습니다."),
    EPISODE_DUPLICATED(HttpStatus.CONFLICT, "이미 등록된 회차 번호입니다."),
    EPISODE_ANALYSIS_IN_PROGRESS(HttpStatus.CONFLICT, "분석 중인 회차는 변경하거나 삭제할 수 없습니다."),
    EPISODE_SOURCE_PURGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "기존 회차 원문을 영구 삭제하지 못했습니다."),
    EPISODE_UPLOAD_DUPLICATED(HttpStatus.CONFLICT, "업로드 요청에 중복된 회차 번호가 있습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
