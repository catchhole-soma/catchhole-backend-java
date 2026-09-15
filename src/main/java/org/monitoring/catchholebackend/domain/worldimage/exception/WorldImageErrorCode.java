package org.monitoring.catchholebackend.domain.worldimage.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum WorldImageErrorCode implements ResultCode {
    WORLD_IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "이미지를 찾을 수 없습니다. 다른 이미지를 선택해 주세요."),
    WORLD_IMAGE_CATEGORY_MISMATCH(HttpStatus.BAD_REQUEST, "대상과 같은 분류의 이미지를 선택해 주세요."),
    WORLD_IMAGE_VERSION_CONFLICT(HttpStatus.CONFLICT, "이미지가 먼저 변경되었습니다. 최신 이미지를 확인한 뒤 다시 선택해 주세요.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
