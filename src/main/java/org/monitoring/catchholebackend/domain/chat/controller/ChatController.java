package org.monitoring.catchholebackend.domain.chat.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.*;
import org.monitoring.catchholebackend.domain.chat.service.ChatService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/works/{workId}/chat-questions")
@Tag(name = "Chat execution", description = "단일 질문 실행 v1. 대화·스트리밍은 후속 구현")
@SecurityRequirement(name = "bearerAuth")
public class ChatController {
    private final ChatService service;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            operationId = "submitChatQuestion",
            summary = "단일 질문 접수",
            description =
                    "같은 사용자·작품·Idempotency-Key와 같은 입력은 기존 실행을 반환합니다. 입력 충돌은 409, 한도 초과는 429입니다.")
    public CommonResponse<View> submit(
            @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody Submit request) {
        return CommonResponse.success(service.submit(member.memberId(), workId, key, request));
    }

    @GetMapping("/{questionId}")
    @Operation(operationId = "getChatQuestion", summary = "실행 상태와 저장된 결과 조회")
    public CommonResponse<View> get(
            @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID questionId) {
        return CommonResponse.success(service.get(member.memberId(), workId, questionId));
    }

    @PostMapping("/{questionId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            operationId = "retryChatQuestion",
            summary = "실패·시간 초과 실행의 명시적 재시도",
            description = "previousAttemptId가 재시도 멱등성 키입니다. 이전 호출 사용량은 보존합니다.")
    public CommonResponse<View> retry(
            @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID questionId,
            @Valid @RequestBody Retry request) {
        return CommonResponse.success(
                service.retry(member.memberId(), workId, questionId, request));
    }
}
