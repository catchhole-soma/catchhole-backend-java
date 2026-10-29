package org.monitoring.catchholebackend.domain.validation.entity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
/** Previous semantic question revisions remain distinguishable for the future author-review API. */
@Entity @Table(name="validation_check_history",uniqueConstraints=@UniqueConstraint(columnNames={"check_id","revision"})) @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ValidationCheckHistory extends BaseEntity {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="report_id",nullable=false,updatable=false) private ValidationReport report;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="check_id",nullable=false,updatable=false) private ValidationCheck check;
    @Column(nullable=false,updatable=false) private long revision;
    @Column(name="input_digest",nullable=false,length=64,updatable=false) private String inputDigest;
    @Column(name="change_reason",nullable=false,length=200,updatable=false) private String changeReason;
    @Column(length=30,updatable=false) private String outcome;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb",updatable=false) private JsonNode state;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb",updatable=false) private JsonNode decision;
    public static ValidationCheckHistory supersede(ValidationCheck c,String reason) {var h=new ValidationCheckHistory();h.report=c.getReport();h.check=c;h.revision=c.getRevision();h.inputDigest=c.getInputDigest();h.changeReason=reason;h.outcome=c.getOutcome();h.state=c.getState().deepCopy();h.decision=c.getDecision()==null?null:c.getDecision().deepCopy();return h;}
}
