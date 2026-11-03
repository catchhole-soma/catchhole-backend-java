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
    @Operation(summary = "고정 검수 기준과 원문 읽기", description = "내부 API key로 인증한 서버가 회원·작품·report와 예상 fingerprint를 고정합니다. 전용 Job이 있으면 해당 Job ID와 실제 claim lease를 읽기 전후 확인합니다. operation은 INPUT(고정 출처 목록), SUBJECTS(주체 이름 목록, 한 번에 전체), SETTINGS(subjectRefs 최대 100개 주체의 현재값 행, 세계관 이력 제외), READ_SOURCE(원문 전체·범위), VALIDATE(출처 현재성 확인), ENTRIES(이전 계약 호환용 전체 항목 페이지)입니다. 추출·결과·lease를 이 경로에서 생성하지 않습니다.")
    public CommonResponse<Map<String, Object>> readValidationData(@RequestHeader("X-Validation-Member-Id") Long memberId,
            @PathVariable UUID workId, @PathVariable UUID reportId,
            @RequestHeader(value="X-Validation-Job-Id",required=false) UUID jobId,
            @RequestHeader(value="X-Worker-Lease-Token",required=false) UUID leaseToken,
            @Valid @RequestBody ValidationDataRequest request) {
        return CommonResponse.success(mapper.toTransport(service.readValidationData(memberId, workId, reportId, request, jobId, leaseToken)));
    }
}
