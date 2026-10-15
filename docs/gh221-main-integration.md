# #221 Java main 통합 검증

2026-10-15 기준 #221 Java 변경을 main `836a4b7`에 통합한 검토 기록이다. 제품 변경 기준 commit은 `b7de498`, 실행 설정 기준 commit은 `ded0236`이다. 이 문서는 통합 범위와 검증 상태를 기록하며 병합·배포 완료를 의미하지 않는다.

## 변경 범위

- **설정집 분석 기반:** 별도 `/api/v1/works/{workId}/setting-books` 업로드, 불변 revision과 분석 당시 원문 근거, `SETTING_BOOK` singleton 분석·후보·출처 분기 및 Worker capability 검증을 제공한다. 회차 업로드의 설정집 동시 첨부는 제거했다. TXT·DOCX·HWP·HWPX 지원은 유지한다.
- **누적 모순 처리:** 같은 항목의 모순 기여 후보를 검토로 남기고 실제 변경 slot/path의 frozen 입력 값·부재·출처를 복원한다. 독립 항목의 자동 반영과 확정된 이력은 보존한다.
- **승인된 시점·이력 정책:** 설정집 기준 시점과 캐릭터 서사 시점, 현재값 보호, PRESENT 자동 반영의 승인된 예외 및 PAST/HISTORY_ONLY 자동 이력 확정을 적용한다. 현재값·직접 수정 보호·출처/시도 검증의 경계를 유지한다.
- **고정 근거와 사용량 연계:** 불변 원문 revision·hash·lease와 누적 입력 근거를 검증하며 기존 분석 토큰 예약·정산 및 main의 채팅/검색 원장과 공존하도록 통합한다. 새 모델 호출로 품질을 재평가한 결과는 포함하지 않는다.

## Migration 순서

main의 V74(표시명), V75(채팅 실행), V76(원문 검색)은 보존했다. 기존 BOOK V74/V75/V76은 내용 바이트를 바꾸지 않고 V77/V78/V79로 번호만 옮겼다.

| 버전 | 파일 |
| --- | --- |
| V77 | `V77__add_setting_book_analysis.sql` |
| V78 | `V78__setting_book_reference_time_and_current_protection.sql` |
| V79 | `V79__character_narrative_time.sql` |

기존 DB에 적용한 migration의 checksum 변경이나 Flyway repair를 수행하지 않았다. 실제 PostgreSQL 검증은 이 작업 소유의 새 격리 DB를 사용한다. 향후 적용 순서는 Java V77–V79 이후 AI PR [#81](https://github.com/catchhole-soma/catchhole-backend-ai/pull/81)·[#82](https://github.com/catchhole-soma/catchhole-backend-ai/pull/82)이며, 이번 작업에서 병합·배포하지 않는다.

## 검증 상태

증거는 작업공간의 `outputs/pr221-java-main-integration-20261015/tests/`에 보관한다. 저장소에는 개인 원고·gold·원본 응답·실행 결과와 비이식적인 로컬 검증 harness 네 개를 추가하지 않는다.

| 검사 | 결과 |
| --- | --- |
| Java compile 및 bootJar | 통과 |
| H2 중심 전체 회귀 검사 | 실제 실행 2,228건 통과, 실패·오류 0. XML 151개 파일의 전체 2,463건 중 235건 skip |
| PostgreSQL/Flyway 통합 검사 | 새 격리 DB에서 215건 통과. V1→V79 신규 설치·JPA validate, 별도 scratch schema의 main V76→V79 증분 적용 및 기존 채팅 데이터·checksum 이력 보존 확인 |
| 수정 대상 재검증 | PostgreSQL 215건 + 회차 API 67건 + 설정집 API 6건 + 회차 없는 설정집 비교 1건 = 289건 통과 |
| Python SQLAlchemy와 Java migration 호환 | 신규 BOOK 30개 컬럼의 타입·nullable 일치. 9개 테이블 172개 컬럼 strict 검사에는 기존 V4/V44/V45 매핑 차이 4건이 남으며 이번 변경에서 수정하지 않음 |
| 독립 Astra 제품 리뷰 | 조치가 필요한 제품 문제 없음 |

`full-h2-final.log`는 `BUILD SUCCESSFUL`(2분 13초)이다. XML skip에는 환경 조건으로 비활성화된 parameterized template descriptor 73개가 포함되므로 Gradle 표시 건수와 다를 수 있다. 위 통과 건수는 실제 실행 기준이다. 새 컨테이너 생성을 금지한 로컬 검증 범위에 맞춰 기존 Testcontainers 클래스 15개를 제외했으며 해당 검증은 CI에서 수행해야 한다. 비이식적인 로컬 harness 네 개도 공개 검증 범위에서 제외했다.

SQLAlchemy strict 검사 차이는 `analysis_jobs.journal_invalidation_reason` 길이(V44), `setting_candidates.provisional_subject_key` 길이(V45), `works.genre` nullable과 `works.description` 타입(V4)이다. 신규 BOOK schema의 호환 범위를 확인한 결과이며 전체 legacy ORM이 완전히 일치한다는 의미는 아니다. 원본 strict 결과와 BOOK 한정 summary를 모두 보존했다.

## 재현과 실행 경계

일반 checkout에서는 `./gradlew --no-daemon compileJava compileTestJava bootJar`와 `./gradlew --no-daemon test`로 빌드·CI 검증을 수행한다. 이번 로컬 범위는 저장소 밖 검증 runner가 외부 환경변수와 위 Testcontainers/harness를 제외한 뒤 실행했다. 동일 작업공간에서의 재현 명령은 다음과 같다. PostgreSQL 명령에는 이미 생성한 작업 소유 DB receipt가 필요하다.

```sh
python3 outputs/pr221-java-main-integration-20261015/tests/run-java.py test --rerun-tasks
python3 outputs/pr221-java-main-integration-20261015/tests/run-java.py --owned-postgres test --tests '*SettingBookMainMigrationPostgresIntegrationTest' --rerun-tasks
```

집계·원문 로그는 `final-verification.json`, `full-h2-final.log`, `targeted-fixes-summary.json`, `targeted-fixes.log`, `sqlalchemy-conformance.json`, `sqlalchemy-book-conformance-summary.json`에 남겼다. 공개 코드·리소스·빌드 파일 967개의 실행 전후 SHA-256은 동일하며 `final-tested-source-hashes.json`에 보존했다. 이후 테스트 commit `6877e8a`에서 테스트 파일 3개의 EOF 빈 줄만 각각 1바이트 제거했으며, 의미 변경 없이 원본문이 동일함과 tested/after SHA를 `whitespace-normalization.json`에 기록했다.

새 모델 호출이나 API/Worker 재시작을 수행하지 않았다. 테스트용 PostgreSQL은 사용자 승인에 따라 기존 `gh221-local-postgres` 컨테이너를 시작한 뒤 유지했으며, 새 작업 소유 DB와 scratch schema만 사용했다. 기존 테스트 DB의 drop/reset/repair나 새 컨테이너 생성은 수행하지 않았다.
