package org.monitoring.catchholebackend.domain.retrieval.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.retrieval.mapper.RetrievalIndexLagAlertMapper;
import org.monitoring.catchholebackend.domain.retrieval.repository.RetrievalIndexLagAlertStateStore;
import org.monitoring.catchholebackend.domain.retrieval.repository.RetrievalIndexLagAlertStateStore.State;
import org.monitoring.catchholebackend.domain.retrieval.repository.RetrievalIndexLagRepository;
import org.monitoring.catchholebackend.domain.retrieval.repository.RetrievalIndexLagRepository.Snapshot;
import org.monitoring.catchholebackend.global.config.retrievalalert.RetrievalIndexLagAlertProperties;
import org.monitoring.catchholebackend.global.notification.DiscordWebhookClient;
import org.monitoring.catchholebackend.global.notification.DiscordWebhookClient.Delivery;

@DisplayName("검색 색인 지연 알림의 발송 정책")
class RetrievalIndexLagAlertSchedulerTest {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final UUID WORK_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID WORK_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    final RetrievalIndexLagRepository repository = mock(RetrievalIndexLagRepository.class);
    final DiscordWebhookClient webhook = mock(DiscordWebhookClient.class);
    final List<String> sent = new ArrayList<>();
    final FakeStateStore store = new FakeStateStore();
    final AdjustableClock clock = new AdjustableClock();
    RetrievalIndexLagAlertScheduler scheduler;

    @BeforeEach
    void setUp() {
        delivering(new Delivery(true, Duration.ZERO));
        scheduler = scheduler(webhook);
    }

    @Test
    @DisplayName("지연 회차가 0개에서 생기면 개수·대기 시간·작품 ID와 확인 안내를 보내고 기록한다")
    void notifiesWhenLagStarts() {
        lagging(5, 2, Duration.ofHours(3));

        scheduler.checkRetrievalIndexLag();

        assertThat(sent).singleElement().satisfies(message -> assertThat(message)
                .startsWith("⚠️ [운영] 검색 색인 지연 · 작품 2개 / 회차 5개\n")
                .contains("1시간이 넘도록", "가장 오래 기다린 회차: 3시간", WORK_A + ", " + WORK_B,
                        "catchhole-retrieval-backfill")
                .doesNotContain("증가", "회원"));
        assertThat(store.state).isEqualTo(new State(5, clock.current));
        verify(repository).read(LocalDateTime.of(2026, 11, 11, 11, 0), "v1", 3);
    }

    @Test
    @DisplayName("늘어난 지연은 직전 알림 뒤 1시간이 지나야 다시 보내고 같거나 줄어든 개수는 보내지 않는다")
    void notifiesGrowthOnlyAfterTheRepeatInterval() {
        Instant first = clock.current;
        store.state = new State(5, first);
        lagging(8, 2, Duration.ofHours(4));

        clock.advance(Duration.ofMinutes(59));
        scheduler.checkRetrievalIndexLag();
        assertThat(sent).isEmpty();
        assertThat(store.state).isEqualTo(new State(5, first));

        clock.advance(Duration.ofMinutes(1));
        scheduler.checkRetrievalIndexLag();
        assertThat(sent).singleElement().satisfies(message -> assertThat(message)
                .startsWith("⚠️ [운영] 검색 색인 지연 증가 · 작품 2개 / 회차 8개\n")
                .contains("직전 알림보다 늘었습니다: 5개 → 8개"));
        assertThat(store.state).isEqualTo(new State(8, clock.current));

        clock.advance(Duration.ofHours(2));
        lagging(8, 2, Duration.ofHours(6));
        scheduler.checkRetrievalIndexLag();
        lagging(3, 1, Duration.ofHours(6));
        scheduler.checkRetrievalIndexLag();
        assertThat(sent).hasSize(1);
    }

    @Test
    @DisplayName("지연이 이어지면 늘지 않아도 직전 알림 뒤 24시간마다 다시 알린다")
    void remindsDailyWhileLagging() {
        Instant first = clock.current;
        store.state = new State(8, first);
        lagging(3, 1, Duration.ofDays(2));

        clock.advance(Duration.ofHours(23).plusMinutes(59));
        scheduler.checkRetrievalIndexLag();
        assertThat(sent).isEmpty();

        clock.advance(Duration.ofMinutes(1));
        scheduler.checkRetrievalIndexLag();
        assertThat(sent).singleElement().satisfies(message -> assertThat(message)
                .startsWith("⚠️ [운영] 검색 색인 지연 계속 · 작품 1개 / 회차 3개\n")
                .contains("가장 오래 기다린 회차: 3일")); // 고정한 원고 시각에서 2일 + 그 뒤 하루
        assertThat(store.state).isEqualTo(new State(3, clock.current));
    }

    @Test
    @DisplayName("지연 알림 뒤 0개가 되면 해소를 한 번만 알리고 지연이 없던 상태의 0개는 보내지 않는다")
    void notifiesResolutionOnce() {
        store.state = new State(8, clock.current);
        lagging(0, 0, null);
        clock.advance(Duration.ofMinutes(10));

        scheduler.checkRetrievalIndexLag();
        clock.advance(Duration.ofMinutes(10));
        scheduler.checkRetrievalIndexLag();

        assertThat(sent).singleElement().satisfies(message -> assertThat(message)
                .isEqualTo("✅ [운영] 검색 색인 지연 해소\n기준 1시간을 넘겨 검색 색인이 없는 회차가 이제 없습니다.\n"));
        assertThat(store.state.notifiedCount()).isZero();

        store.state = State.NONE;
        scheduler.checkRetrievalIndexLag();
        assertThat(sent).hasSize(1);
    }

    @Test
    @DisplayName("Discord 발송이 실패하면 기록하지 않아 다음 확인에서 다시 보낸다")
    void failedDeliveryIsNotRecorded() {
        lagging(5, 2, Duration.ofHours(3));
        delivering(new Delivery(false, Duration.ZERO));

        scheduler.checkRetrievalIndexLag();
        assertThat(sent).hasSize(1);
        assertThat(store.state).isEqualTo(State.NONE);

        delivering(new Delivery(true, Duration.ZERO));
        clock.advance(Duration.ofMinutes(10));
        scheduler.checkRetrievalIndexLag();
        assertThat(sent).hasSize(2);
        assertThat(store.state).isEqualTo(new State(5, clock.current));
    }

    @Test
    @DisplayName("웹훅이 요구한 대기 시간 동안은 조회도 발송도 하지 않고 그 뒤에 다시 보낸다")
    void honoursTheWebhookPause() {
        lagging(5, 2, Duration.ofHours(3));
        delivering(new Delivery(false, Duration.ofHours(1)));
        scheduler.checkRetrievalIndexLag();

        clock.advance(Duration.ofMinutes(50));
        scheduler.checkRetrievalIndexLag();
        assertThat(sent).hasSize(1);

        delivering(new Delivery(true, Duration.ZERO));
        clock.advance(Duration.ofMinutes(10));
        scheduler.checkRetrievalIndexLag();
        assertThat(sent).hasSize(2);
        assertThat(store.state.notifiedCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("DB·Redis·Discord 오류는 스케줄러 밖으로 던지지 않고 기록도 바꾸지 않는다")
    void neverThrowsOutOfTheScheduler() {
        doThrow(new IllegalStateException("database unavailable")).when(repository).read(any(), anyString(), anyInt());
        assertThatCode(scheduler::checkRetrievalIndexLag).doesNotThrowAnyException();

        lagging(5, 2, Duration.ofHours(3));
        store.failRead = true;
        assertThatCode(scheduler::checkRetrievalIndexLag).doesNotThrowAnyException();
        store.failRead = false;
        assertThat(sent).isEmpty();

        doThrow(new IllegalStateException("unexpected")).when(webhook).send(anyString());
        assertThatCode(scheduler::checkRetrievalIndexLag).doesNotThrowAnyException();
        assertThat(store.state).isEqualTo(State.NONE);

        delivering(new Delivery(true, Duration.ZERO));
        store.failWrite = true;
        assertThatCode(scheduler::checkRetrievalIndexLag).doesNotThrowAnyException();
        assertThat(sent).hasSize(1);
        assertThat(store.state).isEqualTo(State.NONE);
    }

    @Test
    @DisplayName("웹훅이 없으면 조회·기록·발송을 하지 않는다")
    void staysInertWithoutWebhook() {
        RetrievalIndexLagAlertScheduler inert = scheduler(null);

        inert.checkRetrievalIndexLag();

        verifyNoInteractions(repository);
        assertThat(store.reads).isZero();
        verify(webhook, never()).send(anyString());
    }

    private RetrievalIndexLagAlertScheduler scheduler(DiscordWebhookClient client) {
        return new RetrievalIndexLagAlertScheduler(repository, store, new RetrievalIndexLagAlertMapper(), client, "prod",
                "v1", new RetrievalIndexLagAlertProperties(Duration.ofMinutes(60), Duration.ofHours(1)), clock);
    }

    private void lagging(long episodes, long works, Duration oldestWait) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.current, KST);
        doReturn(new Snapshot(episodes, works, oldestWait == null ? null : now.minus(oldestWait),
                episodes == 0 ? List.of() : works == 1 ? List.of(WORK_A) : List.of(WORK_A, WORK_B)))
                .when(repository).read(any(), anyString(), anyInt());
    }

    private void delivering(Delivery delivery) {
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return delivery;
        }).when(webhook).send(anyString());
    }

    private static final class FakeStateStore extends RetrievalIndexLagAlertStateStore {
        State state = State.NONE;
        boolean failRead, failWrite;
        int reads;

        FakeStateStore() { super(null); }

        @Override public State read() {
            reads++;
            if (failRead) throw new IllegalStateException("redis unavailable");
            return state;
        }

        @Override public void write(State next) {
            if (failWrite) throw new IllegalStateException("redis unavailable");
            state = next;
        }
    }

    private static final class AdjustableClock extends Clock {
        Instant current = Instant.parse("2026-11-11T03:00:00Z");
        void advance(Duration duration) { current = current.plus(duration); }
        @Override public ZoneId getZone() { return KST; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
