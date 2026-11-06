package org.monitoring.catchholebackend.domain.validation.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationDataRequest;

public interface ValidationDataService {
    /** 실행 queue·retry 전에 저장 기준이 아직 현재 원고·출처·설정과 맞는지 확인한다. live report는 설정 fingerprint, legacy report는 저장 seed를 본다. */
    void validateValidationBaseline(Long memberId, UUID workId, UUID reportId);
    JsonNode readValidationData(Long memberId, UUID workId, UUID reportId, ValidationDataRequest request);
    JsonNode readValidationData(Long memberId, UUID workId, UUID reportId, ValidationDataRequest request, UUID jobId, UUID leaseToken);
}
