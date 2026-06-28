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
import org.monitoring.catchholebackend.domain.character.service.SettingCandidateService;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/works/{workId}/setting-candidates")
@Tag(name = "SettingCandidate", description = "로그인한 사용자의 작품별 캐릭터 설정 후보 조회 및 수정 API")
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
            @PathVariable UUID workId,
            @RequestParam(required = false) SettingCandidateReviewStatus reviewStatus,
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
            @PathVariable UUID workId,
            @PathVariable UUID candidateId
    ) {
        return CommonResponse.success(
                settingCandidateService.getSettingCandidate(member.memberId(), workId, candidateId)
        );
    }

    @PatchMapping("/{candidateId}")
    @Operation(
            summary = "설정 후보 수정",
            description = "로그인한 사용자가 본인 작품의 검토 대기 설정 후보 내용을 보정합니다."
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
            @PathVariable UUID workId,
            @PathVariable UUID candidateId,
            @Valid @RequestBody SettingCandidateUpdateRequest request
    ) {
        return CommonResponse.success(
                "설정 후보가 수정되었습니다.",
                settingCandidateService.updateSettingCandidate(member.memberId(), workId, candidateId, request)
        );
    }
}
