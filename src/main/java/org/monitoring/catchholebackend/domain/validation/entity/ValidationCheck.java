package org.monitoring.catchholebackend.domain.validation.entity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
@Entity @Table(name="validation_checks",uniqueConstraints=@UniqueConstraint(columnNames={"report_id","local_ref"})) @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ValidationCheck extends BaseEntity {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="report_id",nullable=false,updatable=false) private ValidationReport report;
    @Column(name="local_ref",nullable=false,length=100,updatable=false) private String localRef;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="original_refs",nullable=false,columnDefinition="jsonb",updatable=false) private JsonNode originalRefs;
    @Column(nullable=false) private long revision=1;
    @Column(name="input_digest",nullable=false,length=64) private String inputDigest;
    @Column(name="change_reason",length=200) private String changeReason;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") private JsonNode state;
    @Column(length=30) private String outcome;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") private JsonNode decision;
    public static ValidationCheck create(ValidationReport r,String ref,JsonNode original,JsonNode state,String digest) {
        var x=new ValidationCheck();x.report=r;x.localRef=ref;x.originalRefs=original.deepCopy();x.state=state.deepCopy();x.inputDigest=digest;return x;
    }
    public void revise(JsonNode input,String digest,String reason) { revision++;state=input.deepCopy();inputDigest=digest;changeReason=reason;outcome=null;decision=null; }
    public void decide(String outcome,JsonNode decision) { this.outcome=outcome;this.decision=decision.deepCopy(); }
    public boolean completed() { return outcome!=null&&!"INCOMPLETE".equals(outcome); }
}
