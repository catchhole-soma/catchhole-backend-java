# 한글 원고 업로드 (GH224)

## 지원 범위와 추출 규칙

TXT(UTF-8), DOCX에 일반 HWP 5.x 및 HWPX를 추가한다. 한 회차, 한 파일 여러 회차, 여러 파일 다회차, 설정집, 회차 파일 교체가 같은 `TextDocumentReader`를 사용한다. 여러 파일은 형식을 섞을 수 있으며 번호 감지·확정 매핑·분석 순서는 기존 계약을 유지한다. 업로드 응답 배열은 원본 파일 순서를 유지하며 분석 순서는 확정 회차 번호 기준이다.

- 문단/명시적 줄바꿈은 `\n`, 탭은 `\t`로 보존한다. 마지막 문단에 파서가 덧붙인 줄바꿈 하나만 제거한다. 회차 분량 계산에는 앞뒤 공백도 포함한다.
- 표의 셀 사이에는 탭, 행 사이에는 줄바꿈을 넣는다. 여러 문단인 셀의 내부 줄바꿈도 보존한다. 병합 셀 내용을 복제하거나 화면 너비를 공백으로 재현하지 않는다.
- 표·글상자·도형 안 텍스트와 캡션은 연결된 문단 뒤에 문서 저장 순서대로 한 번 넣는다. 페이지상의 x/y 좌표나 다단 조판의 시각적 읽기 순서를 재구성하지 않는다.
- HWP는 `BodyText/Section0…N` 순서, HWPX는 `Contents/content.hpf`의 manifest/spine 순서로 모든 구역을 읽는다. ZIP 엔트리 순서나 미리보기 `PrvText`를 본문으로 사용하지 않는다.
- HWPX의 구역·문단 요소는 Hancom HWPML 2011 및 OWPML 2021/2024 네임스페이스를 허용 목록으로 판별한다. 접두사나 태그 이름만으로 다른 XML을 본문으로 해석하지 않으며, 명시적인 `hyphen` 요소는 `-`로 보존한다. 네임스페이스별 문단·표·글상자 추출과 부가 텍스트 제외를 합성 문서로 검증한다.
- 머리말·꼬리말·바탕쪽·각주·미주·숨은 설명은 제외한다. 반복된 회차 제목이나 각주가 회차 경계로 인식되는 일을 방지한다.
- 그림/미리보기/OLE 첨부/스크립트의 내용을 읽거나 실행하지 않는다. 그림 주변의 본문은 포함한다. OCR, 수식의 시각적 표현, 자동 번호/글머리표의 서식 재구성은 지원하지 않는다.
- HWP 3.x, 암호 문서, 배포용 HWP, DRM/인증서로 보호된 HWP는 이번 지원 범위 밖이다. 일반 HWP 5 또는 HWPX로 다시 저장하도록 안내하며 잠금 해제를 시도하지 않는다.

## 구현과 자원 제한

Apache POI 5.5.1(Apache-2.0)의 POIFS로 HWP OLE 컨테이너를 연다. 본문 추출에 필요한 HWP 5 공개 규격의 레코드 경계·문단·컨트롤·셀 주소만 읽고 전체 서식/그림 객체 모델은 만들지 않는다. HWPX는 JDK ZIP/StAX로 읽는다. 사전 검토한 hwplib/hwpxlib는 합성 fixture 생성과 로컬 결과 대조에만 쓰며 운영 JAR에는 포함하지 않는다. 이 방식은 실제 압축 해제량과 구조를 본문을 읽는 동안 제한하기 위해 선택했다.

| 대상 | 제한 |
| --- | --- |
| 업로드 원본 | 파일당 10 MiB, multipart 전체 25 MiB (기존) |
| 회차 원고 | 요청 내 추출 본문 합계 250,000 Unicode 코드 포인트, 공백 포함 (기존) |
| HWP | OLE 엔트리 256개, 디렉터리 깊이 64, 선언 스트림 합계 20 MiB. 실제 읽는 FileHeader/DocInfo/본문은 스트림당 10 MiB, 합계 20 MiB |
| HWPX | ZIP 엔트리 256개, 중복 경로 거절. 전체 엔트리 실제 해제량 합계 20 MiB, 엔트리당 10 MiB |
| 구조 | HWP 레코드/XML 노드 각 part당 200,000개, 깊이 64. 추출 결과 최대 10 Mi UTF-16 코드 단위 |
| XML | DTD/엔터티 선언 거절, 외부 엔터티·외부 리소스 조회 금지 |

바이너리 크기나 레코드가 선언한 길이만 믿고 배열을 할당하지 않는다. HWP는 문서에서 선택한 본문 스트림만 제한된 raw-deflate로 해제하며 BinData/Scripts는 해제하지 않는다. HWPX는 나중 엔트리도 끝까지 제한 검사하며, 본문 구역의 누락/중복을 거절한다. 파일을 파일시스템에 풀지 않는다.

기존 DOCX의 10 MiB 본문/20 MiB 누적/256개 제한은 유지한다. 새로운 제한 오류는 원본 파일 크기 초과와 구분한다. TXT의 UTF-8 디코딩도 실패 시 깨진 문자로 성공시키지 않는다.

## API와 저장

요청/응답 필드·DB 모델은 바뀌지 않는다. 원본 바이트는 원본 객체에 그대로 저장하고, 추출된 본문은 기존 UTF-8 텍스트 객체에 저장한다. 설정집 편집은 텍스트 객체만 변경하며 원본 파일명/크기/MIME을 유지한다. 원고 교체의 정리·재분석 계약도 그대로 유지한다. 파일 파싱은 AI 호출이나 외부 변환 API를 사용하지 않는다.

| 확장자 | 원본 MIME |
| --- | --- |
| `.txt` | `text/plain; charset=UTF-8` |
| `.docx` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` |
| `.hwp` | `application/x-hwp` |
| `.hwpx` | `application/hwp+zip` |

| 오류 | 의미 |
| --- | --- |
| `UPLOAD_FILE_EMPTY` | 파일 자체 또는 읽을 본문이 없음 |
| `UPLOAD_FILE_TYPE_NOT_SUPPORTED` | 지원하지 않는 확장자/파일명 없음 |
| `UPLOAD_FILE_TOO_LARGE` / `UPLOAD_SIZE_LIMIT_EXCEEDED` | 기존 원본/multipart 크기 제한 |
| `UPLOAD_CHARACTER_LIMIT_EXCEEDED` | 추출 회차 본문 합계 초과 |
| `UPLOAD_DOCUMENT_PASSWORD_PROTECTED` | HWP 암호 flag, HWPX ZIP 암호 또는 manifest 암호화 표시 |
| `UPLOAD_DOCUMENT_VERSION_NOT_SUPPORTED` | 구형/배포용/DRM HWP |
| `UPLOAD_DOCUMENT_INVALID` | 손상·확장자와 내용 불일치·잘린 레코드·잘못된 XML |
| `UPLOAD_DOCUMENT_LIMIT_EXCEEDED` | 새 파서의 해제량/엔트리/노드/깊이 제한 초과 |

## 검증 자료

`src/test/resources/upload`의 소설 문장은 테스트를 위해 직접 작성했다. `scripts/upload-fixtures/CreateHangulFixtures.java`는 hwplib 1.1.11 / hwpxlib 1.0.9로 기본 문서와 HWP 여러 구역 문서를 만든다. `create_hwpx.py`가 HWPX의 기본 서식/패키지 정보를 유지하며 합성 문단을 넣는다. 파서 테스트는 별도 생성기로 만든 이 바이너리를 읽고, 구조/오류 테스트는 공개 형식에 맞춘 최소 컨테이너를 따로 구성한다. 개인 제공 문서/그 추출 원문은 포함하지 않는다.

```sh
# Maven Central에서 받은 fixture 생성용 jar 두 개의 경로를 지정한다.
FIXTURE_LIBS=/path/to/hwplib-1.1.11.jar:/path/to/hwpxlib-1.0.9.jar
javac -cp "$FIXTURE_LIBS" -d /tmp/hangul-fixture-classes scripts/upload-fixtures/CreateHangulFixtures.java
java -cp "$FIXTURE_LIBS:/tmp/hangul-fixture-classes" CreateHangulFixtures src/test/resources/upload
python3 scripts/upload-fixtures/create_hwpx.py src/test/resources/upload
./gradlew test --tests '*HangulDocumentReaderTest' --tests '*EpisodeFileParserTest' --tests '*EpisodeControllerIntegrationTest' --tests '*SettingBookControllerIntegrationTest'
```

참고: [한컴 공개 포맷 안내](https://www.hancom.com/etc/hwpDownload.do), [한컴의 HWPX 구역·문단 스키마와 인라인 요소 설명](https://tech.hancom.com/python-hwpx-parsing-2/), [KS X 6101](https://standard.go.kr/KSCI/standardIntro/getStandardSearchView.do?ksNo=KSX6101&menuId=503&tmprKsNo=KSX6101&topMenuId=502), [Apache POI](https://poi.apache.org/), [hwplib](https://github.com/neolord0/hwplib), [hwpxlib](https://github.com/neolord0/hwpxlib).

로컬 검증에서 제공된 HWP의 본문/표 문단 46개가 별도 라이브러리 기준과 같은 순서로 일치했다(표 2개·셀 9개, 머리말 컨트롤 2개 제외). 이는 해당 문서의 텍스트 대조 결과이며 모든 한글 서식의 호환성을 보장하지 않는다. 실제 API/DB/로컬 파일 저장소 검증은 합성 원고만 사용했으며 단일/한 파일 여러 회차/혼합 여러 파일, 설정집 조회·편집·재조회, 원고 교체를 통과했다.
