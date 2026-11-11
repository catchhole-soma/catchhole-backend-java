package org.monitoring.catchholebackend.domain.productusage.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.*;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Entity @Table(name = "product_usage_daily") @Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductUsageDaily extends BaseEntity {
    @Id @Column(name = "period_start") private LocalDate date;
    @Column(name = "members_1d", nullable = false) private long members1d;
    @Column(name = "members_7d", nullable = false) private long members7d;
    @Column(name = "members_30d", nullable = false) private long members30d;
    @Column(nullable = false) private long completedRequests;
    @Column(nullable = false) private long repeatRequests;
    @Column(nullable = false) private long unknownRequests;
    @Column(nullable = false) private long worksCreated;
    public ProductUsageDaily(LocalDate date) { this.date = date; }
    public void addMember(int days) {
        switch (days) {
            case 1 -> members1d++;
            case 7 -> members7d++;
            case 30 -> members30d++;
            default -> throw new IllegalArgumentException("지원하지 않는 제품 집계 기간입니다.");
        }
    }
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
