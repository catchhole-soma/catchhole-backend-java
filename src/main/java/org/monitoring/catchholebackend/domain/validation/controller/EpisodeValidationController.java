package org.monitoring.catchholebackend.domain.validation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationStartRequest;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationRecheckRequest;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationCorrectionRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationCorrectionReceipt;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationStagingResponse;
import org.monitoring.catchholebackend.domain.validation.service.EpisodeValidationService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/works/{workId}/episode-validations")
@Tag(name = "EpisodeValidation", description = "신규 회차 검수 접수·전용 실행·읽기 전용 결과. 작가 판단과 추출 허가는 후속입니다.")
@SecurityRequirement(name = "bearerAuth")
public class EpisodeValidationController {
    private final EpisodeValidationService service;
    private final org.monitoring.catchholebackend.domain.validation.service.ValidationWorkerService workers;
    private final org.monitoring.catchholebackend.domain.validation.service.ValidationResultService results;
    private final org.monitoring.catchholebackend.domain.validation.service.ValidationRecheckService rechecks;
    private final org.monitoring.catchholebackend.domain.validation.service.ValidationCorrectionService corrections;
    @PostMapping
    @Operation(operationId = "startEpisodeValidation", summary = "신규 회차 검수 접수", description = "현재 원고 버전과 단일 append 업로드 자격을 확인하고 고정 기준과 오류 검사/검토 대기를 저장합니다. 설정 추출 Job/후보를 만들지 않으며 같은 입력은 같은 식별자를 반환합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "접수 또는 동일 입력의 재전송"),
            @ApiResponse(responseCode = "400", description = "신규 단일 회차/비교 자료/필드 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 소유권 또는 검수 요청 없음"),
            @ApiResponse(responseCode = "409", description = "동일 키 다른 입력, 원고 버전 충돌 또는 토큰 한도")})
    public CommonResponse<ValidationStagingResponse> start(@AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId, @Valid @RequestBody ValidationStartRequest request) {
        return CommonResponse.success("신규 회차 검수를 접수했습니다.", service.start(member.memberId(), workId, request));
    }
    @GetMapping("/{reportId}")
    @Operation(operationId = "getEpisodeValidationStaging", summary = "검수 준비 상태 조회", description = "기준 준비와 오류 검사/검토 대기 또는 보류 사유를 조회합니다. 기준 READY는 검사/검토 완료가 아니며 설정 추출은 NOT_STARTED입니다.")
    public CommonResponse<ValidationStagingResponse> get(@AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId, @PathVariable UUID reportId) {
        return CommonResponse.success(service.get(member.memberId(), workId, reportId));
    }

    @GetMapping("/current")
    @Operation(operationId="getCurrentEpisodeValidation", summary="회차의 현재 검수 조회")
    public CommonResponse<ValidationStagingResponse> current(@AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId, @RequestParam UUID episodeId) {
        return CommonResponse.success(rechecks.getCurrentValidation(member.memberId(),workId,episodeId));
    }

    @PostMapping("/{reportId}/rechecks")
    @Operation(operationId="recheckEpisodeValidation", summary="수정 후 새 검수 접수", description="원고·기준이 달라진 최신 회차를 명시적으로 다시 접수합니다. 같은 입력의 실패 재시도와 구분하며 추출 Job·후보를 만들지 않습니다.")
    public CommonResponse<ValidationStagingResponse> recheck(@AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,@PathVariable UUID reportId,@Valid @RequestBody ValidationRecheckRequest request) {
        return CommonResponse.success(rechecks.recheckValidation(member.memberId(),workId,reportId,request));
    }

    @GetMapping("/{reportId}/results")
    @Operation(operationId="getValidationExecutionResults",summary="검수 진행 및 저장 결과 읽기 전용 조회")
    public CommonResponse<java.util.Map<String,Object>> results(@AuthenticationPrincipal MemberPrincipal member,@PathVariable UUID workId,@PathVariable UUID reportId) {
        return CommonResponse.success(results.getValidationProgress(member.memberId(),workId,reportId));
    }
    @PostMapping("/{reportId}/retries")
    @Operation(operationId="retryValidationExecution",summary="같은 입력의 미완료 검사 재시도")
    public CommonResponse<ValidationStagingResponse> retry(@AuthenticationPrincipal MemberPrincipal member,@PathVariable UUID workId,@PathVariable UUID reportId,@Valid @RequestBody org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.Retry request) {
        return CommonResponse.success(workers.retryValidation(member.memberId(),workId,reportId,request));
    }

    @PostMapping("/{reportId}/corrections")
    @Operation(operationId="correctValidationBaselineSetting", summary="실제 설정 수정과 검수 기준 정정 연결", description="기준 항목·현재 버전·대상 경로를 검증해 기존 설정 편집과 정정 영수증을 원자적으로 저장합니다. 일반 저장을 직접 정정으로 추정하지 않습니다.")
    public CommonResponse<ValidationCorrectionReceipt> correct(@AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,@PathVariable UUID reportId,@Valid @RequestBody ValidationCorrectionRequest request) {
        return CommonResponse.success(corrections.correctSettingAndLinkBaseline(member.memberId(),workId,reportId,request));
    }
}
