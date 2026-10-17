package org.monitoring.catchholebackend.domain.relationship.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RelationshipErrorCode implements ResultCode {

    RELATIONSHIP_NOT_FOUND(HttpStatus.NOT_FOUND, "가족 관계를 찾을 수 없습니다."),
    RELATIONSHIP_CANDIDATE_NOT_FOUND(HttpStatus.NOT_FOUND, "가족 관계 후보를 찾을 수 없습니다."),
    RELATIONSHIP_INVALID_RELATIONSHIP(HttpStatus.BAD_REQUEST, "가족 관계의 대상 또는 유형이 올바르지 않습니다."),
    RELATIONSHIP_INVALID_SOURCE(HttpStatus.BAD_REQUEST, "현재 캐릭터 설정에 연결된 가족 관계 출처를 선택해 주세요."),
    RELATIONSHIP_STALE_SOURCE(HttpStatus.CONFLICT, "가족 관계의 바탕이 된 설정이 변경되었습니다. 최신 설정을 다시 확인해 주세요."),
    RELATIONSHIP_STALE_VERSION(HttpStatus.CONFLICT, "가족 관계 검토 내용이 변경되었습니다. 최신 내용을 다시 확인해 주세요."),
    RELATIONSHIP_INVALID_CANDIDATE_STATE(HttpStatus.CONFLICT, "현재 상태에서는 가족 관계 후보를 변경할 수 없습니다."),
    RELATIONSHIP_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "같은 요청 식별자에 다른 가족 관계 내용이 전달되었습니다."),
    RELATIONSHIP_REVIEW_REQUIRED(HttpStatus.CONFLICT, "가족 관계의 대상과 주장 내용을 직접 확인해 주세요."),
    RELATIONSHIP_ANCESTRY_CYCLE(HttpStatus.CONFLICT, "자신의 조상이 되는 부모·자식 관계는 확정할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
