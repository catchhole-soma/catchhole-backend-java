package org.monitoring.catchholebackend.domain.character.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterTimelineResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterTimelineSummaryResponse;
import org.monitoring.catchholebackend.domain.character.service.CharacterTimelineService;
import org.monitoring.catchholebackend.domain.character.type.CharacterTimelineFactFilter;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping(
        value = "/api/v1/works/{workId}/characters/{characterId}/timeline",
        produces = MediaType.APPLICATION_JSON_VALUE
)
@Tag(name = "CharacterTimeline", description = "캐릭터별 확정 설정 전체 이력 타임라인 API")
@SecurityRequirement(name = "bearerAuth")
public class CharacterTimelineController {

    private final CharacterTimelineService characterTimelineService;

    @GetMapping("/summary")
    @Operation(
            operationId = "getCharacterTimelineSummary",
            summary = "캐릭터 설정 이력 타임라인 요약 조회",
            description = "본인 작품의 ACTIVE 캐릭터에서 TIME을 제외한 현재·과거 Fact 개수와 회차 바로가기를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "캐릭터 타임라인 요약 조회 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "지원하지 않는 Fact 유형",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "작품 또는 ACTIVE 캐릭터를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<CharacterTimelineSummaryResponse> getCharacterTimelineSummary(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID characterId,
            @Parameter(
                    name = "factType",
                    in = ParameterIn.QUERY,
                    description = "타임라인 Fact 유형 필터. TIME은 지원하지 않습니다.",
                    example = "STATUS"
            )
            @RequestParam(defaultValue = "ALL")
            CharacterTimelineFactFilter factType,
            @Parameter(
                    description = "종류별 보기에서 OR로 적용할 상위 Fact 유형 목록. ALL은 다른 값·factKeys와 함께 쓸 수 없습니다.",
                    example = "STAT,PROFILE"
            )
            @RequestParam(required = false)
            List<CharacterTimelineFactFilter> factTypes,
            @Parameter(
                    description = "종류별 보기에서 OR로 적용할 canonical Fact key 목록.",
                    example = "stats.strength,profile.height"
            )
            @RequestParam(required = false)
            List<@NotBlank @Size(max = 150) String> factKeys
    ) {
        return CommonResponse.success(characterTimelineService.getSummary(
                member.memberId(),
                workId,
                characterId,
                factType,
                factTypes,
                factKeys
        ));
    }

    @GetMapping
    @Operation(
            operationId = "getCharacterTimeline",
            summary = "캐릭터 설정 이력 타임라인 조회",
            description = "회차, 첫 원문 근거 offset, 생성 시각, Fact ID 순서로 현재·과거 Fact를 조회합니다. "
                    + "cursor는 응답 값을 그대로 사용하고 회차 이동 시에만 fromEpisodeNo를 전달합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "캐릭터 타임라인 조회 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "Fact 유형, cursor, 시작 회차 또는 크기 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "작품 또는 ACTIVE 캐릭터를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<CharacterTimelineResponse> getCharacterTimeline(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID characterId,
            @RequestParam(defaultValue = "ALL")
            CharacterTimelineFactFilter factType,
            @Parameter(description = "종류별 보기에서 OR로 적용할 상위 Fact 유형 목록")
            @RequestParam(required = false)
            List<CharacterTimelineFactFilter> factTypes,
            @Parameter(description = "종류별 보기에서 OR로 적용할 canonical Fact key 목록")
            @RequestParam(required = false)
            List<@NotBlank @Size(max = 150) String> factKeys,
            @RequestParam(required = false)
            @Size(max = 512, message = "cursor는 512자 이하여야 합니다.")
            String cursor,
            @RequestParam(required = false)
            @Min(value = 1, message = "시작 회차는 1 이상이어야 합니다.")
            Integer fromEpisodeNo,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "조회 크기는 1 이상이어야 합니다.")
            @Max(value = 100, message = "조회 크기는 100 이하여야 합니다.")
            int size
    ) {
        return CommonResponse.success(characterTimelineService.getTimeline(
                member.memberId(),
                workId,
                characterId,
                factType,
                factTypes,
                factKeys,
                cursor,
                fromEpisodeNo,
                size
        ));
    }
}
