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
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipCandidateCreateRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipCorrectionRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.request.RelationshipDecisionRequest;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipCandidateResponse;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipEvidenceResponse;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipResponse;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipSourceResponse;
import org.monitoring.catchholebackend.domain.relationship.service.RelationshipService;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/works/{workId}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Relationship", description = "현재 프로필에서 확인한 가족 관계와 검토 후보 API")
@SecurityRequirement(name = "bearerAuth")
public class RelationshipController {

    private final RelationshipService relationshipService;

    @GetMapping("/relationship-sources")
    @Operation(operationId = "getRelationshipSources", summary = "관계 생성용 현재 프로필 조회", description = "현재 슬롯과 sourceFacts 양쪽에 남아 있는 PROFILE만 반환합니다. 전체 Fact 이력이나 원문을 재추출하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 성공"),
            @ApiResponse(responseCode = "400", description = "입력 또는 근거 검증 실패", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 요청한 항목을 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "버전 충돌, 근거 변경 또는 추가 검토 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<List<RelationshipSourceResponse>> getRelationshipSources(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Parameter(in = ParameterIn.QUERY, description = "현재 프로필을 읽을 캐릭터 ID") @RequestParam UUID characterId
    ) {
        return CommonResponse.success(relationshipService.getRelationshipSources(member.memberId(), workId, characterId));
    }

    @GetMapping("/relationship-candidates")
    @Operation(operationId = "getRelationshipCandidates", summary = "가족 관계 후보 목록 조회", description = "검토 완료와 보류 후보를 모두 조회하며 reviewReasons로 현재 보류 이유를 제공합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 성공"),
            @ApiResponse(responseCode = "400", description = "입력 또는 근거 검증 실패", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 요청한 항목을 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "버전 충돌, 근거 변경 또는 추가 검토 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<PageResponse<RelationshipCandidateResponse>> getRelationshipCandidates(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Parameter(in = ParameterIn.QUERY, description = "0부터 시작하는 페이지") @RequestParam(defaultValue = "0") @Min(value = 0, message = "페이지 번호는 0 이상이어야 합니다.") int page,
            @Parameter(in = ParameterIn.QUERY, description = "페이지당 항목 수, 최대 100") @RequestParam(defaultValue = "20") @Min(value = 1, message = "페이지 크기는 1 이상이어야 합니다.") @Max(value = 100, message = "페이지 크기는 100 이하여야 합니다.") int size
    ) {
        return CommonResponse.success(relationshipService.getRelationshipCandidates(member.memberId(), workId, page, size));
    }

    @GetMapping("/relationship-candidates/{candidateId}")
    @Operation(operationId = "getRelationshipCandidate", summary = "가족 관계 후보 상세 조회", description = "현재 근거 유효성 및 상대 연결 상태를 다시 확인합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 성공"),
            @ApiResponse(responseCode = "400", description = "입력 또는 근거 검증 실패", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 요청한 항목을 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "버전 충돌, 근거 변경 또는 추가 검토 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<RelationshipCandidateResponse> getRelationshipCandidate(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID candidateId
    ) {
        return CommonResponse.success(relationshipService.getRelationshipCandidate(member.memberId(), workId, candidateId));
    }

    @PostMapping("/relationship-candidates")
    @Operation(operationId = "createRelationshipCandidate", summary = "가족 관계 후보 등록", description = "기존 현재 프로필 Fact를 명시해 검토 후보만 저장합니다. 같은 requestId와 입력은 기존 결과를 반환하며 자동 확정하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 성공"),
            @ApiResponse(responseCode = "400", description = "입력 또는 근거 검증 실패", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 요청한 항목을 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "버전 충돌, 근거 변경 또는 추가 검토 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<RelationshipCandidateResponse> createRelationshipCandidate(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Valid @RequestBody RelationshipCandidateCreateRequest request
    ) {
        return CommonResponse.success(relationshipService.createRelationshipCandidate(member.memberId(), workId, request));
    }

    @PutMapping("/relationship-candidates/{candidateId}")
    @Operation(operationId = "updateRelationshipCandidate", summary = "가족 관계 후보 수정", description = "대기 후보의 상대·유형·시점·근거를 수정합니다. expectedVersion과 sourceToken이 오래되면 거절합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 성공"),
            @ApiResponse(responseCode = "400", description = "입력 또는 근거 검증 실패", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 요청한 항목을 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "버전 충돌, 근거 변경 또는 추가 검토 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<RelationshipCandidateResponse> updateRelationshipCandidate(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID candidateId,
            @Valid @RequestBody RelationshipCandidateUpdateRequest request
    ) {
        return CommonResponse.success(relationshipService.updateRelationshipCandidate(member.memberId(), workId, candidateId, request));
    }

    @PostMapping("/relationship-candidates/{candidateId}/confirm")
    @Operation(operationId = "confirmRelationshipCandidate", summary = "가족 관계 후보 확정", description = "명시적 단정과 알려진 상대·시점만 확정합니다. 동일 결정 재시도는 멱등하며 동일 관계에는 근거를 추가합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 성공"),
            @ApiResponse(responseCode = "400", description = "입력 또는 근거 검증 실패", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 요청한 항목을 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "버전 충돌, 근거 변경 또는 추가 검토 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<RelationshipCandidateResponse> confirmRelationshipCandidate(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID candidateId,
            @Valid @RequestBody RelationshipDecisionRequest request
    ) {
        return CommonResponse.success(relationshipService.confirmRelationshipCandidate(member.memberId(), workId, candidateId, request));
    }

    @PostMapping("/relationship-candidates/{candidateId}/dismiss")
    @Operation(operationId = "dismissRelationshipCandidate", summary = "가족 관계 후보 제외", description = "검토 대기 후보를 제외합니다. 같은 결정의 재전송으로 상태를 되돌리지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 성공"),
            @ApiResponse(responseCode = "400", description = "입력 또는 근거 검증 실패", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 요청한 항목을 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "버전 충돌, 근거 변경 또는 추가 검토 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<RelationshipCandidateResponse> dismissRelationshipCandidate(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID candidateId,
            @Valid @RequestBody RelationshipDecisionRequest request
    ) {
        return CommonResponse.success(relationshipService.dismissRelationshipCandidate(member.memberId(), workId, candidateId, request));
    }

    @GetMapping("/relationships")
    @Operation(operationId = "getRelationships", summary = "가족 관계 목록 조회", description = "과거·철회·재검토 관계를 포함합니다. 기본 가계도는 visibleInFamilyTree=true인 관계를 사용합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 성공"),
            @ApiResponse(responseCode = "400", description = "입력 또는 근거 검증 실패", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 요청한 항목을 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "버전 충돌, 근거 변경 또는 추가 검토 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<PageResponse<RelationshipResponse>> getRelationships(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Parameter(in = ParameterIn.QUERY, description = "0부터 시작하는 페이지") @RequestParam(defaultValue = "0") @Min(value = 0, message = "페이지 번호는 0 이상이어야 합니다.") int page,
            @Parameter(in = ParameterIn.QUERY, description = "페이지당 항목 수, 최대 100") @RequestParam(defaultValue = "20") @Min(value = 1, message = "페이지 크기는 1 이상이어야 합니다.") @Max(value = 100, message = "페이지 크기는 100 이하여야 합니다.") int size
    ) {
        return CommonResponse.success(relationshipService.getRelationships(member.memberId(), workId, page, size));
    }

    @GetMapping("/relationships/{relationshipId}")
    @Operation(operationId = "getRelationship", summary = "가족 관계 상세 조회", description = "검토 기록과 현재 근거 유효성을 구분해 제공합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 성공"),
            @ApiResponse(responseCode = "400", description = "입력 또는 근거 검증 실패", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 요청한 항목을 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "버전 충돌, 근거 변경 또는 추가 검토 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<RelationshipResponse> getRelationship(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID relationshipId
    ) {
        return CommonResponse.success(relationshipService.getRelationship(member.memberId(), workId, relationshipId));
    }

    @GetMapping("/relationships/{relationshipId}/evidence")
    @Operation(operationId = "getRelationshipEvidence", summary = "가족 관계 근거 조회", description = "복수의 Fact 참조와 현재 유효성을 반환합니다. 원문은 evidencePath의 기존 Fact API에서 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 성공"),
            @ApiResponse(responseCode = "400", description = "입력 또는 근거 검증 실패", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 요청한 항목을 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "버전 충돌, 근거 변경 또는 추가 검토 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<List<RelationshipEvidenceResponse>> getRelationshipEvidence(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID relationshipId
    ) {
        return CommonResponse.success(relationshipService.getRelationshipEvidence(member.memberId(), workId, relationshipId));
    }

    @PutMapping("/relationships/{relationshipId}")
    @Operation(operationId = "correctRelationship", summary = "가족 관계 정정", description = "기존 관계를 철회하고 새 근거로 대체 관계를 확정합니다. 이전 명제와 근거는 이력에 남으며 전체 작업을 원자적으로 처리합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 성공"),
            @ApiResponse(responseCode = "400", description = "입력 또는 근거 검증 실패", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 요청한 항목을 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "버전 충돌, 근거 변경 또는 추가 검토 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<RelationshipResponse> correctRelationship(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID relationshipId,
            @Valid @RequestBody RelationshipCorrectionRequest request
    ) {
        return CommonResponse.success(relationshipService.correctRelationship(member.memberId(), workId, relationshipId, request));
    }

    @PostMapping("/relationships/{relationshipId}/withdraw")
    @Operation(operationId = "withdrawRelationship", summary = "가족 관계 철회", description = "잘못 확정한 관계를 철회합니다. 서사상 이혼·사망 등 관계 종료를 뜻하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 성공"),
            @ApiResponse(responseCode = "400", description = "입력 또는 근거 검증 실패", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 요청한 항목을 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "버전 충돌, 근거 변경 또는 추가 검토 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<RelationshipResponse> withdrawRelationship(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID relationshipId,
            @Valid @RequestBody RelationshipDecisionRequest request
    ) {
        return CommonResponse.success(relationshipService.withdrawRelationship(member.memberId(), workId, relationshipId, request));
    }
}
