package org.monitoring.catchholebackend.global.common.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "공통 API 응답 Envelope")
public record CommonResponse<T>(
        @Schema(description = "요청 처리 성공 여부", example = "true")
        boolean success,

        @Schema(description = "응답 메시지", example = "요청이 성공했습니다.")
        String message,

        @Schema(description = "성공 응답 데이터. 실패 응답에서는 null입니다.")
        T data,

        @Schema(description = "에러 정보. 성공 응답에서는 null입니다.")
        ErrorResponse error,

        @Schema(description = "응답 생성 시각", example = "2026-06-01T16:30:00")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime timestamp
) {

    private static final String DEFAULT_SUCCESS_MESSAGE = "요청이 성공했습니다.";

    public static <T> CommonResponse<T> success(T data) {
        return new CommonResponse<>(true, DEFAULT_SUCCESS_MESSAGE, data, null, LocalDateTime.now());
    }

    public static <T> CommonResponse<T> success(String message, T data) {
        return new CommonResponse<>(true, message, data, null, LocalDateTime.now());
    }

    public static CommonResponse<Void> failure(String message, ErrorResponse error) {
        return new CommonResponse<>(false, message, null, error, LocalDateTime.now());
    }
}
