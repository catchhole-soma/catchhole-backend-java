# CatchHole Analysis Context

CatchHole에서 회차 원문을 설정 후보로 바꾸고 사용자가 검토할 때까지의 분석 실행·중단·복구 언어를 정의한다. Backend가 상태와 데이터 소유권의 기준이며 AI Worker와 Frontend도 같은 용어를 사용한다.

## Language

**Analysis Job**:
작품에 속한 단일 회차의 AI 분석 실행과 상태를 추적하는 단위다.
_Avoid_: Batch job, upload analysis

**Upload Batch**:
한 번의 업로드 요청에서 만들어진 회차와 분석 결과를 화면에서 묶어 보는 출처 단위다. 분석 실행 단위는 아니다.
_Avoid_: Analysis run, job batch

**Analysis Checkpoint**:
Analysis Job이 외부 호출과 저장을 중복하지 않고 다시 시작할 수 있도록 기록한 완료 단계다.
_Avoid_: Progress, current step

**Hidden Comparison Job**:
캐릭터 또는 세계관 후보 한 건의 재비교만 처리하며 공개 분석 목록과 회차 상태에서 제외되는 Analysis Job이다.
_Avoid_: Retry job, background analysis

**Token Reservation**:
AI provider 호출 전에 회원 잔여량에서 해당 요청의 예상 최대 사용량을 원자적으로 확보한 원장 상태다.
_Avoid_: Token charge, estimated usage

**Token-Interrupted Comparison**:
1차 추출 결과는 보존됐지만 회원 사용량 부족으로 아직 완료하지 못해, 추가 지급 후 같은 후보로 재개할 수 있는 세계관 비교다.
_Avoid_: Permanent failure, full analysis failure

**World Setting Candidate**:
회차 원문에서 추출되어 확정 세계관에 반영되기 전 사용자의 검토를 기다리는 설정 하나다.
_Avoid_: World setting, extracted fact

**Failure Code**:
Worker·Backend·Frontend가 실패 원인을 문자열 문구 파싱 없이 구분하는 기계 판독용 분석 코드다.
_Avoid_: Error message, exception text
