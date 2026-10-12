package org.monitoring.catchholebackend.domain.chat.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.*;
import org.monitoring.catchholebackend.domain.chat.service.*;
import org.monitoring.catchholebackend.domain.settingtool.dto.*;
import org.monitoring.catchholebackend.domain.settingtool.service.SettingToolService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/v1/chat-runs")
@Tag(name = "Internal chat execution")
@SecurityRequirement(name = "internalApiKey")
public class InternalChatController {
    private final ChatService service;
    private final ChatToolAccess access;
    private final SettingToolService tools;

    @PostMapping("/{id}/start")
    @Operation(operationId = "startChatRun", summary = "Python 실행 소유권 획득")
    public CommonResponse<Context> start(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Chat-Capability", required = false) String token,
            @RequestHeader(value = "X-Chat-Runner", required = false) UUID runner) {
        return CommonResponse.success(service.start(id, token, runner));
    }

    @GetMapping("/{id}/context")
    @Operation(operationId = "getChatRunContext", summary = "유효한 실행 문맥 재확인")
    public CommonResponse<Context> context(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Chat-Capability", required = false) String token,
            @RequestHeader(value = "X-Chat-Runner", required = false) UUID runner) {
        return CommonResponse.success(service.context(id, token, runner));
    }

    @PostMapping("/{id}/finish")
    @Operation(operationId = "finishChatRun", summary = "결과 저장. 정산은 별도 복구 가능")
    public CommonResponse<View> finish(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Chat-Capability", required = false) String token,
            @RequestHeader(value = "X-Chat-Runner", required = false) UUID runner,
            @Valid @RequestBody Finish request) {
        return CommonResponse.success(service.finish(id, token, runner, request));
    }

    @PostMapping("/{id}/calls")
    @Operation(operationId = "reserveChatCall", summary = "provider 호출 전 토큰 예약")
    public CommonResponse<CallView> reserve(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Chat-Capability", required = false) String token,
            @RequestHeader(value = "X-Chat-Runner", required = false) UUID runner,
            @Valid @RequestBody Reserve request) {
        return CommonResponse.success(service.reserve(id, token, runner, request));
    }

    @PostMapping("/{id}/calls/{callId}")
    @Operation(operationId = "reportChatCall", summary = "실측 사용량 또는 호출 전 해제 보고")
    public CommonResponse<CallView> report(
            @PathVariable UUID id,
            @PathVariable UUID callId,
            @RequestHeader(value = "X-Chat-Capability", required = false) String token,
            @RequestHeader(value = "X-Chat-Runner", required = false) UUID runner,
            @Valid @RequestBody Usage request) {
        return CommonResponse.success(service.report(id, token, runner, callId, request));
    }

    @PostMapping("/{id}/tools")
    @Operation(operationId = "queryChatSettingTools", summary = "실행에 고정된 회원·작품·출처 범위로 설정 조회")
    public CommonResponse<SettingToolResponse> query(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Chat-Capability", required = false) String token,
            @RequestHeader(value = "X-Chat-Runner", required = false) UUID runner,
            @Valid @RequestBody SettingToolQuery request) {
        var a = access.begin(id, token, runner, request);
        var result = tools.querySettings(a.memberId(), a.workId(), a.query());
        access.complete(id, token, runner, result);
        return CommonResponse.success(result);
    }

    @PostMapping("/operations/{id}")
    @Operation(
            operationId = "createOperationalAiRun",
            summary = "운영 백필 귀속·예산 생성",
            description = "신뢰 운영자 전용. 사용자 잔액을 차감하지 않습니다. id는 재전송 멱등성 키입니다.")
    public CommonResponse<Dispatch> operation(
            @PathVariable UUID id,
            @Valid @RequestBody
                    org.monitoring.catchholebackend.domain.chat.dto.ChatContract.Operation
                            request) {
        return CommonResponse.success(service.operation(id, request));
    }
}
