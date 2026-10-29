package org.monitoring.catchholebackend.domain.validation.entity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.domain.validation.type.ValidationBranch;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
@Entity @Table(name="validation_checkpoints",uniqueConstraints=@UniqueConstraint(columnNames={"report_id","branch"})) @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ValidationCheckpoint extends BaseEntity {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="report_id",nullable=false,updatable=false) private ValidationReport report;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20,updatable=false) private ValidationBranch branch;
    @Column(name="checkpoint_version",nullable=false) private long version;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") private JsonNode payload;
    public static ValidationCheckpoint create(ValidationReport r,ValidationBranch b) { var x=new ValidationCheckpoint();x.report=r;x.branch=b;return x; }
    public void invalidate() {payload=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();version++;}
    public void publish(JsonNode payload) { this.payload=payload.deepCopy();version++; }
}
