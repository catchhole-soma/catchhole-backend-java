package org.monitoring.catchholebackend.domain.upload.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UploadErrorCode implements ResultCode {

    UPLOAD_TYPE_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "지원하지 않는 업로드 방식입니다."),
    UPLOAD_FILE_REQUIRED(HttpStatus.BAD_REQUEST, "업로드할 회차 파일이 필요합니다."),
    UPLOAD_FILE_EMPTY(HttpStatus.BAD_REQUEST, "빈 파일은 업로드할 수 없습니다."),
    UPLOAD_EPISODE_NO_REQUIRED(HttpStatus.BAD_REQUEST, "단일 회차 업로드에는 회차 번호가 필요합니다."),
    UPLOAD_EPISODE_NO_DETECTION_FAILED(HttpStatus.BAD_REQUEST, "회차 번호를 인식할 수 없습니다."),
    UPLOAD_FILE_PARSE_FAILED(HttpStatus.BAD_REQUEST, "업로드 파일을 회차로 분리할 수 없습니다."),
    UPLOAD_FILE_READ_FAILED(HttpStatus.BAD_REQUEST, "업로드 파일을 읽을 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
