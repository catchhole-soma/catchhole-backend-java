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
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationJudgmentRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationFindingDetailResponse;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationFindingSummaryResponse;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationReviewSummaryResponse;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationCorrectionReceipt;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationStagingResponse;
import org.monitoring.catchholebackend.domain.validation.service.EpisodeValidationService;
import org.monitoring.catchholebackend.domain.validation.service.ValidationReviewService;
import org.monitoring.catchholebackend.domain.validation.type.ValidationBranch;
import org.monitoring.catchholebackend.domain.validation.type.ValidationJudgmentValue;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/works/{workId}/episode-validations")
@Tag(name = "EpisodeValidation", description = "신규 회차 검수 접수·전용 실행·결과·작가 판단. 검토 완료와 설정 추출 허가는 후속입니다.")
@SecurityRequirement(name = "bearerAuth")
public class EpisodeValidationController {
    private final EpisodeValidationService service;
    private final org.monitoring.catchholebackend.domain.validation.service.ValidationWorkerService workers;
    private final org.monitoring.catchholebackend.domain.validation.service.ValidationResultService results;
    private final org.monitoring.catchholebackend.domain.validation.service.ValidationRecheckService rechecks;
    private final org.monitoring.catchholebackend.domain.validation.service.ValidationCorrectionService corrections;
    private final ValidationReviewService reviews;
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

    @GetMapping("/{reportId}/findings")
    @Operation(operationId="listValidationFindings", summary="현재 발견 항목과 작가 판단 목록", description="현재 check revision의 설정 항목과 오탈자 제안만 생성 순서로 반환합니다. 판단을 저장한 적 없는 항목은 PENDING이며 근거 행은 상세에서만 제공합니다. page는 0부터, size는 1~100입니다.")
    @ApiResponses({@ApiResponse(responseCode="200", description="조회 성공"),
            @ApiResponse(responseCode="400", description="페이지·필터 값 오류"),
            @ApiResponse(responseCode="404", description="작품 소유권 또는 검수 요청 없음")})
    public CommonResponse<PageResponse<ValidationFindingSummaryResponse>> findings(@AuthenticationPrincipal MemberPrincipal member,@PathVariable UUID workId,@PathVariable UUID reportId,
            @RequestParam(required=false) ValidationBranch branch,@RequestParam(required=false) ValidationJudgmentValue judgment,@RequestParam(required=false) String outcome,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return CommonResponse.success(reviews.getValidationFindings(member.memberId(),workId,reportId,branch,judgment,outcome,page,size));
    }

    @GetMapping("/{reportId}/findings/{findingId}")
    @Operation(operationId="getValidationFinding", summary="발견 항목 상세", description="공개 판정 내용, 저장 때 검증한 인용, 현재 판단·허용 판단, 판단 이력과 이전 revision의 변경 사유를 반환합니다. 이전 revision 항목은 stale=true입니다.")
    @ApiResponses({@ApiResponse(responseCode="200", description="조회 성공"),
            @ApiResponse(responseCode="404", description="작품 소유권·검수 요청·발견 항목 없음")})
    public CommonResponse<ValidationFindingDetailResponse> finding(@AuthenticationPrincipal MemberPrincipal member,@PathVariable UUID workId,@PathVariable UUID reportId,@PathVariable UUID findingId) {
        return CommonResponse.success(reviews.getValidationFinding(member.memberId(),workId,reportId,findingId));
    }

    @PatchMapping("/{reportId}/findings/{findingId}/judgment")
    @Operation(operationId="saveValidationJudgment", summary="발견 항목의 작가 판단 저장", description="검사 종료·중단 뒤 현재 항목의 판단을 저장합니다. 같은 값 재전송은 변경하지 않으며 PENDING으로 되돌릴 수 있습니다. 마지막 판단을 저장해도 검토 완료나 설정 추출을 시작하지 않습니다.")
    @ApiResponses({@ApiResponse(responseCode="200", description="저장 또는 같은 값 재전송"),
            @ApiResponse(responseCode="400", description="항목 분기에 맞지 않는 판단 또는 필드 검증 실패"),
            @ApiResponse(responseCode="404", description="작품 소유권·검수 요청·발견 항목 없음"),
            @ApiResponse(responseCode="409", description="검사 진행 중·현재 검수 아님(LOCKED), 바뀐 항목(STALE), 판단 version 충돌")})
    public CommonResponse<ValidationFindingSummaryResponse> judge(@AuthenticationPrincipal MemberPrincipal member,@PathVariable UUID workId,@PathVariable UUID reportId,@PathVariable UUID findingId,
            @Valid @RequestBody ValidationJudgmentRequest request) {
        return CommonResponse.success("작가 판단을 저장했습니다.",reviews.saveValidationJudgment(member.memberId(),workId,reportId,findingId,request));
    }

    @GetMapping("/{reportId}/review")
    @Operation(operationId="getValidationReviewSummary", summary="작가 검토 요약", description="분기별 판단 수, 판단 잠금, 검토 종료 경로와 보류 사유를 반환합니다. AUTOMATIC_ELIGIBLE·EXPLICIT_REQUIRED도 설정 추출을 시작하지 않습니다.")
    @ApiResponses({@ApiResponse(responseCode="200", description="조회 성공"),
            @ApiResponse(responseCode="404", description="작품 소유권 또는 검수 요청 없음")})
    public CommonResponse<ValidationReviewSummaryResponse> review(@AuthenticationPrincipal MemberPrincipal member,@PathVariable UUID workId,@PathVariable UUID reportId) {
        return CommonResponse.success(reviews.getValidationReview(member.memberId(),workId,reportId));
    }
}
