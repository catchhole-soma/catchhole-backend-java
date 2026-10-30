package org.monitoring.catchholebackend.domain.validation.service;

import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationReviewCompletionRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationReviewCompletionResponse;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationJob;

/**
 * GH312 S2 검토 종료와 설정 추출 release. receipt와 SETTING_EXTRACTION Job은 Work 잠금 아래 같은 트랜잭션에서 함께 만든다.
 * 작가 판단 저장은 이 서비스를 호출하지 않는다.
 */
public interface ValidationReviewCompletionService {
    /**
     * /complete가 terminal 상태를 정한 같은 트랜잭션에서 호출한다. 검토 gate가 AUTOMATIC_ELIGIBLE일 때만 receipt와 Job을 만들고
     * 그 밖의 상태는 아무것도 하지 않는다. 추출 생성 조건 오류는 그대로 던지므로 호출자가 트랜잭션 전체를 되돌린다.
     */
    Optional<ValidationReviewCompletionResponse> completeValidationReviewAutomatically(ValidationJob job);

    /** 판단을 모두 마친 report의 명시적 검토 완료. 같은 작품·키·입력은 같은 receipt를 반환한다. */
    ValidationReviewCompletionResponse completeValidationReview(Long memberId, UUID workId, UUID reportId, ValidationReviewCompletionRequest request);
}
