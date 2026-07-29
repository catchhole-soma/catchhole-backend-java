package org.monitoring.catchholebackend.domain.upload.controller;

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
import org.monitoring.catchholebackend.domain.upload.dto.request.SettingBookUpdateRequest;
import org.monitoring.catchholebackend.domain.upload.dto.response.SettingBookResponse;
import org.monitoring.catchholebackend.domain.upload.dto.response.SettingBookSummaryResponse;
import org.monitoring.catchholebackend.domain.upload.service.SettingBookService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/works/{workId}/setting-books")
@Tag(name = "SettingBook", description = "작품별 설정집 원본 관리 API")
@SecurityRequirement(name = "bearerAuth")
public class SettingBookController {

    private final SettingBookService settingBookService;

    @GetMapping
    @Operation(
            operationId = "getSettingBooks",
            summary = "활성 설정집 원본 목록 조회",
            description = "본인 작품의 삭제되지 않은 설정집 원본을 최근 업로드 순으로 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정집 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품을 찾을 수 없음")
    })
    public CommonResponse<List<SettingBookSummaryResponse>> getSettingBooks(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId
    ) {
        return CommonResponse.success(settingBookService.getSettingBooks(member.memberId(), workId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            operationId = "uploadSettingBook",
            summary = "설정집 원본 단독 업로드",
            description = "TXT 또는 DOCX 원본 한 개를 새 설정집으로 추가합니다. 같은 파일명도 새 항목으로 누적합니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true)
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정집 업로드 성공"),
            @ApiResponse(responseCode = "400", description = "파일 형식, 크기 또는 내용 검증 실패"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품을 찾을 수 없음")
    })
    public CommonResponse<SettingBookSummaryResponse> uploadSettingBook(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @RequestPart("file") MultipartFile file
    ) {
        return CommonResponse.success(
                "설정집 원본이 업로드되었습니다.",
                settingBookService.uploadSettingBook(member.memberId(), workId, file)
        );
    }

    @GetMapping("/{settingBookId}")
    @Operation(
            operationId = "getSettingBook",
            summary = "설정집 전체 원문 조회",
            description = "선택한 설정집의 현재 편집용 텍스트와 업로드 원본 메타데이터를 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정집 원문 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 설정집을 찾을 수 없음")
    })
    public CommonResponse<SettingBookResponse> getSettingBook(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID settingBookId
    ) {
        return CommonResponse.success(
                settingBookService.getSettingBook(member.memberId(), workId, settingBookId)
        );
    }

    @PatchMapping("/{settingBookId}")
    @Operation(
            operationId = "updateSettingBook",
            summary = "설정집 전체 원문 수정",
            description = "업로드 원본은 보존하고 작품/설정집/원본 파일명 기반의 고정 key에 현재 원문을 덮어씁니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정집 원문 수정 성공"),
            @ApiResponse(responseCode = "400", description = "원문 내용 또는 크기 검증 실패"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 설정집을 찾을 수 없음")
    })
    public CommonResponse<SettingBookResponse> updateSettingBook(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID settingBookId,
            @Valid @RequestBody SettingBookUpdateRequest request
    ) {
        return CommonResponse.success(
                "설정집 원문이 수정되었습니다.",
                settingBookService.updateSettingBook(member.memberId(), workId, settingBookId, request)
        );
    }

    @DeleteMapping("/{settingBookId}")
    @Operation(
            operationId = "deleteSettingBook",
            summary = "설정집 원본 soft delete",
            description = "설정집 DB 행과 저장 객체는 보존하고 활성 목록에서 숨깁니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정집 삭제 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 설정집을 찾을 수 없음")
    })
    public CommonResponse<Void> deleteSettingBook(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID settingBookId
    ) {
        settingBookService.deleteSettingBook(member.memberId(), workId, settingBookId);
        return CommonResponse.success("설정집 원본이 삭제되었습니다.", null);
    }
}
