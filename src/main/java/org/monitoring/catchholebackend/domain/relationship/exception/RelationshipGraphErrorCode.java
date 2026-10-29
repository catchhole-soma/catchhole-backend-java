package org.monitoring.catchholebackend.domain.relationship.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.global.exception.ResultCode;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RelationshipGraphErrorCode implements ResultCode {
    RELATIONSHIP_GRAPH_INVALID_INPUT(HttpStatus.BAD_REQUEST, "관계 분석의 입력 또는 결정이 올바르지 않습니다."),
    RELATIONSHIP_GRAPH_CONFLICT(HttpStatus.CONFLICT, "관계 분석의 입력 또는 버전이 변경되었습니다."),
    RELATIONSHIP_GRAPH_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 회차의 관계를 찾을 수 없습니다."),
    RELATIONSHIP_GRAPH_INCOMPLETE(HttpStatus.CONFLICT, "관계 분석을 먼저 마무리해 주세요.");
    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
