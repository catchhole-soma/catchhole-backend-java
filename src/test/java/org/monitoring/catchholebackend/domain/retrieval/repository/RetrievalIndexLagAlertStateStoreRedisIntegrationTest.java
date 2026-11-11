package org.monitoring.catchholebackend.domain.retrieval.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.retrieval.repository.RetrievalIndexLagAlertStateStore.State;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Redis 검색 색인 지연 알림 기록")
class RetrievalIndexLagAlertStateStoreRedisIntegrationTest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.10-alpine3.21")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private RetrievalIndexLagAlertStateStore store;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        store = new RetrievalIndexLagAlertStateStore(redis);
    }

    @Test
    @DisplayName("기록이 없으면 지연 알림이 없는 상태로 읽고, 쓴 값은 만료 시간과 함께 다시 읽힌다")
    void roundTripsTheLastNotification() {
        assertThat(store.read()).isEqualTo(State.NONE);

        State state = new State(1294, Instant.parse("2026-11-11T03:00:00.123Z"));
        store.write(state);

        assertThat(store.read()).isEqualTo(state);
        assertThat(redis.getExpire(RetrievalIndexLagAlertStateStore.KEY))
                .isPositive().isLessThanOrEqualTo(RetrievalIndexLagAlertStateStore.TTL.toSeconds());
    }

    @Test
    @DisplayName("알아볼 수 없는 값은 기록 없음으로 읽어 지연 알림이 침묵하지 않게 한다")
    void unreadableValueReadsAsNone() {
        for (String value : new String[] {"garbage", "5", "-1:0", "5:abc", "5:1:2"}) {
            redis.opsForValue().set(RetrievalIndexLagAlertStateStore.KEY, value);
            assertThat(store.read()).as(value).isEqualTo(State.NONE);
        }
    }
}
