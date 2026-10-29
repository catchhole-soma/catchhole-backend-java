package org.monitoring.catchholebackend.domain.validation.entity;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;
import org.monitoring.catchholebackend.domain.validation.dto.ValidationExecutionContract.Citation;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
@Entity @Table(name="validation_evidence") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ValidationEvidence extends BaseEntity {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="report_id",nullable=false,updatable=false) private ValidationReport report;
    @Column(name="owner_ref",nullable=false,length=200,updatable=false) private String ownerRef;
    @Column(name="source_ref",nullable=false,length=100,updatable=false) private String sourceRef;
    @Column(name="source_hash",nullable=false,length=64,updatable=false) private String sourceHash;
    @Column(name="source_version",length=255,updatable=false) private String sourceVersion;
    @Column(name="start_cp",nullable=false,updatable=false) private int start;
    @Column(name="end_cp",nullable=false,updatable=false) private int end;
    @Column(nullable=false,columnDefinition="text",updatable=false) private String quote;
    public static ValidationEvidence create(ValidationReport r,String owner,Citation c) {var x=new ValidationEvidence();x.report=r;x.ownerRef=owner;x.sourceRef=c.sourceRef();x.sourceHash=c.sourceHash();x.sourceVersion=c.sourceVersion();x.start=c.start();x.end=c.end();x.quote=c.quote();return x;}
}
