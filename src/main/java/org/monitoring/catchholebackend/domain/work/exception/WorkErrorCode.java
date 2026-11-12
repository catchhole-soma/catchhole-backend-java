package org.monitoring.catchholebackend.domain.work.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum WorkErrorCode implements ResultCode {

    WORK_NOT_FOUND(HttpStatus.NOT_FOUND, "작품을 찾을 수 없습니다."),
    WORK_COVER_INVALID(HttpStatus.BAD_REQUEST, "JPG·PNG·WebP 이미지(5MB 이하, 가로·세로 각각 4,096px 이하)를 선택해 주세요."),
    WORK_COVER_CONFLICT(HttpStatus.CONFLICT, "표지가 다른 곳에서 변경되었거나 저장 중입니다. 최신 표지를 확인한 뒤 다시 시도해 주세요."),
    WORK_COVER_NOT_FOUND(HttpStatus.NOT_FOUND, "표지 이미지를 찾을 수 없습니다."),
    WORK_ACCESS_DENIED(HttpStatus.FORBIDDEN, "작품에 접근할 권한이 없습니다."),
    WORK_PURGE_IN_PROGRESS(HttpStatus.CONFLICT, "영구 삭제 중인 작품은 변경하거나 분석할 수 없습니다."),
    WORK_PURGE_NOT_FOUND(HttpStatus.NOT_FOUND, "영구 삭제 요청을 찾을 수 없습니다."),
    WORK_PURGE_RETRY_NOT_ALLOWED(HttpStatus.CONFLICT, "현재 상태에서는 영구 삭제를 다시 시도할 수 없습니다."),
    WORK_PURGE_STORAGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "원고 저장소 삭제를 완료하지 못했습니다."),
    WORK_PURGE_DATABASE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "원고 파생 데이터 삭제를 완료하지 못했습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
