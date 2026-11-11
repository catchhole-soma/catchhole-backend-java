package org.monitoring.catchholebackend.global.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.monitoring.catchholebackend.domain.productusage.dto.ProductUsageCompletion;
import org.monitoring.catchholebackend.domain.productusage.service.ProductUsageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test") @Transactional
@DisplayName("영속 제품 지표의 기간별 읽기")
class ProductUsageSnapshotIntegrationTest {
    @Autowired ProductUsageService usage;
    @Autowired ProductUsageSnapshotRepository repository;

    @Test @DisplayName("시작 전에는 수집되지 않은 기간을 정상 0으로 만들지 않는다")
    void doesNotInventDataBeforeActivation() {
        var snapshot = repository.read(LocalDateTime.of(2026, 11, 11, 12, 0));
        assertThat(snapshot.startedAt()).isNull();
        assertThat(snapshot.days()).isEmpty();
        assertThat(snapshot.months()).isEmpty();
    }

    @Test @DisplayName("미래 활동 창은 숨기고 오늘 및 이번 달의 서로 다른 회원을 읽는다")
    void readsCurrentPeriodsWithoutFutureProjections() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 11, 12, 0);
        LocalDateTime now = LocalDateTime.of(2026, 11, 12, 23, 0);
        usage.initialize(start);
        UUID work = UUID.randomUUID();
        usage.accept(new ProductUsageCompletion(UUID.randomUUID(), work, 1L, start.plusMinutes(1),
                start.plusMinutes(2), start.plusHours(1)));
        usage.accept(new ProductUsageCompletion(UUID.randomUUID(), work, 1L, start.plusMinutes(1),
                start.plusMinutes(2), now.minusHours(1)));
        usage.collect(now);
        var snapshot = repository.read(now);
        assertThat(snapshot.startedAt()).isEqualTo(start);
        assertThat(snapshot.collectedAt()).isEqualTo(now);
        assertThat(snapshot.days()).hasSize(2);
        assertThat(snapshot.days().getLast().date()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(snapshot.days().getLast().members1d()).isEqualTo(1);
        assertThat(snapshot.days().getLast().members7d()).isEqualTo(1);
        assertThat(snapshot.days().getLast().members30d()).isEqualTo(1);
        assertThat(snapshot.months()).singleElement().satisfies(month -> {
            assertThat(month.analysisMembers()).isEqualTo(1);
            assertThat(month.completedRequests()).isEqualTo(2);
            assertThat(month.repeatRequests()).isEqualTo(1);
        });
    }

    @Test @DisplayName("관측 기간 안의 활동 없음은 0이며 날짜 60개와 달 12개로 노출을 제한한다")
    void boundsHistoryAndFillsOnlyObservedEmptyPeriods() {
        usage.initialize(LocalDateTime.of(2025, 1, 1, 0, 0));
        LocalDateTime now = LocalDateTime.of(2026, 11, 11, 12, 0);
        usage.collect(now);
        var snapshot = repository.read(now);
        assertThat(snapshot.days()).hasSize(60);
        assertThat(snapshot.days().getFirst().date()).isEqualTo(LocalDate.of(2026, 9, 13));
        assertThat(snapshot.months()).hasSize(12);
        assertThat(snapshot.months().getFirst().month()).isEqualTo(LocalDate.of(2025, 12, 1));
        assertThat(snapshot.days().getLast().completedRequests()).isZero();
    }

    @Test @DisplayName("잠금을 늦게 얻은 이전 시각의 수집은 성공 시각을 뒤로 돌리지 않는다")
    void doesNotRegressCollectionTime() {
        usage.initialize(LocalDateTime.of(2026, 11, 11, 0, 0));
        LocalDateTime latest = LocalDateTime.of(2026, 11, 11, 12, 0);
        usage.collect(latest);
        usage.collect(latest.minusSeconds(1));
        assertThat(repository.read(latest).collectedAt()).isEqualTo(latest);
    }
}
