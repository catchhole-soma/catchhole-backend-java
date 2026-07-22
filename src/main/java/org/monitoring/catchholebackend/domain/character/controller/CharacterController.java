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
import org.monitoring.catchholebackend.domain.character.dto.request.CharacterUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterArchiveResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterDetailResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterSummaryResponse;
import org.monitoring.catchholebackend.domain.character.service.CharacterService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/works/{workId}/characters")
@Tag(name = "Character", description = "작품별 활성 캐릭터 조회, 수정, 삭제 버튼 처리 API")
@SecurityRequirement(name = "bearerAuth")
public class CharacterController {

    private final CharacterService characterService;

    @GetMapping
    @Operation(
            summary = "캐릭터 목록 조회",
            description = "본인 작품의 ACTIVE 캐릭터 카드 목록을 최신 생성순으로 조회합니다. 보관된 캐릭터는 제외합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "캐릭터 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품을 찾을 수 없음")
    })
    public CommonResponse<List<CharacterSummaryResponse>> getCharacters(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId
    ) {
        return CommonResponse.success(characterService.getCharacters(member.memberId(), workId));
    }

    @GetMapping("/{characterId}")
    @Operation(
            summary = "캐릭터 현재 상세 조회",
            description = "ACTIVE 캐릭터의 기본 정보와 PROFILE, STAT, SKILL, ITEM, STATUS 현재 설정을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "캐릭터 상세 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 활성 캐릭터를 찾을 수 없음")
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
            summary = "캐릭터 현재 설정 전체 수정",
            description = "기본 정보와 현재 설정 전체를 한 트랜잭션에서 수정합니다. 변경된 설정은 새 수동 정정 Fact로 기록합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "캐릭터 수정 성공"),
            @ApiResponse(responseCode = "400", description = "요청 값 또는 설정 key 검증 실패"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품, 활성 캐릭터 또는 첫 등장 회차를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "같은 작품 안의 캐릭터 이름 중복")
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
            summary = "캐릭터 삭제 버튼 처리",
            description = "캐릭터와 설정 이력은 삭제하지 않고 상태를 ACTIVE에서 ARCHIVED로 변경합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "캐릭터 보관 전환 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 활성 캐릭터를 찾을 수 없음")
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
}
