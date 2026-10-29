package org.monitoring.catchholebackend.domain.validation.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Entity @Table(name="validation_corrections", uniqueConstraints=@UniqueConstraint(columnNames={"work_id","request_key"}))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ValidationCorrection extends BaseEntity {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="work_id",nullable=false,updatable=false) private Work work;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="report_id",nullable=false,updatable=false) private ValidationReport report;
    @Column(name="request_key",nullable=false,length=100,updatable=false) private String requestKey;
    @Column(name="request_hash",nullable=false,length=64,updatable=false) private String requestHash;
    @Column(name="report_version",nullable=false,updatable=false) private long reportVersion;
    @Column(name="baseline_revision",nullable=false,updatable=false) private long baselineRevision;
    @Column(name="baseline_hash",nullable=false,length=64,updatable=false) private String baselineHash;
    @Column(name="entry_ref",nullable=false,length=30,updatable=false) private String entryRef;
    @Column(name="entry_hash",nullable=false,length=64,updatable=false) private String entryHash;
    @Column(name="entity_version",nullable=false,updatable=false) private long entityVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="correction_scope",nullable=false,columnDefinition="jsonb",updatable=false) private JsonNode scope;
    public static ValidationCorrection create(ValidationReport report, String key, String hash, String entryRef,
            String entryHash, long baselineRevision, String baselineHash, long entityVersion, JsonNode scope) {
        var correction=new ValidationCorrection(); correction.work=report.getWork(); correction.report=report;
        correction.requestKey=key; correction.requestHash=hash; correction.reportVersion=report.getVersion();
        correction.entryRef=entryRef; correction.entryHash=entryHash; correction.baselineRevision=baselineRevision;
        correction.baselineHash=baselineHash; correction.entityVersion=entityVersion; correction.scope=scope.deepCopy(); return correction;
    }
}
