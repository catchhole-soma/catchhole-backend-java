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

/*
 * analysis_jobs 테이블
 *
 * 작품 단위 AI 분석 작업을 추적하는 테이블이다.
 * 사용자가 분석을 요청하면 AnalysisJob이 PENDING 상태로 생성되고,
 * Python AI Worker가 내부 API로 작업을 claim하면서 RUNNING 상태로 변경된다.
 *
 * 분석 대상은 주로 upload_batches.batch_id를 기준으로 연결한다.
 * Worker는 batch -> upload_files -> episodes 흐름으로 실제 분석할 회차 목록을 찾고,
 * Episode에 저장된 S3 원문 메타데이터를 사용해 분석을 수행한다.
 *
 * 이 테이블에는 원문 본문을 저장하지 않고, 작업 유형, 상태, 현재 단계,
 * 사용 모델명, 토큰 수, 요약 결과 JSON, 실패 사유, 시작/완료 시각 같은
 * 분석 작업의 상태와 결과 메타데이터만 저장한다.
 *
 * episode_id는 batch 전체가 아니라 특정 회차 단위 분석이 필요할 때 사용하는 선택 연결이다.
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

    //해당 컬럼은 특정 회차 단위 분석이 필요한 경우에 사용(상황 : 재분석)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "episode_id",
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_analysis_jobs_episode")
    )
    private Episode episode;

    //분석작업의 종류(설정집 , 회차 검수)
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

    @Column(name = "model_name", length = 100)
    private String modelName;

    // 사용자가 요청한 작업의 입력 토큰값
    @Column(name = "input_token_count")
    private Integer inputTokenCount;

    //사용자의 분석의 필요한 결과 반환 토큰 값.
    @Column(name = "output_token_count")
    private Integer outputTokenCount;

    //ai worker 에서 받은 분석 내용(json)
    //TODO: 해당 컬럼이 필요한지 고민 할 필요가 있음
    @Column(name = "summary_json", columnDefinition = "text")
    private String summaryJson;

    //분석 실패시의 errorMessage 내용
    //TODO : 실패 사유가 여러 도메인에서 필요해지면 공통 실패 이력 Table 로 분리하는 방안도 검토하기
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    //ai worker 에서 api 호출하여 분석이 실행됐을때의 실제 시간
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    //분석 완료 시간
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
