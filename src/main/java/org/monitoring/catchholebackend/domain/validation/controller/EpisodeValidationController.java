package org.monitoring.catchholebackend.domain.validation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
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
import org.monitoring.catchholebackend.domain.validation.service.ValidationReviewCompletionService;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationReviewCompletionRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationReviewCompletionResponse;
import org.monitoring.catchholebackend.domain.validation.type.ValidationBranch;
import org.monitoring.catchholebackend.domain.validation.type.ValidationImpact;
import org.monitoring.catchholebackend.domain.validation.type.ValidationSubjectType;
import org.monitoring.catchholebackend.domain.validation.type.ValidationJudgmentValue;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/works/{workId}/episode-validations")
@Tag(name = "EpisodeValidation", description = "신규 회차 검수 접수·전용 실행·결과·작가 판단·검토 종료와 설정 추출 release·회차 전체 확정.")
@SecurityRequirement(name = "bearerAuth")
public class EpisodeValidationController {
    private final EpisodeValidationService service;
    private final org.monitoring.catchholebackend.domain.validation.service.ValidationWorkerService workers;
    private final org.monitoring.catchholebackend.domain.validation.service.ValidationResultService results;
    private final org.monitoring.catchholebackend.domain.validation.service.ValidationRecheckService rechecks;
    private final org.monitoring.catchholebackend.domain.validation.service.ValidationCorrectionService corrections;
    private final ValidationReviewService reviews;
    private final ValidationReviewCompletionService completions;
    private final org.monitoring.catchholebackend.domain.validation.service.ValidationSettingsApplicationService applications;
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
    @Operation(operationId = "getEpisodeValidationStaging", summary = "검수 준비 상태 조회", description = "기준 준비, 오류 검사·작가 검토 상태, 검토 종료 뒤 설정 추출(extractionStatus)·회차 전체 확정(candidateStatus)과 파생 검수 단계(reviewStage)를 조회합니다. 기준 READY는 검사·검토 완료가 아니며 검토 종료 전 설정 추출은 NOT_STARTED입니다. 조회는 추출·반영을 시작하지 않습니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "작품 소유권 또는 검수 요청 없음")})
    public CommonResponse<ValidationStagingResponse> get(@AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId, @PathVariable UUID reportId) {
        return CommonResponse.success(service.get(member.memberId(), workId, reportId));
    }

    @GetMapping("/current")
    @Operation(operationId="getCurrentEpisodeValidation", summary="회차의 현재 검수 조회", description="회차의 현재(is_current) 검수 report를 단건 조회와 같은 형태로 반환합니다. 과거 report는 이력 조회를 사용합니다.")
    @ApiResponses({@ApiResponse(responseCode="200", description="조회 성공"),
            @ApiResponse(responseCode="400", description="episodeId 누락"),
            @ApiResponse(responseCode="404", description="작품 소유권 없음 또는 회차의 현재 검수 없음")})
    public CommonResponse<ValidationStagingResponse> current(@AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId, @RequestParam UUID episodeId) {
        return CommonResponse.success(rechecks.getCurrentValidation(member.memberId(),workId,episodeId));
    }

    @GetMapping("/eligibility")
    @Operation(operationId="getEpisodeValidationEligibility", summary="신규 회차 검수 접수 전 자격 조회", description="접수(POST)와 같은 판정 코드로 회차의 검수 접수 가능 여부·사유(reasonCode)·최신 회차 여부·현재 검수 report와 작품 단위 다음 회차 등록 차단(nextEpisodeAdmission)을 반환합니다. 조회 전용이며 report·Job·요청 키를 만들지 않습니다. episodeId를 생략하면 다음 회차 등록 차단만 채웁니다. 접수 자격의 최종 권위는 접수 요청입니다.")
    @ApiResponses({@ApiResponse(responseCode="200", description="조회 성공(접수 불가도 200의 reasonCode로 반환)"),
            @ApiResponse(responseCode="400", description="episodeId 형식 오류"),
            @ApiResponse(responseCode="404", description="작품 소유권 없음")})
    public CommonResponse<org.monitoring.catchholebackend.domain.validation.dto.response.ValidationEligibilityResponse> eligibility(@AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId, @RequestParam(required=false) UUID episodeId) {
        return CommonResponse.success(service.getEpisodeValidationEligibility(member.memberId(), workId, episodeId));
    }

    @GetMapping("/history")
    @Operation(operationId="listEpisodeValidationHistory", summary="회차 검수 이력", description="회차의 모든 검수 report를 최신 revision부터 요약합니다. 각 행은 실행 상태, 현재 항목 수, 검토 종료·회차 전체 확정 receipt, 자동 종료 보류, 무효화 시각과 파생 검수 단계(reviewStage)만 담고 인용·근거·판정 내용은 반환하지 않습니다. 다른 작품의 회차는 빈 페이지입니다. page는 0부터, size는 1~100입니다.")
    @ApiResponses({@ApiResponse(responseCode="200", description="조회 성공"),
            @ApiResponse(responseCode="400", description="페이지 값 오류 또는 episodeId 누락"),
            @ApiResponse(responseCode="404", description="작품 소유권 없음")})
    public CommonResponse<PageResponse<org.monitoring.catchholebackend.domain.validation.dto.response.ValidationReportHistoryResponse>> history(@AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,@RequestParam UUID episodeId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return CommonResponse.success(reviews.getValidationHistory(member.memberId(),workId,episodeId,page,size));
    }

    @PostMapping("/{reportId}/rechecks")
    @Operation(operationId="recheckEpisodeValidation", summary="수정 후 새 검수 접수", description="원고·기준이 달라진 최신 회차를 명시적으로 다시 접수합니다. 같은 입력의 실패 재시도와 구분하며 추출 Job·후보를 만들지 않습니다.")
    @ApiResponses({@ApiResponse(responseCode="200", description="새 검수 접수 또는 같은 요청 재전송"),
            @ApiResponse(responseCode="400", description="필드 검증 실패, 최신 활성 회차가 아니거나 진행 중 분석·원문 파기 대기가 있는 회차(VALIDATION_TARGET_INVALID)"),
            @ApiResponse(responseCode="404", description="작품 소유권 또는 검수 요청 없음"),
            @ApiResponse(responseCode="409", description="report version 충돌·현재 검수 아님, 원고·기준 버전 변경, 같은 입력(VALIDATION_RECHECK_INPUT_UNCHANGED), 정정 영수증 불일치, 같은 키 다른 입력")})
    public CommonResponse<ValidationStagingResponse> recheck(@AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,@PathVariable UUID reportId,@Valid @RequestBody ValidationRecheckRequest request) {
        return CommonResponse.success(rechecks.recheckValidation(member.memberId(),workId,reportId,request));
    }

    @GetMapping("/{reportId}/results")
    @Operation(operationId="getValidationExecutionResults",summary="검수 진행 및 저장 결과 읽기 전용 조회",description="전용 검사 실행의 단계·check·저장된 공개 결과를 읽기만 합니다. 실행 전이면 NOT_STARTED와 빈 목록입니다. 전체 근거 bundle·catalogue·checkpoint·호출 원장은 반환하지 않습니다.")
    @ApiResponses({@ApiResponse(responseCode="200", description="조회 성공"),
            @ApiResponse(responseCode="404", description="작품 소유권 또는 검수 요청 없음")})
    public CommonResponse<java.util.Map<String,Object>> results(@AuthenticationPrincipal MemberPrincipal member,@PathVariable UUID workId,@PathVariable UUID reportId) {
        return CommonResponse.success(results.getValidationProgress(member.memberId(),workId,reportId));
    }
    @PostMapping("/{reportId}/retries")
    @Operation(operationId="retryValidationExecution",summary="같은 입력의 미완료 검사 재시도",description="같은 원고·기준으로 중단(PARTIAL/FAILED)되었거나 아직 시작하지 않은 검사를 다시 queue합니다. 성공한 단계와 사용량은 보존하며 같은 키·같은 입력은 저장된 응답을 반환합니다. 원고가 바뀌었으면 재검수를 사용합니다.")
    @ApiResponses({@ApiResponse(responseCode="200", description="재시도 접수 또는 같은 요청 재전송"),
            @ApiResponse(responseCode="400", description="필드 검증 실패 또는 최신 활성 회차가 아님(VALIDATION_TARGET_INVALID)"),
            @ApiResponse(responseCode="404", description="작품 소유권 또는 검수 요청 없음"),
            @ApiResponse(responseCode="409", description="검수 실행 비활성, 이미 검토 완료, report version·원고 버전 충돌, 재시도할 수 없는 실행 상태, 실행 예산 부족, 같은 키 다른 입력")})
    public CommonResponse<ValidationStagingResponse> retry(@AuthenticationPrincipal MemberPrincipal member,@PathVariable UUID workId,@PathVariable UUID reportId,@Valid @RequestBody org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.Retry request) {
        return CommonResponse.success(workers.retryValidation(member.memberId(),workId,reportId,request));
    }

    @PostMapping("/{reportId}/corrections")
    @Operation(operationId="correctValidationBaselineSetting", summary="실제 설정 수정과 검수 기준 정정 연결", description="기준 항목·현재 버전·대상 경로를 검증해 기존 설정 편집과 정정 영수증을 원자적으로 저장합니다. 일반 저장을 직접 정정으로 추정하지 않습니다.")
    @ApiResponses({@ApiResponse(responseCode="200", description="정정 저장 또는 같은 요청 재전송"),
            @ApiResponse(responseCode="400", description="필드 검증 실패 또는 설정 편집 값 오류"),
            @ApiResponse(responseCode="404", description="작품 소유권·검수 요청·대상 설정 없음"),
            @ApiResponse(responseCode="409", description="report version 충돌·현재 검수 아님, 기준 항목·설정 버전을 정확히 연결할 수 없음(VALIDATION_CORRECTION_CONFLICT), 같은 키 다른 입력")})
    public CommonResponse<ValidationCorrectionReceipt> correct(@AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,@PathVariable UUID reportId,@Valid @RequestBody ValidationCorrectionRequest request) {
        return CommonResponse.success(corrections.correctSettingAndLinkBaseline(member.memberId(),workId,reportId,request));
    }

    @GetMapping("/{reportId}/findings")
    @Operation(operationId="listValidationFindings", summary="현재 발견 항목과 작가 판단 목록", description="현재 check revision의 설정 항목과 오탈자 제안만 생성 순서로 반환합니다. 판단을 저장한 적 없는 항목은 PENDING이며 근거 행은 상세에서만 제공합니다. 설정 항목은 고정 기준의 대상(subject)·설정명(settingName·settingKey)을 함께 반환합니다(#314 B2). 모든 필터는 서버에서 거르며 impact·subjectType 필터는 오탈자와 대상이 없는 항목을 제외합니다. 검토 요약(/review)의 집계는 필터와 무관합니다. page는 0부터, size는 1~100입니다.")
    @ApiResponses({@ApiResponse(responseCode="200", description="조회 성공"),
            @ApiResponse(responseCode="400", description="페이지·필터 값 오류(알 수 없는 branch·judgment·impact·subjectType 포함)"),
            @ApiResponse(responseCode="404", description="작품 소유권 또는 검수 요청 없음")})
    public CommonResponse<PageResponse<ValidationFindingSummaryResponse>> findings(@AuthenticationPrincipal MemberPrincipal member,@PathVariable UUID workId,@PathVariable UUID reportId,
            @RequestParam(required=false) ValidationBranch branch,@RequestParam(required=false) ValidationJudgmentValue judgment,@RequestParam(required=false) String outcome,
            @Parameter(in=ParameterIn.QUERY,name="impact",description="설정 항목의 영향 등급(SEVERE·CAUTION·REFERENCE). 지정하면 오탈자 제안은 제외됩니다.") @RequestParam(required=false) ValidationImpact impact,
            @Parameter(in=ParameterIn.QUERY,name="subjectType",description="설정 항목 대상 종류(CHARACTER·WORLD_SETTING). 지정하면 오탈자와 대상이 없는 설정 항목은 제외됩니다.") @RequestParam(required=false) ValidationSubjectType subjectType,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return CommonResponse.success(reviews.getValidationFindings(member.memberId(),workId,reportId,branch,judgment,outcome,impact,subjectType,page,size));
    }

    @GetMapping("/{reportId}/findings/{findingId}")
    @Operation(operationId="getValidationFinding", summary="발견 항목 상세", description="공개 판정 내용, 저장 때 검증한 인용, 현재 판단·허용 판단, 판단 이력과 이전 revision의 변경 사유를 반환합니다. 이전 revision 항목은 stale=true입니다. 설정 항목은 목록과 같은 대상·설정명과 정정 요청(POST /{reportId}/corrections)에 그대로 쓰는 고정 기준 항목(basis: entryRef·entryHash·대상 ID·당시 version, 맞는 slot/path)을 함께 반환합니다(#314 B2).")
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
    @Operation(operationId="getValidationReviewSummary", summary="작가 검토 요약", description="분기별 판단 수, 판단 잠금, 검토 종료 경로·보류 사유, 검토 종료 receipt와 설정 추출 상태·회차 전체 확정 결과를 반환합니다. 조회는 검토 종료·설정 추출·반영을 시작하지 않습니다.")
    @ApiResponses({@ApiResponse(responseCode="200", description="조회 성공"),
            @ApiResponse(responseCode="404", description="작품 소유권 또는 검수 요청 없음")})
    public CommonResponse<ValidationReviewSummaryResponse> review(@AuthenticationPrincipal MemberPrincipal member,@PathVariable UUID workId,@PathVariable UUID reportId) {
        return CommonResponse.success(reviews.getValidationReview(member.memberId(),workId,reportId));
    }

    @PostMapping("/{reportId}/review-completion")
    @Operation(operationId="completeValidationReview", summary="검토 완료 후 설정 추출", description="검사가 정상 완료되고 설정 항목은 모두 충돌 아님, 오탈자는 모두 확인·수정 대기 없음일 때 검토 종료 receipt와 설정 추출 Job을 함께 만듭니다. 마지막 판단 저장은 이 요청을 대신하지 않습니다. 같은 키·같은 입력은 같은 receipt를 반환합니다.")
    @ApiResponses({@ApiResponse(responseCode="200", description="검토 종료 또는 같은 요청 재전송"),
            @ApiResponse(responseCode="400", description="필드 검증 실패"),
            @ApiResponse(responseCode="404", description="작품 소유권 또는 검수 요청 없음"),
            @ApiResponse(responseCode="409", description="report version 충돌, 검토 종료 불가(context.holdReasons), 이미 종료, 같은 키 다른 입력, 원고 버전 변경 또는 추출 생성 조건 실패")})
    public CommonResponse<ValidationReviewCompletionResponse> completeReview(@AuthenticationPrincipal MemberPrincipal member,@PathVariable UUID workId,@PathVariable UUID reportId,
            @Valid @RequestBody ValidationReviewCompletionRequest request) {
        return CommonResponse.success("검토를 완료하고 설정 추출을 시작했습니다.",completions.completeValidationReview(member.memberId(),workId,reportId,request));
    }

    @PostMapping("/{reportId}/settings-application")
    @Operation(operationId="applyValidationSettings", summary="검토 완료 회차의 설정 전체 반영", description="검토를 완료한 회차의 설정 추출 결과를 다시 평가해 캐릭터·세계관 후보를 한 번에 반영합니다. 하나라도 반영할 수 없으면 아무것도 반영하지 않고 보류 receipt(HELD_REVIEW)를 200으로 반환합니다. 같은 키·같은 입력은 같은 receipt, 이미 반영한 회차는 다른 키로도 같은 APPLIED receipt를 반환합니다. 검수 회차의 개별·그룹 확정은 이 요청으로만 대신합니다.")
    @ApiResponses({@ApiResponse(responseCode="200", description="반영(APPLIED), 보류(HELD_REVIEW) 또는 같은 요청 재전송"),
            @ApiResponse(responseCode="400", description="필드 검증 실패"),
            @ApiResponse(responseCode="404", description="작품 소유권 또는 검수 요청 없음"),
            @ApiResponse(responseCode="409", description="추출·비교 진행 중(VALIDATION_APPLICATION_NOT_READY), 검토 종료 전이거나 현재 검수 아님, report version 충돌, 같은 키 다른 입력")})
    public CommonResponse<org.monitoring.catchholebackend.domain.validation.dto.response.ValidationSettingsApplicationResponse> applySettings(@AuthenticationPrincipal MemberPrincipal member,@PathVariable UUID workId,@PathVariable UUID reportId,
            @Valid @RequestBody org.monitoring.catchholebackend.domain.validation.dto.request.ValidationSettingsApplicationRequest request) {
        return CommonResponse.success("회차 설정 반영 결과를 저장했습니다.",applications.applySettings(member.memberId(),workId,reportId,request));
    }
}
