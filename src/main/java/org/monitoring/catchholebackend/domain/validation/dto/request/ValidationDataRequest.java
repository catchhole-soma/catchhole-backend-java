package org.monitoring.catchholebackend.domain.validation.dto.request;

import jakarta.validation.constraints.*;

/** 서버 실행이 고정한 식별값이다. 모델 tool 인자로 회원·작품·기준을 받지 않는다. */
public record ValidationDataRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedBaselineHash,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedTargetHash,
        @NotBlank @Pattern(regexp = "INPUT|ENTRIES|READ_SOURCE|VALIDATE") String operation,
        @Min(0) int offset,
        @Min(1) @Max(100) int limit,
        String sourceRef,
        String expectedSourceHash,
        String expectedSourceVersion,
        @Min(0) Integer start,
        @Min(1) Integer end
) {}
