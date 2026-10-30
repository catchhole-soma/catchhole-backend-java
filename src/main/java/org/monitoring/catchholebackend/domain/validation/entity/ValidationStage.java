package org.monitoring.catchholebackend.domain.validation.entity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
@Entity @Table(name="validation_stages",uniqueConstraints=@UniqueConstraint(columnNames={"report_id","branch","stage","unit_ref"})) @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ValidationStage extends BaseEntity {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="report_id",nullable=false,updatable=false) private ValidationReport report;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20,updatable=false) private ValidationBranch branch;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30,updatable=false) private ValidationStageKind stage;
    @Column(name="unit_ref",nullable=false,length=200,updatable=false) private String ref;
    @Column(name="payload_hash",nullable=false,length=64) private String payloadHash;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") private JsonNode payload;
    @Column(nullable=false) private boolean successful;
    public void publish(String hash,JsonNode payload,boolean success) {this.payloadHash=hash;this.payload=payload.deepCopy();this.successful=success;}
    public static ValidationStage create(ValidationReport r,ValidationBranch b,ValidationStageKind s,String ref,String hash,JsonNode payload,boolean successful) {
        var x=new ValidationStage();x.report=r;x.branch=b;x.stage=s;x.ref=ref;x.payloadHash=hash;x.payload=payload.deepCopy();x.successful=successful;return x;
    }
}
