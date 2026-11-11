package org.monitoring.catchholebackend.domain.productusage.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.*;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Entity @Table(name = "product_usage_monthly") @Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductUsageMonthly extends BaseEntity {
    @Id @Column(name = "period_start") private LocalDate month;
    @Column(nullable = false) private long analysisMembers;
    @Column(nullable = false) private long completedRequests;
    @Column(nullable = false) private long repeatRequests;
    @Column(nullable = false) private long unknownRequests;
    @Column(nullable = false) private long worksCreated;
    public ProductUsageMonthly(LocalDate month) { this.month = month.withDayOfMonth(1); }
    public void addMember() { analysisMembers++; }
    public void complete(boolean repeated, boolean unknown) {
        completedRequests++;
        if (repeated) repeatRequests++;
        if (unknown) unknownRequests++;
    }
    public void reclassifyFirstAsRepeat(boolean wasUnknown) {
        repeatRequests++;
        if (wasUnknown) unknownRequests--;
    }
    public void workCreated() { worksCreated++; }
}
