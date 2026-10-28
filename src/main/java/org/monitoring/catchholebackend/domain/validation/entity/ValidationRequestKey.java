package org.monitoring.catchholebackend.domain.validation.entity;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Entity
@Table(name = "validation_request_keys", uniqueConstraints = @UniqueConstraint(columnNames = {"work_id", "request_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ValidationRequestKey extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "work_id", nullable = false, updatable = false) private Work work;
    @Column(name = "request_key", nullable = false, length = 100, updatable = false) private String requestKey;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "report_id", nullable = false, updatable = false) private ValidationReport report;
    public static ValidationRequestKey create(Work work, String key, ValidationReport report) {
        ValidationRequestKey request = new ValidationRequestKey(); request.work = work; request.requestKey = key; request.report = report; return request;
    }
}
