package org.monitoring.catchholebackend.domain.worldimage.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum WorldImageErrorCode implements ResultCode {
    PRIVATE_IMAGE_VAULT_CONFLICT(HttpStatus.CONFLICT, "이미지 보관함이 이미 있어요. 복구키로 잠금을 해제해 주세요."),
    PRIVATE_IMAGE_VAULT_REQUIRED(HttpStatus.BAD_REQUEST, "내 이미지 보관함을 먼저 만들어 주세요."),
    PRIVATE_IMAGE_INVALID(HttpStatus.BAD_REQUEST, "암호화한 이미지 파일을 확인해 주세요."),
    PRIVATE_IMAGE_LIMIT(HttpStatus.BAD_REQUEST, "한 작품에는 개인 이미지를 50개까지 보관할 수 있어요."),
    PRIVATE_IMAGE_CONFLICT(HttpStatus.CONFLICT, "이미지 저장 상태를 다시 확인해 주세요."),
    PRIVATE_IMAGE_IN_USE(HttpStatus.CONFLICT, "사용 중인 이미지예요. 대상의 대표 이미지를 바꾼 뒤 삭제해 주세요."),
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
