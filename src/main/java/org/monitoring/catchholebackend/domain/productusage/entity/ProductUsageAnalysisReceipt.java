package org.monitoring.catchholebackend.domain.productusage.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.monitoring.catchholebackend.domain.productusage.dto.ProductUsageCompletion;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Entity @Table(name = "product_usage_analysis_receipts")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductUsageAnalysisReceipt extends BaseEntity {
    @Id private UUID requestId;
    @Column(nullable = false) private UUID workId;
    @Column(nullable = false) private Long memberId;
    @Column(nullable = false) private LocalDateTime requestedAt;
    @Column(nullable = false) private LocalDateTime completedAt;
    public ProductUsageAnalysisReceipt(ProductUsageCompletion event) {
        requestId = event.requestId(); workId = event.workId(); memberId = event.memberId();
        requestedAt = event.requestedAt(); completedAt = event.completedAt();
    }
}
