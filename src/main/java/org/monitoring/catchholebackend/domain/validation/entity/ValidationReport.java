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
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "episode_id", nullable = false, unique = true, updatable = false) private Episode episode;
    @Column(name = "source_episode_no", nullable = false, updatable = false) private int sourceEpisodeNo;
    @Column(name = "source_content_hash", nullable = false, length = 64, updatable = false) private String sourceContentHash;
    @Column(name = "source_content_s3_key", nullable = false, length = 512, updatable = false) private String sourceContentS3Key;
    @Column(name = "source_content_s3_version", length = 255, updatable = false) private String sourceContentS3Version;
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false) private String requestHash;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "baseline_seed", columnDefinition = "jsonb") private JsonNode baselineSeed;
    @Column(name = "baseline_hash", length = 64) private String baselineHash;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "baseline_entries", columnDefinition = "jsonb") private JsonNode baselineEntries;
    @Column(name = "confirmed_state_hash", length = 64) private String confirmedStateHash;
    @Enumerated(EnumType.STRING) @Column(name = "baseline_status", nullable = false, length = 20) private ValidationPreparationStatus baselineStatus;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 40) private ValidationReportStatus status;
    @Column(name = "hold_reason", length = 100) private String holdReason;

    public static ValidationReport create(Work work, Episode episode, UploadBatch batch, String requestHash) {
        ValidationReport r = new ValidationReport(); r.work = work; r.member = work.getMember(); r.episode = episode; r.batch = batch;
        r.sourceEpisodeNo = episode.getEpisodeNo(); r.sourceContentHash = episode.getContentHash();
        r.sourceContentS3Key = episode.getContentS3Key(); r.sourceContentS3Version = episode.getContentS3Version();
        r.requestHash = requestHash; r.baselineStatus = ValidationPreparationStatus.PENDING; r.status = ValidationReportStatus.PREPARING_BASELINE;
        return r;
    }
    public void captureSeed(JsonNode seed, String hash, String confirmedHash) {
        if (status == ValidationReportStatus.INVALIDATED || baselineSeed != null) return;
        baselineSeed = seed.deepCopy(); baselineHash = hash; confirmedStateHash = confirmedHash;
    }
    public void baselineReady(JsonNode entries) {
        if (status == ValidationReportStatus.INVALIDATED) return;
        baselineEntries = entries.deepCopy(); baselineStatus = ValidationPreparationStatus.READY;
        status = ValidationReportStatus.WAITING_FOR_ERROR_REVIEW; holdReason = null;
    }
    public void hold(String reason) {
        if (status == ValidationReportStatus.INVALIDATED) return;
        baselineEntries = null; baselineStatus = ValidationPreparationStatus.FAILED;
        status = ValidationReportStatus.HELD_INCOMPLETE; holdReason = reason;
    }
    /** 고정 대상 fingerprint는 무효 결과 식별용 감사 메타데이터다. 원문 복사본/출처 목록은 재사용하지 않는다. */
    public void purge() {
        baselineSeed = null; baselineEntries = null; baselineHash = null; confirmedStateHash = null;
        baselineStatus = ValidationPreparationStatus.INVALIDATED; status = ValidationReportStatus.INVALIDATED; holdReason = "SOURCE_EVIDENCE_PURGED";
    }
}
