package org.monitoring.catchholebackend.domain.analysis.mapper;

import java.util.List;
import java.util.stream.Collectors;
import org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureDiagnostics;
import org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotification;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.springframework.stereotype.Component;

@Component
public class AnalysisFailureNotificationMapper {
    public String toMessage(AnalysisFailureNotification event, String environment) {
        AnalysisFailureCode code;
        try { code = AnalysisFailureCode.valueOf(event.failureCode()); }
        catch (IllegalArgumentException | NullPointerException ex) { code = AnalysisFailureCode.UNEXPECTED_ERROR; }
        var diagnostic = event.failureDiagnostics();
        if (diagnostic != null && (diagnostic.schemaVersion() != 1
                || !AnalysisFailureDiagnostics.ORIGINS.contains(diagnostic.origin() == null ? "" : diagnostic.origin()))) {
            diagnostic = null;
        }
        String reason = diagnostic == null ? "UNKNOWN" : allowed(diagnostic.reasonCode(), AnalysisFailureDiagnostics.REASONS);
        boolean candidate = isCandidate(event);
        String safeEnvironment = "prod".equals(environment) ? "운영" : "local".equals(environment) ? "로컬" : "기타";
        var sourceJob = event.sourceAnalysisJobId() != null ? event.sourceAnalysisJobId() : candidate ? event.jobId() : null;
        var execution = event.executionJobId() != null ? event.executionJobId()
                : "ANALYSIS_JOB".equals(event.failureKind()) ? event.jobId() : null;
        StringBuilder message = new StringBuilder();
        // 식별자·원인·단계를 먼저 확보한다. 선택 진단 때문에 필수 문맥이 잘리지 않는다.
        line(message, "🚨 [" + safeEnvironment + "] " + title(event));
        line(message, "단계: " + stage(diagnostic == null ? null : diagnostic.stage()));
        line(message, "원인: " + explanation(reason) + " (" + reason + ")");
        line(message, "실패 코드: " + code.name() + " / " + code.getPublicMessage());
        line(message, "대상 회차: " + targetEpisodes(event) + " / "
                + (candidate ? "실패 항목: " + Math.max(1, event.failureCount()) + "개" : "실패 작업: 1건"));
        line(message, "재시도: " + retry(reason, code));
        line(message, "실행 Job ID: " + known(execution));
        line(message, "원본 추출 Job ID: " + known(sourceJob));
        line(message, "비교 묶음 ID: " + known(event.comparisonBatchId()));
        if (event.executionJobId() == null) line(message, "기록 Job ID: " + known(event.jobId()));
        line(message, "회원 ID: " + known(event.memberId()) + " / 작품 ID: " + known(event.workId()));
        line(message, "기록 회차 ID: " + known(event.episodeId()));
        line(message, "발생 시각: " + known(event.occurredAt()) + " (서버 시간)");
        line(message, "알림 ID: " + known(event.id()));
        if (diagnostic != null) {
            line(message, "진단 경계: " + diagnostic.origin());
            String sourceCode = allowed(diagnostic.sourceErrorCode(), AnalysisFailureDiagnostics.SOURCE_CODES);
            line(message, "원천 코드: " + ("UNKNOWN".equals(sourceCode) ? "확인 불가 (UNKNOWN)" : sourceCode));
            if (diagnostic.httpStatus() != null && diagnostic.httpStatus() >= 100 && diagnostic.httpStatus() <= 599) {
                line(message, "HTTP: " + diagnostic.httpStatus());
            }
            String requestPattern = "PROVIDER".equals(diagnostic.origin()) ? "[A-Za-z0-9_-]{1,128}"
                    : "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
            line(message, "요청 ID: " + safeIdentifier(diagnostic.requestId(), requestPattern));
            line(message, "Trace ID: " + safeIdentifier(diagnostic.traceId(), "[0-9a-f]{32}"));
            line(message, "원인 근거 회차: " + (diagnostic.sourceEpisodeNo() != null && diagnostic.sourceEpisodeNo() > 0
                    ? diagnostic.sourceEpisodeNo() + "화" : "확인 불가"));
            line(message, "원인 근거 Job ID: " + known(diagnostic.sourceAnalysisJobId()));
            line(message, "원인 후보 ID: " + known(diagnostic.sourceCandidateId()));
            line(message, "원인 회차 ID: " + known(diagnostic.sourceEpisodeId()));
        }
        return message.toString();
    }

    private static boolean isCandidate(AnalysisFailureNotification event) {
        return "CHARACTER_COMPARISON".equals(event.failureKind()) || "WORLD_COMPARISON".equals(event.failureKind());
    }

    private static String title(AnalysisFailureNotification event) {
        if ("CHARACTER_COMPARISON".equals(event.failureKind())) return "캐릭터 비교 항목 실패";
        if ("WORLD_COMPARISON".equals(event.failureKind())) return "세계관 비교 항목 실패";
        if ("CHARACTER_FACT_COMPARISON".equals(event.jobType())) return "캐릭터 재비교 실행 실패";
        if ("WORLD_SETTING_COMPARISON".equals(event.jobType())) return "세계관 재비교 실행 실패";
        if ("SETTING_EXTRACTION".equals(event.jobType())) return "회차 설정 추출 실패";
        if ("EPISODE_VALIDATION".equals(event.jobType())) return "회차 검수 실패";
        return "회차 작업 실패 (종류 확인 불가)";
    }

    private static String targetEpisodes(AnalysisFailureNotification event) {
        List<Integer> episodes = event.targetEpisodeNos();
        if (episodes == null) {
            if ((isCandidate(event) || "SETTING_EXTRACTION".equals(event.jobType()) || "EPISODE_VALIDATION".equals(event.jobType()))
                    && event.episodeNo() != null && event.episodeNo() > 0) return event.episodeNo() + "화";
            return "확인 불가";
        }
        var valid = episodes.stream().filter(no -> no != null && no > 0).distinct().sorted().toList();
        if (valid.isEmpty()) return "확인 불가";
        String numbers = valid.stream().limit(8).map(String::valueOf).collect(Collectors.joining("·"));
        return numbers + "화" + (valid.size() > 8 ? " 외 (총 " + valid.size() + "개 회차)" : "");
    }

    private static String stage(String value) {
        return switch (value == null ? "UNKNOWN" : value) {
            case "CLAIM_INPUT" -> "분석 입력 준비";
            case "EXTRACTION" -> "회차 설정 추출";
            case "CHARACTER_CONTEXT" -> "기존 캐릭터 비교 문맥 준비";
            case "CHARACTER_COMPARE" -> "캐릭터 비교";
            case "WORLD_CONTEXT" -> "기존 세계관 비교 문맥 준비";
            case "WORLD_COMPARE" -> "세계관 비교";
            case "FINALIZE" -> "결과 저장·완료";
            default -> "확인 불가";
        };
    }

    private static String explanation(String reason) {
        return switch (reason) {
            case "CURRENT_EVIDENCE_BINDING_INVALID" -> "원문 연결 불일치";
            case "CURRENT_EVIDENCE_MANIFEST_INVALID" -> "원문 목록 검증 실패";
            case "CURRENT_EVIDENCE_OFFSET_INVALID" -> "원문 위치 검증 실패";
            case "CURRENT_EVIDENCE_QUOTE_NOT_FOUND" -> "원문 인용 확인 불가";
            case "CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH" -> "고정 원문 해시 불일치";
            case "CURRENT_EVIDENCE_SOURCE_READ_FAILED" -> "원문 저장소 읽기 실패";
            case "CURRENT_EVIDENCE_STRUCTURE_INVALID" -> "근거 구조 검증 실패";
            case "COMPARISON_RESULT_INVALID" -> "비교 결과 검증 실패";
            case "INTERNAL_API_HTTP_ERROR" -> "내부 API HTTP 오류";
            case "INTERNAL_API_TRANSPORT_ERROR" -> "내부 API 연결 실패";
            case "LLM_OUTPUT_TRUNCATED" -> "AI 응답 길이 제한";
            case "LLM_PROVIDER_ERROR" -> "AI 제공자 오류";
            case "LLM_RATE_LIMIT" -> "AI 요청 한도 초과";
            case "LLM_TIMEOUT" -> "AI 응답 시간 초과";
            default -> "확인 불가";
        };
    }

    private static String retry(String reason, AnalysisFailureCode code) {
        return switch (reason) {
            case "CURRENT_EVIDENCE_BINDING_INVALID", "CURRENT_EVIDENCE_MANIFEST_INVALID", "CURRENT_EVIDENCE_OFFSET_INVALID",
                    "CURRENT_EVIDENCE_QUOTE_NOT_FOUND", "CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH", "CURRENT_EVIDENCE_STRUCTURE_INVALID"
                    -> "원문 연결 확인 전 재시도 필요 없음";
            case "CURRENT_EVIDENCE_SOURCE_READ_FAILED" -> "저장소 복구 후 재시도 가능";
            case "COMPARISON_RESULT_INVALID" -> "비교 결과 검토 후 재시도 판단";
            case "INTERNAL_API_HTTP_ERROR" -> "HTTP 상태와 서버 로그 확인 후 재시도 판단";
            case "INTERNAL_API_TRANSPORT_ERROR" -> "내부 API 연결 복구 후 재시도 가능";
            case "LLM_OUTPUT_TRUNCATED" -> "출력 한도 확인 후 재시도 판단";
            case "LLM_PROVIDER_ERROR" -> "제공자 상태 확인 후 재시도 판단";
            case "LLM_RATE_LIMIT" -> "제공자 대기 시간 이후 재시도 가능";
            case "LLM_TIMEOUT" -> "일시 장애 해소 후 재시도 가능";
            default -> switch (code) {
                case AI_TOKEN_QUOTA_EXHAUSTED -> "AI 토큰 충전 후 재시도 가능";
                default -> "원인 확인 후 재시도 판단";
            };
        };
    }

    private static String allowed(String value, java.util.Set<String> values) {
        return value != null && values.contains(value) ? value : "UNKNOWN";
    }

    private static String safeIdentifier(String value, String pattern) {
        return value != null && value.length() <= 128 && value.matches(pattern) ? value : "확인 불가";
    }

    private static String known(Object value) { return value == null ? "확인 불가" : value.toString(); }

    private static void line(StringBuilder message, String line) {
        if (message.length() + line.length() + 1 <= 2000) message.append(line).append('\n');
    }
}
