package org.monitoring.catchholebackend.domain.worldsetting.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCreateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingIdentityUpdateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingPropertyCreateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingPropertyUpdateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingDetailResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingListResponse;
import org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingService;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSort;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping(
        value = "/api/v1/works/{workId}/world-settings",
        produces = MediaType.APPLICATION_JSON_VALUE
)
@Tag(name = "WorldSetting", description = "작품별 확정 세계관 설정 조회, 추가, 수정 API")
@SecurityRequirement(name = "bearerAuth")
public class WorldSettingController {

    private final WorldSettingService worldSettingService;

    @GetMapping
    @Operation(
            operationId = "getWorldSettings",
            summary = "세계관 대상 목록 조회",
            description = "대상명·설정명·설정값 검색, 분류 필터와 고정 정렬을 적용해 세계관 대상을 페이지 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "세계관 대상 목록 조회 성공"),
            @ApiResponse(responseCode = "400", description = "페이지 또는 query parameter 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<WorldSettingListResponse> getWorldSettings(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) WorldSettingCategory category,
            @RequestParam(defaultValue = "CATEGORY_SUBJECT_ASC") WorldSettingSort sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return CommonResponse.success(worldSettingService.getWorldSettings(
                member.memberId(),
                workId,
                q,
                category,
                sort,
                page,
                size
        ));
    }

    @GetMapping("/{worldSettingId}")
    @Operation(operationId = "getWorldSetting", summary = "세계관 대상 상세 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "세계관 대상 상세 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 세계관 대상을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<WorldSettingDetailResponse> getWorldSetting(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID worldSettingId
    ) {
        return CommonResponse.success(
                worldSettingService.getWorldSetting(member.memberId(), workId, worldSettingId)
        );
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "createWorldSetting",
            summary = "세계관 대상 직접 추가",
            description = "분류·대상명과 첫 문자열 설정을 후보나 LLM 비교 없이 현재 세계관에 바로 추가합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "세계관 대상 추가 성공"),
            @ApiResponse(responseCode = "400", description = "입력값 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "같은 분류와 대상명 중복",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<WorldSettingDetailResponse> createWorldSetting(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Valid @RequestBody WorldSettingCreateRequest request
    ) {
        return CommonResponse.success(
                "세계관 대상이 추가되었습니다.",
                worldSettingService.createWorldSetting(member.memberId(), workId, request)
        );
    }

    @PatchMapping(value = "/{worldSettingId}/identity", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateWorldSettingIdentity", summary = "세계관 대상 분류·이름 수정")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "세계관 대상 정보 수정 성공"),
            @ApiResponse(responseCode = "400", description = "입력값 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 세계관 대상을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "버전 충돌 또는 같은 분류와 대상명 중복",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<WorldSettingDetailResponse> updateWorldSettingIdentity(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID worldSettingId,
            @Valid @RequestBody WorldSettingIdentityUpdateRequest request
    ) {
        return CommonResponse.success(
                "세계관 대상 정보가 수정되었습니다.",
                worldSettingService.updateWorldSettingIdentity(
                        member.memberId(), workId, worldSettingId, request
                )
        );
    }

    @PostMapping(value = "/{worldSettingId}/properties", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "addWorldSettingProperty",
            summary = "세계관 설정 속성 추가",
            description = "현재 JSON object 전체가 아니라 설정명·설정값 한 개만 추가합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "세계관 설정 속성 추가 성공"),
            @ApiResponse(responseCode = "400", description = "입력값 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 세계관 대상을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "버전 충돌 또는 설정명 중복",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<WorldSettingDetailResponse> addWorldSettingProperty(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID worldSettingId,
            @Valid @RequestBody WorldSettingPropertyCreateRequest request
    ) {
        return CommonResponse.success(
                "세계관 설정이 추가되었습니다.",
                worldSettingService.addWorldSettingProperty(member.memberId(), workId, worldSettingId, request)
        );
    }

    @PatchMapping(value = "/{worldSettingId}/properties", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "updateWorldSettingProperty",
            summary = "세계관 설정 속성 수정",
            description = "지정한 설정명 한 개만 이름·값을 수정하며 다른 JSON 속성은 유지합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "세계관 설정 속성 수정 성공"),
            @ApiResponse(responseCode = "400", description = "입력값 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품, 대상 또는 설정명을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "버전 충돌 또는 설정명 중복",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<WorldSettingDetailResponse> updateWorldSettingProperty(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID worldSettingId,
            @Valid @RequestBody WorldSettingPropertyUpdateRequest request
    ) {
        return CommonResponse.success(
                "세계관 설정이 수정되었습니다.",
                worldSettingService.updateWorldSettingProperty(member.memberId(), workId, worldSettingId, request)
        );
    }
}
