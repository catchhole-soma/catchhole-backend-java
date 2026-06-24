package org.monitoring.catchholebackend.domain.work.type;

public enum WorkStatus {

    /**
     * 생성 직후와 복구 후의 기본 작품 상태.
     * Work.create()와 activate()가 이 상태로 둔다.
     */
    ACTIVE,

    /**
     * 작품을 일반 사용 흐름에서 제외하는 보관 상태.
     * archive()로 전환하지만, 현재 API는 hard delete를 사용하므로 보관/복구 정책은 후속 결정이 필요하다.
     */
    ARCHIVED
}
