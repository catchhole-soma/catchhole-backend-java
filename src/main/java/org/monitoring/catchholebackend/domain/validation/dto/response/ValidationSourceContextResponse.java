package org.monitoring.catchholebackend.domain.validation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 오류 리포트 문맥 블록(2026-11-03): 고정 출처 원문의 줄 단위 창. 위치는 원문 전체 기준 Unicode code point [start,end)다. */
@Schema(description = "검사 당시 고정 원문(hash 확인)의 줄 단위 창. 공백만 있는 줄은 세지도 돌려주지도 않으며 줄 글에는 줄 끝 문자가 없습니다. 위치는 원문 전체 기준 Unicode code point [start,end)이고 화면은 1부터 셉니다.")
public record ValidationSourceContextResponse(
        @Schema(description = "출처 참조(화면 표시용 아님). 상세 blocks[].sourceRef와 같습니다.", example = "R1") String sourceRef,
        @Schema(description = "첫 줄 시작 code point. 줄이 없으면 anchor 위치", example = "2120") int start,
        @Schema(description = "마지막 줄 끝 code point(제외). 줄이 없으면 anchor 위치", example = "2290") int end,
        @Schema(description = "원문 전체 code point 수", example = "7408") int totalCodePoints,
        @Schema(description = "이 창 위에 더 읽을 줄이 있으면 true") boolean hasMoreBefore,
        @Schema(description = "이 창 아래에 더 읽을 줄이 있으면 true") boolean hasMoreAfter,
        @Schema(description = "원문 순서의 줄") List<Line> lines) {
    @Schema(name = "ValidationSourceContextLine", description = "원문 한 줄(500자보다 긴 줄은 나눈 조각 하나). 위치는 원문 전체 기준 code point [start,end)")
    public record Line(@Schema(example = "2120") int start, @Schema(example = "2160") int end,
            @Schema(description = "줄 글(줄 끝 문자 제외)", example = "합성 문맥 줄") String text) {}
}
