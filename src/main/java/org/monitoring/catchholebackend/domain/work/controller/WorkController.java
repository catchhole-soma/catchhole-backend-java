package org.monitoring.catchholebackend.domain.work.controller;

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
import org.monitoring.catchholebackend.domain.work.dto.request.WorkCreateRequest;
import org.monitoring.catchholebackend.domain.work.dto.request.WorkUpdateRequest;
import org.monitoring.catchholebackend.domain.work.dto.response.WorkResponse;
import org.monitoring.catchholebackend.domain.work.service.WorkService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/works")
@Tag(name = "Work", description = "로그인한 사용자의 작품 등록, 조회, 수정, 삭제 API")
@SecurityRequirement(name = "bearerAuth")
public class WorkController {

    private final WorkService workService;

    @PostMapping
    @Operation(
            summary = "내 작품 생성",
            description = "로그인한 사용자의 새 작품을 등록합니다. 작품 상태와 최신 회차 번호는 서버에서 초기화합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "작품 생성 성공"),
            @ApiResponse(responseCode = "400", description = "요청 값 검증 실패"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "인증된 회원 정보를 찾을 수 없음")
    })
    public CommonResponse<WorkResponse> createWork(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @Valid @RequestBody WorkCreateRequest request
    ) {
        return CommonResponse.success("작품이 등록되었습니다.", workService.createWork(member.memberId(), request));
    }

    @GetMapping
    @Operation(
            summary = "내 작품 목록 조회",
            description = "로그인한 사용자가 등록한 작품 목록을 최신 생성순으로 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "작품 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패")
    })
    public CommonResponse<List<WorkResponse>> getMyWorks(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member
    ) {
        return CommonResponse.success(workService.getMyWorks(member.memberId()));
    }

    @PatchMapping("/{workId}")
    @Operation(
            summary = "내 작품 수정",
            description = "로그인한 사용자가 본인 작품의 제목, 장르, 설명을 수정합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "작품 수정 성공"),
            @ApiResponse(responseCode = "400", description = "요청 값 검증 실패"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품을 찾을 수 없음")
    })
    public CommonResponse<WorkResponse> updateWork(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Valid @RequestBody WorkUpdateRequest request
    ) {
        return CommonResponse.success("작품이 수정되었습니다.", workService.updateWork(member.memberId(), workId, request));
    }

    @DeleteMapping("/{workId}")
    @Operation(
            summary = "내 작품 삭제",
            description = "로그인한 사용자가 본인 작품을 삭제합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "작품 삭제 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품을 찾을 수 없음")
    })
    public CommonResponse<Void> deleteWork(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId
    ) {
        workService.deleteWork(member.memberId(), workId);
        return CommonResponse.success("작품이 삭제되었습니다.", null);
    }
}
