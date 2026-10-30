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
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false) private String requestHash;
    @Column(name = "request_kind", nullable = false, length = 20, updatable = false) private String requestKind;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "report_id", nullable = false, updatable = false) private ValidationReport report;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name="response_snapshot",columnDefinition="jsonb") private com.fasterxml.jackson.databind.JsonNode responseSnapshot;
    public void recordRetryResponse(com.fasterxml.jackson.databind.JsonNode response) {if(!"RETRY".equals(requestKind)||responseSnapshot!=null)throw new IllegalStateException("재시도 응답은 한 번만 저장합니다.");responseSnapshot=response.deepCopy();}
    public static ValidationRequestKey create(Work work, String key, ValidationReport report) {
        return create(work, key, report, "START", report.getRequestHash());
    }
    public static ValidationRequestKey create(Work work, String key, ValidationReport report, String kind, String hash) {
        ValidationRequestKey request = new ValidationRequestKey(); request.work = work; request.requestKey = key; request.report = report;
        request.requestKind = kind; request.requestHash = hash; return request;
    }
}
