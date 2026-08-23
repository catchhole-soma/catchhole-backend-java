package org.monitoring.catchholebackend.domain.work.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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
import org.monitoring.catchholebackend.domain.work.dto.request.WorkPurgeCreateRequest;
import org.monitoring.catchholebackend.domain.work.dto.request.WorkUpdateRequest;
import org.monitoring.catchholebackend.domain.work.dto.response.WorkPurgeResponse;
import org.monitoring.catchholebackend.domain.work.dto.response.WorkResponse;
import org.monitoring.catchholebackend.domain.work.service.WorkPurgeService;
import org.monitoring.catchholebackend.domain.work.service.WorkService;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
@RequestMapping(value = "/api/v1/works", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Work", description = "로그인한 사용자의 작품 등록, 조회, 수정, 삭제 API")
@SecurityRequirement(name = "bearerAuth")
public class WorkController {

    private final WorkService workService;
    private final WorkPurgeService workPurgeService;

    @PostMapping
    @Operation(
            operationId = "createWork",
            summary = "내 작품 생성",
            description = "로그인한 사용자의 새 작품을 제목, 선택형 50자 한 줄 소개와 MVP 고정 장르로 등록합니다. "
                    + "회차 업로드와 독립된 요청이며 최신 회차 번호는 0으로 초기화합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "작품 생성 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "제목, 작품 설명 또는 장르 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "인증된 회원 정보를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<WorkResponse> createWork(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @Valid @RequestBody WorkCreateRequest request
    ) {
        return CommonResponse.success("작품이 등록되었습니다.", workService.createWork(member.memberId(), request));
    }

    @GetMapping
    @Operation(
            operationId = "getMyWorks",
            summary = "내 작품 목록 조회",
            description = "로그인한 사용자가 등록한 작품 목록을 최신 생성순으로 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "작품 목록 조회 성공"),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<List<WorkResponse>> getMyWorks(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member
    ) {
        return CommonResponse.success(workService.getMyWorks(member.memberId()));
    }

    @GetMapping("/{workId}")
    @Operation(
            operationId = "getWork",
            summary = "내 작품 상세 조회",
            description = "로그인한 사용자가 본인 작품의 상세 정보를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "작품 상세 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품을 찾을 수 없음")
    })
    public CommonResponse<WorkResponse> getWork(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId
    ) {
        return CommonResponse.success(workService.getWork(member.memberId(), workId));
    }

    @PatchMapping("/{workId}")
    @Operation(
            operationId = "updateWork",
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
            operationId = "deleteWork",
            summary = "내 작품 영구 삭제 요청",
            description = "확인 문구가 정확히 일치하면 작품을 변경 불가 상태로 전환하고 비동기 영구 삭제를 요청합니다. "
                    + "같은 작품에 대한 반복 요청은 기존 요청을 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "영구 삭제 요청 접수"),
            @ApiResponse(responseCode = "400", description = "영구 삭제 확인 문구 불일치"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품을 찾을 수 없음")
    })
    public ResponseEntity<CommonResponse<WorkPurgeResponse>> deleteWork(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Valid @RequestBody WorkPurgeCreateRequest request
    ) {
        WorkPurgeResponse response = workPurgeService.requestPurge(member.memberId(), workId, request);
        return ResponseEntity.accepted().body(CommonResponse.success("작품 영구 삭제 요청이 접수되었습니다.", response));
    }

    @GetMapping("/purge-requests/{requestId}")
    @Operation(
            operationId = "getWorkPurgeRequest",
            summary = "작품 영구 삭제 상태 조회",
            description = "작품 행이 삭제된 뒤에도 본인이 요청한 영구 삭제 진행 상태와 삭제 건수를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "영구 삭제 상태 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "본인의 영구 삭제 요청을 찾을 수 없음")
    })
    public CommonResponse<WorkPurgeResponse> getWorkPurgeRequest(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID requestId
    ) {
        return CommonResponse.success(workPurgeService.getPurgeRequest(member.memberId(), requestId));
    }

    @GetMapping("/{workId}/purge-request")
    @Operation(
            operationId = "getWorkPurgeRequestByWork",
            summary = "작품별 영구 삭제 상태 조회",
            description = "새로고침 후에도 PURGING 작품의 기존 영구 삭제 요청과 재시도 가능 여부를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "작품별 영구 삭제 상태 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "본인 작품의 영구 삭제 요청을 찾을 수 없음")
    })
    public CommonResponse<WorkPurgeResponse> getWorkPurgeRequestByWork(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId
    ) {
        return CommonResponse.success(workPurgeService.getPurgeRequestByWork(member.memberId(), workId));
    }

    @PostMapping("/purge-requests/{requestId}/retry")
    @Operation(
            operationId = "retryWorkPurgeRequest",
            summary = "실패한 작품 영구 삭제 재시도",
            description = "FAILED 또는 PARTIAL_FAILED 상태인 본인의 영구 삭제 요청을 다시 대기 상태로 전환합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "영구 삭제 재시도 접수 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "본인의 영구 삭제 요청을 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "현재 상태에서는 재시도할 수 없음")
    })
    public CommonResponse<WorkPurgeResponse> retryWorkPurgeRequest(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID requestId
    ) {
        return CommonResponse.success(
                "작품 영구 삭제를 다시 요청했습니다.",
                workPurgeService.retryPurge(member.memberId(), requestId)
        );
    }
}
