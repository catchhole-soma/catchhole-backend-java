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
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateCharacterMatchRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateListResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateReviewStatusResponse;
import org.monitoring.catchholebackend.domain.character.service.SettingCandidateService;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
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
        value = "/api/v1/works/{workId}/setting-candidates",
        produces = MediaType.APPLICATION_JSON_VALUE
)
@Tag(name = "SettingCandidate", description = "로그인한 사용자의 작품별 캐릭터 설정 후보 조회, 수정, 검토 상태 전이 API")
@SecurityRequirement(name = "bearerAuth")
public class SettingCandidateController {

    private final SettingCandidateService settingCandidateService;

    @GetMapping
    @Operation(
            operationId = "getSettingCandidates",
            summary = "작품별 설정 후보 목록 조회",
            description = "로그인한 사용자가 본인 작품의 한 업로드 묶음에 속한 AI 설정 후보를 페이지 조회합니다. "
                    + "회차 번호, 생성 시각, 후보 ID 오름차순으로 정렬하며 집계와 회차 범위는 필터와 무관한 묶음 전체 기준입니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정 후보 목록 조회 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "필수 query parameter 누락 또는 페이지 번호·크기 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "작품 또는 해당 작품의 업로드 묶음을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<SettingCandidateListResponse> getSettingCandidates(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @Parameter(description = "설정 후보를 조회할 작품 ID", example = "0198a3f0-0000-7000-8000-000000000001")
            @PathVariable UUID workId,
            @Parameter(
                    name = "batchId",
                    in = ParameterIn.QUERY,
                    description = "설정 후보 검토 범위인 업로드 묶음 ID",
                    example = "0198a3f0-0000-7000-8000-000000000101",
                    required = true
            )
            @RequestParam UUID batchId,
            @Parameter(description = "후보 검토 상태 필터", example = "PENDING_REVIEW")
            @RequestParam(required = false) SettingCandidateReviewStatus reviewStatus,
            @Parameter(description = "후보 캐릭터 연결 상태 필터", example = "AMBIGUOUS")
            @RequestParam(required = false) SettingCandidateMatchStatus matchStatus,
            @Parameter(
                    name = "page",
                    in = ParameterIn.QUERY,
                    description = "0부터 시작하는 페이지 번호",
                    example = "0"
            )
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "페이지 번호는 0 이상이어야 합니다.")
            int page,
            @Parameter(
                    name = "size",
                    in = ParameterIn.QUERY,
                    description = "페이지 크기. 1~100 사이로 요청합니다.",
                    example = "20"
            )
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "페이지 크기는 1 이상이어야 합니다.")
            @Max(value = 100, message = "페이지 크기는 100 이하여야 합니다.")
            int size
    ) {
        return CommonResponse.success(
                settingCandidateService.getSettingCandidates(
                        member.memberId(),
                        workId,
                        batchId,
                        reviewStatus,
                        matchStatus,
                        page,
                        size
                )
        );
    }

    @GetMapping("/{candidateId}")
    @Operation(
            operationId = "getSettingCandidate",
            summary = "설정 후보 상세 조회",
            description = "로그인한 사용자가 본인 작품의 현재 검토 업로드 묶음에 속한 특정 AI 설정 후보를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정 후보 상세 조회 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "필수 batchId query parameter 누락 또는 UUID 형식 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "작품 또는 현재 업로드 묶음의 설정 후보를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<SettingCandidateResponse> getSettingCandidate(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @Parameter(description = "설정 후보가 속한 작품 ID", example = "0198a3f0-0000-7000-8000-000000000001")
            @PathVariable UUID workId,
            @Parameter(
                    name = "batchId",
                    in = ParameterIn.QUERY,
                    description = "현재 설정 후보 검토 범위인 업로드 묶음 ID",
                    example = "0198a3f0-0000-7000-8000-000000000101",
                    required = true
            )
            @RequestParam UUID batchId,
            @Parameter(
                    description = "조회할 설정 후보 ID. setting_candidates.id 값을 사용합니다.",
                    example = "0198a3f0-0000-7000-8000-000000000301"
            )
            @PathVariable UUID candidateId
    ) {
        return CommonResponse.success(
                settingCandidateService.getSettingCandidate(member.memberId(), workId, batchId, candidateId)
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

    @PatchMapping("/{candidateId}/character-match")
    @Operation(
            summary = "설정 후보 캐릭터 연결 해소",
            description = "로그인한 사용자가 본인 작품의 PENDING_REVIEW 설정 후보를 기존 캐릭터에 연결하거나 새 캐릭터로 확정합니다. "
                    + "검토 상태는 PENDING_REVIEW로 유지합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정 후보 캐릭터 연결 해소 성공"),
            @ApiResponse(responseCode = "400", description = "요청 값 검증 실패"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 설정 후보를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "검토 대기 상태가 아니거나 캐릭터 연결 요청이 올바르지 않음")
    })
    public CommonResponse<SettingCandidateResponse> updateSettingCandidateCharacterMatch(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @Parameter(description = "설정 후보가 속한 작품 ID", example = "0198a3f0-0000-7000-8000-000000000001")
            @PathVariable UUID workId,
            @Parameter(
                    description = "캐릭터 연결을 해소할 설정 후보 ID. setting_candidates.id 값을 사용합니다.",
                    example = "0198a3f0-0000-7000-8000-000000000301"
            )
            @PathVariable UUID candidateId,
            @Valid @RequestBody SettingCandidateCharacterMatchRequest request
    ) {
        return CommonResponse.success(
                "설정 후보 캐릭터 연결이 수정되었습니다.",
                settingCandidateService.updateSettingCandidateCharacterMatch(
                        member.memberId(),
                        workId,
                        candidateId,
                        request
                )
        );
    }

    @PostMapping("/{candidateId}/confirm")
    @Operation(
            summary = "설정 후보 확정",
            description = "로그인한 사용자가 본인 작품의 설정 후보를 CONFIRMED 상태로 전환합니다. "
                    + "PENDING_REVIEW 후보가 처음 확정되는 경우 활성 schema를 schemaKey 정확 일치, 별칭, 마지막이 .*로 끝나는 속성 패턴 순으로 매칭하고 값 타입과 merge policy를 검증합니다. "
                    + "UNRESOLVED 캐릭터 후보는 같은 이름의 활성 캐릭터를 재사용하거나 새로 생성하고, 같은 이름의 검토 대기 미해소 후보도 해당 캐릭터에 연결합니다. "
                    + "검증을 통과하면 CharacterFact를 생성하고 WorkCharacter 현재 스냅샷을 갱신합니다. "
                    + "이미 확정된 후보는 성공으로 처리하되 CharacterFact를 중복 생성하지 않으며, 무시된 후보는 상태 충돌로 거절합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정 후보 확정 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "활성 schema 미매칭, 값 타입 불일치 또는 구조화 값의 공개 속성 계약 위반"
            ),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 설정 후보를 찾을 수 없음"),
            @ApiResponse(
                    responseCode = "409",
                    description = "검토/캐릭터 매칭 상태 충돌, 유효하지 않은 연결, "
                            + "schema 복수 매칭 또는 미지원 merge policy"
            )
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
