package org.monitoring.catchholebackend.domain.validation.entity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.domain.validation.type.ValidationBranch;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
@Entity @Table(name="validation_findings") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ValidationFinding extends BaseEntity {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="report_id",nullable=false,updatable=false) private ValidationReport report;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20,updatable=false) private ValidationBranch branch;
    @Column(name="unit_ref",nullable=false,length=200,updatable=false) private String unitRef;
    @Column(name="check_id",updatable=false) private UUID checkId;
    @Column(name="check_revision",updatable=false) private Long checkRevision;
    @Column(nullable=false,length=30) private String outcome;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") private JsonNode payload;
    public static ValidationFinding create(ValidationReport r,ValidationBranch b,String ref,UUID check,Long revision,String outcome,JsonNode data) {
        var x=new ValidationFinding();x.report=r;x.branch=b;x.unitRef=ref;x.checkId=check;x.checkRevision=revision;x.outcome=outcome;x.payload=data.deepCopy();return x;
    }
}
