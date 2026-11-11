package org.monitoring.catchholebackend.domain.productusage.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.productusage.dto.ProductUsageCompletion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@Transactional
@DisplayName("삭제 뒤에도 보존하는 제품 사용 합계")
class ProductUsageAggregationIntegrationTest {
    @Autowired EntityManager em;
    @Autowired ProductUsageService service;
    final LocalDateTime start = LocalDateTime.of(2026, 11, 11, 0, 0);
    final UUID work = UUID.randomUUID();

    @BeforeEach void initialize() { service.initialize(start); }

    @Test @DisplayName("같은 회원의 요청은 각각 세되 재발견과 월간 회원은 중복되지 않는다")
    void deduplicatesReceiptsAndMembers() {
        var first = completion(1L, start.plusHours(1), start.plusHours(2));
        service.accept(first);
        service.accept(first);
        service.accept(completion(1L, start.plusHours(1), start.plusHours(3)));
        service.accept(completion(1L, start.plusHours(1), start.plusDays(1)));
        assertThat(total("product_usage_daily", "completed_requests", "2026-11-11")).isEqualTo(2);
        assertThat(total("product_usage_daily", "repeat_requests", "2026-11-11")).isEqualTo(1);
        assertThat(total("product_usage_daily", "members_1d", "2026-11-11")).isEqualTo(1);
        assertThat(total("product_usage_daily", "members_7d", "2026-11-12")).isEqualTo(1);
        assertThat(total("product_usage_monthly", "analysis_members", "2026-11-01")).isEqualTo(1);
        assertThat(total("product_usage_monthly", "completed_requests", "2026-11-01")).isEqualTo(3);
    }

    @Test @DisplayName("늦게 발견한 첫 완료는 이전 달의 불명 분류를 반복으로 바로잡는다")
    void correctsEarlierCompletionAcrossMonths() {
        service.accept(completion(1L, start.minusDays(3), LocalDateTime.of(2026, 12, 1, 1, 0)));
        assertThat(total("product_usage_monthly", "unknown_requests", "2026-12-01")).isEqualTo(1);
        service.accept(completion(1L, start.minusDays(3), LocalDateTime.of(2026, 11, 30, 23, 0)));
        assertThat(total("product_usage_monthly", "unknown_requests", "2026-12-01")).isZero();
        assertThat(total("product_usage_monthly", "repeat_requests", "2026-12-01")).isEqualTo(1);
        assertThat(total("product_usage_monthly", "unknown_requests", "2026-11-01")).isEqualTo(1);
        assertThat(total("product_usage_monthly", "analysis_members", "2026-12-01")).isEqualTo(1);
    }

    @Test @DisplayName("배포 전 완료는 기간 합계에서 빼고 이후 반복 판단에만 사용한다")
    void usesHistoricalCompletionOnlyAsBaseline() {
        service.accept(new ProductUsageCompletion(UUID.randomUUID(), work, 1L, start.minusDays(3),
                start.minusDays(2), start.minusDays(1)));
        service.accept(completion(1L, start.minusDays(3), start.plusHours(2)));
        assertThat(total("product_usage_daily", "completed_requests", "2026-11-10")).isZero();
        assertThat(total("product_usage_daily", "repeat_requests", "2026-11-11")).isEqualTo(1);
        assertThat(total("product_usage_daily", "unknown_requests", "2026-11-11")).isZero();
    }

    @Test @DisplayName("작품 삭제 뒤 다른 작품 완료도 반복이고 탈퇴 뒤에는 식별 기록 없이 창이 만료된다")
    void preservesAnonymousTotalsAfterDeletionAndExpiry() {
        service.accept(completion(1L, start.plusMinutes(1), start.plusHours(1)));
        service.purgeWork(work);
        service.accept(new ProductUsageCompletion(UUID.randomUUID(), UUID.randomUUID(), 1L, start.plusMinutes(1),
                start.plusHours(2), start.plusHours(3)));
        service.purgeMember(1L);
        em.flush();
        for (String table : new String[]{"product_usage_members", "product_usage_member_days", "product_usage_analysis_receipts"}) {
            assertThat(((Number) em.createNativeQuery("select count(*) from " + table).getSingleResult()).longValue()).isZero();
        }
        assertThat(total("product_usage_daily", "completed_requests", "2026-11-11")).isEqualTo(2);
        assertThat(total("product_usage_daily", "repeat_requests", "2026-11-11")).isEqualTo(1);
        assertThat(total("product_usage_daily", "members_7d", "2026-11-17")).isEqualTo(1);
        assertThat(total("product_usage_daily", "members_7d", "2026-11-18")).isZero();
        assertThat(total("product_usage_daily", "members_30d", "2026-12-10")).isEqualTo(1);
        assertThat(total("product_usage_daily", "members_30d", "2026-12-11")).isZero();
    }

    @Test @DisplayName("새 날짜가 기존 7일·30일 창과 겹쳐도 같은 회원을 두 번 늘리지 않는다")
    void extendsWindowOnlyOnNewCoverage() {
        service.accept(completion(1L, start.plusMinutes(1), start.plusHours(1)));
        service.accept(completion(1L, start.plusMinutes(1), start.plusDays(4)));
        service.accept(completion(2L, start.plusMinutes(1), start.plusDays(4)));
        assertThat(total("product_usage_daily", "members_7d", "2026-11-15")).isEqualTo(2);
        assertThat(total("product_usage_daily", "members_7d", "2026-11-21")).isEqualTo(2);
        assertThat(total("product_usage_daily", "members_7d", "2026-11-22")).isZero();
        assertThat(total("product_usage_monthly", "analysis_members", "2026-11-01")).isEqualTo(2);
    }

    @Test @DisplayName("초기화 재호출과 재시작은 수집 시작 시각을 바꾸지 않는다")
    void preservesStartTime() {
        service.initialize(start.plusDays(10));
        service.accept(completion(1L, start.plusMinutes(1), start.plusHours(1)));
        assertThat(total("product_usage_daily", "completed_requests", "2026-11-11")).isEqualTo(1);
    }

    private ProductUsageCompletion completion(Long memberId, LocalDateTime joined, LocalDateTime ready) {
        return new ProductUsageCompletion(UUID.randomUUID(), work, memberId, joined, start.plusMinutes(30), ready);
    }

    private long total(String table, String column, String date) {
        em.flush();
        var rows = em.createNativeQuery("select " + column + " from " + table + " where period_start = :day")
                .setParameter("day", LocalDate.parse(date)).getResultList();
        return rows.isEmpty() ? 0 : ((Number) rows.getFirst()).longValue();
    }
}
