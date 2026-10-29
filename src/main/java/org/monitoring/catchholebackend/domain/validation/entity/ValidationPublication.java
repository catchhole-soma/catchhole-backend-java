package org.monitoring.catchholebackend.domain.validation.entity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
@Entity @Table(name="validation_publications",uniqueConstraints=@UniqueConstraint(columnNames={"report_id","request_key"})) @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ValidationPublication extends BaseEntity {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="report_id",nullable=false,updatable=false) private ValidationReport report;
    @Column(name="request_key",nullable=false,length=200,updatable=false) private String requestKey;
    @Column(name="request_hash",nullable=false,length=64,updatable=false) private String requestHash;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb",updatable=false) private JsonNode response;
    public static ValidationPublication create(ValidationReport report,String key,String hash,JsonNode response) {
        var x=new ValidationPublication();x.report=report;x.requestKey=key;x.requestHash=hash;x.response=response.deepCopy();return x;
    }
}
