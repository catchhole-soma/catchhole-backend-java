package org.monitoring.catchholebackend.domain.analysis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

@Getter
@Entity
@Table(name = "analysis_jobs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisJob extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "work_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_analysis_jobs_work")
    )
    private Work work;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "batch_id",
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_analysis_jobs_upload_batch")
    )
    private UploadBatch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "episode_id",
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_analysis_jobs_episode")
    )
    private Episode episode;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 40)
    private AnalysisJobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AnalysisJobStatus status;

    @Column(name = "current_step", length = 100)
    private String currentStep;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "input_token_count")
    private Integer inputTokenCount;

    @Column(name = "output_token_count")
    private Integer outputTokenCount;

    @Column(name = "summary_json", columnDefinition = "text")
    private String summaryJson;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    private AnalysisJob(Work work, UploadBatch batch, Episode episode, AnalysisJobType jobType) {
        this.work = work;
        this.batch = batch;
        this.episode = episode;
        this.jobType = jobType;
        this.status = AnalysisJobStatus.PENDING;
    }

    public static AnalysisJob create(
            Work work,
            UploadBatch batch,
            Episode episode,
            AnalysisJobType jobType
    ) {
        return new AnalysisJob(work, batch, episode, jobType);
    }

    public void start(String modelName, String currentStep) {
        this.status = AnalysisJobStatus.RUNNING;
        if (modelName != null) {
            this.modelName = modelName;
        }
        if (currentStep != null) {
            this.currentStep = currentStep;
        }
        this.errorMessage = null;
        this.startedAt = LocalDateTime.now();
    }

    public void updateCurrentStep(String currentStep) {
        this.currentStep = currentStep;
    }

    public void succeed(String summaryJson, Integer inputTokenCount, Integer outputTokenCount) {
        this.status = AnalysisJobStatus.SUCCEEDED;
        this.summaryJson = summaryJson;
        this.inputTokenCount = inputTokenCount;
        this.outputTokenCount = outputTokenCount;
        this.errorMessage = null;
        this.completedAt = LocalDateTime.now();
    }

    public void fail(String errorMessage) {
        this.status = AnalysisJobStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }
}
