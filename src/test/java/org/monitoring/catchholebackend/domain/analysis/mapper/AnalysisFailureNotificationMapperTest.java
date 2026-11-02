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
    void showsObservedValuesAndCandidateDispositionInOneDelivery() {
        var d = new AnalysisFailureDiagnostics(1, "WORKER", "WORLD_CONTEXT", "WORLD_SUBJECT_REFERENCE_INVALID",
                "UNKNOWN", null, null, null, null, null, null, null,
                List.of(new org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureCheck("selectedSubjectRefs", "IN_SET", "S1,S2", "S9", 1)));
        var candidate = new AnalysisFailureNotification(ID, WORK, 37L, EXTRACTION, EPISODE, 59, "WORLD_COMPARISON",
                "COMPARISON_VALIDATION_FAILED", 1, OCCURRED, 1, null, d, EXECUTION, EXTRACTION, null,
                "SETTING_EXTRACTION", List.of(59), "PENDING_REVIEW", List.of(CAUSE_CANDIDATE));
        var group = new org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotificationDelivery(ID, EXECUTION, List.of(candidate));
        var payload = mapper.toPayload(group, "prod");
        assertThat(payload.embeds()).hasSize(1);
        assertThat(payload.embeds().getFirst().description()).contains("후보 저장됨", "검토 대기", "비교 실패", "S1,S2", "실제: S9", "응답 항목 1", CAUSE_CANDIDATE.toString())
                .doesNotContain("작업 FAILED", "전체 분석 실패");
    }

    @Test
    void distinguishesRelationshipPartialFailureFromStoppedJobs() {
        var partial = event("RELATIONSHIP_PARTIAL", "SETTING_EXTRACTION", 1,
                diagnostic("RELATIONSHIP_COMPARE", "RELATIONSHIP_MERGE_CONTENT_CHANGED", null), List.of(59));
        var payload = mapper.toPayload(new org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotificationDelivery(ID, EXECUTION, List.of(partial)), "prod");
        assertThat(payload.content()).contains("관계도 부분 실패", "실패 작업: 0건", "관계도 부분 실패: 1건").doesNotContain("회차 분석 중단");
        assertThat(payload.embeds().getFirst().title()).contains("관계도 부분 실패");
        assertThat(payload.embeds().getFirst().description()).contains("관계도만 실패", "나머지 분석 계속")
                .doesNotContain("작업 FAILED", "검토 대기", "실패 작업: 1건");
    }

    @Test
    void groupedWholeJobFailureTakesPrecedenceOverEarlierRelationshipPartialFailure() {
        var partial = event("RELATIONSHIP_PARTIAL", "SETTING_EXTRACTION", 1,
                diagnostic("RELATIONSHIP_COMPARE", "RELATIONSHIP_MERGE_CONTENT_CHANGED", null), List.of(59));
        var stopped = event("ANALYSIS_JOB", "SETTING_EXTRACTION", 1,
                diagnostic("WORLD_COMPARE", "LLM_TIMEOUT", null), List.of(59));
        var payload = mapper.toPayload(new org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotificationDelivery(ID, EXECUTION, List.of(partial, stopped)), "prod");
        assertThat(payload.content()).startsWith("🚨 [운영] 회차 분석 중단").contains("실패 작업: 1건", "관계도 부분 실패: 1건");
        assertThat(payload.embeds().getFirst().description()).contains("관계도 부분 실패", "회차 분석은 별도 오류로 중단").doesNotContain("나머지 분석 계속");
        assertThat(payload.embeds().get(1).description()).contains("작업 FAILED", "회차 분석 중단");
    }

    @Test
    void keepsFourDetailedEventsWithinDiscordLimit() {
        var check = new org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureCheck("inputTokens", "MAX", "64000", "72001", null);
        var d = new AnalysisFailureDiagnostics(1, "WORKER", "RELATIONSHIP_COMPARE", "RELATIONSHIP_INPUT_TOO_LARGE",
                "UNKNOWN", null, null, null, null, null, null, null, List.of(check, check, check));
        var events = IntStream.range(0, 4).mapToObj(i -> event("ANALYSIS_JOB", "RELATIONSHIP_EXTRACTION", 1, d, List.of(59))).toList();
        var payload = mapper.toPayload(new org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotificationDelivery(ID, EXECUTION, events), "prod");
        assertThat(payload.content().length()).isLessThanOrEqualTo(2000);
        assertThat(payload.embeds()).hasSize(4);
        assertThat(payload.embeds().stream().mapToInt(e -> e.title().length() + e.description().length()).sum()).isLessThanOrEqualTo(6000);
        payload.embeds().forEach(e -> assertThat(e.description()).contains("작업 FAILED", "관계도 전용", "64000", "실제: 72001"));
    }

    @Test
    void legacyPayloadRetainsRecordedIdsWithoutInventingExecution() {
        var legacy = new AnalysisFailureNotification(ID, WORK, 37L, EXTRACTION, EPISODE, 59,
                "WORLD_COMPARISON", "LLM_NETWORK_ERROR", 1, OCCURRED, 1, null);
        var payload = mapper.toPayload(new org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotificationDelivery(ID, null, List.of(legacy)), "prod");
        assertThat(payload.content()).contains("1번 기록 Job ID: " + EXTRACTION, "1번 기록 회차 ID: " + EPISODE).doesNotContain("실행 Job ID:");
        assertThat(payload.embeds().getFirst().description()).contains("당시 검토 상태 미기록");
    }

    @Test
    void longestSafeReferenceChecksFitAllFourEmbeds() {
        String refs = java.util.stream.IntStream.range(10000000, 10000008).mapToObj(n -> "S" + n).collect(java.util.stream.Collectors.joining(","));
        var check = new org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureCheck("selectedSubjectRefs", "IN_SET", refs, refs, 100000);
        var d = new AnalysisFailureDiagnostics(1, "WORKER", "WORLD_CONTEXT", "WORLD_SUBJECT_REFERENCE_INVALID", "UNKNOWN", 599,
                REQUEST, TRACE, CAUSE_JOB, CAUSE_CANDIDATE, CAUSE_EPISODE, Integer.MAX_VALUE, List.of(check, check, check));
        var events = IntStream.range(0, 4).mapToObj(i -> new AnalysisFailureNotification(UUID.randomUUID(), WORK, Long.MAX_VALUE,
                EXTRACTION, EPISODE, 59, "WORLD_COMPARISON", "COMPARISON_VALIDATION_FAILED", Integer.MAX_VALUE, OCCURRED, 1, null,
                d, null, EXTRACTION, BATCH, "SETTING_EXTRACTION", IntStream.rangeClosed(Integer.MAX_VALUE - 20, Integer.MAX_VALUE).boxed().toList(),
                "PENDING_REVIEW", IntStream.range(0, 8).mapToObj(n -> UUID.randomUUID()).toList())).toList();
        var payload = mapper.toPayload(new org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotificationDelivery(ID, null, events), "prod");
        assertThat(payload.content().length()).isLessThanOrEqualTo(2000);
        assertThat(payload.embeds().stream().mapToInt(e -> e.title().length() + e.description().length()).sum()).isLessThanOrEqualTo(6000);
        for (int i=0; i<4; i++) assertThat(payload.embeds().get(i).description()).contains(events.get(i).id().toString(), "응답 항목 100000");
    }

    @Test
    void associatesEachGroupedCauseWithItsOwnCandidateCount() {
        var d = diagnostic("WORLD_COMPARE", "COMPARISON_RESULT_INVALID", null);
        var events = List.of(event("WORLD_COMPARISON", "SETTING_EXTRACTION", 3, d, List.of(59)),
                event("WORLD_COMPARISON", "SETTING_EXTRACTION", 7, d, List.of(59)));
        var payload = mapper.toPayload(new org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotificationDelivery(ID, EXECUTION, events), "prod");
        assertThat(payload.content()).contains("비교 실패 후보: 10개");
        assertThat(payload.embeds().get(0).description()).contains("실패 후보: 3개");
        assertThat(payload.embeds().get(1).description()).contains("실패 후보: 7개");
    }

    @Test
    void showsSubjectNamesAndPassagesBeforeTechnicalResponse() {
        var json = new tools.jackson.databind.json.JsonMapper();
        var raw = json.createObjectNode().put("schemaVersion", 1).put("origin", "WORKER").put("stage", "WORLD_CONTEXT")
                .put("reasonCode", "WORLD_SUBJECT_REFERENCE_INVALID").put("subjectName", "백탑");
        raw.putArray("checks").addObject().put("field", "selectedSubjectRefs").put("rule", "IN_SET").put("expected", "S1,S2").put("actual", "S9");
        var excerpts = raw.putArray("excerpts");
        excerpts.addObject().put("kind", "SOURCE").put("field", "evidenceQuote").put("text", "백탑은 왕국 북쪽에 있다.");
        excerpts.addObject().put("kind", "EXPECTED").put("field", "subjectOptions").put("text", "백탑, 왕궁");
        excerpts.addObject().put("kind", "RESPONSE").put("field", "responseJson").put("text", "{\"selected_subject_refs\":[\"S9\"]}");
        var d = new org.monitoring.catchholebackend.domain.analysis.processor.AnalysisFailureDiagnosticsSanitizer(org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class)).sanitize(raw, WORK);
        var event = new AnalysisFailureNotification(ID, WORK, 37L, EXTRACTION, EPISODE, 59, "WORLD_COMPARISON", "COMPARISON_VALIDATION_FAILED", 1,
                OCCURRED, 1, null, d, EXECUTION, EXTRACTION, null, "SETTING_EXTRACTION", List.of(59), "PENDING_REVIEW", List.of(CAUSE_CANDIDATE), true);
        var payload = mapper.toPayload(new org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotificationDelivery(ID, EXECUTION, List.of(event)), "prod");
        assertThat(payload.embeds().getFirst().title()).contains("백탑", "59화");
        String text = payload.embeds().getFirst().description();
        assertThat(text).contains("AI가 선택지에 없는 대상을 골랐습니다.", "선택지: 백탑, 왕궁", "AI 선택: S9", "검토 목록에 보관", "나머지 분석 계속", "백탑은 왕국 북쪽에 있다.");
        assertThat(text.indexOf("선택지: 백탑")).isLessThan(text.indexOf("selected"));
        assertThat(text).doesNotContain("실제 값: 이 실패 경로에서 검사 값이 기록되지 않음");
    }

    @Test
    void boundsFourCardsWithExcerptsAndEscapesDiscordFormatting() {
        String refs = IntStream.range(10000000, 10000008).mapToObj(n -> "S" + n).collect(java.util.stream.Collectors.joining(","));
        var check = new org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureCheck("selectedSubjectRefs", "IN_SET", refs, refs, 100000);
        var excerpts = List.of(
                new org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureExcerpt("SOURCE", "evidenceQuote", "@everyone ``` [링크](https://example.test) 😀".repeat(8), false, 100000),
                new org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureExcerpt("EXPECTED", "relationshipSummary", "😀".repeat(120), false, 100000),
                new org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureExcerpt("RESPONSE", "relationshipSummary", "가".repeat(120), false, 100000));
        var d = new AnalysisFailureDiagnostics(1, "WORKER", "RELATIONSHIP_COMPARE", "RELATIONSHIP_MERGE_CONTENT_CHANGED", "UNKNOWN", 599,
                REQUEST, TRACE, CAUSE_JOB, CAUSE_CANDIDATE, CAUSE_EPISODE, Integer.MAX_VALUE, List.of(check, check, check), excerpts);
        var events = IntStream.range(0,4).mapToObj(i -> event("WORLD_COMPARISON", "SETTING_EXTRACTION", Integer.MAX_VALUE, d,
                IntStream.rangeClosed(Integer.MAX_VALUE-20, Integer.MAX_VALUE).boxed().toList())).toList();
        var payload = mapper.toPayload(new org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotificationDelivery(ID, EXECUTION, events), "prod");
        assertThat(payload.embeds().stream().mapToInt(e -> e.title().length()+e.description().length()).sum()).isLessThanOrEqualTo(6000);
        for (var embed : payload.embeds()) assertThat(embed.description())
                .contains("후보 근거:", "기존 관계 설명:", "AI가 반환한 관계 설명:", "발췌", ID.toString())
                .doesNotContain("@everyone", "```", "[링크](");
    }

    @ParameterizedTest
    @CsvSource({"RELATIONSHIP_OBSERVATION_REFS_INVALID,관계 관측 참조가 없거나 중복됨",
            "RELATIONSHIP_OBSERVATION_ORDER_INVALID,관측 참조 순서가 잘못됐거나 새 근거가 없음",
            "RELATIONSHIP_DISPOSITION_CONFLICT,보류·제외 판단이 중복되거나 변경 판단과 충돌함",
            "RELATIONSHIP_ADD_SLOT_EXISTS,새 관계 추가에 기존 관계 참조나 중복 관계가 포함됨",
            "RELATIONSHIP_NONPRESENT_MUTATION,현재로 분류되지 않은 관계를 변경하려 함"})
    @DisplayName("관계 오류 설명은 검증 조건의 여러 분기를 포괄하고 관측하지 않은 특정 원인으로 단정하지 않는다")
    void doesNotOverstateRelationshipValidationCause(String reason, String explanation) {
        assertThat(mapper.toMessage(event("ANALYSIS_JOB", "RELATIONSHIP_EXTRACTION", 1,
                diagnostic("RELATIONSHIP_COMPARE", reason, null), List.of(59)), "prod"))
                .contains(explanation);
    }

    @Test
    @DisplayName("묶음에도 실패 발생 시각과 각 사건의 근거·요청 식별자를 보존한다")
    void preservesGroupedOccurrenceAndCauseLinks() {
        var d = new AnalysisFailureDiagnostics(1, "SPRING", "CHARACTER_CONTEXT", "CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH",
                "ANALYSIS_CURRENT_EVIDENCE_INVALID", 409, REQUEST, TRACE, CAUSE_JOB, CAUSE_CANDIDATE, CAUSE_EPISODE, 24);
        var events = List.of(event("ANALYSIS_JOB", "SETTING_EXTRACTION", 1, d, List.of(59)),
                event("CHARACTER_COMPARISON", "SETTING_EXTRACTION", 12, d, List.of(59)));
        var delivery = new org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotificationDelivery(
                UUID.randomUUID(), UUID.randomUUID(), events);
        assertThat(mapper.toMessage(delivery, "prod")).contains(OCCURRED.toString(), CAUSE_JOB.toString(),
                "1번 요청 ID: " + REQUEST, "2번 원인 근거 Job ID: " + CAUSE_JOB);
    }

    @Test
    @DisplayName("최대 4개 진단과 다회차도 모든 원인·알림 ID를 2000자 안에 담는다")
    void preservesAllReasonsAndIdsInMaximumDelivery() {
        var d = new AnalysisFailureDiagnostics(1, "PROVIDER", "CHARACTER_CONTEXT", "CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH",
                "SETTING_CANDIDATE_COMPARISON_BATCH_RESPONSE_INVALID", 599, "r".repeat(128), TRACE,
                CAUSE_JOB, CAUSE_CANDIDATE, CAUSE_EPISODE, Integer.MAX_VALUE);
        var events = IntStream.range(0, 4).mapToObj(i -> new AnalysisFailureNotification(UUID.randomUUID(), WORK,
                Long.MAX_VALUE, EXTRACTION, EPISODE, Integer.MAX_VALUE, "CHARACTER_COMPARISON", "COMPARISON_VALIDATION_FAILED",
                Integer.MAX_VALUE, OCCURRED, 1, null, d, EXECUTION, EXTRACTION, BATCH,
                "CHARACTER_FACT_COMPARISON", IntStream.rangeClosed(Integer.MAX_VALUE - 20, Integer.MAX_VALUE).boxed().toList())).toList();
        var delivery = new org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotificationDelivery(
                UUID.randomUUID(), UUID.randomUUID(), events);
        String message = mapper.toMessage(delivery, "prod");
        assertThat(message.length()).isLessThanOrEqualTo(2000);
        for (var e : events) assertThat(message).contains(e.id().toString());
        assertThat(message.split("CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH", -1)).hasSize(5);
    }

    @Test
    @DisplayName("같은 실패의 작업·후보를 한 메시지에 묶고 서로 다른 원인은 각각 표시한다")
    void summarizesDeliveryWithoutCountingJobAsCandidate() {
        var job = event("ANALYSIS_JOB", "SETTING_EXTRACTION", 1,
                diagnostic("WORLD_COMPARE", "LLM_TIMEOUT", null), List.of(59));
        var candidate = event("WORLD_COMPARISON", "SETTING_EXTRACTION", 12,
                diagnostic("WORLD_CONTEXT", "WORLD_SUBJECT_RESOLUTION_FAILED", null), List.of(59));
        var delivery = new org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotificationDelivery(
                UUID.randomUUID(), UUID.randomUUID(), List.of(job, candidate));
        String message = mapper.toMessage(delivery, "prod");
        assertThat(message).contains("실패 작업: 1건", "실패 항목: 12개", "LLM_TIMEOUT",
                "WORLD_SUBJECT_RESOLUTION_FAILED", delivery.id().toString(), delivery.executionAttemptId().toString());
        assertThat(message.length()).isLessThanOrEqualTo(2000);
    }

    @Test
    @DisplayName("토큰 부족은 HTTP 분류보다 충전 안내를 우선하고 관측되지 않은 선택 정보는 생략한다")
    void prioritizesQuotaAndOmitsAbsentOptionalMetadata() {
        var d = new AnalysisFailureDiagnostics(1, "SPRING", "WORLD_COMPARE", "INTERNAL_API_HTTP_ERROR",
                "AI_TOKEN_QUOTA_EXHAUSTED", 409, null, null, null, null, null, null);
        var e = new AnalysisFailureNotification(ID, WORK, 2L, EXECUTION, EPISODE, 59,
                "ANALYSIS_JOB", "AI_TOKEN_QUOTA_EXHAUSTED", 1, OCCURRED, 1, null,
                d, EXECUTION, null, null, "WORLD_SETTING_COMPARISON", List.of(59));
        assertThat(mapper.toMessage(e, "prod")).contains("원인: AI 토큰 부족", "AI 토큰 충전 후 재시도 가능", "HTTP: 409")
                .doesNotContain("요청 ID:", "Trace ID:", "원인 후보 ID:", "원본 추출 Job ID:", "비교 묶음 ID:");
    }

    @ParameterizedTest
    @CsvSource({"RELATIONSHIP_DUPLICATE_SLOT,같은 인물 관계를 중복 변경함",
            "RELATIONSHIP_OBSERVATION_COVERAGE_MISSING,추출한 관계 일부의 판단이 누락됨",
            "WORLD_SUBJECT_RESOLUTION_FAILED,세계관 설정의 대상을 확정하지 못함",
            "WORLD_COMPARISON_INPUT_TOO_LARGE,세계관 비교 입력 한도 초과"})
    @DisplayName("관계도와 세계관 세부 원인을 구체적인 한글 설명으로 표시한다")
    void explainsConcreteReasons(String reason, String explanation) {
        assertThat(mapper.toMessage(event("ANALYSIS_JOB", "SETTING_EXTRACTION", 1,
                diagnostic("WORLD_CONTEXT", reason, null), List.of(59)), "prod"))
                .contains("원인: " + explanation, reason);
    }

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
            "ANALYSIS_JOB,RELATIONSHIP_EXTRACTION,인물 관계 분석 실패",
            "CHARACTER_COMPARISON,CHARACTER_FACT_COMPARISON,캐릭터 비교 항목 실패",
            "WORLD_COMPARISON,WORLD_SETTING_COMPARISON,세계관 비교 항목 실패"})
    @DisplayName("추출·재비교 실행·비교 후보의 사건 제목을 구분한다")
    void distinguishesExecutionAndCandidateKinds(String kind, String type, String title) {
        assertThat(mapper.toMessage(event(kind, type, 1, null, List.of(59)), "local"))
                .contains("[로컬] " + title);
    }

    @ParameterizedTest
    @CsvSource({"RELATIONSHIP_EXTRACTION,인물 관계 추출,RELATIONSHIP_CONTEXT_INVALID",
            "RELATIONSHIP_COMPARE,인물 관계 비교,RELATIONSHIP_DUPLICATE_SLOT"})
    @DisplayName("관계 전용 실패의 제목·단계·안전한 원인 코드를 보존하고 추출 Job을 추정하지 않는다")
    void formatsRelationshipFailureWithoutInventingOriginalExtraction(String stage, String label, String reason) {
        var diagnostic = new AnalysisFailureDiagnostics(1, "WORKER", stage, reason, "UNKNOWN",
                null, REQUEST, TRACE, null, null, null, null);
        var relationship = new AnalysisFailureNotification(ID, WORK, 2L, EXECUTION, EPISODE, 59,
                "ANALYSIS_JOB", "COMPARISON_VALIDATION_FAILED", 1, OCCURRED, 1, null,
                diagnostic, EXECUTION, null, null, "RELATIONSHIP_EXTRACTION", List.of(59));

        assertThat(mapper.toMessage(relationship, "prod"))
                .contains("[운영] 인물 관계 분석 실패", "단계: " + label, "(" + reason + ")",
                        "대상 회차: 59화", "실패 작업: 1건", "실행 Job ID: " + EXECUTION)
                .doesNotContain("종류 확인 불가", "단계: 확인 불가", "실패 항목", "HTTP:", "원본 추출 Job ID:", "비교 묶음 ID:");
    }

    @Test
    @DisplayName("공통 관계 원인 20개를 고정 설명으로 표시하고 무조건 재시도를 권하지 않는다")
    void describesEveryRelationshipReasonWithBoundedRecoveryGuidance() {
        var contract = new tools.jackson.databind.ObjectMapper().readTree(getClass().getResourceAsStream(
                "/contracts/relationship-failure-diagnostics-v1.json"));
        assertThat(contract.path("reasonCodes").size()).isEqualTo(20);
        for (var value : contract.path("reasonCodes")) {
            String reason = value.stringValue();
            String message = mapper.toMessage(event("ANALYSIS_JOB", "RELATIONSHIP_EXTRACTION", 1,
                    diagnostic("RELATIONSHIP_COMPARE", reason, null), List.of(59)), "prod");
            assertThat(message).contains("(" + reason + ")",
                            "재시도: 원인 확인 후 제한된 복구 여부 판단")
                    .doesNotContain("원인: 확인 불가", "재시도 가능");
        }
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
        assertThat(message).contains("(" + reason + ")", "재시도: " + retry);
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
        assertThat(message).contains("[기타]", "확인 불가", "UNEXPECTED_ERROR")
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
