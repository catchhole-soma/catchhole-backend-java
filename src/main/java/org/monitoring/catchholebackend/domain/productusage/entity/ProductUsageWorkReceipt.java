package org.monitoring.catchholebackend.domain.productusage.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Entity @Table(name = "product_usage_work_receipts")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductUsageWorkReceipt extends BaseEntity {
    @Id private UUID workId;
    @Column(nullable = false) private Long memberId;
    @Column(nullable = false) private LocalDateTime occurredAt;
    @Column(nullable = false) private boolean processed;
    public ProductUsageWorkReceipt(Work work) {
        workId = work.getId(); memberId = work.getMember().getId(); occurredAt = work.getCreatedAt();
    }
    public void processed() { processed = true; }
}
