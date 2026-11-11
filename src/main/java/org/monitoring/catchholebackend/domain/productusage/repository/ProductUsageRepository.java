package org.monitoring.catchholebackend.domain.productusage.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.productusage.dto.ProductUsageCompletion;
import org.monitoring.catchholebackend.domain.productusage.entity.*;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.springframework.stereotype.Repository;

@Repository @RequiredArgsConstructor
public class ProductUsageRepository {
    private final EntityManager em;
    public ProductUsageState state() { return em.find(ProductUsageState.class, 1); }
    public void recordWork(Work work) {
        if (em.find(ProductUsageWorkReceipt.class, work.getId()) == null) em.persist(new ProductUsageWorkReceipt(work));
    }
    public List<ProductUsageWorkReceipt> pendingWorks(UUID workId) {
        var query = em.createQuery("select r from ProductUsageWorkReceipt r where r.processed = false"
                + (workId == null ? "" : " and r.workId = :work"), ProductUsageWorkReceipt.class);
        if (workId != null) query.setParameter("work", workId);
        return query.getResultList();
    }
    public ProductUsageState lockState() {
        ProductUsageState state = em.find(ProductUsageState.class, 1, LockModeType.PESSIMISTIC_WRITE);
        if (state == null) {
            // 운영은 migration이 singleton을 만든다. create-drop 테스트도 같은 저장 경계를 사용한다.
            state = new ProductUsageState(); em.persist(state); em.flush();
        }
        return state;
    }
    public ProductUsageDaily daily(LocalDate day) {
        ProductUsageDaily row = em.find(ProductUsageDaily.class, day);
        if (row == null) { row = new ProductUsageDaily(day); em.persist(row); }
        return row;
    }
    public ProductUsageMonthly monthly(LocalDate day) {
        LocalDate month = day.withDayOfMonth(1);
        ProductUsageMonthly row = em.find(ProductUsageMonthly.class, month);
        if (row == null) { row = new ProductUsageMonthly(month); em.persist(row); }
        return row;
    }
    public ProductUsageMember member(Long id, boolean priorHistoryUnknown) {
        ProductUsageMember row = em.find(ProductUsageMember.class, id);
        if (row == null) { row = new ProductUsageMember(id, priorHistoryUnknown); em.persist(row); }
        return row;
    }
    public boolean recorded(UUID requestId) {
        return em.find(ProductUsageAnalysisReceipt.class, requestId) != null;
    }
    public void record(ProductUsageCompletion event) { em.persist(new ProductUsageAnalysisReceipt(event)); }
    public List<LocalDate> activityDates(Long memberId, LocalDate day) {
        LocalDate from = day.minusDays(29).isBefore(day.withDayOfMonth(1))
                ? day.minusDays(29) : day.withDayOfMonth(1);
        LocalDate to = day.plusDays(29).isAfter(day.withDayOfMonth(day.lengthOfMonth()))
                ? day.plusDays(29) : day.withDayOfMonth(day.lengthOfMonth());
        return em.createQuery("select a.activityDate from ProductUsageMemberDay a where a.memberId = :id "
                + "and a.activityDate between :from and :to", LocalDate.class)
                .setParameter("id", memberId).setParameter("from", from).setParameter("to", to).getResultList();
    }
    public void recordActivity(Long memberId, LocalDate day) { em.persist(new ProductUsageMemberDay(memberId, day)); }
    public void purgeWork(UUID workId) {
        em.flush();
        em.createQuery("delete from ProductUsageAnalysisReceipt r where r.workId = :id")
                .setParameter("id", workId).executeUpdate();
        em.createQuery("delete from ProductUsageWorkReceipt r where r.workId = :id")
                .setParameter("id", workId).executeUpdate();
    }
    public void purgeMember(Long memberId) {
        em.flush();
        for (String entity : List.of("ProductUsageWorkReceipt", "ProductUsageAnalysisReceipt", "ProductUsageMemberDay", "ProductUsageMember")) {
            em.createQuery("delete from " + entity + " r where r.memberId = :id")
                    .setParameter("id", memberId).executeUpdate();
        }
    }
}
