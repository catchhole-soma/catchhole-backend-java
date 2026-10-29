package org.monitoring.catchholebackend.domain.validation.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Entity @Table(name="validation_jobs") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ValidationJob extends BaseEntity {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @OneToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="report_id",nullable=false,unique=true,updatable=false) private ValidationReport report;
    @Column(name="input_digest",nullable=false,length=64,updatable=false) private String inputDigest;
    @Column(name="manifest_hash",nullable=false,length=64,updatable=false) private String manifestHash;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") private JsonNode configuration;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) private ValidationExecutionStatus status;
    @Column(nullable=false) private long generation=1;
    @Column(nullable=false) private int attempt;
    @Column(nullable=false) private int recoveries;
    @Column(name="lease_token") private UUID leaseToken;
    @Column(name="worker_id",length=100) private String workerId;
    @Column(name="lease_expires_at") private Instant leaseExpiresAt;
    @Column(name="grid_ready",nullable=false) private boolean gridReady;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="required_grid",columnDefinition="jsonb") private JsonNode requiredGrid;
    @Column(name="failure_code",length=100) private String failureCode;
    public static ValidationJob queue(ValidationReport report,String digest,String manifest,JsonNode configuration) {
        var j=new ValidationJob();j.report=report;j.inputDigest=digest;j.manifestHash=manifest;j.configuration=configuration.deepCopy();j.status=ValidationExecutionStatus.QUEUED;return j;
    }
    public void reviseBudgetSnapshot(JsonNode snapshot) {configuration=snapshot.deepCopy();}
    public void claim(String worker,Duration lease) { status=ValidationExecutionStatus.RUNNING;workerId=worker;leaseToken=UUID.randomUUID();leaseExpiresAt=Instant.now().plus(lease);attempt++; }
    public boolean runningLease(UUID token) { return status==ValidationExecutionStatus.RUNNING&&token!=null&&token.equals(leaseToken)&&leaseExpiresAt!=null&&Instant.now().isBefore(leaseExpiresAt); }
    public void heartbeat(Duration lease) { leaseExpiresAt=Instant.now().plus(lease); }
    public void expire(int maxRecoveries) { recoveries++;generation++;leaseToken=null;leaseExpiresAt=null;status=recoveries<=maxRecoveries?ValidationExecutionStatus.QUEUED:ValidationExecutionStatus.FAILED;failureCode="WORKER_LEASE_EXPIRED"; }
    public void retry() { generation++;recoveries=0;status=ValidationExecutionStatus.QUEUED;leaseToken=null;leaseExpiresAt=null;failureCode=null; }
    public void initializeGrid(ValidationBranch branch,JsonNode grid) {
        if(requiredGrid==null)requiredGrid=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        ((com.fasterxml.jackson.databind.node.ObjectNode)requiredGrid).set(branch.name(),grid.deepCopy());
        gridReady=requiredGrid.has("SETTING")&&requiredGrid.has("TYPO");
    }
    public void finish(ValidationExecutionStatus result,String reason) { status=result;failureCode=reason; }
    public void cancel() { status=ValidationExecutionStatus.CANCELED;leaseToken=null;leaseExpiresAt=null;requiredGrid=null;gridReady=false;failureCode="SOURCE_EVIDENCE_PURGED"; }
}
