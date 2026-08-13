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
import org.monitoring.catchholebackend.domain.character.dto.request.CharacterUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterArchiveResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterDetailResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterRestoreResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterSummaryResponse;
import org.monitoring.catchholebackend.domain.character.service.CharacterService;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping(
        value = "/api/v1/works/{workId}/characters",
        produces = MediaType.APPLICATION_JSON_VALUE
)
@Tag(name = "Character", description = "작품별 캐릭터 조회, 수정, 보관, 복구 API")
@SecurityRequirement(name = "bearerAuth")
public class CharacterController {

    private final CharacterService characterService;

    @GetMapping
    @Operation(
            operationId = "getCharacters",
            summary = "캐릭터 목록 조회",
            description = "본인 작품의 ACTIVE 캐릭터 카드를 updatedAt DESC, id DESC 순서로 페이지 조회합니다. "
                    + "보관된 캐릭터는 제외합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "캐릭터 목록 조회 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "페이지 번호 또는 크기 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "작품을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<PageResponse<CharacterSummaryResponse>> getCharacters(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
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
                    description = "페이지 크기. 화면 배치에 맞춰 1~24 사이로 요청합니다.",
                    example = "12"
            )
            @RequestParam(defaultValue = "24")
            @Min(value = 1, message = "페이지 크기는 1 이상이어야 합니다.")
            @Max(value = 24, message = "페이지 크기는 24 이하여야 합니다.")
            int size
    ) {
        return CommonResponse.success(characterService.getCharacters(member.memberId(), workId, page, size));
    }

    @GetMapping("/archived")
    @Operation(
            operationId = "getArchivedCharacters",
            summary = "보관 캐릭터 목록 조회",
            description = "본인 작품의 ARCHIVED 캐릭터 카드를 updatedAt DESC, id DESC 순서로 페이지 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "보관 캐릭터 목록 조회 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "페이지 번호 또는 크기 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "작품을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<PageResponse<CharacterSummaryResponse>> getArchivedCharacters(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
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
                    description = "페이지 크기. 보관함은 기본 9개이며 1~24 사이로 요청합니다.",
                    example = "9"
            )
            @RequestParam(defaultValue = "9")
            @Min(value = 1, message = "페이지 크기는 1 이상이어야 합니다.")
            @Max(value = 24, message = "페이지 크기는 24 이하여야 합니다.")
            int size
    ) {
        return CommonResponse.success(
                characterService.getArchivedCharacters(member.memberId(), workId, page, size)
        );
    }

    @GetMapping("/{characterId}")
    @Operation(
            operationId = "getCharacter",
            summary = "캐릭터 현재 상세 조회",
            description = "ACTIVE 캐릭터의 기본 정보와 PROFILE, STAT, SKILL, ITEM, STATUS 현재 설정을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "캐릭터 상세 조회 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "경로 UUID 형식 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "작품 또는 활성 캐릭터를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<CharacterDetailResponse> getCharacter(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID characterId
    ) {
        return CommonResponse.success(characterService.getCharacter(member.memberId(), workId, characterId));
    }

    @PatchMapping("/{characterId}")
    @Operation(
            operationId = "updateCharacter",
            summary = "캐릭터 현재 설정 전체 수정",
            description = "기본 정보와 현재 설정 전체를 한 트랜잭션에서 수정합니다. 변경된 설정은 새 수동 정정 Fact로 기록합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "캐릭터 수정 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "요청 값 또는 설정 key 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "작품, 활성 캐릭터 또는 첫 등장 회차를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "같은 작품 안의 다른 활성 캐릭터와 이름 중복",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<CharacterDetailResponse> updateCharacter(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID characterId,
            @Valid @RequestBody CharacterUpdateRequest request
    ) {
        return CommonResponse.success(
                "캐릭터가 수정되었습니다.",
                characterService.updateCharacter(member.memberId(), workId, characterId, request)
        );
    }

    @DeleteMapping("/{characterId}")
    @Operation(
            operationId = "deleteCharacter",
            summary = "캐릭터 삭제 버튼 처리",
            description = "캐릭터와 설정 이력은 삭제하지 않고 상태를 ACTIVE에서 ARCHIVED로 변경합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "캐릭터 보관 전환 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "경로 UUID 형식 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "작품 또는 활성 캐릭터를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<CharacterArchiveResponse> deleteCharacter(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID characterId
    ) {
        return CommonResponse.success(
                "캐릭터가 삭제되었습니다.",
                characterService.archiveCharacter(member.memberId(), workId, characterId)
        );
    }

    @PatchMapping("/{characterId}/restore")
    @Operation(
            operationId = "restoreCharacter",
            summary = "보관 캐릭터 복구",
            description = "보관된 캐릭터의 설정 이력과 원문 근거는 유지한 채 상태를 ARCHIVED에서 ACTIVE로 변경합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "캐릭터 복구 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "경로 UUID 형식 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "작품 또는 보관 캐릭터를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "같은 작품 안의 다른 활성 캐릭터와 이름 중복",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<CharacterRestoreResponse> restoreCharacter(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID characterId
    ) {
        return CommonResponse.success(
                "캐릭터가 복구되었습니다.",
                characterService.restoreCharacter(member.memberId(), workId, characterId)
        );
    }
}
