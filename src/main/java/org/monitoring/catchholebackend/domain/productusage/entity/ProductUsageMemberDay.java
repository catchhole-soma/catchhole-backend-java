package org.monitoring.catchholebackend.domain.productusage.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;
import lombok.*;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Entity @Table(name = "product_usage_member_days",
        uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "activity_date"}))
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductUsageMemberDay extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false) private Long memberId;
    @Column(nullable = false) private LocalDate activityDate;
    public ProductUsageMemberDay(Long memberId, LocalDate date) {
        this.memberId = memberId;
        activityDate = date;
    }
}
