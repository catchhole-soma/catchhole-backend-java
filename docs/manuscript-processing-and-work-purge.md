# 원고 처리 안내와 작품 영구 삭제

## 범위와 법무 검토 경계

이 문서는 현재 제품 동작과 개발 계약을 설명합니다. 개인정보처리방침이나 법률 자문을 대신하지 않으며, 출시 전에 실제 사업자 정보·인프라 계약·국외 이전 근거를 기준으로 법무 검토가 필요합니다.

현재 제품은 원고 분석을 서비스 계약의 핵심 기능으로 보고, 개인정보 보호법 제28조의8 제1항 제3호 가목의 `계약 체결·이행에 필요한 국외 처리위탁·보관 + 개인정보처리방침 공개` 경로를 전제로 설계합니다. 따라서 업로드별 동의 API와 증빙 테이블은 두지 않습니다. 이 전제가 실제 서비스·OpenAI 계약 관계와 일치하는지는 출시 전 법무 검토로 확정합니다.

GitHub #130의 최초 요구사항에는 업로드별 미공개 원고 처리 동의 이력이 포함되어 있었지만, 최종 제품 결정은 이를 별도 동의로 받지 않고 회원가입 시 이용약관 동의와 개인정보처리방침 확인을 문서별로 기록하는 것입니다. 현재 두 서비스 화면 문서 버전은 `2026-08-23`이며, `member_legal_records`에는 `TERMS_OF_SERVICE + AGREED`와 `PRIVACY_POLICY + ACKNOWLEDGED`를 별도 행으로 저장합니다. AI 처리는 실제 분석 실행 화면의 안내와 개인정보처리방침 공개로 설명합니다.

근거 자료는 다음과 같습니다.

- [개인정보 보호법 제28조의8](https://www.law.go.kr/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1029334953)
- [개인정보보호위원회 국외 이전 안내](https://www.pipc.go.kr/np/default/page.do?mCode=D060040010)
- [OpenAI API 데이터 제어 문서](https://developers.openai.com/api/docs/guides/your-data#default-usage-policies-by-endpoint)
- [한국 개인정보보호 약관 초안 저장소](https://github.com/kimlawtech/korean-privacy-terms)

## 사용자 안내와 API 경계

AI 분석을 실제로 실행하는 화면에는 다음 핵심 내용을 체크박스 없이 표시하고, 상세 개인정보처리방침으로 연결합니다.

> AI 분석을 실행하면 원고의 필요한 구간이 OpenAI API로 처리됩니다.<br>
> API 입력·출력은 기본적으로 모델 학습에 사용되지 않습니다. 자세히 보기

개인정보처리방침에는 이전 항목, 국가, 시점·방법, 이전받는 자와 연락처, 목적, 보유기간, 거부 방법과 영향을 공개합니다. 분석을 실행하지 않는 것이 국외 처리를 거부하는 방법이며, 거부하면 AI 분석 기능을 이용할 수 없습니다.

Backend의 회차·설정집 업로드와 회차 파일 교체 API에는 동의 버전이나 동의 여부 필드를 포함하지 않습니다. 업로드는 저장 요청을 처리하고, AI 분석은 별도 분석 작업 생성 API가 담당합니다.

OpenAI 공식 문서상 API 데이터는 명시적으로 공유에 옵트인하지 않는 한 모델 학습에 사용되지 않습니다. 현재 AI Worker는 Responses API 요청에 `store: false`를 명시하지 않으므로 응답 application state는 기본 30일 보관 대상이고, abuse monitoring log에도 고객 콘텐츠가 최대 30일 포함될 수 있습니다. ZDR 또는 Modified Abuse Monitoring을 보장하지 않는 한 개인정보처리방침에 이 제한을 그대로 알립니다.

## 영구 삭제 API

```http
DELETE /api/v1/works/{workId}
Content-Type: application/json

{"confirmation":"영구 삭제"}
```

공백이나 유사 문구는 허용하지 않습니다. 접수 성공은 `202 Accepted`이며 같은 회원·작품의 반복 요청은 같은 `requestId`를 반환합니다.

```http
GET /api/v1/works/purge-requests/{requestId}
GET /api/v1/works/{workId}/purge-request
POST /api/v1/works/purge-requests/{requestId}/retry
```

상태는 `REQUESTED → PROCESSING → COMPLETED`로 전이하고, 실패하면 `FAILED` 또는 일부 객체를 지운 `PARTIAL_FAILED`가 됩니다. 실패 상태만 재시도할 수 있습니다.

Spring 스케줄러는 기본 10초 간격으로 한 번 실행될 때 최대 10건을 처리하되, 실제 삭제를 시작할 요청만 한 건씩 `PROCESSING`으로 전환합니다. 따라서 앞 요청 처리 중 서버가 재시작되어도 아직 실행하지 않은 뒤 요청은 `REQUESTED` 상태를 유지합니다.

## 처리 순서와 경합 방지

1. 작품 row를 잠그고 `lifecycle_status=PURGING`으로 바꿉니다.
2. `PENDING`, `RUNNING` 분석 작업을 `CANCELED`로 바꾸고 lease를 제거합니다.
3. 예약 중인 AI 토큰을 `WORK_PURGE_CANCELED` 사유로 반환합니다.
4. 실행 중 작업이 있었다면 기본 75초 동안 Worker가 heartbeat 거절을 관찰하도록 기다립니다.
5. `works/{workId}/`와 모든 `upload-batches/{batchId}/` prefix의 S3 object version과 delete marker를 삭제합니다.
6. 저장소 삭제가 모두 성공한 경우에만 한 DB 트랜잭션에서 후보·근거·분석 작업·회차·업로드·작품을 자식부터 삭제합니다.
7. 삭제 요청 감사 row의 만료 시각을 완료 후 1년으로 설정합니다.

`PURGING` 작품은 잠금을 사용하는 모든 변경 API와 새 분석 요청에서 `WORK_PURGE_IN_PROGRESS`로 거절됩니다. Worker claim 쿼리도 `ACTIVE` 작품만 선택합니다. 저장소 삭제가 실패하면 DB를 보존하므로 prefix 삭제를 안전하게 재시도할 수 있습니다.
여러 S3 prefix 중 뒤 prefix의 목록 조회가 실패해도 앞 prefix에서 이미 집계한 대상·삭제 건수는 보존하며, 요청은 저장소 실패 건수를 포함한 실패 상태로 기록합니다.

## 보존하는 최소 감사 정보

작품 FK 없이 다음 삭제 처리 정보만 보존합니다.

- 삭제 요청 ID, 원래 작품 ID, 회원 ID
- 요청·처리·완료 시각과 시도 횟수
- S3·DB 대상/삭제/실패 건수와 정규화된 실패 코드

원고 본문, prompt, 모델 응답, 후보·근거 JSON과 업로드별 안내 확인 기록은 감사 row에 포함하지 않습니다. 완료 후 1년 보존은 현재 제품 기본값이며 출시 전 최종 법무 검토 대상으로 명시합니다.

## 운영 관측

- `work.purge.completed`: 완료 요청 수
- `work.purge.failed`: 실패 시도 수
- `work.purge.completion`: 요청부터 완료까지 걸린 시간
- `work.purge.overdue`: 요청 후 24시간 안에 완료되지 않은 요청 수
- `work.purge.sla.breached`: 완료까지 24시간을 넘긴 요청 수

저장소·DB 예외 로그에는 `requestId`만 남기고 원고 key나 본문을 기록하지 않습니다.

## 운영 검증 절차

PR·배포 전에는 복구 가능한 검증용 작품으로 다음 순서를 확인합니다.

1. 삭제 전 `works/{workId}/`와 해당 작품의 `upload-batches/{batchId}/` 목록을 기록합니다. Versioning 버킷이면 객체 화면의 `버전 표시` 또는 `aws s3api list-object-versions`로 과거 version과 delete marker도 확인합니다.
2. `DELETE /api/v1/works/{workId}`를 호출한 뒤 상태 API를 polling해 `COMPLETED`와 저장소·DB 실패 건수 0을 확인합니다.
3. 삭제 후 같은 prefix들을 다시 조회해 현재 객체, 과거 version, delete marker가 모두 0건인지 확인합니다.
4. `works`, `episodes`, `characters`, 후보·확정 설정, `analysis_jobs`, `ai_token_usages`, `upload_batches`, `upload_files` 등 작품 FK 범위를 조회해 잔존 데이터가 없는지 확인합니다.
5. `work_purge_requests`에는 본문 없이 요청 ID, 원래 작품 ID, 상태·시각·시도 횟수와 저장소별 대상/삭제/실패 건수만 남았는지 확인합니다.

검증용 작품도 실제 원고를 포함할 수 있으므로 work·batch ID는 PR 본문처럼 접근이 제한된 개발 기록에만 남기고 사용자용 문서나 애플리케이션 로그에는 원고 key를 기록하지 않습니다.
