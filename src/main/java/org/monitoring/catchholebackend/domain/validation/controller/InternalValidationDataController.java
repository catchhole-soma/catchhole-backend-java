package org.monitoring.catchholebackend.domain.validation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationDataRequest;
import org.monitoring.catchholebackend.domain.validation.service.ValidationDataService;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @SecurityRequirement(name = "internalApiKey")
@RequestMapping("/api/internal/v1/works/{workId}/validation-reports/{reportId}/data")
public class InternalValidationDataController {
    private final ValidationDataService service;
    private final ValidationDataMapper mapper;

    @PostMapping
    @Operation(summary = "고정 검수 기준과 원문 읽기", description = "내부 API key로 인증한 서버가 회원·작품·report와 예상 fingerprint를 고정합니다. 전용 Job이 있으면 해당 Job ID와 실제 claim lease를 읽기 전후 확인합니다. 추출·결과·lease를 이 경로에서 생성하지 않습니다.")
    public CommonResponse<Map<String, Object>> readValidationData(@RequestHeader("X-Validation-Member-Id") Long memberId,
            @PathVariable UUID workId, @PathVariable UUID reportId,
            @RequestHeader(value="X-Validation-Job-Id",required=false) UUID jobId,
            @RequestHeader(value="X-Worker-Lease-Token",required=false) UUID leaseToken,
            @Valid @RequestBody ValidationDataRequest request) {
        return CommonResponse.success(mapper.toTransport(service.readValidationData(memberId, workId, reportId, request, jobId, leaseToken)));
    }
}
