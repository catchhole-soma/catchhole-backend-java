package org.monitoring.catchholebackend.domain.productusage.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.monitoring.catchholebackend.domain.productusage.dto.ProductUsageCompletion;
import org.monitoring.catchholebackend.domain.productusage.type.ProductUsageFirstKind;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Entity @Table(name = "product_usage_members") @Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductUsageMember extends BaseEntity {
    @Id private Long memberId;
    @Column(nullable = false) private boolean priorHistoryUnknown;
    private LocalDateTime firstCompletedAt;
    private UUID firstRequestId;
    @Enumerated(EnumType.STRING) @Column(length = 20) private ProductUsageFirstKind firstKind;
    public ProductUsageMember(Long id, boolean priorHistoryUnknown) {
        memberId = id;
        this.priorHistoryUnknown = priorHistoryUnknown;
    }
    public boolean isEarlier(ProductUsageCompletion event) {
        return firstCompletedAt == null || event.completedAt().isBefore(firstCompletedAt)
                || (event.completedAt().equals(firstCompletedAt)
                && event.requestId().toString().compareTo(firstRequestId.toString()) < 0);
    }
    public void observeFirst(ProductUsageCompletion event, ProductUsageFirstKind kind) {
        firstCompletedAt = event.completedAt();
        firstRequestId = event.requestId();
        firstKind = kind;
    }
}
