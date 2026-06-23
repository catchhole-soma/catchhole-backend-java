# Upload Episode Workflow

회차 업로드 API가 `UploadBatch`, `UploadFile`, `Episode`를 어떻게 함께 생성하는지 정리합니다.

도메인별 필드 설명은 [Upload](upload.md), [Episode](episode.md)를 기준으로 확인합니다.

## 전체 흐름

```mermaid
flowchart TD
    A["Client"] --> B["POST /api/v1/works/{workId}/episodes"]
    B --> C["작품 소유권 확인"]
    C --> D{"본인 작품인가?"}
    D -- "아니오" --> E["WORK_NOT_FOUND"]
    D -- "예" --> F["EpisodeFileParser.parse"]
    F --> G{"업로드 유형/파일 유효한가?"}
    G -- "아니오" --> H["UploadErrorCode"]
    G -- "예" --> I["회차 번호 중복 검사"]
    I --> J{"중복 회차인가?"}
    J -- "예" --> K["EPISODE_DUPLICATED"]
    J -- "아니오" --> L["UploadBatch 생성"]
    L --> M["원본 파일 S3 저장"]
    M --> N["UploadFile 저장 및 PARSED 표시"]
    N --> O["회차 원문 S3 저장"]
    O --> P["Episode 저장"]
    P --> Q["Work.latestEpisodeNo 갱신"]
    Q --> R["설정집 파일 있으면 UploadFile 저장"]
    R --> S["UploadBatch COMPLETED"]
    S --> T["EpisodeUploadResponse 반환"]
```

## Sequence

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant Controller as EpisodeController
    participant Service as EpisodeService
    participant Processor as EpisodeUploadProcessor
    participant Parser as EpisodeFileParser
    participant BatchRepo as UploadBatchRepository
    participant FileRepo as UploadFileRepository
    participant EpisodeRepo as EpisodeRepository
    participant Storage as ObjectStorageService

    Client->>Controller: multipart POST /works/{workId}/episodes
    Controller->>Service: uploadEpisodes(memberId, workId, request, files, settingBook)
    Service->>Service: getOwnedWork(workId, memberId)
    Service->>Processor: upload(work, request, files, settingBook)
    Processor->>Parser: parse(request, episodeFiles)
    Parser-->>Processor: List<ParsedEpisodeFile>
    Processor->>EpisodeRepo: existsByWorkIdAndEpisodeNo(...)
    alt 회차 번호 중복
        EpisodeRepo-->>Processor: true
        Processor-->>Controller: EPISODE_DUPLICATED
        Controller-->>Client: 409
    else 업로드 가능
        Processor->>BatchRepo: save(UploadBatch PENDING)
        Processor->>BatchRepo: status PROCESSING, fileCount 갱신
        loop parsed upload file
            Processor->>Storage: putUploadFile(batchId, originalFilename, bytes)
            Storage-->>Processor: StoredObject
            Processor->>FileRepo: save(UploadFile EPISODE)
            Processor->>FileRepo: markParsed(startNo, endNo, count)
            loop parsed episode
                Processor->>Storage: putEpisodeContent(workId, episodeNo, content)
                Storage-->>Processor: StoredTextObject
                Processor->>EpisodeRepo: save(Episode UPLOADED)
            end
        end
        opt settingBookFile present
            Processor->>Storage: putUploadFile(batchId, settingBookFile)
            Processor->>FileRepo: save(UploadFile SETTING_BOOK)
            Processor->>FileRepo: markParsed(null, null, null)
        end
        Processor->>BatchRepo: status COMPLETED
        Processor-->>Controller: EpisodeUploadResponse
        Controller-->>Client: 200 OK
    end
```

## 업로드 유형별 파싱

```mermaid
flowchart LR
    A["EpisodeUploadRequest.uploadType"] --> B{"SINGLE_EPISODE"}
    A --> C{"MULTI_EPISODE_MULTI_FILE"}
    A --> D{"MULTI_EPISODE_SINGLE_FILE"}
    A --> E{"INITIAL_IMPORT"}

    B --> B1["파일 1개 + episodeNo 필수"]
    B1 --> B2["요청 title 또는 파일명으로 제목 결정"]

    C --> C1["각 파일명 또는 본문에서 회차 번호 감지"]
    C1 --> C2["파일 1개를 회차 1개로 저장"]

    D --> D1["파일 1개 본문에서 heading 목록 감지"]
    D1 --> D2["heading 사이 본문을 회차별로 분리"]

    E --> E1["UPLOAD_TYPE_NOT_SUPPORTED"]
```

## 저장 경로

```text
원본 업로드 파일:
upload-batches/{batchId}/{randomUUID}-{originalFilename}

회차 원문:
works/{workId}/episodes/{episodeNo}.txt
```

## 분석 작업과의 연결

분석 작업은 `batchId`를 입력으로 받도록 설계되어 있습니다.

회차 업로드가 끝나면 다음 관계로 분석 대상을 찾을 수 있습니다.

```mermaid
flowchart LR
    A["analysis_jobs.batch_id"] --> B["upload_batches.id"]
    B --> C["upload_files.batch_id"]
    C --> D["upload_files.id"]
    D --> E["episodes.source_file_id"]
    E --> F["Episode"]
```
