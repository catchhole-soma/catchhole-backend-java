package org.monitoring.catchholebackend.domain.validation.entity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.domain.validation.type.ValidationBranch;
import org.monitoring.catchholebackend.domain.validation.type.ValidationImpact;
import org.monitoring.catchholebackend.domain.validation.type.ValidationSubjectType;
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
    /** 설정 판정 payload의 영향 등급(V102, #314 B2). 목록 필터용 사본이며 오탈자와 V102 이전의 비정상 값은 null이다. */
    @Enumerated(EnumType.STRING) @Column(length=20,updatable=false) private ValidationImpact impact;
    /**
     * 설정 finding이 가리키는 고정 기준 항목(V102, #314 B2). 저장 시점의 report 고정 기준(baselineEntries)과 질문 상태에서만 계산하며
     * 현재 설정값을 읽지 않는다. 질문 대상이 기준 항목으로 이어지지 않으면 모두 null, 기준 항목이 실제 대상에 연결되지 않으면 subjectId만 null이다.
     */
    @Enumerated(EnumType.STRING) @Column(name="subject_type",length=20,updatable=false) private ValidationSubjectType subjectType;
    @Column(name="subject_id",updatable=false) private UUID subjectId;
    @Column(name="subject_name",columnDefinition="text",updatable=false) private String subjectName;
    /** 질문 속성이 대상의 고정 slot/path 하나와 정확히 맞을 때만 채운다. 캐릭터 "AGE:age", 세계관 저장 경로 키 ["범위","설정명"] 또는 [null,"설정명"]. */
    @Column(name="setting_key",columnDefinition="text",updatable=false) private String settingKey;
    /** settingKey의 화면 표시명. 캐릭터는 schema 표시명, 세계관은 "범위 / 설정명" 또는 "설정명". */
    @Column(name="setting_name",columnDefinition="text",updatable=false) private String settingName;
    public static ValidationFinding create(ValidationReport r,ValidationBranch b,String ref,UUID check,Long revision,String outcome,JsonNode data) {
        var x=new ValidationFinding();x.report=r;x.branch=b;x.unitRef=ref;x.checkId=check;x.checkRevision=revision;x.outcome=outcome;x.payload=data.deepCopy();return x;
    }
    /** 저장 직전 한 번만 기록한다. 이후 값·기준 변경으로 다시 계산하지 않는다. */
    public ValidationFinding describeTarget(ValidationImpact impact,ValidationSubjectType subjectType,UUID subjectId,String subjectName,String settingKey,String settingName) {
        this.impact=impact;this.subjectType=subjectType;this.subjectId=subjectType==null?null:subjectId;this.subjectName=subjectType==null?null:subjectName;
        this.settingKey=subjectType==null?null:settingKey;this.settingName=this.settingKey==null?null:settingName;return this;
    }
}
