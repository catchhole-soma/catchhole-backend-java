package org.monitoring.catchholebackend.domain.analysis.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureDiagnostics;
import org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotification;

@DisplayName("실패 당시 snapshot으로 만드는 안전한 Discord 진단")
class AnalysisFailureNotificationMapperTest {
    final AnalysisFailureNotificationMapper mapper = new AnalysisFailureNotificationMapper();
    static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final UUID WORK = UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final UUID EPISODE = UUID.fromString("00000000-0000-4000-8000-000000000003");
    static final UUID EXECUTION = UUID.fromString("00000000-0000-4000-8000-000000000004");
    static final UUID EXTRACTION = UUID.fromString("00000000-0000-4000-8000-000000000005");
    static final UUID CAUSE_JOB = UUID.fromString("00000000-0000-4000-8000-000000000006");
    static final UUID CAUSE_CANDIDATE = UUID.fromString("00000000-0000-4000-8000-000000000007");
    static final UUID CAUSE_EPISODE = UUID.fromString("00000000-0000-4000-8000-000000000008");
    static final UUID BATCH = UUID.fromString("00000000-0000-4000-8000-000000000009");
    static final LocalDateTime OCCURRED = LocalDateTime.of(2026, 10, 28, 12, 0);
    static final String REQUEST = "11111111-2222-4333-8444-555555555555";
    static final String TRACE = "0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("재비교 실행·추출·원인 근거를 구분하고 다회차 실행을 실패 후보 개수로 표시하지 않는다")
    void formatsActionableRecomparisonSnapshotWithThreeDifferentJobRoles() {
        var diagnostic = new AnalysisFailureDiagnostics(1, "SPRING", "CHARACTER_CONTEXT",
                "CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH", "ANALYSIS_CURRENT_EVIDENCE_INVALID", 409,
                REQUEST, TRACE, CAUSE_JOB, CAUSE_CANDIDATE, CAUSE_EPISODE, 24);
        String message = mapper.toMessage(event("ANALYSIS_JOB", "CHARACTER_FACT_COMPARISON", 1, diagnostic, List.of(59, 60)), "prod");
        assertThat(message).contains("[운영] 캐릭터 재비교 실행 실패", "단계: 기존 캐릭터 비교 문맥 준비",
                "원인: 고정 원문 해시 불일치 (CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH)",
                "실패 코드: COMPARISON_VALIDATION_FAILED", "대상 회차: 59·60화", "실패 작업: 1건",
                "원인 근거 회차: 24화", "원인 근거 Job ID: " + CAUSE_JOB,
                "원인 후보 ID: " + CAUSE_CANDIDATE, "원인 회차 ID: " + CAUSE_EPISODE,
                "실행 Job ID: " + EXECUTION, "원본 추출 Job ID: " + EXTRACTION, "비교 묶음 ID: " + BATCH,
                "HTTP: 409", "요청 ID: " + REQUEST, "Trace ID: " + TRACE,
                "원문 연결 확인 전 재시도 필요 없음", "알림 ID: " + ID)
                .doesNotContain("실패 항목", "대상 회차: 59화");
    }

    @ParameterizedTest
    @CsvSource({"ANALYSIS_JOB,SETTING_EXTRACTION,회차 설정 추출 실패",
            "ANALYSIS_JOB,EPISODE_VALIDATION,회차 검수 실패",
            "ANALYSIS_JOB,CHARACTER_FACT_COMPARISON,캐릭터 재비교 실행 실패",
            "ANALYSIS_JOB,WORLD_SETTING_COMPARISON,세계관 재비교 실행 실패",
            "CHARACTER_COMPARISON,CHARACTER_FACT_COMPARISON,캐릭터 비교 항목 실패",
            "WORLD_COMPARISON,WORLD_SETTING_COMPARISON,세계관 비교 항목 실패"})
    @DisplayName("추출·재비교 실행·비교 후보의 사건 제목을 구분한다")
    void distinguishesExecutionAndCandidateKinds(String kind, String type, String title) {
        assertThat(mapper.toMessage(event(kind, type, 1, null, List.of(59)), "local"))
                .contains("[로컬] " + title);
    }

    @Test
    @DisplayName("후보 사건의 수만 실패 항목으로 표시한다")
    void reportsCandidateCountOnlyForCandidateEvents() {
        String message = mapper.toMessage(event("WORLD_COMPARISON", "WORLD_SETTING_COMPARISON", 17,
                diagnostic("WORLD_COMPARE", "LLM_TIMEOUT", null), List.of(59)), "prod");
        assertThat(message).contains("실패 항목: 17개", "세계관 비교", "LLM_TIMEOUT").doesNotContain("실패 작업");
    }

    @ParameterizedTest
    @CsvSource({
            "CURRENT_EVIDENCE_BINDING_INVALID,원문 연결 불일치,원문 연결 확인 전 재시도 필요 없음",
            "CURRENT_EVIDENCE_MANIFEST_INVALID,원문 목록 검증 실패,원문 연결 확인 전 재시도 필요 없음",
            "CURRENT_EVIDENCE_OFFSET_INVALID,원문 위치 검증 실패,원문 연결 확인 전 재시도 필요 없음",
            "CURRENT_EVIDENCE_QUOTE_NOT_FOUND,원문 인용 확인 불가,원문 연결 확인 전 재시도 필요 없음",
            "CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH,고정 원문 해시 불일치,원문 연결 확인 전 재시도 필요 없음",
            "CURRENT_EVIDENCE_SOURCE_READ_FAILED,원문 저장소 읽기 실패,저장소 복구 후 재시도 가능",
            "CURRENT_EVIDENCE_STRUCTURE_INVALID,근거 구조 검증 실패,원문 연결 확인 전 재시도 필요 없음",
            "COMPARISON_RESULT_INVALID,비교 결과 검증 실패,비교 결과 검토 후 재시도 판단",
            "INTERNAL_API_HTTP_ERROR,내부 API HTTP 오류,HTTP 상태와 서버 로그 확인 후 재시도 판단",
            "INTERNAL_API_TRANSPORT_ERROR,내부 API 연결 실패,내부 API 연결 복구 후 재시도 가능",
            "LLM_OUTPUT_TRUNCATED,AI 응답 길이 제한,출력 한도 확인 후 재시도 판단",
            "LLM_PROVIDER_ERROR,AI 제공자 오류,제공자 상태 확인 후 재시도 판단",
            "LLM_RATE_LIMIT,AI 요청 한도 초과,제공자 대기 시간 이후 재시도 가능",
            "LLM_TIMEOUT,AI 응답 시간 초과,일시 장애 해소 후 재시도 가능",
            "UNKNOWN,확인 불가,원인 확인 후 재시도 판단"})
    @DisplayName("상세 원인은 고정 한글 설명과 원인에 맞는 재시도 판단을 제공한다")
    void describesEverySupportedReasonAndRetryDecision(String reason, String explanation, String retry) {
        String message = mapper.toMessage(event("ANALYSIS_JOB", "SETTING_EXTRACTION", 1,
                diagnostic("EXTRACTION", reason, null), List.of(59)), "prod");
        assertThat(message).contains("원인: " + explanation + " (" + reason + ")", "재시도: " + retry);
    }

    @Test
    @DisplayName("일반 500도 실제 HTTP·단계·요청·trace를 보존하고 응답이 없는 경우 HTTP를 만들지 않는다")
    void includesGenericHttpFailureAndNeverInventsResponseStatus() {
        String observed = mapper.toMessage(event("ANALYSIS_JOB", "SETTING_EXTRACTION", 1,
                diagnostic("FINALIZE", "INTERNAL_API_HTTP_ERROR", 500), List.of(59)), "prod");
        assertThat(observed).contains("HTTP: 500", "단계: 결과 저장·완료", "COMMON_INTERNAL_SERVER_ERROR",
                "요청 ID: " + REQUEST, "Trace ID: " + TRACE);
        String noResponse = mapper.toMessage(event("ANALYSIS_JOB", "SETTING_EXTRACTION", 1,
                diagnostic("CLAIM_INPUT", "INTERNAL_API_TRANSPORT_ERROR", null), List.of(59)), "prod");
        assertThat(noResponse).contains("단계: 분석 입력 준비").doesNotContain("HTTP:");
    }

    @Test
    @DisplayName("구 row의 원본 Job은 유지하되 실제 재비교 실행과 묶음·전체 범위는 추정하지 않는다")
    void fallsBackForLegacyRowsWithoutInventingExecutionOrRange() {
        var candidate = new AnalysisFailureNotification(ID, WORK, 2L, EXTRACTION, EPISODE, 59,
                "CHARACTER_COMPARISON", "LLM_PROVIDER_ERROR", 2, OCCURRED, 1, null);
        assertThat(mapper.toMessage(candidate, "prod")).contains("실행 Job ID: 확인 불가",
                "원본 추출 Job ID: " + EXTRACTION, "단계: 확인 불가", "원인: 확인 불가", "실패 항목: 2개");
        var unknownExecution = new AnalysisFailureNotification(ID, WORK, 2L, EXECUTION, EPISODE, 59,
                "ANALYSIS_JOB", "UNEXPECTED_ERROR", 1, OCCURRED, 1, null);
        assertThat(mapper.toMessage(unknownExecution, "prod")).contains("종류 확인 불가", "대상 회차: 확인 불가",
                "기록 Job ID: " + EXECUTION, "실행 Job ID: " + EXECUTION).doesNotContain("대상 회차: 59화", "실패 항목");
        var unboundGroup = event("ANALYSIS_JOB", "CHARACTER_FACT_COMPARISON", 1, null, null);
        assertThat(mapper.toMessage(unboundGroup, "prod")).contains("대상 회차: 확인 불가").doesNotContain("대상 회차: 59화");
    }

    @Test
    @DisplayName("임의 코드·환경·원고·연락처·URL·인증·예외·mention을 안전한 고정값으로 대체한다")
    void rejectsUntrustedStringsEvenInMalformedStoredSnapshots() {
        String privateText = "PRIVATE_MANUSCRIPT writer@example.test https://private.test Bearer SECRET @everyone <@123>";
        var diagnostic = new AnalysisFailureDiagnostics(1, "SPRING", privateText, privateText, privateText,
                999, privateText, privateText, null, null, null, null);
        var poisoned = new AnalysisFailureNotification(ID, WORK, 2L, EXECUTION, EPISODE, 59,
                privateText, privateText, 1, OCCURRED, 1, null, diagnostic, EXECUTION, EXTRACTION, BATCH,
                privateText, List.of(59));
        String message = mapper.toMessage(poisoned, privateText);
        assertThat(message).contains("[기타]", "확인 불가", "UNEXPECTED_ERROR", "원천 코드: 확인 불가 (UNKNOWN)")
                .doesNotContain("PRIVATE_MANUSCRIPT", "writer@", "private.test", "SECRET", "@everyone", "<@", "HTTP: 999");
        var unsupported = new AnalysisFailureDiagnostics(2, "SPRING", "FINALIZE", "INTERNAL_API_HTTP_ERROR",
                "COMMON_INTERNAL_SERVER_ERROR", 500, REQUEST, TRACE, null, null, null, null);
        assertThat(mapper.toMessage(event("ANALYSIS_JOB", "SETTING_EXTRACTION", 1, unsupported, List.of(59)), "prod"))
                .contains("단계: 확인 불가", "원인: 확인 불가").doesNotContain("HTTP: 500", REQUEST, TRACE);
    }

    @Test
    @DisplayName("많은 회차와 최대 길이 상관 ID도 2000자 이내에서 필수 식별자·원인·단계를 보존한다")
    void boundsPayloadWhilePreservingActionableIdentifiers() {
        var diagnostic = new AnalysisFailureDiagnostics(1, "PROVIDER", "CHARACTER_CONTEXT", "LLM_RATE_LIMIT",
                "rate_limit_exceeded", 429, "r".repeat(128), TRACE, CAUSE_JOB, CAUSE_CANDIDATE, CAUSE_EPISODE, Integer.MAX_VALUE);
        var manyEpisodes = IntStream.rangeClosed(1, 1000).boxed().toList();
        String message = mapper.toMessage(event("ANALYSIS_JOB", "CHARACTER_FACT_COMPARISON", Integer.MAX_VALUE, diagnostic, manyEpisodes), "prod");
        assertThat(message).hasSizeLessThanOrEqualTo(2000).contains("총 1000개 회차", "LLM_RATE_LIMIT", "단계:",
                "알림 ID: " + ID, "실행 Job ID: " + EXECUTION, "작품 ID: " + WORK, "HTTP: 429", TRACE);
    }

    private AnalysisFailureDiagnostics diagnostic(String stage, String reason, Integer status) {
        return new AnalysisFailureDiagnostics(1, "SPRING", stage, reason, "COMMON_INTERNAL_SERVER_ERROR",
                status, REQUEST, TRACE, null, null, null, null);
    }

    private AnalysisFailureNotification event(String kind, String type, int count,
            AnalysisFailureDiagnostics diagnostic, List<Integer> episodes) {
        return new AnalysisFailureNotification(ID, WORK, 2L, "ANALYSIS_JOB".equals(kind) ? EXECUTION : EXTRACTION,
                EPISODE, 59, kind, "COMPARISON_VALIDATION_FAILED", count, OCCURRED, 1, null,
                diagnostic, EXECUTION, EXTRACTION, BATCH, type, episodes);
    }
}
