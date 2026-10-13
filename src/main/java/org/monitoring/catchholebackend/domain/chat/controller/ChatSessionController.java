package org.monitoring.catchholebackend.domain.chat.controller;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.chat.dto.ChatSessionContract.*;
import org.monitoring.catchholebackend.domain.chat.service.ChatSessionService;
import org.monitoring.catchholebackend.global.common.response.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/works/{workId}/chat-sessions")
@Tag(name = "Chat sessions", description = "종료·2시간 미사용 시 본문이 삭제되는 일회성 대화")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true),
    @ApiResponse(
            responseCode = "400",
            description = "잘못된 입력",
            content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
    @ApiResponse(
            responseCode = "401",
            description = "로그인 필요",
            content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
    @ApiResponse(
            responseCode = "403",
            description = "작품 접근 불가",
            content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
    @ApiResponse(
            responseCode = "404",
            description = "대화 없음",
            content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
    @ApiResponse(
            responseCode = "409",
            description = "대화 한도 초과",
            content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
    @ApiResponse(
            responseCode = "503",
            description = "챗봇 비활성",
            content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
    @ApiResponse(
            responseCode = "410",
            description = "종료·만료된 대화",
            content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
})
public class ChatSessionController {
    private final ChatSessionService service;

    @PostMapping
    @Operation(
            operationId = "createChatSession",
            summary = "일회성 대화 생성",
            description = "같은 requestId는 기존 대화를 반환하며 종료된 대화를 복구하지 않습니다. 비활성 503, 활성 대화 한도 409입니다.")
    public CommonResponse<Session> create(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Valid @RequestBody Create request) {
        return CommonResponse.success(
                service.createSession(member.memberId(), workId, request.requestId()));
    }

    @GetMapping
    @Operation(
            operationId = "listChatSessions",
            summary = "활성 대화 목록",
            description = "생성 시각·ID 오름차순, 최대 50개. 종료로 항목이 제거되면 첫 페이지부터 새로 조회합니다.")
    public CommonResponse<PageResponse<Session>> list(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return CommonResponse.success(service.listSessions(member.memberId(), workId, page, size));
    }

    @GetMapping("/{sessionId}")
    @Operation(
            operationId = "getChatSession",
            summary = "활성 대화 조회",
            description = "읽기·새로고침은 만료 시각을 연장하지 않습니다.")
    public CommonResponse<Session> get(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID sessionId) {
        return CommonResponse.success(service.getSession(member.memberId(), workId, sessionId));
    }

    @GetMapping("/{sessionId}/messages")
    @Operation(
            operationId = "listChatMessages",
            summary = "대화의 질문·답변 조회",
            description = "질문 접수 순서 오름차순, 최대 50개. 메시지는 추가만 하므로 다음 페이지에서도 순서가 유지됩니다.")
    public CommonResponse<PageResponse<Message>> messages(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return CommonResponse.success(
                service.listMessages(member.memberId(), workId, sessionId, page, size));
    }

    @DeleteMapping("/{sessionId}")
    @Operation(
            operationId = "closeChatSession",
            summary = "대화 종료·본문 삭제",
            description = "질문·답변·근거를 제거하고 늦은 답변을 막습니다. 실행·정산 메타데이터는 유지합니다. 같은 종료 요청은 멱등입니다.")
    public CommonResponse<Void> close(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID sessionId) {
        service.closeSession(member.memberId(), workId, sessionId);
        return CommonResponse.success(null);
    }
}
