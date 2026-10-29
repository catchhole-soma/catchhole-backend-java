package org.monitoring.catchholebackend.domain.validation.type;
/**
 * 회차 전체 확정 receipt 상태(GH312 S3). HELD_REVIEW는 이번 시도에서 어떤 확정 설정도 쓰지 않았다는 뜻이다.
 * APPLIED는 캐릭터·세계관 두 도메인이 한 트랜잭션에서 모두 성공한 뒤에만 기록한다. FAILED는 DB CHECK에만 예약했으며 S3는 쓰지 않는다.
 */
public enum ValidationApplicationStatus { HELD_REVIEW, APPLIED, FAILED }
