package org.monitoring.catchholebackend.domain.validation.dto.request;

import jakarta.validation.constraints.*;

/** 서버 실행이 고정한 식별값이다. 모델 tool 인자로 회원·작품·기준을 받지 않는다. */
public record ValidationDataRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedBaselineHash,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedTargetHash,
        @NotBlank @Pattern(regexp = "INPUT|ENTRIES|SUBJECTS|SETTINGS|READ_SOURCE|VALIDATE") String operation,
        @Min(0) int offset,
        @Min(1) @Max(100) int limit,
        String sourceRef,
        String expectedSourceHash,
        String expectedSourceVersion,
        @Min(0) Integer start,
        @Min(1) Integer end,
        /** SETTINGS 전용: 현재값을 읽을 고정 주체 참조(S1..). 최대 100개이며 다른 조회에서는 null이다. */
        @Size(max = 100) java.util.List<@Pattern(regexp = "S[1-9][0-9]*") String> subjectRefs
) {}
