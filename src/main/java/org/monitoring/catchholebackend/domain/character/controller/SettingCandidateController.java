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
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateCharacterMatchRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateConfirmRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateGroupConfirmRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateGroupCharacterMatchRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateListResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateGroupActionResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateReviewStatusResponse;
import org.monitoring.catchholebackend.domain.character.service.SettingCandidateService;
import org.monitoring.catchholebackend.domain.character.service.SettingCandidateConfirmResult;
import org.monitoring.catchholebackend.domain.character.service.SettingCandidateGroupConfirmResult;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.global.exception.AppException;
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
            @Parameter(
                    description = "후보 캐릭터 연결 상태 필터 목록. 생략하거나 비우면 전체 상태를 조회합니다.",
                    example = "MATCHED,AUTO_MATCHED_BY_NAME"
            )
            @RequestParam(required = false) Set<SettingCandidateMatchStatus> matchStatuses,
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
            int size,
            @Parameter(
                    name = "includeLegacyCandidates",
                    in = ParameterIn.QUERY,
                    description = "구형 단건 후보 페이지를 함께 반환할지 여부. 그룹 화면은 false로 요청해 중복 payload를 줄입니다.",
                    example = "false"
            )
            @RequestParam(defaultValue = "true")
            boolean includeLegacyCandidates
    ) {
        return CommonResponse.success(
                settingCandidateService.getSettingCandidates(
                        member.memberId(),
                        workId,
                        batchId,
                        reviewStatus,
                        matchStatuses,
                        page,
                        size,
                        includeLegacyCandidates
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
            operationId = "updateSettingCandidate",
            summary = "설정 후보 수정",
            description = "로그인한 사용자가 본인 작품의 PENDING_REVIEW 설정 후보에서 설정명과 표시값만 보정합니다. "
                    + "고정 schema key는 이름을 바꿀 수 없고 동적 key는 같은 pattern 안에서만 바꿀 수 있습니다. "
                    + "값 타입과 AI 근거는 유지하며 CONFIRMED 또는 DISMISSED 후보는 수정할 수 없습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정 후보 수정 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "요청 값, schema key 또는 값 타입 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "작품 또는 설정 후보를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "검토 대기 상태가 아니거나 schema 해석이 모호함",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
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

    @PatchMapping(value = "/group-character-match", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "updateSettingCandidateGroupCharacterMatch",
            summary = "캐릭터 설정 후보 그룹 일괄 연결",
            description = "현재 같은 이름으로 묶인 모든 검토 대기 후보를 하나의 기존 캐릭터에 연결하거나 "
                    + "같은 이름의 새 캐릭터 등록 예정 그룹으로 지정합니다. 일부 후보만 전달하면 거절합니다."
    )
    public CommonResponse<SettingCandidateGroupActionResponse> updateSettingCandidateGroupCharacterMatch(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Valid @RequestBody SettingCandidateGroupCharacterMatchRequest request
    ) {
        return CommonResponse.success(
                "캐릭터 설정 후보 그룹 연결이 수정되었습니다.",
                settingCandidateService.updateSettingCandidateGroupCharacterMatch(member.memberId(), workId, request)
        );
    }

    @PatchMapping("/{candidateId}/character-match")
    @Operation(
            operationId = "updateSettingCandidateCharacterMatch",
            summary = "설정 후보 캐릭터 연결 해소",
            description = "로그인한 사용자가 본인 작품의 PENDING_REVIEW 설정 후보를 기존 캐릭터에 연결하거나, "
                    + "confirm 전 새 캐릭터 등록 예정인 UNRESOLVED 상태로 지정합니다. "
                    + "CREATE_NEW는 캐릭터를 즉시 생성하거나 후보를 확정하지 않으며, 실제 캐릭터 생성은 후보 confirm 때 수행합니다. "
                    + "검토 상태는 PENDING_REVIEW로 유지합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정 후보 캐릭터 연결 해소 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "요청 값 또는 연결 방식별 필수 값 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "작품 또는 설정 후보를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "검토 대기 상태가 아니거나 캐릭터 연결 요청이 올바르지 않음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
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
            operationId = "confirmSettingCandidate",
            summary = "설정 후보 확정",
            description = "로그인한 사용자가 본인 작품의 설정 후보를 CONFIRMED 상태로 전환합니다. "
                    + "기본 APPLY_PROPOSAL은 COMPLETED 상태의 2차 비교 operation·시간 범위·최종값과 관련 snapshot 문맥을 다시 검증한 뒤 append-only CharacterFact, WorkCharacter snapshot, provenance를 한 트랜잭션으로 반영합니다. "
                    + "HISTORY_ONLY는 CharacterFact와 원문 근거만 보존하고 snapshot·provenance·version을 바꾸지 않습니다. "
                    + "UNRESOLVED 이름이 실제 신규이면 빈 캐릭터에 deterministic ADD를 허용하지만, 동명 활성 캐릭터가 있으면 먼저 연결하고 숨김 재비교 Job을 만든 뒤 409로 재조회시킵니다. "
                    + "이미 확정된 후보는 성공으로 처리하되 CharacterFact를 중복 생성하지 않으며, 무시된 후보는 상태 충돌로 거절합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정 후보 확정 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "활성 schema 미매칭, 값 타입 불일치 또는 구조화 값의 공개 속성 계약 위반",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "작품 또는 설정 후보를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "검토/캐릭터 매칭 상태 충돌, 비교 대기·실패·stale 문맥, "
                            + "EXCLUDE/REVIEW_REQUIRED 제안 적용 시도, 동명 기존 캐릭터 연결 후 재비교 필요, "
                            + "schema 복수 매칭 또는 미지원 merge policy",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
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
            @PathVariable UUID candidateId,
            @Valid @RequestBody(required = false) SettingCandidateConfirmRequest request
    ) {
        SettingCandidateConfirmResult result = settingCandidateService.confirmSettingCandidate(
                member.memberId(),
                workId,
                candidateId,
                request
        );
        if (result.recomparisonRequired()) {
            // 서비스 트랜잭션에서 기존 캐릭터 연결과 재비교 Job을 커밋한 뒤 클라이언트에 재시도를 알린다.
            throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_NOT_READY);
        }
        return CommonResponse.success(
                "설정 후보가 확정되었습니다.",
                result.response()
        );
    }

    @PostMapping(value = "/group-confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "confirmSettingCandidateGroup",
            summary = "캐릭터 설정 후보 그룹 전체 확정",
            description = "같은 캐릭터 이름의 모든 검토 대기 후보를 먼저 함께 검증하고 한 트랜잭션으로 확정합니다. "
                    + "EXCLUDE 제안은 현재 설정이나 이력을 만들지 않고 자동으로 무시 완료하며, "
                    + "그 밖의 후보는 선택한 반영 방식대로 저장합니다. 단일 후보 확정 UI는 제공하지 않습니다."
    )
    public CommonResponse<SettingCandidateGroupActionResponse> confirmSettingCandidateGroup(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Valid @RequestBody SettingCandidateGroupConfirmRequest request
    ) {
        SettingCandidateGroupConfirmResult result = settingCandidateService.confirmSettingCandidateGroup(
                member.memberId(), workId, request
        );
        if (result.recomparisonRequired()) {
            throw new AppException(
                    CharacterErrorCode.SETTING_CANDIDATE_COMPARISON_NOT_READY,
                    java.util.Map.of("affectedCandidateIds", result.recomparisonCandidateIds())
            );
        }
        return CommonResponse.success("캐릭터 설정 후보 그룹이 확정되었습니다.", result.response());
    }

    @PostMapping("/{candidateId}/recompare")
    @Operation(
            operationId = "retrySettingCandidateComparison",
            summary = "캐릭터 설정 후보 2차 비교 재요청",
            description = "실패하거나 현재 문맥과 어긋난 PENDING_REVIEW 후보의 비교 상태를 초기화하고 "
                    + "동일 후보에 대한 숨김 비교 Job을 멱등 생성합니다."
    )
    public CommonResponse<SettingCandidateResponse> retrySettingCandidateComparison(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID candidateId
    ) {
        return CommonResponse.success(
                "캐릭터 설정 후보 재비교를 요청했습니다.",
                settingCandidateService.retryComparison(member.memberId(), workId, candidateId)
        );
    }

    @PostMapping("/{candidateId}/dismiss")
    @Operation(
            operationId = "dismissSettingCandidate",
            summary = "설정 후보 무시",
            description = "로그인한 사용자가 본인 작품의 설정 후보를 DISMISSED 상태로 전환합니다. "
                    + "이미 무시된 후보는 성공으로 처리하며, 확정된 후보는 상태 충돌로 거절합니다. "
                    + "무시 처리에서는 CharacterFact 생성이나 WorkCharacter 스냅샷 갱신을 하지 않습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정 후보 무시 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "작품 또는 설정 후보 ID 형식 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "작품 또는 설정 후보를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "설정 후보 검토 상태 충돌",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
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
