package org.monitoring.catchholebackend.domain.validation.entity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
/**
 * report당 하나인 회차 전체 확정 receipt(GH312 S3). 추출 완료(AUTOMATIC) 또는 작가 요청(EXPLICIT)의 평가마다 갱신한다.
 * APPLIED는 두 도메인 반영이 같은 트랜잭션에서 성공한 뒤에만 기록하며 이후에는 바꾸지 않는다. report가 무효화·퇴역해도 이력으로 남는다.
 */
@Entity @Table(name="validation_settings_applications",uniqueConstraints=@UniqueConstraint(name="uk_validation_settings_applications_key",columnNames={"work_id","idempotency_key"}))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ValidationSettingsApplication extends BaseEntity {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @OneToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="report_id",nullable=false,unique=true,updatable=false) private ValidationReport report;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="work_id",nullable=false,updatable=false) private Work work;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="completion_id",nullable=false,updatable=false) private ValidationReviewCompletion completion;
    /** 평가한 release Job. 검토 종료 receipt의 현재 연결 Job과 같다. */
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="extraction_job_id",nullable=false) private AnalysisJob extractionJob;
    /** 평가 당시 검토 종료 receipt의 extractionJobGeneration. */
    @Column(name="extraction_job_generation",nullable=false) private long extractionJobGeneration;
    @Enumerated(EnumType.STRING) @Column(name="status",nullable=false,length=20) private ValidationApplicationStatus status;
    /** HELD_REVIEW만 값이 있다. [{code, candidateType, candidateId?, detail?}] */
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="hold_reasons",columnDefinition="jsonb") private JsonNode holdReasons;
    @Column(name="attempt",nullable=false) private long attempt;
    @Enumerated(EnumType.STRING) @Column(name="trigger",nullable=false,length=20) private ValidationReviewTrigger trigger;
    /** 마지막 EXPLICIT 요청 키와 {reportId, expectedReportVersion} hash. AUTOMATIC 시도는 비운다. */
    @Column(name="idempotency_key",length=100) private String idempotencyKey;
    @Column(name="request_hash",length=64) private String requestHash;
    /** 반영 트랜잭션을 되돌린 AppException 코드(또는 UNEXPECTED_ERROR). */
    @Column(name="last_error_code",length=60) private String lastErrorCode;
    /** 이 시도가 report version을 올린 뒤의 값. 재전송이 같은 응답을 받게 한다. */
    @Column(name="report_version",nullable=false) private long reportVersion;
    @Column(name="applied_at") private LocalDateTime appliedAt;
    /** 기록할 때마다 1 증가하는 명시적 version. JPA @Version은 쓰지 않는다. */
    @Column(name="version",nullable=false) private long version;

    public static ValidationSettingsApplication start(ValidationReport report,ValidationReviewCompletion completion) {
        var a=new ValidationSettingsApplication();a.report=report;a.work=report.getWork();a.completion=completion;a.attempt=0;a.version=0;return a;
    }
    public boolean isApplied() {return status==ValidationApplicationStatus.APPLIED;}
    public boolean isNew() {return status==null;}
    public void hold(ValidationReviewTrigger trigger,String key,String hash,JsonNode reasons,String errorCode,long reportVersion) {
        record(trigger,key,hash);status=ValidationApplicationStatus.HELD_REVIEW;holdReasons=reasons.deepCopy();lastErrorCode=errorCode==null?null:errorCode.length()>60?errorCode.substring(0,60):errorCode;this.reportVersion=reportVersion;
    }
    public void apply(ValidationReviewTrigger trigger,String key,String hash,long reportVersion) {
        record(trigger,key,hash);status=ValidationApplicationStatus.APPLIED;holdReasons=null;lastErrorCode=null;appliedAt=LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);this.reportVersion=reportVersion;
    }
    private void record(ValidationReviewTrigger trigger,String key,String hash) {
        if(isApplied())throw new IllegalStateException("이미 반영한 회차 전체 확정은 다시 기록할 수 없습니다.");
        if((trigger==ValidationReviewTrigger.EXPLICIT)!=(key!=null&&hash!=null))throw new IllegalArgumentException("명시 요청만 요청 키를 가진다.");
        extractionJob=completion.getExtractionJob();extractionJobGeneration=completion.getExtractionJobGeneration();
        this.trigger=trigger;idempotencyKey=key;requestHash=hash;attempt++;version++;
    }
}
