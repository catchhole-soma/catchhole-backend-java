package org.monitoring.catchholebackend.domain.retrieval.repository;

import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 마지막으로 보낸 검색 색인 지연 알림(회차 수·시각)을 Redis key 하나에 둔다(#422).
 * API 재배포마다 같은 알림을 다시 보내지 않기 위함이다. 운영 Redis는 비영속이라 Redis 재시작 뒤에는
 * 기록 없음(0개)으로 읽혀 지연 알림이 한 번 다시 갈 수 있다.
 */
@Component
@RequiredArgsConstructor
public class RetrievalIndexLagAlertStateStore {
    static final String KEY = "retrieval-index-lag-alert:last-notified";
    // 지연 중에는 24시간마다 다시 쓰므로 만료되지 않는다. 해소 기록(0개)은 만료돼도 기록 없음과 같다.
    static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    /** notifiedCount=0이면 지연 알림이 나가 있지 않은 상태다(해소 알림을 보냈거나 기록 없음). */
    public record State(long notifiedCount, Instant notifiedAt) {
        public static final State NONE = new State(0, Instant.EPOCH);
    }

    public State read() {
        String value = redis.opsForValue().get(KEY);
        if (value == null) return State.NONE;
        try {
            String[] parts = value.split(":", -1);
            long count = Long.parseLong(parts[0]);
            if (parts.length != 2 || count < 0) return State.NONE;
            return new State(count, Instant.ofEpochMilli(Long.parseLong(parts[1])));
        } catch (RuntimeException ex) {
            // 알아볼 수 없는 값은 기록 없음으로 본다. 지연 중이면 알림이 한 번 다시 갈 뿐 침묵하지 않는다.
            return State.NONE;
        }
    }

    /** 값 하나를 한 번에 써서 두 인스턴스가 동시에 써도 반쯤 쓴 상태가 남지 않는다. */
    public void write(State state) {
        redis.opsForValue().set(KEY, state.notifiedCount() + ":" + state.notifiedAt().toEpochMilli(), TTL);
    }
}
