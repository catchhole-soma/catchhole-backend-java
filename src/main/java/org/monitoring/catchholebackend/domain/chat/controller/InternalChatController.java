package org.monitoring.catchholebackend.domain.chat.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.*;
import org.monitoring.catchholebackend.domain.chat.service.*;
import org.monitoring.catchholebackend.domain.settingtool.dto.*;
import org.monitoring.catchholebackend.domain.settingtool.service.SettingToolService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/v1/chat-runs")
@Tag(name = "Internal chat execution", description = "내부 인증을 사용하는 채팅 실행·운영 백필 API")
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

    @PostMapping("/{id}/retrieval-sources")
    @Operation(operationId = "verifyChatRetrievalSources", summary = "실행에 고정된 범위로 현재 원문 검색 인용을 검증하고 사용 허가")
    public CommonResponse<RetrievalSources> retrievalSources(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Chat-Capability", required = false) String token,
            @RequestHeader(value = "X-Chat-Runner", required = false) UUID runner,
            @Valid @RequestBody RetrievalSourcesRequest request) {
        return CommonResponse.success(access.retrievalSources(id, token, runner, request));
    }

    @GetMapping("/operations/readiness")
    @Operation(
            operationId = "getOperationalAiReadiness",
            summary = "운영 백필 접수 준비 설정 확인",
            description = "내부 API key를 인증하고 운영·공개 채팅 게이트와 실행 비밀값의 설정 여부를 반환합니다. 실행 생성·DB 접근·provider 호출은 하지 않습니다. 호출자가 필요한 게이트를 확인해야 합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "설정 확인 성공. 비활성 게이트는 false로 반환합니다."),
        @ApiResponse(responseCode = "401", description = "내부 API key 인증 실패",
                content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<OperationalReadiness> operationalReadiness() {
        return CommonResponse.success(service.operationalReadiness());
    }

    @PostMapping("/operations/{id}")
    @Operation(
            operationId = "createOperationalAiRun",
            summary = "운영 백필 귀속·예산 생성",
            description = "신뢰 운영자 전용. CHAT_OPERATIONS_ENABLED로 새 접수를 제어하며 사용자 잔액을 차감하지 않습니다. id는 재전송 멱등성 키이며 동일 입력 재전송은 운영 게이트를 꺼도 허용합니다.")
    public CommonResponse<Dispatch> operation(
            @PathVariable UUID id,
            @Valid @RequestBody
                    org.monitoring.catchholebackend.domain.chat.dto.ChatContract.Operation
                            request) {
        return CommonResponse.success(service.operation(id, request));
    }
}
