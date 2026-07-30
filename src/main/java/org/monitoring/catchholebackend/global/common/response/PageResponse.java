package org.monitoring.catchholebackend.global.common.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

@Schema(description = "서버 페이지네이션 응답")
public record PageResponse<T>(
        @Schema(description = "현재 페이지 항목")
        List<T> content,

        @Schema(description = "0부터 시작하는 현재 페이지 번호", example = "0")
        int page,

        @Schema(description = "현재 페이지 요청 크기", example = "12")
        int size,

        @Schema(description = "전체 항목 수", example = "48")
        long totalElements,

        @Schema(description = "전체 페이지 수", example = "4")
        int totalPages,

        @Schema(description = "다음 페이지 존재 여부", example = "true")
        boolean hasNext
) {

    public static <T> PageResponse<T> from(Page<?> page, List<T> content) {
        return new PageResponse<>(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext()
        );
    }
}
