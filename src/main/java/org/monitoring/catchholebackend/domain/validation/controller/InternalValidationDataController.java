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
    @Operation(summary = "고정 검수 기준과 원문 읽기", description = "내부 API key로 인증한 서버가 회원·작품·report와 예상 fingerprint를 고정합니다. 추출·검토 완료·결과 저장 또는 Worker lease를 생성하지 않습니다.")
    public CommonResponse<Map<String, Object>> readValidationData(@RequestHeader("X-Validation-Member-Id") Long memberId,
            @PathVariable UUID workId, @PathVariable UUID reportId, @Valid @RequestBody ValidationDataRequest request) {
        return CommonResponse.success(mapper.toTransport(service.readValidationData(memberId, workId, reportId, request)));
    }
}
