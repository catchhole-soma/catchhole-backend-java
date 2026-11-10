package org.monitoring.catchholebackend.domain.episode.type;

public enum EpisodeNumberDetectionStatus {
    /** 파일명 또는 본문 제목 행에서 회차 번호를 하나로 확정했다. */
    DETECTED,
    /** 파일명과 본문 제목 행 모두에서 회차 번호를 찾지 못했다. */
    REQUIRES_INPUT,
    /** 파일명과 본문 제목 행의 회차 번호가 서로 다르다. 어느 쪽도 고르지 않는다. */
    NUMBER_CONFLICT
}
