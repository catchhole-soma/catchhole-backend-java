package org.monitoring.catchholebackend.domain.feedback.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.feedback.dto.request.FeedbackCreateRequest;
import org.monitoring.catchholebackend.domain.feedback.dto.response.FeedbackCreateResponse;
import org.monitoring.catchholebackend.domain.feedback.service.FeedbackService;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/feedbacks", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Feedback", description = "로그인 사용자의 서비스 의견 API")
@SecurityRequirement(name = "bearerAuth")
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "createMyFeedback",
            summary = "서비스 의견 등록",
            description = "의견은 횟수 제한 없이 저장합니다. 일반 피드백 보상용 추가 사용량 요청은 회원당 한 번만 생성하고, 다른 요청이 처리 대기 중이면 이번 의견만 저장합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "의견 등록 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "의견 또는 화면 경로 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<FeedbackCreateResponse> createMyFeedback(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @Valid @RequestBody FeedbackCreateRequest request
    ) {
        return CommonResponse.success(
                "의견이 접수되었습니다.",
                feedbackService.createFeedback(member.memberId(), request)
        );
    }
}
