package org.monitoring.catchholebackend.domain.productusage.event;

import java.util.UUID;

/** 원문을 무효화하기 전 같은 트랜잭션에서 이미 준비된 결과를 정산한다. */
public record ProductUsageBeforeWorkChange(UUID workId) {}
