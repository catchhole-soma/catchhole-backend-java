package org.monitoring.catchholebackend.domain.analysis.entity;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
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
    @OnDelete(action = OnDeleteAction.SET_NULL)
    @JoinColumn(
            name = "world_setting_candidate_id",
            foreignKey = @ForeignKey(name = "fk_analysis_jobs_world_setting_candidate")
    )
    private WorldSettingCandidate worldSettingCandidate;

    // CHARACTER_FACT_COMPARISON Job이 다시 비교할 후보. 일반 회차 분석 Job에서는 null이다.
    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.SET_NULL)
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

    // 실행 도중 파일 수·남은 Job 수로 재해석하지 않는 입력 정책. legacy는 확정 설정 경로다.
    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_mode", nullable = false, length = 30, updatable = false)
    private AnalysisMode analysisMode = AnalysisMode.CONFIRMED_ONLY;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_mode", nullable = false, length = 20, updatable = false)
    private AnalysisReviewMode reviewMode = AnalysisReviewMode.MANUAL;

    // 자동 반영 회차는 실제 시작 직전의 작품 설정과 선행 미해결 참고를 고정한다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "automatic_input_state", columnDefinition = "jsonb")
    private JsonNode automaticInputState;

    // 캐릭터와 세계관의 자동 반영이 모두 커밋되는 완료 경계다.
    @Column(name = "automatic_applied_at")
    private LocalDateTime automaticAppliedAt;

    @Column(name = "analysis_run_id", updatable = false)
    private UUID analysisRunId;

    @Column(name = "run_generation", updatable = false)
    private Long runGeneration;

    // 회차 번호와 분리한 실행 내부의 0부터 시작하는 순서다.
    @Column(name = "run_sequence", updatable = false)
    private Integer runSequence;

    @Column(name = "predecessor_job_id", updatable = false)
    private UUID predecessorJobId;

    // 첫 Job에만 고정 S0를 저장한다. 다음 Job은 선행 변경 기록을 재적용한다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "run_base_state", columnDefinition = "jsonb")
    private JsonNode runBaseState;

    @Column(name = "input_state_hash", length = 64)
    private String inputStateHash;

    // 실행 대상의 회차 번호도 고정해 원문이 같아도 순서가 바뀐 결과를 거절한다.
    @Column(name = "source_episode_no", updatable = false)
    private Integer sourceEpisodeNo;

    @Column(name = "source_content_hash", length = 64, updatable = false)
    private String sourceContentHash;

    @Column(name = "source_content_s3_key", columnDefinition = "text", updatable = false)
    private String sourceContentS3Key;

    @Column(name = "source_content_s3_version", length = 255, updatable = false)
    private String sourceContentS3Version;

    // 후보 현재값과 분리된 당시 검증 결과. SEALED 이후 내용은 변경하지 않는다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state_journal", columnDefinition = "jsonb")
    private JsonNode stateJournal;

    @Enumerated(EnumType.STRING)
    @Column(name = "journal_status", length = 30)
    private AnalysisJournalStatus journalStatus;

    @Column(name = "journal_invalidation_reason", length = 200)
    private String journalInvalidationReason;

    // 분석 작업의 큰 상태. 작업 제어와 조회 필터링에 사용한다.
    // PENDING -> RUNNING -> SUCCEEDED/FAILED/CANCELED
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

    // Worker 실패를 재시도 정책과 사용자 메시지에 사용할 수 있도록 정규화한 코드.
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 60)
    private AnalysisFailureCode failureCode;

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
            sourceEpisodeNo = episode.getEpisodeNo();
            sourceContentHash = episode.getContentHash();
            sourceContentS3Key = episode.getContentS3Key();
            sourceContentS3Version = episode.getContentS3Version();
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
        this.failureCode = null;
        this.startedAt = LocalDateTime.now();
        this.leaseToken = UUID.randomUUID();
        this.leaseExpiresAt = leaseExpiresAt;
        this.claimAttemptCount++;
        return leaseToken;
    }

    public boolean isOrderedProvisional() {
        return analysisMode == AnalysisMode.ORDERED_PROVISIONAL;
    }

    public boolean isAutomaticReview() {
        return reviewMode == AnalysisReviewMode.AUTOMATIC;
    }

    public boolean isAutomaticApplicationPending() {
        return isAutomaticReview() && automaticAppliedAt == null
                && (status == AnalysisJobStatus.PENDING || status == AnalysisJobStatus.RUNNING);
    }

    public void configureReviewMode(AnalysisReviewMode mode) {
        if (status != AnalysisJobStatus.PENDING || analysisRunId != null || startedAt != null) {
            throw new IllegalStateException("시작한 분석의 반영 방식은 변경할 수 없습니다.");
        }
        reviewMode = Objects.requireNonNull(mode);
    }

    public void captureAutomaticInput(JsonNode input) {
        if (!isAutomaticReview() || !isOrderedProvisional() || inputStateHash != null
                || automaticInputState != null || input == null || !input.isObject()) {
            throw new IllegalStateException("자동 분석의 시작 문맥을 다시 지정할 수 없습니다.");
        }
        automaticInputState = input.deepCopy();
    }

    public void completeAutomaticApplication() {
        if (!isAutomaticReview() || status != AnalysisJobStatus.RUNNING
                || journalStatus != AnalysisJournalStatus.SEALED) {
            throw new IllegalStateException("분석이 완료되지 않아 설정을 자동 반영할 수 없습니다.");
        }
        if (automaticAppliedAt == null) {
            automaticAppliedAt = LocalDateTime.now();
        }
    }

    public void initializeOrderedRun(UUID runId, long generation, int sequence, UUID predecessorId, JsonNode base) {
        if (status != AnalysisJobStatus.PENDING || analysisRunId != null || episode == null
                || jobType != AnalysisJobType.SETTING_EXTRACTION || runId == null || generation < 1
                || sequence < 0 || (sequence == 0) != (predecessorId == null)
                || (sequence == 0) != (base != null && base.isObject())
                || episode.getContentHash() == null || !episode.getContentHash().matches("[0-9a-f]{64}")
                || episode.getContentS3Key() == null) {
            throw new IllegalArgumentException("누적 실행의 대상·순서·시작 상태·원문 버전이 올바르지 않습니다.");
        }
        analysisMode = AnalysisMode.ORDERED_PROVISIONAL;
        analysisRunId = runId;
        runGeneration = generation;
        runSequence = sequence;
        predecessorJobId = predecessorId;
        runBaseState = base == null ? null : base.deepCopy();
        sourceEpisodeNo = episode.getEpisodeNo();
        sourceContentHash = episode.getContentHash();
        sourceContentS3Key = episode.getContentS3Key();
        sourceContentS3Version = episode.getContentS3Version();
        journalStatus = AnalysisJournalStatus.PENDING;
    }

    public boolean hasCurrentSourceVersion() {
        return !isOrderedProvisional() || (episode != null
                && Objects.equals(sourceEpisodeNo, episode.getEpisodeNo())
                && episode.getStatus() != org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.ARCHIVED
                && Objects.equals(sourceContentHash, episode.getContentHash())
                && Objects.equals(sourceContentS3Key, episode.getContentS3Key())
                && Objects.equals(sourceContentS3Version, episode.getContentS3Version()));
    }

    public void prepareOrderedInput(String hash) {
        if (!isOrderedProvisional() || journalStatus != AnalysisJournalStatus.PENDING
                || hash == null || !hash.matches("[0-9a-f]{64}")
                || (inputStateHash != null && !inputStateHash.equals(hash))) {
            throw new IllegalArgumentException("누적 분석 입력 상태가 일치하지 않습니다.");
        }
        inputStateHash = hash;
    }

    public void replacePendingJournal(JsonNode journal) {
        if (journalStatus != AnalysisJournalStatus.PENDING || journal == null || !journal.isObject()) {
            throw new IllegalStateException("완료되거나 무효화된 변경 기록을 수정할 수 없습니다.");
        }
        stateJournal = journal.deepCopy();
    }

    public void sealJournal() {
        if (journalStatus != AnalysisJournalStatus.PENDING || stateJournal == null
                || !stateJournal.path("outputStateHash").isTextual()) {
            throw new IllegalStateException("완성되지 않은 변경 기록은 후속 회차에 전달할 수 없습니다.");
        }
        journalStatus = AnalysisJournalStatus.SEALED;
    }

    public void markJournalIncomplete() {
        if (journalStatus == AnalysisJournalStatus.PENDING) {
            journalStatus = AnalysisJournalStatus.INCOMPLETE;
        }
    }

    public void invalidateJournal(String reason) {
        if (!isOrderedProvisional()) {
            return;
        }
        journalStatus = AnalysisJournalStatus.INVALIDATED;
        journalInvalidationReason = reason;
        if (status == AnalysisJobStatus.PENDING || status == AnalysisJobStatus.RUNNING) {
            status = AnalysisJobStatus.CANCELED;
            completedAt = LocalDateTime.now();
            clearLease();
        }
    }

    public JsonNode getRunBaseState() {
        return runBaseState == null ? null : runBaseState.deepCopy();
    }

    public void purgeJournalSourceEvidence(JsonNode redacted) {
        if (journalStatus != AnalysisJournalStatus.INVALIDATED) {
            throw new IllegalStateException("원문 근거는 실행을 무효화한 뒤에만 파기할 수 있습니다.");
        }
        stateJournal = redacted == null ? null : redacted.deepCopy();
        runBaseState = purgeCapturedEvidence(runBaseState);
        automaticInputState = purgeCapturedEvidence(automaticInputState);
    }

    private JsonNode purgeCapturedEvidence(JsonNode captured) {
        if (captured == null) return null;
        com.fasterxml.jackson.databind.node.ObjectNode redacted = captured.deepCopy();
        for (String domain : List.of("characters", "worldSettings")) {
            redacted.path(domain).forEach(target -> {
                if (target.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) target).remove("identityEvidence");
            });
        }
        redacted.putObject("references");
        redacted.put("sourceEvidencePurged", true);
        return redacted;
    }

    public JsonNode getStateJournal() {
        return stateJournal == null ? null : stateJournal.deepCopy();
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
        this.failureCode = null;
        this.completedAt = null;
    }

    /** 사용자 재시도는 같은 실행의 완료된 prefix와 추출 checkpoint를 보존한다. */
    public void resumeFailedOrderedAttempt() {
        if (!isOrderedProvisional()
                || (status != AnalysisJobStatus.FAILED
                    && !(status == AnalysisJobStatus.SUCCEEDED && journalStatus == AnalysisJournalStatus.INCOMPLETE))
                || (journalStatus != AnalysisJournalStatus.PENDING
                    && journalStatus != AnalysisJournalStatus.INCOMPLETE)
                || !hasCurrentSourceVersion()) {
            throw new IllegalStateException("같은 입력에서 재개할 수 있는 누적 분석 실패가 아닙니다.");
        }
        this.status = AnalysisJobStatus.PENDING;
        this.journalStatus = AnalysisJournalStatus.PENDING;
        this.currentStep = "실패한 누적 분석 재시도 대기";
        this.errorMessage = null;
        this.failureCode = null;
        this.summaryJson = null;
        this.startedAt = null;
        this.completedAt = null;
        // 사용자 명시 요청마다 자동 lease 복구 한도를 새로 부여한다. 사용 토큰은 보존한다.
        this.claimAttemptCount = 0;
        clearLease();
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
        this.failureCode = null;
        this.completedAt = LocalDateTime.now();
        clearLease();
    }

    public void fail(String errorMessage) {
        fail(AnalysisFailureCode.UNEXPECTED_ERROR, errorMessage, inputTokenCount, outputTokenCount);
    }

    public void fail(String errorMessage, Integer inputTokenCount, Integer outputTokenCount) {
        fail(AnalysisFailureCode.UNEXPECTED_ERROR, errorMessage, inputTokenCount, outputTokenCount);
    }

    public void fail(AnalysisFailureCode failureCode, String errorMessage) {
        fail(failureCode, errorMessage, inputTokenCount, outputTokenCount);
    }

    public void fail(
            AnalysisFailureCode failureCode,
            String errorMessage,
            Integer inputTokenCount,
            Integer outputTokenCount
    ) {
        this.status = AnalysisJobStatus.FAILED;
        this.failureCode = AnalysisFailureCode.orUnexpected(failureCode);
        this.errorMessage = errorMessage;
        this.inputTokenCount = inputTokenCount;
        this.outputTokenCount = outputTokenCount;
        this.completedAt = LocalDateTime.now();
        clearLease();
    }

    public void cancelForWorkPurge() {
        if (status != AnalysisJobStatus.PENDING && status != AnalysisJobStatus.RUNNING) {
            return;
        }
        this.status = AnalysisJobStatus.CANCELED;
        this.currentStep = "작품 영구 삭제로 취소됨";
        this.errorMessage = null;
        this.failureCode = null;
        this.completedAt = LocalDateTime.now();
        clearLease();
    }

    /** 기존 확정 설정 경로에서 세계관 비교 전용 API로 재개할 토큰 중단인지 판별한다. */
    public boolean isResumableTokenInterruption() {
        return !isOrderedProvisional()
                && jobType == AnalysisJobType.SETTING_EXTRACTION
                && status == AnalysisJobStatus.FAILED
                && failureCode == AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED
                && hasReachedCheckpoint(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
    }

    private void clearLease() {
        this.leaseToken = null;
        this.leaseExpiresAt = null;
    }
}
