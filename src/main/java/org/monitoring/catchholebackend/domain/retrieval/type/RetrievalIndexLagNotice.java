package org.monitoring.catchholebackend.domain.retrieval.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 검색 색인 지연 알림의 종류(#422). 어느 경우에 보내는지는 RetrievalIndexLagAlertScheduler가 정한다. */
@Getter
@RequiredArgsConstructor
public enum RetrievalIndexLagNotice {
    /** 지연 회차가 0개에서 1개 이상이 됐다. */
    STARTED("검색 색인 지연"),
    /** 직전 알림보다 지연 회차가 늘었고 반복 간격이 지났다. */
    GREW("검색 색인 지연 증가"),
    /** 지연이 이어진 채 직전 알림 뒤 24시간이 지났다. */
    REMINDER("검색 색인 지연 계속"),
    /** 지연 알림을 보낸 뒤 지연 회차가 0개가 됐다. */
    RESOLVED("검색 색인 지연 해소");

    private final String toKorean;
}
