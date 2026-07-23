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
    UPLOAD_FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "업로드 파일은 10MB 이하여야 합니다."),
    UPLOAD_SIZE_LIMIT_EXCEEDED(
            HttpStatus.BAD_REQUEST,
            "업로드 파일은 각각 10MB, 요청 전체는 25MB 이하여야 합니다."
    ),
    UPLOAD_FILE_TYPE_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "TXT 또는 DOCX 파일만 업로드할 수 있습니다."),
    UPLOAD_MULTI_FILE_TYPE_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "다회차 여러 파일 업로드는 TXT 파일만 지원합니다."),
    UPLOAD_EPISODE_NO_REQUIRED(HttpStatus.BAD_REQUEST, "단일 회차 업로드에는 회차 번호가 필요합니다."),
    UPLOAD_EPISODE_NO_INVALID(HttpStatus.BAD_REQUEST, "감지한 회차 번호는 1 이상의 정수여야 합니다."),
    UPLOAD_EPISODE_NO_DETECTION_FAILED(HttpStatus.BAD_REQUEST, "회차 번호를 인식할 수 없습니다."),
    UPLOAD_EPISODE_NO_CONFLICT(HttpStatus.BAD_REQUEST, "파일명과 원문에서 서로 다른 회차 번호를 감지했습니다."),
    UPLOAD_EPISODE_COUNT_INVALID(HttpStatus.BAD_REQUEST, "다회차 업로드에는 두 개 이상의 회차가 필요합니다."),
    UPLOAD_MULTI_FILE_EPISODE_COUNT_INVALID(
            HttpStatus.BAD_REQUEST,
            "다회차 여러 파일 업로드에서는 파일마다 회차가 하나여야 합니다."
    ),
    UPLOAD_EPISODE_ORDER_INVALID(HttpStatus.BAD_REQUEST, "파일 안의 회차 번호는 중복 없이 오름차순이어야 합니다."),
    UPLOAD_EPISODE_CONFIRMATION_REQUIRED(HttpStatus.BAD_REQUEST, "다회차 업로드에는 확정한 회차 정보가 필요합니다."),
    UPLOAD_EPISODE_CONFIRMATION_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "단일 회차 업로드에는 회차 확정 목록을 사용할 수 없습니다."),
    UPLOAD_EPISODE_CONFIRMATION_INVALID(HttpStatus.BAD_REQUEST, "확정한 회차 정보가 감지 결과와 일치하지 않습니다."),
    UPLOAD_SINGLE_EPISODE_METADATA_NOT_ALLOWED(
            HttpStatus.BAD_REQUEST,
            "다회차 업로드에는 단일 회차 번호와 제목을 사용할 수 없습니다."
    ),
    UPLOAD_SETTING_BOOK_DUPLICATED(HttpStatus.CONFLICT, "같은 이름의 설정집이 이미 업로드되어 있습니다."),
    UPLOAD_SETTING_BOOK_NOT_FOUND(HttpStatus.NOT_FOUND, "설정집 원본을 찾을 수 없습니다."),
    UPLOAD_FILE_PARSE_FAILED(HttpStatus.BAD_REQUEST, "업로드 파일을 회차로 분리할 수 없습니다."),
    UPLOAD_FILE_READ_FAILED(HttpStatus.BAD_REQUEST, "업로드 파일을 읽을 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
