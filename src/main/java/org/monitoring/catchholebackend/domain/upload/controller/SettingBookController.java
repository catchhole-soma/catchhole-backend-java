package org.monitoring.catchholebackend.domain.upload.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.upload.dto.response.SettingBookResponse;
import org.monitoring.catchholebackend.domain.upload.dto.response.SettingBookSummaryResponse;
import org.monitoring.catchholebackend.domain.upload.service.SettingBookService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    @Operation(operationId = "getSettingBooks", summary = "활성 설정집 원본 목록 조회")
    public CommonResponse<List<SettingBookSummaryResponse>> getSettingBooks(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId
    ) {
        return CommonResponse.success(settingBookService.getSettingBooks(member.memberId(), workId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(operationId = "uploadSettingBook", summary = "설정집 원본 단독 업로드")
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
    @Operation(operationId = "getSettingBook", summary = "설정집 읽기 전용 원문 조회")
    public CommonResponse<SettingBookResponse> getSettingBook(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID settingBookId
    ) {
        return CommonResponse.success(
                settingBookService.getSettingBook(member.memberId(), workId, settingBookId)
        );
    }

    @DeleteMapping("/{settingBookId}")
    @Operation(operationId = "deleteSettingBook", summary = "설정집 원본 soft delete")
    public CommonResponse<Void> deleteSettingBook(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID settingBookId
    ) {
        settingBookService.deleteSettingBook(member.memberId(), workId, settingBookId);
        return CommonResponse.success("설정집 원본이 삭제되었습니다.", null);
    }
}
