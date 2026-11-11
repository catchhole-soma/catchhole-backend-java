package org.monitoring.catchholebackend.global.monitoring;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.productusage.entity.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository @RequiredArgsConstructor
public class ProductUsageSnapshotRepository {
    private final EntityManager em;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductUsageSnapshot read(LocalDateTime asOf) {
        var state = em.find(ProductUsageState.class, 1);
        if (state == null || state.getStartedAt() == null || state.getStartedAt().isAfter(asOf)) {
            return new ProductUsageSnapshot(asOf, null, null, List.of(), List.of());
        }
        LocalDate today = asOf.toLocalDate();
        LocalDate fromDay = later(today.minusDays(59), state.getStartedAt().toLocalDate());
        LocalDate currentMonth = today.withDayOfMonth(1);
        LocalDate fromMonth = later(currentMonth.minusMonths(11), state.getStartedAt().toLocalDate().withDayOfMonth(1));
        var daily = new HashMap<LocalDate, ProductUsageSnapshot.Day>();
        for (var row : em.createQuery("select d from ProductUsageDaily d where d.date between :from and :to", ProductUsageDaily.class)
                .setParameter("from", fromDay).setParameter("to", today).getResultList()) {
            daily.put(row.getDate(), new ProductUsageSnapshot.Day(row.getDate(), row.getMembers1d(), row.getMembers7d(),
                    row.getMembers30d(), row.getCompletedRequests(), row.getRepeatRequests(), row.getUnknownRequests(), row.getWorksCreated()));
        }
        var monthly = new HashMap<LocalDate, ProductUsageSnapshot.Month>();
        for (var row : em.createQuery("select m from ProductUsageMonthly m where m.month between :from and :to", ProductUsageMonthly.class)
                .setParameter("from", fromMonth).setParameter("to", currentMonth).getResultList()) {
            monthly.put(row.getMonth(), new ProductUsageSnapshot.Month(row.getMonth(), row.getAnalysisMembers(),
                    row.getCompletedRequests(), row.getRepeatRequests(), row.getUnknownRequests(), row.getWorksCreated()));
        }
        var days = new ArrayList<ProductUsageSnapshot.Day>();
        for (LocalDate day = fromDay; !day.isAfter(today); day = day.plusDays(1)) {
            days.add(daily.getOrDefault(day, ProductUsageSnapshot.Day.empty(day)));
        }
        var months = new ArrayList<ProductUsageSnapshot.Month>();
        for (LocalDate month = fromMonth; !month.isAfter(currentMonth); month = month.plusMonths(1)) {
            months.add(monthly.getOrDefault(month, ProductUsageSnapshot.Month.empty(month)));
        }
        return new ProductUsageSnapshot(asOf, state.getStartedAt(), state.getCollectedAt(), days, months);
    }
    private LocalDate later(LocalDate first, LocalDate second) { return first.isAfter(second) ? first : second; }
}
