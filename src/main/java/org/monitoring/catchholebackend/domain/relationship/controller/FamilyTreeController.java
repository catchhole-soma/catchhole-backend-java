package org.monitoring.catchholebackend.domain.relationship.controller;

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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.relationship.dto.response.FamilyTreeResponse;
import org.monitoring.catchholebackend.domain.relationship.service.FamilyTreeService;
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
@RequestMapping(value = "/api/v1/works/{workId}/family-tree", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Relationship", description = "현재 프로필에서 확인한 가족 관계와 검토 후보 API")
@SecurityRequirement(name = "bearerAuth")
public class FamilyTreeController {
    private final FamilyTreeService familyTreeService;

    @GetMapping
    @Operation(operationId = "getFamilyTree", summary = "가계도 요약 조회", description = "동일한 DB 스냅샷에서 유효한 확정 관계와 현재 설정에 이름이 명시된 미등록 인물을 출처별로 구분해 조회합니다. 기본은 현재 관계이며 과거 포함을 선택할 수 있습니다. 원문·전체 근거는 제외하고 기존 상세 API를 사용합니다. 초점 인물이 있으면 해당 인물과 연결된 가족 범위만 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 성공. 상한을 넘으면 truncated=true"),
            @ApiResponse(responseCode = "400", description = "조회 조건 또는 표시 상한 오류", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 활성 초점 인물을 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<FamilyTreeResponse> getFamilyTree(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Parameter(in = ParameterIn.QUERY, description = "유효한 과거 관계도 포함할지 여부") @RequestParam(defaultValue = "false") boolean includeHistorical,
            @Parameter(in = ParameterIn.QUERY, description = "연결 가족 범위의 기준이 될 같은 작품의 활성 인물 ID") @RequestParam(required = false) UUID focusCharacterId,
            @Parameter(in = ParameterIn.QUERY, description = "간선 표시 상한. 1~200, 인물 수는 최대 간선 수의 두 배")
            @RequestParam(defaultValue = "100") @Min(value = 1, message = "표시 상한은 1 이상이어야 합니다.")
            @Max(value = 200, message = "표시 상한은 200 이하여야 합니다.") int limit
    ) {
        return CommonResponse.success(familyTreeService.getFamilyTree(member.memberId(), workId, includeHistorical, focusCharacterId, limit));
    }
}
