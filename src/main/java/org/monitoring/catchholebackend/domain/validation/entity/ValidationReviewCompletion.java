package org.monitoring.catchholebackend.domain.validation.entity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.validation.type.ValidationReviewTrigger;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;
/**
 * report당 하나인 검토 종료 receipt. 같은 Work 잠금 트랜잭션에서 SETTING_EXTRACTION Job과 함께 만든다(GH312 S2).
 * report가 무효화·퇴역해도 이력으로 남지만 추출 허가는 현재 report의 receipt만 인정한다.
 */
@Entity @Table(name="validation_review_completions",uniqueConstraints=@UniqueConstraint(name="uk_validation_review_completions_key",columnNames={"work_id","idempotency_key"}))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ValidationReviewCompletion extends BaseEntity {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @OneToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="report_id",nullable=false,unique=true,updatable=false) private ValidationReport report;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="work_id",nullable=false,updatable=false) private Work work;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="episode_id",nullable=false,updatable=false) private Episode episode;
    @Enumerated(EnumType.STRING) @Column(name="trigger",nullable=false,length=20,updatable=false) private ValidationReviewTrigger trigger;
    /** 서버가 검토 종료를 판정한 report version. 종료로 올리기 전 값이다. */
    @Column(name="report_version",nullable=false,updatable=false) private long reportVersion;
    /** EXPLICIT 요청 키. AUTOMATIC은 null이며 PostgreSQL은 null을 제외한 부분 unique index로 (work, key)를 막는다. */
    @Column(name="idempotency_key",length=100,updatable=false) private String idempotencyKey;
    /** EXPLICIT 요청의 {reportId, expectedReportVersion} hash. 같은 키의 다른 입력을 거절한다. */
    @Column(name="request_hash",length=64,updatable=false) private String requestHash;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="extraction_job_id",nullable=false) private AnalysisJob extractionJob;
    /** 공개 재시도가 연결 Job ID를 새 Job으로 바꿀 때마다 증가한다. 같은 Job 재개는 유지한다. */
    @Column(name="extraction_job_generation",nullable=false) private long extractionJobGeneration=1;
    @Column(name="completed_at",nullable=false,updatable=false) private LocalDateTime completedAt;
    public static ValidationReviewCompletion complete(ValidationReport report,ValidationReviewTrigger trigger,String key,String requestHash,AnalysisJob job) {
        var c=new ValidationReviewCompletion();c.report=report;c.work=report.getWork();c.episode=report.getEpisode();c.trigger=trigger;c.reportVersion=report.getVersion();
        c.idempotencyKey=key;c.requestHash=requestHash;c.extractionJob=job;c.completedAt=LocalDateTime.now();return c;
    }
    public void relinkExtractionJob(AnalysisJob job) { if(!extractionJob.getId().equals(job.getId())) {extractionJob=job;extractionJobGeneration++;} }
}
