package org.monitoring.catchholebackend.domain.relationship.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipDecisionRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipMentionGroupCreateRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipMentionGroupResponse;
import org.monitoring.catchholebackend.domain.relationship.service.RelationshipMentionGroupService;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/works/{workId}/relationship-mention-groups", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Relationship", description = "현재 프로필에서 확인한 가족 관계와 검토 후보 API")
@SecurityRequirement(name = "bearerAuth")
public class RelationshipMentionGroupController {
    private final RelationshipMentionGroupService service;

    @PostMapping
    @Operation(operationId = "createRelationshipMentionGroup", summary = "미등록 인물 명시적 묶기",
            description = "사용자가 선택한 현재 표시 가능한 미등록 후보 2~20개만 묶습니다. 이름으로 추가하지 않으며 각 관계와 근거는 보존합니다. 후보 버전·현재 출처·순환·세대 차이를 작품 쓰기 잠금 안에서 검증합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "묶음 생성 또는 동일 요청 재전송 성공"),
            @ApiResponse(responseCode = "400", description = "선택 수·이름·중복 후보 등 입력 오류", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "소유한 작품 또는 같은 작품의 후보를 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "버전·멱등 요청·현재 출처·기존 묶음·순환·세대 차이 충돌", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<RelationshipMentionGroupResponse> createRelationshipMentionGroup(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member, @PathVariable UUID workId,
            @Valid @RequestBody RelationshipMentionGroupCreateRequest request) {
        return CommonResponse.success(service.createMentionGroup(member.memberId(), workId, request));
    }

    @GetMapping
    @Operation(operationId = "getRelationshipMentionGroups", summary = "활성 미등록 인물 묶음 조회",
            description = "현재 작품의 활성 묶음과 검토 사유를 조회합니다. 출처 변경이나 관계 충돌로 가계도에서 숨겨진 묶음도 확인·해제를 위해 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "활성 묶음 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "소유한 작품을 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<List<RelationshipMentionGroupResponse>> getRelationshipMentionGroups(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member, @PathVariable UUID workId) {
        return CommonResponse.success(service.getMentionGroups(member.memberId(), workId));
    }

    @GetMapping("/{groupId}")
    @Operation(operationId = "getRelationshipMentionGroup", summary = "미등록 인물 묶음 상세 조회",
            description = "현재 작품에 속한 묶음의 상태·버전·구성 후보·검토 사유를 조회합니다. 후보별 설정과 원문 근거는 기존 후보 상세 API를 사용합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "묶음 상세 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "소유한 작품 또는 같은 작품의 묶음을 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<RelationshipMentionGroupResponse> getRelationshipMentionGroup(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member, @PathVariable UUID workId,
            @PathVariable UUID groupId) {
        return CommonResponse.success(service.getMentionGroup(member.memberId(), workId, groupId));
    }

    @PostMapping("/{groupId}/ungroup")
    @Operation(operationId = "ungroupRelationshipMentions", summary = "미등록 인물 묶음 전체 해제",
            description = "조회 당시 그룹 버전을 검사하고 모든 구성원을 원래 후보별 인물 표시로 되돌립니다. 캐릭터·후보·관계·근거를 삭제하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "전체 해제 또는 동일 해제 요청 재전송 성공"),
            @ApiResponse(responseCode = "400", description = "필수 버전 등 입력 오류", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "소유한 작품 또는 같은 작품의 묶음을 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "그룹 버전 또는 현재 상태 충돌", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<RelationshipMentionGroupResponse> ungroupRelationshipMentions(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member, @PathVariable UUID workId,
            @PathVariable UUID groupId, @Valid @RequestBody RelationshipDecisionRequest request) {
        return CommonResponse.success(service.ungroupMentions(member.memberId(), workId, groupId, request));
    }
}
