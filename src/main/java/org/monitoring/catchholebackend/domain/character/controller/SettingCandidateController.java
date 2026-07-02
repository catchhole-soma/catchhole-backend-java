package org.monitoring.catchholebackend.domain.character.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateReviewStatusResponse;
import org.monitoring.catchholebackend.domain.character.service.SettingCandidateService;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/works/{workId}/setting-candidates")
@Tag(name = "SettingCandidate", description = "로그인한 사용자의 작품별 캐릭터 설정 후보 조회, 수정, 검토 상태 전이 API")
@SecurityRequirement(name = "bearerAuth")
public class SettingCandidateController {

    private final SettingCandidateService settingCandidateService;

    @GetMapping
    @Operation(
            summary = "작품별 설정 후보 목록 조회",
            description = "로그인한 사용자가 본인 작품의 AI 설정 후보 목록을 최신 생성순으로 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정 후보 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품을 찾을 수 없음")
    })
    public CommonResponse<List<SettingCandidateResponse>> getSettingCandidates(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @Parameter(description = "설정 후보를 조회할 작품 ID", example = "0198a3f0-0000-7000-8000-000000000001")
            @PathVariable UUID workId,
            @Parameter(description = "후보 검토 상태 필터", example = "PENDING_REVIEW")
            @RequestParam(required = false) SettingCandidateReviewStatus reviewStatus,
            @Parameter(description = "후보 대상 캐릭터명 필터", example = "아리아")
            @RequestParam(required = false) String entityName
    ) {
        return CommonResponse.success(
                settingCandidateService.getSettingCandidates(member.memberId(), workId, reviewStatus, entityName)
        );
    }

    @GetMapping("/{candidateId}")
    @Operation(
            summary = "설정 후보 상세 조회",
            description = "로그인한 사용자가 본인 작품의 특정 AI 설정 후보를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정 후보 상세 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 설정 후보를 찾을 수 없음")
    })
    public CommonResponse<SettingCandidateResponse> getSettingCandidate(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @Parameter(description = "설정 후보가 속한 작품 ID", example = "0198a3f0-0000-7000-8000-000000000001")
            @PathVariable UUID workId,
            @Parameter(
                    description = "조회할 설정 후보 ID. setting_candidates.id 값을 사용합니다.",
                    example = "0198a3f0-0000-7000-8000-000000000301"
            )
            @PathVariable UUID candidateId
    ) {
        return CommonResponse.success(
                settingCandidateService.getSettingCandidate(member.memberId(), workId, candidateId)
        );
    }

    @PatchMapping("/{candidateId}")
    @Operation(
            summary = "설정 후보 수정",
            description = "로그인한 사용자가 본인 작품의 PENDING_REVIEW 설정 후보에서 검토용 필드만 보정합니다. "
                    + "CONFIRMED 또는 DISMISSED 후보는 수정할 수 없습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정 후보 수정 성공"),
            @ApiResponse(responseCode = "400", description = "요청 값 검증 실패"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 설정 후보를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "검토 대기 상태가 아닌 설정 후보")
    })
    public CommonResponse<SettingCandidateResponse> updateSettingCandidate(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @Parameter(description = "설정 후보가 속한 작품 ID", example = "0198a3f0-0000-7000-8000-000000000001")
            @PathVariable UUID workId,
            @Parameter(
                    description = "수정할 설정 후보 ID. setting_candidates.id 값을 사용합니다.",
                    example = "0198a3f0-0000-7000-8000-000000000301"
            )
            @PathVariable UUID candidateId,
            @Valid @RequestBody SettingCandidateUpdateRequest request
    ) {
        return CommonResponse.success(
                "설정 후보가 수정되었습니다.",
                settingCandidateService.updateSettingCandidate(member.memberId(), workId, candidateId, request)
        );
    }

    @PostMapping("/{candidateId}/confirm")
    @Operation(
            summary = "설정 후보 확정",
            description = "로그인한 사용자가 본인 작품의 설정 후보를 CONFIRMED 상태로 전환합니다. "
                    + "PENDING_REVIEW 후보가 처음 확정되는 경우 CharacterFact를 생성하고 WorkCharacter 현재 스냅샷을 갱신합니다. "
                    + "이미 확정된 후보는 성공으로 처리하되 CharacterFact를 중복 생성하지 않으며, 무시된 후보는 상태 충돌로 거절합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정 후보 확정 성공"),
            @ApiResponse(responseCode = "400", description = "지원하지 않는 후보 속성"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 설정 후보를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "설정 후보 검토 상태 충돌")
    })
    public CommonResponse<SettingCandidateReviewStatusResponse> confirmSettingCandidate(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @Parameter(description = "설정 후보가 속한 작품 ID", example = "0198a3f0-0000-7000-8000-000000000001")
            @PathVariable UUID workId,
            @Parameter(
                    description = "확정할 설정 후보 ID. setting_candidates.id 값을 사용합니다.",
                    example = "0198a3f0-0000-7000-8000-000000000301"
            )
            @PathVariable UUID candidateId
    ) {
        return CommonResponse.success(
                "설정 후보가 확정되었습니다.",
                settingCandidateService.confirmSettingCandidate(member.memberId(), workId, candidateId)
        );
    }

    @PostMapping("/{candidateId}/dismiss")
    @Operation(
            summary = "설정 후보 무시",
            description = "로그인한 사용자가 본인 작품의 설정 후보를 DISMISSED 상태로 전환합니다. "
                    + "이미 무시된 후보는 성공으로 처리하며, 확정된 후보는 상태 충돌로 거절합니다. "
                    + "무시 처리에서는 CharacterFact 생성이나 WorkCharacter 스냅샷 갱신을 하지 않습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정 후보 무시 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 설정 후보를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "설정 후보 검토 상태 충돌")
    })
    public CommonResponse<SettingCandidateReviewStatusResponse> dismissSettingCandidate(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @Parameter(description = "설정 후보가 속한 작품 ID", example = "0198a3f0-0000-7000-8000-000000000001")
            @PathVariable UUID workId,
            @Parameter(
                    description = "무시할 설정 후보 ID. setting_candidates.id 값을 사용합니다.",
                    example = "0198a3f0-0000-7000-8000-000000000304"
            )
            @PathVariable UUID candidateId
    ) {
        return CommonResponse.success(
                "설정 후보가 무시되었습니다.",
                settingCandidateService.dismissSettingCandidate(member.memberId(), workId, candidateId)
        );
    }
}
