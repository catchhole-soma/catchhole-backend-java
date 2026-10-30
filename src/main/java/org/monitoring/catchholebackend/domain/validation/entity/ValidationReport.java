package org.monitoring.catchholebackend.domain.validation.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

/** 오류 검수 접수와 고정 기준. 설정 추출 Job/후보를 만들거나 검토 완료를 추정하지 않는다. */
@Entity
@Table(name = "validation_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ValidationReport extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "work_id", nullable = false, updatable = false) private Work work;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "member_id", nullable = false, updatable = false) private Member member;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "batch_id", nullable = false, updatable = false) private UploadBatch batch;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "episode_id", nullable = false, updatable = false) private Episode episode;
    @Column(name = "is_current", nullable = false) private boolean current = true;
    @Column(name = "revision", nullable = false, updatable = false) private long revision = 1;
    @Column(name = "baseline_revision", nullable = false, updatable = false) private long baselineRevision = 1;
    @Column(name = "version", nullable = false) private long version;
    @Column(name = "previous_report_id", updatable = false) private UUID previousReportId;
    @Enumerated(EnumType.STRING) @Column(name = "mode", nullable = false, length = 30, updatable = false) private ValidationReportMode mode = ValidationReportMode.BEFORE_CONFIRMATION;
    @Column(name = "previous_application_kind", length = 40, updatable = false) private String previousApplicationKind;
    @Column(name = "previous_application_id", updatable = false) private UUID previousApplicationId;
    @Column(name = "previous_application_job_id", updatable = false) private UUID previousApplicationJobId;
    @Column(name = "previous_application_at", updatable = false) private java.time.LocalDateTime previousApplicationAt;
    @Column(name = "source_content_revision", nullable = false, updatable = false) private long sourceContentRevision;
    @Column(name = "source_episode_no", nullable = false, updatable = false) private int sourceEpisodeNo;
    @Column(name = "source_content_hash", nullable = false, length = 64, updatable = false) private String sourceContentHash;
    @Column(name = "source_content_s3_key", nullable = false, length = 512, updatable = false) private String sourceContentS3Key;
    @Column(name = "source_content_s3_version", length = 255, updatable = false) private String sourceContentS3Version;
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false) private String requestHash;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "baseline_seed", columnDefinition = "jsonb") private JsonNode baselineSeed;
    @Column(name = "baseline_hash", length = 64) private String baselineHash;
    @Column(name = "baseline_fingerprint", length = 64) private String baselineFingerprint;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "baseline_entries", columnDefinition = "jsonb") private JsonNode baselineEntries;
    @Column(name = "confirmed_state_hash", length = 64) private String confirmedStateHash;
    @Enumerated(EnumType.STRING) @Column(name = "baseline_status", nullable = false, length = 20) private ValidationPreparationStatus baselineStatus;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 40) private ValidationReportStatus status;
    @Column(name = "hold_reason", length = 100) private String holdReason;
    /** /complete의 자동 검토 종료가 추출 생성 조건(AppException 코드)에 막혀 되돌려진 이유. 검토 종료 receipt를 만들면 비운다(GH312). */
    @Column(name = "release_hold_code", length = 60) private String releaseHoldCode;
    @Column(name = "release_hold_at") private java.time.LocalDateTime releaseHoldAt;
    /** 처음 INVALIDATED로 바뀐 시각(V100, GH312 S4 이력). 유효한 동안과 V100 이전에 무효화된 report는 null이다. 반복 파기는 바꾸지 않는다. */
    @Column(name = "invalidated_at") private java.time.LocalDateTime invalidatedAt;
    @OneToOne(mappedBy = "report", fetch = FetchType.LAZY) private ValidationJob validationJob;
    public void attachValidationJob(ValidationJob job) { validationJob=job; advanceVersion(); }

    public static ValidationReport create(Work work, Episode episode, UploadBatch batch, String requestHash) {
        ValidationReport r = new ValidationReport(); r.work = work; r.member = work.getMember(); r.episode = episode; r.batch = batch;
        r.sourceEpisodeNo = episode.getEpisodeNo(); r.sourceContentHash = episode.getContentHash();
        r.sourceContentS3Key = episode.getContentS3Key(); r.sourceContentS3Version = episode.getContentS3Version();
        r.sourceContentRevision = episode.getContentRevision();
        r.requestHash = requestHash; r.baselineStatus = ValidationPreparationStatus.PENDING; r.status = ValidationReportStatus.PREPARING_BASELINE;
        return r;
    }
    /** 재검수 report는 이전 report가 아니라 재검수 시점 회차의 현재 원본 업로드 batch에 묶는다. 파일 교체는 새 batch를 만든다(GH312). */
    public static ValidationReport recheck(ValidationReport previous, UploadBatch currentBatch, String requestHash,
            org.monitoring.catchholebackend.domain.validation.dto.response.ValidationApplicationReference application) {
        var report = create(previous.work, previous.episode, currentBatch, requestHash);
        report.previousReportId = previous.id; report.revision = previous.revision + 1;
        report.baselineRevision = previous.baselineRevision + 1;
        if (application != null) {
            report.mode = ValidationReportMode.DIAGNOSTIC_ONLY; report.previousApplicationKind = application.kind();
            report.previousApplicationId = application.applicationId(); report.previousApplicationJobId = application.analysisJobId();
            report.previousApplicationAt = application.appliedAt();
        }
        return report;
    }
    public void retire() { if (current) { current = false; version++; } purge(); }
    public void holdAutomaticRelease(String code) { releaseHoldCode = code.length() > 60 ? code.substring(0, 60) : code; releaseHoldAt = java.time.LocalDateTime.now(); }
    public void clearReleaseHold() { releaseHoldCode = null; releaseHoldAt = null; }
    /** Work 잠금 아래 의미 있는 report 변경마다 증가한다. JSON dirty-check/조회 flush와 분리한다. */
    public void advanceVersion() { version++; }
    public void captureSeed(JsonNode seed, String hash, String confirmedHash) {
        if (status == ValidationReportStatus.INVALIDATED || baselineSeed != null) return;
        baselineSeed = seed.deepCopy(); baselineHash = hash; baselineFingerprint = hash; confirmedStateHash = confirmedHash;
        version++;
    }
    public void baselineReady(JsonNode entries) {
        if (status == ValidationReportStatus.INVALIDATED) return;
        baselineEntries = entries.deepCopy(); baselineStatus = ValidationPreparationStatus.READY;
        status = ValidationReportStatus.WAITING_FOR_ERROR_REVIEW; holdReason = null;
        version++;
    }
    public void hold(String reason) {
        if (status == ValidationReportStatus.INVALIDATED) return;
        baselineEntries = null; baselineStatus = ValidationPreparationStatus.FAILED;
        status = ValidationReportStatus.HELD_INCOMPLETE; holdReason = reason;
        version++;
    }
    /** 고정 대상 fingerprint는 무효 결과 식별용 감사 메타데이터다. 원문 복사본/출처 목록은 재사용하지 않는다. */
    public void purge() {
        if (status != ValidationReportStatus.INVALIDATED || baselineSeed != null || baselineEntries != null) version++;
        if (status != ValidationReportStatus.INVALIDATED) invalidatedAt = java.time.LocalDateTime.now();
        baselineSeed = null; baselineEntries = null; baselineHash = null; confirmedStateHash = null;
        baselineStatus = ValidationPreparationStatus.INVALIDATED; status = ValidationReportStatus.INVALIDATED; holdReason = "SOURCE_EVIDENCE_PURGED";
    }
}
