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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactDetailResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactEvidenceResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactSearchResponse;
import org.monitoring.catchholebackend.domain.character.service.CharacterFactService;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactSearchScope;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactSearchType;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping(
        value = "/api/v1/works/{workId}/character-facts",
        produces = MediaType.APPLICATION_JSON_VALUE
)
@Tag(name = "CharacterFact", description = "작품별 확정 캐릭터 설정 검색과 원문 근거 상세 API")
@SecurityRequirement(name = "bearerAuth")
public class CharacterFactController {

    private final CharacterFactService characterFactService;

    @GetMapping("/search")
    @Operation(
            operationId = "searchCharacterFacts",
            summary = "설정DB CharacterFact 검색",
            description = "MVP에서는 본인 작품의 ACTIVE 캐릭터 Fact를 키·표시값의 대소문자 무시 LIKE 부분 일치로 검색합니다. "
                    + "현재 설정, 적용 회차, 생성 시각, Fact ID 순서로 고정 정렬합니다. "
                    + "장소·세계관·타임라인·관계 등 다른 설정 범위를 포함하는 통합 검색은 후속 범위입니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "CharacterFact 검색 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "검색 유형, 시점, 페이지 번호 또는 크기 검증 실패",
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
    public CommonResponse<PageResponse<CharacterFactSearchResponse>> searchCharacterFacts(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Parameter(
                    name = "q",
                    in = ParameterIn.QUERY,
                    description = "factKey와 factValue의 검색어. 앞뒤 공백을 제거하며 빈 값은 전체 조회합니다.",
                    example = "체력 물약"
            )
            @RequestParam(defaultValue = "")
            String q,
            @Parameter(
                    name = "factType",
                    in = ParameterIn.QUERY,
                    description = "설정 유형 필터",
                    example = "ITEM"
            )
            @RequestParam(defaultValue = "ALL")
            CharacterFactSearchType factType,
            @Parameter(
                    name = "scope",
                    in = ParameterIn.QUERY,
                    description = "전체 이력, 현재 설정, 이전 설정 필터",
                    example = "CURRENT"
            )
            @RequestParam(defaultValue = "ALL")
            CharacterFactSearchScope scope,
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
                    description = "페이지 크기. 설정DB MVP 화면은 20을 사용합니다.",
                    example = "20"
            )
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "페이지 크기는 1 이상이어야 합니다.")
            @Max(value = 100, message = "페이지 크기는 100 이하여야 합니다.")
            int size
    ) {
        return CommonResponse.success(characterFactService.searchCharacterFacts(
                member.memberId(),
                workId,
                q,
                factType,
                scope,
                page,
                size
        ));
    }

    @GetMapping("/{characterFactId}")
    @Operation(
            operationId = "getCharacterFact",
            summary = "설정DB CharacterFact 상세 조회",
            description = "본인 작품의 ACTIVE 캐릭터 Fact와 연결된 SettingCandidate의 저장된 근거 인용문을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "CharacterFact 상세 조회 성공"),
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
                    description = "작품, CharacterFact 또는 ACTIVE 캐릭터를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<CharacterFactDetailResponse> getCharacterFact(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID characterFactId
    ) {
        return CommonResponse.success(
                characterFactService.getCharacterFact(member.memberId(), workId, characterFactId)
        );
    }

    @GetMapping("/{characterFactId}/evidence")
    @Operation(
            operationId = "getCharacterFactEvidence",
            summary = "캐릭터 설정 원문 근거 조회",
            description = "본인 작품의 CharacterFact가 생성된 분석 당시 회차 전체 원문과 근거 범위를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "캐릭터 설정 원문 근거 조회 성공"),
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
                    description = "작품 또는 캐릭터 설정을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<CharacterFactEvidenceResponse> getCharacterFactEvidence(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID characterFactId
    ) {
        return CommonResponse.success(
                characterFactService.getEvidence(member.memberId(), workId, characterFactId)
        );
    }
}
