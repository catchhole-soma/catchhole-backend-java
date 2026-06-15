# Analysis Workflow

Analysis 도메인의 API별 처리 흐름을 눈으로 확인하기 위한 문서입니다.

상세 필드와 설계 결정은 [Analysis](analysis.md)를 기준으로 확인합니다.

## 전체 흐름

```mermaid
flowchart TD
    A["Client"] --> B["POST /api/v1/works/{workId}/analysis-jobs"]
    B --> C["작품 소유권 확인"]
    C --> D{"본인 작품인가?"}
    D -- "아니오" --> E["WORK_NOT_FOUND"]
    D -- "예" --> F["batchId로 UploadBatch 조회"]
    F --> G{"작품에 속한 batch인가?"}
    G -- "아니오" --> H["ANALYSIS_JOB_TARGET_NOT_FOUND"]
    G -- "예" --> I["AnalysisJob 생성"]
    I --> J["status = PENDING"]
    J --> K["AnalysisJobResponse 반환"]
```

## 분석 작업 생성 API

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant Controller as AnalysisJobController
    participant Service as AnalysisJobService
    participant WorkRepo as WorkRepository
    participant BatchRepo as UploadBatchRepository
    participant JobRepo as AnalysisJobRepository

    Client->>Controller: POST /works/{workId}/analysis-jobs
    Note over Client,Controller: body: jobType, batchId
    Controller->>Service: createAnalysisJob(memberId, workId, request)
    Service->>WorkRepo: getOwnedWork(workId, memberId)
    alt 작품 없음 또는 타인 작품
        WorkRepo-->>Service: empty
        Service-->>Controller: WORK_NOT_FOUND
        Controller-->>Client: 404
    else 본인 작품
        WorkRepo-->>Service: Work
        Service->>BatchRepo: findByIdAndWorkId(batchId, workId)
        alt batch 없음 또는 다른 작품 batch
            BatchRepo-->>Service: empty
            Service-->>Controller: ANALYSIS_JOB_TARGET_NOT_FOUND
            Controller-->>Client: 404
        else 분석 대상 batch 확인
            BatchRepo-->>Service: UploadBatch
            Service->>JobRepo: save(AnalysisJob PENDING)
            JobRepo-->>Service: saved AnalysisJob
            Service-->>Controller: AnalysisJobResponse
            Controller-->>Client: 200 OK
        end
    end
```

## 분석 작업 목록 조회 API

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant Controller as AnalysisJobController
    participant Service as AnalysisJobService
    participant WorkRepo as WorkRepository
    participant JobRepo as AnalysisJobRepository

    Client->>Controller: GET /works/{workId}/analysis-jobs
    Controller->>Service: getAnalysisJobs(memberId, workId)
    Service->>WorkRepo: getOwnedWork(workId, memberId)
    alt 작품 없음 또는 타인 작품
        WorkRepo-->>Service: empty
        Service-->>Controller: WORK_NOT_FOUND
        Controller-->>Client: 404
    else 본인 작품
        WorkRepo-->>Service: Work
        Service->>JobRepo: findAllByWorkIdOrderByCreatedAtDesc(workId)
        JobRepo-->>Service: List<AnalysisJob>
        Service-->>Controller: List<AnalysisJobResponse>
        Controller-->>Client: 200 OK
    end
```

## 분석 작업 상세 조회 API

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant Controller as AnalysisJobController
    participant Service as AnalysisJobService
    participant WorkRepo as WorkRepository
    participant JobRepo as AnalysisJobRepository

    Client->>Controller: GET /works/{workId}/analysis-jobs/{analysisJobId}
    Controller->>Service: getAnalysisJob(memberId, workId, analysisJobId)
    Service->>WorkRepo: getOwnedWork(workId, memberId)
    alt 작품 없음 또는 타인 작품
        WorkRepo-->>Service: empty
        Service-->>Controller: WORK_NOT_FOUND
        Controller-->>Client: 404
    else 본인 작품
        WorkRepo-->>Service: Work
        Service->>JobRepo: findByIdAndWorkId(analysisJobId, workId)
        alt 작업 없음 또는 다른 작품 작업
            JobRepo-->>Service: empty
            Service-->>Controller: ANALYSIS_JOB_NOT_FOUND
            Controller-->>Client: 404
        else 작업 확인
            JobRepo-->>Service: AnalysisJob
            Service-->>Controller: AnalysisJobResponse
            Controller-->>Client: 200 OK
        end
    end
```

## Batch 내부 분석 대상 조회

분석 작업 생성 request는 `batchId`만 받습니다.

실제 분석 단계에서 batch에 속한 회차 목록이 필요하면 다음 관계를 따라 조회합니다.

```mermaid
flowchart LR
    A["analysis_jobs.batch_id"] --> B["upload_batches.id"]
    B --> C["upload_files.batch_id"]
    C --> D["upload_files.id"]
    D --> E["episodes.source_file_id"]
    E --> F["Episode list"]
    F --> G["AI Worker 분석 대상"]
```

## 상태 전이

```mermaid
stateDiagram-v2
    [*] --> PENDING: 분석 작업 생성
    PENDING --> RUNNING: 워커가 작업 시작
    RUNNING --> SUCCEEDED: 분석 성공
    RUNNING --> FAILED: 분석 실패
    FAILED --> RUNNING: 재시도 정책 확정 후 가능
    SUCCEEDED --> [*]
```

현재 구현 범위에서는 생성 시 `PENDING` 상태로 저장하고, 이후 `RUNNING`, `SUCCEEDED`, `FAILED` 전이는 워커 연동 작업에서 연결합니다.
