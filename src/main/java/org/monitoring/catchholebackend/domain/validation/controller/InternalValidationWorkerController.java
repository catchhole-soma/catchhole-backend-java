package org.monitoring.catchholebackend.domain.validation.controller;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.*;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.*;
import org.monitoring.catchholebackend.domain.validation.service.*;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @SecurityRequirement(name="internalApiKey")
@RequestMapping("/api/internal/v1/validation-jobs")
@Tag(name="ValidationWorker",description="검수 전용 Job·lease·의미 단계·완료 기록·사용량. 추출과 작가 판단은 생성하지 않습니다.")
@ApiResponses({@ApiResponse(responseCode="200",description="실행 scope 또는 저장 영수증"),@ApiResponse(responseCode="400",description="필드·필수 처리 범위·원문 인용 검증 실패"),@ApiResponse(responseCode="401",description="내부 API key 인증 실패"),@ApiResponse(responseCode="404",description="검수 Job 또는 원장 없음"),@ApiResponse(responseCode="409",description="lease·입력·revision·멱등 키 충돌 또는 실행/계정 예산 부족")})
public class InternalValidationWorkerController {
    private final ValidationWorkerService workers;
    private final ValidationResultService results;
    private final ValidationUsageService usage;
    @PostMapping("/claim") @Operation(operationId="claimValidationJob",summary="준비된 검수 원자 선점",description="v1 scope와 실제 Job 소유권을 검증합니다. 필드·예시는 docs/contracts/validation-execution-v1.json을 따릅니다.") public CommonResponse<Map<String,Object>> claimValidationJob(@Valid @RequestBody Claim c) {return CommonResponse.success(workers.claimValidationJob(c));}
    @PostMapping("/{jobId}/heartbeat") @Operation(operationId="heartbeatValidationJob",summary="검수 lease 연장",description="v1 scope와 실제 Job 소유권을 검증합니다. 필드·예시는 docs/contracts/validation-execution-v1.json을 따릅니다.") public CommonResponse<Map<String,Object>> heartbeatValidationJob(@PathVariable UUID jobId,@RequestHeader("X-Worker-Lease-Token") UUID lease,@Valid @RequestBody Lease c) {return CommonResponse.success(workers.heartbeatValidationJob(jobId,lease,c));}
    @GetMapping("/{jobId}/checkpoint") @Operation(operationId="loadValidationCheckpoint",summary="현재 lease의 저장 단계와 체크포인트 조회",description="v1 scope와 실제 Job 소유권을 검증합니다. 필드·예시는 docs/contracts/validation-execution-v1.json을 따릅니다.") public CommonResponse<Map<String,Object>> loadValidationCheckpoint(@PathVariable UUID jobId,@RequestHeader("X-Worker-Lease-Token") UUID lease) {return CommonResponse.success(workers.loadValidationCheckpoint(jobId,lease));}
    @PostMapping("/{jobId}/plans") @Operation(operationId="publishValidationPlan",summary="검증된 계획 단계와 안정 검사 ID 저장",description="v1 scope와 실제 Job 소유권을 검증합니다. 필드·예시는 docs/contracts/validation-execution-v1.json을 따릅니다.") public CommonResponse<Map<String,Object>> publishValidationPlan(@PathVariable UUID jobId,@RequestHeader("X-Worker-Lease-Token") UUID lease,@Valid @RequestBody Publish c) {return CommonResponse.success(results.publishValidationPlan(jobId,lease,c));}
    @PostMapping("/{jobId}/units") @Operation(operationId="publishValidationUnit",summary="현재 검사 revision 또는 오탈자 구간 게시",description="v1 scope와 실제 Job 소유권을 검증합니다. 필드·예시는 docs/contracts/validation-execution-v1.json을 따릅니다.") public CommonResponse<Map<String,Object>> publishValidationUnit(@PathVariable UUID jobId,@RequestHeader("X-Worker-Lease-Token") UUID lease,@Valid @RequestBody Unit c) {return CommonResponse.success(results.publishValidationUnit(jobId,lease,c));}
    @PostMapping("/{jobId}/revisions") @Operation(operationId="reviseValidationChecks",summary="새 단서가 영향을 주는 질문 revision 원자 변경",description="v1 scope와 실제 Job 소유권을 검증합니다. 필드·예시는 docs/contracts/validation-execution-v1.json을 따릅니다.") public CommonResponse<Map<String,Object>> reviseValidationChecks(@PathVariable UUID jobId,@RequestHeader("X-Worker-Lease-Token") UUID lease,@Valid @RequestBody Revise c) {return CommonResponse.success(results.reviseValidationChecks(jobId,lease,c));}
    @PostMapping("/{jobId}/checkpoint") @Operation(operationId="publishValidationCheckpoint",summary="독립 분기의 의미 상태 체크포인트 저장",description="v1 scope와 실제 Job 소유권을 검증합니다. 필드·예시는 docs/contracts/validation-execution-v1.json을 따릅니다.") public CommonResponse<Map<String,Object>> publishValidationCheckpoint(@PathVariable UUID jobId,@RequestHeader("X-Worker-Lease-Token") UUID lease,@Valid @RequestBody Checkpoint c) {return CommonResponse.success(results.publishValidationCheckpoint(jobId,lease,c));}
    @PostMapping("/{jobId}/complete") @Operation(operationId="completeValidationJob",summary="서버의 전체 필수 처리 집계로 실행 종료",description="v1 scope와 실제 Job 소유권을 검증합니다. 필드·예시는 docs/contracts/validation-execution-v1.json을 따릅니다.") public CommonResponse<Map<String,Object>> completeValidationJob(@PathVariable UUID jobId,@RequestHeader("X-Worker-Lease-Token") UUID lease,@Valid @RequestBody Complete c) {return CommonResponse.success(results.completeValidationJob(jobId,lease,c));}
    @PostMapping("/{jobId}/usage/reserve") @Operation(operationId="reserveValidationUsage",summary="실제 검수 Job의 역할별 사용량 예약",description="v1 scope와 실제 Job 소유권을 검증합니다. 필드·예시는 docs/contracts/validation-execution-v1.json을 따릅니다.") public CommonResponse<Map<String,Object>> reserveValidationUsage(@PathVariable UUID jobId,@RequestHeader("X-Worker-Lease-Token") UUID lease,@Valid @RequestBody Reserve c) {return CommonResponse.success(usage.reserveValidationUsage(jobId,lease,c));}
    @PostMapping("/{jobId}/usage/{requestId}") @Operation(operationId="reportValidationUsage",summary="원래 호출 소유권으로 사용량 정산 또는 해제",description="v1 scope와 실제 Job 소유권을 검증합니다. 필드·예시는 docs/contracts/validation-execution-v1.json을 따릅니다.") public CommonResponse<Map<String,Object>> reportValidationUsage(@PathVariable UUID jobId,@PathVariable UUID requestId,@RequestHeader("X-Worker-Lease-Token") UUID lease,@Valid @RequestBody Usage c) {return CommonResponse.success(usage.reportValidationUsage(jobId,lease,requestId,c));}
}
