package org.monitoring.catchholebackend.domain.relationship.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipProcessingResponse;
import org.monitoring.catchholebackend.domain.relationship.service.RelationshipProcessingService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/works/{workId}/relationship-processing", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Relationship", description = "현재 프로필에서 확인한 가족 관계와 검토 후보 API")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
        @ApiResponse(responseCode = "200", description = "처리 성공"),
        @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "작품을 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
})
public class RelationshipProcessingController {
    private final RelationshipProcessingService service;

    @GetMapping
    @Operation(operationId = "getRelationshipProcessing", summary = "가족 관계 처리 상태 조회", description = "기존 설정 저장과 독립적인 규칙 처리의 대기·성공·실패를 조회합니다.")
    public CommonResponse<RelationshipProcessingResponse> getRelationshipProcessing(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member, @PathVariable UUID workId) {
        return CommonResponse.success(service.getStatus(member.memberId(), workId));
    }

    @PostMapping
    @Operation(operationId = "requestRelationshipProcessing", summary = "현재 설정의 가족 관계 처리 요청", description = "이미 저장된 현재 프로필만 규칙으로 처리합니다. AI 재분석이나 사용량 차감 없이 기존 설정을 처음 연결하거나 실패한 처리를 재시도합니다. 제외·수정한 동일 입력 후보는 되살리지 않습니다.")
    @ApiResponse(responseCode = "409", description = "작품 삭제 진행 중", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    public CommonResponse<RelationshipProcessingResponse> requestRelationshipProcessing(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member, @PathVariable UUID workId) {
        return CommonResponse.success(service.requestProcessing(member.memberId(), workId));
    }
}
