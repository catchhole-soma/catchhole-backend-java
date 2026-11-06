package org.monitoring.catchholebackend.domain.relationship.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipGraphCleanupRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipGraphCleanupResponse;
import org.monitoring.catchholebackend.domain.relationship.service.RelationshipGraphCleanupService;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/v1/relationship-graph")
@Tag(name = "Internal Relationship Graph Cleanup", description = "운영자가 이미 저장된 관계도를 작품 단위로 한 번 정리합니다(GH391).")
@SecurityRequirement(name = "internalApiKey")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "요청 형식 또는 필수 입력 오류", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "내부 API 키가 없거나 유효하지 않음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "작품을 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "진행 중·재개 가능한 관계 분석이나 관계 백필이 있어 적용 보류, 저장 데이터 불일치로 중단, 또는 사후 검증 실패로 전부 되돌림", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
})
public class RelationshipGraphCleanupController {
    private final RelationshipGraphCleanupService service;

    @PostMapping("/cleanups")
    @Operation(operationId = "cleanupRelationshipGraph", summary = "관계도 1회 정리 dry-run/적용",
            description = "별칭·짧은 이름 노드를 프로필 노드로 연결·병합하고 겹친 관계 선을 합칩니다. apply가 false면 아무것도 쓰지 않고 보고서만 반환합니다. 응답에는 노드·프로필 이름이 있으므로 공개 이슈·PR에 그대로 붙이지 않습니다.")
    @ApiResponse(responseCode = "200", description = "정리 보고서")
    public CommonResponse<RelationshipGraphCleanupResponse> cleanupRelationshipGraph(
            @Valid @RequestBody RelationshipGraphCleanupRequest request) {
        RelationshipGraphCleanupResponse report = service.cleanup(request.workId(), request.applyRequested());
        return CommonResponse.success(report.applied() ? "관계도를 정리했습니다." : "관계도 정리 보고서입니다.", report);
    }
}
