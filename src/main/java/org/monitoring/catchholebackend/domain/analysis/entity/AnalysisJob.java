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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.global.common.entity.BaseEntity;

/*
 * analysis_jobs 테이블
 *
 * 회차 단위 AI 분석 작업과 세계관·캐릭터 설정 후보 재비교 작업을 추적하는 테이블이다.
 * 사용자가 분석을 요청하면 AnalysisJob이 PENDING 상태로 생성되고,
 * Python AI Worker가 내부 API로 작업을 claim하면서 RUNNING 상태로 변경된다.
 *
 * 신규 작업은 회차별로 하나씩 생성하며, 생성 시점의 단일 분석 대상을
 * targetEpisodes에 스냅샷으로 연결한다.
 * Worker는 이 연결을 기준으로 분석할 회차를 찾고,
 * Episode에 저장된 S3 원문 메타데이터를 사용해 분석을 수행한다.
 *
 * 이 테이블에는 원문 본문을 저장하지 않고, 작업 유형, 상태, 현재 단계,
 * 사용 모델명, 토큰 수, 요약 결과 JSON, 마지막 실패 사유, 시작/완료 시각 같은
 * 분석 작업의 상태와 결과 메타데이터만 저장한다.
 *
 * episode_id는 신규 작업에서 항상 분석 대상 단일 회차를 가리킨다.
 * null 또는 복수 targetEpisodes인 과거 작업 데이터는 조회 이력 호환을 위해 유지한다.
 */

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

    // 신규 분석 작업의 단일 대상 회차. null인 과거 batch 작업은 이력 호환용으로만 남긴다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "episode_id",
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_analysis_jobs_episode")
    )
    private Episode episode;

    // WORLD_SETTING_COMPARISON Job이 다시 비교할 후보. 일반 회차 분석 Job에서는 null이다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "world_setting_candidate_id",
            foreignKey = @ForeignKey(name = "fk_analysis_jobs_world_setting_candidate")
    )
    private WorldSettingCandidate worldSettingCandidate;

    // CHARACTER_FACT_COMPARISON Job이 다시 비교할 후보. 일반 회차 분석 Job에서는 null이다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "setting_candidate_id",
            foreignKey = @ForeignKey(name = "fk_analysis_jobs_setting_candidate")
    )
    private SettingCandidate settingCandidate;

    // 생성 시점의 실제 분석 대상 회차를 보존해 이후 원본 교체·보관과 무관하게 이력을 조회한다.
    @ManyToMany
    @JoinTable(
            name = "analysis_job_episode_targets",
            joinColumns = @JoinColumn(
                    name = "analysis_job_id",
                    foreignKey = @ForeignKey(name = "fk_analysis_job_episode_targets_job")
            ),
            inverseJoinColumns = @JoinColumn(
                    name = "episode_id",
                    foreignKey = @ForeignKey(name = "fk_analysis_job_episode_targets_episode")
            )
    )
    @OrderBy("episodeNo ASC")
    private Set<Episode> targetEpisodes = new LinkedHashSet<>();

    // Worker 처리 목적. 일반 회차 설정 추출과 세계관 후보 재비교를 구분한다.
    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 40)
    private AnalysisJobType jobType;

    // 분석 작업의 큰 상태. 작업 제어와 조회 필터링에 사용한다.
    // PENDING -> RUNNING -> SUCCEEDED/FAILED
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AnalysisJobStatus status;

    // RUNNING 상태에서 ai worker가 기록하는 상세 처리 단계.
    // 예: "본문 청킹 중", "LLM 분석 중", "결과 저장 중"
    @Column(name = "current_step", length = 100)
    private String currentStep;

    // Worker 재시작 시 완료된 내부 stage를 건너뛰기 위한 기계 판독용 checkpoint다.
    @Enumerated(EnumType.STRING)
    @Column(name = "checkpoint_stage", length = 50)
    private AnalysisJobCheckpointStage checkpointStage;

    // 현재 claim 소유자만 상태·토큰·비교 후보를 변경하도록 검증하는 실행별 식별자.
    @Column(name = "lease_token")
    private UUID leaseToken;

    // heartbeat가 갱신하는 lease 만료 시각. 만료된 실행의 후속 쓰기는 거절한다.
    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    // lease 만료 뒤 재claim한 횟수를 포함하며 최대 시도 제한에 사용한다.
    @Column(name = "claim_attempt_count", nullable = false)
    private int claimAttemptCount;

    @Column(name = "model_name", length = 100)
    private String modelName;

    // 이 Job에 정산된 provider 입력 토큰 합계.
    @Column(name = "input_token_count")
    private Integer inputTokenCount;

    // 이 Job에 정산된 provider 출력 토큰 합계.
    @Column(name = "output_token_count")
    private Integer outputTokenCount;

    // Worker 완료 시 후보 수와 stage별 처리량을 남기는 관측용 JSON 문자열.
    @Column(name = "summary_json", columnDefinition = "text")
    private String summaryJson;

    // 분석 작업 상세 조회에 보여줄 마지막 실패 메시지.
    // 실패 처리 이력은 후속 모니터링 기능에서 별도 기록/조회한다.
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    // Worker가 현재 실행 시도를 claim한 시각.
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    // 성공·실패로 종료된 시각.
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    private AnalysisJob(Work work, UploadBatch batch, Episode episode, AnalysisJobType jobType) {
        this.work = work;
        this.batch = batch;
        this.episode = episode;
        this.jobType = jobType;
        this.status = AnalysisJobStatus.PENDING;
        if (episode != null) {
            this.targetEpisodes.add(episode);
        }
    }

    public static AnalysisJob create(
            Work work,
            UploadBatch batch,
            Episode episode,
            AnalysisJobType jobType
    ) {
        return new AnalysisJob(work, batch, episode, jobType);
    }

    public static AnalysisJob createWorldSettingComparison(WorldSettingCandidate candidate) {
        AnalysisJob analysisJob = new AnalysisJob(
                candidate.getWork(),
                candidate.getAnalysisJob().getBatch(),
                candidate.getSourceEpisode(),
                AnalysisJobType.WORLD_SETTING_COMPARISON
        );
        analysisJob.worldSettingCandidate = candidate;
        return analysisJob;
    }

    public static AnalysisJob createCharacterFactComparison(SettingCandidate candidate) {
        AnalysisJob sourceJob = candidate.getAnalysisJob();
        AnalysisJob analysisJob = new AnalysisJob(
                candidate.getWork(),
                sourceJob == null ? null : sourceJob.getBatch(),
                candidate.getEpisode(),
                AnalysisJobType.CHARACTER_FACT_COMPARISON
        );
        analysisJob.settingCandidate = candidate;
        return analysisJob;
    }

    public UUID claim(String modelName, String currentStep, LocalDateTime leaseExpiresAt) {
        this.status = AnalysisJobStatus.RUNNING;
        if (modelName != null) {
            this.modelName = modelName;
        }
        if (currentStep != null) {
            this.currentStep = currentStep;
        }
        this.errorMessage = null;
        this.startedAt = LocalDateTime.now();
        this.leaseToken = UUID.randomUUID();
        this.leaseExpiresAt = leaseExpiresAt;
        this.claimAttemptCount++;
        return leaseToken;
    }

    public void updateTokenCounts(int inputTokenCount, int outputTokenCount) {
        this.inputTokenCount = inputTokenCount;
        this.outputTokenCount = outputTokenCount;
    }

    public void updateCurrentStep(String currentStep) {
        this.currentStep = currentStep;
    }

    public void updateCheckpointStage(AnalysisJobCheckpointStage checkpointStage) {
        if (checkpointStage == null) {
            return;
        }
        if (this.checkpointStage == null || checkpointStage.ordinal() >= this.checkpointStage.ordinal()) {
            this.checkpointStage = checkpointStage;
        }
    }

    public boolean hasReachedCheckpoint(AnalysisJobCheckpointStage checkpointStage) {
        return this.checkpointStage != null
                && this.checkpointStage.ordinal() >= checkpointStage.ordinal();
    }

    public boolean hasLease(UUID leaseToken) {
        return this.leaseToken != null && this.leaseToken.equals(leaseToken);
    }

    public boolean isLeaseExpired(LocalDateTime now) {
        return status == AnalysisJobStatus.RUNNING
                && (leaseExpiresAt == null || !leaseExpiresAt.isAfter(now));
    }

    public void renewLease(LocalDateTime leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public void requeueExpiredLease() {
        this.status = AnalysisJobStatus.PENDING;
        this.leaseToken = null;
        this.leaseExpiresAt = null;
        this.errorMessage = null;
        this.completedAt = null;
    }

    public void unlinkWorldSettingCandidate() {
        this.worldSettingCandidate = null;
    }

    public void unlinkSettingCandidate() {
        this.settingCandidate = null;
    }

    public void addTargetEpisodes(Collection<Episode> episodes) {
        this.targetEpisodes.addAll(episodes);
    }

    public void succeed(String summaryJson, Integer inputTokenCount, Integer outputTokenCount) {
        this.status = AnalysisJobStatus.SUCCEEDED;
        this.summaryJson = summaryJson;
        this.inputTokenCount = inputTokenCount;
        this.outputTokenCount = outputTokenCount;
        this.errorMessage = null;
        this.completedAt = LocalDateTime.now();
        clearLease();
    }

    public void fail(String errorMessage) {
        fail(errorMessage, inputTokenCount, outputTokenCount);
    }

    public void fail(String errorMessage, Integer inputTokenCount, Integer outputTokenCount) {
        this.status = AnalysisJobStatus.FAILED;
        this.errorMessage = errorMessage;
        this.inputTokenCount = inputTokenCount;
        this.outputTokenCount = outputTokenCount;
        this.completedAt = LocalDateTime.now();
        clearLease();
    }

    private void clearLease() {
        this.leaseToken = null;
        this.leaseExpiresAt = null;
    }
}
