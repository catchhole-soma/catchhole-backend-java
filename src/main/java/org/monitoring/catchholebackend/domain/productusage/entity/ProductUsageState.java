package org.monitoring.catchholebackend.domain.productusage.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Entity @Table(name = "product_usage_state") @Getter
public class ProductUsageState extends BaseEntity {
    @Id private Integer id = 1;
    private LocalDateTime startedAt;
    private LocalDateTime collectedAt;
    public void initialize(LocalDateTime start) { if (startedAt == null) startedAt = start; }
    public void collected(LocalDateTime at) { collectedAt = at; }
}
