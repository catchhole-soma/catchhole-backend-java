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

**Token Extension Request**:
사용량 부족 안내에서 회원이 35자 이상의 피드백과 함께 제출하고 운영자의 승인 또는 거절을 기다리는 추가 사용량 요청이다. 한 회원에게 처리 대기 요청은 하나만 존재한다.
_Avoid_: Token reset, email request, automatic refill

**General Feedback**:
회원이 서비스 이용 중 횟수 제한 없이 남기는 의견 한 건이다. 추가 사용량 요청과 수명주기가 다르므로 의견은 요청 처리 상태와 무관하게 모두 보존한다.
_Avoid_: Token request, extension feedback

**General Feedback Reward Request**:
회원의 첫 General Feedback을 계기로 만드는 Token Extension Request다. 회원당 한 번만 존재하며 이후 General Feedback은 새 요청을 만들지 않는다.
_Avoid_: Feedback reset, recurring feedback reward

**Manual Token Grant**:
운영자가 Token Extension Request를 승인할 때 승인 시점의 `AI_TOKEN_DEFAULT_GRANT`를 계정 누적 지급량에 더하고 요청 ID와 함께 남기는 지급 원장이다. 확정 사용량을 초기화하지 않는다.
_Avoid_: Quota reset, configurable approval amount

**World Setting Candidate**:
회차 원문에서 추출되어 확정 세계관에 반영되기 전 사용자의 검토를 기다리는 설정 하나다.
_Avoid_: World setting, extracted fact

**Failure Code**:
Worker·Backend·Frontend가 실패 원인을 문자열 문구 파싱 없이 구분하는 기계 판독용 분석 코드다.
_Avoid_: Error message, exception text

**Member Withdrawal Purge**:
회원 인증을 즉시 차단한 뒤 기존 Work Purge로 모든 소유 작품을 파기하고 회원 행을 물리 삭제할 때까지 추적하는 복구 불가능한 탈퇴 흐름이다.
_Avoid_: Soft delete, account deactivation, grace period

**Legal Document**:
이용자에게 실제로 표시하는 이용약관 또는 개인정보처리방침의 불변 원문과 locale·버전·게시 상태를 묶는 단위다. 게시 뒤 내용이 바뀌면 같은 행을 수정하지 않고 새 문서를 게시한다.
_Avoid_: Current version enum, Front legal copy

**Published Legal Document**:
특정 문서 종류와 locale에서 현재 가입과 공개 화면에 사용하는 유일한 Legal Document다. 교체된 문서는 Retired Legal Document가 되어 과거 기록 조회에만 사용된다.
_Avoid_: Latest text, active flag

**Member Legal Record**:
회원이 실제로 본 Legal Document 하나에 대해 동의하거나 확인한 행위와 서버 기록 시각을 보존하는 감사 기록이다. 문서 버전 문자열만으로 원문을 추측하지 않는다.
_Avoid_: Terms boolean, consent version
