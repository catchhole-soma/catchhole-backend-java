package org.monitoring.catchholebackend.domain.analysis.mapper;

import java.util.List;
import org.monitoring.catchholebackend.global.notification.DiscordWebhookClient;
import org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureCheck;
import org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureExcerpt;
import org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotificationDelivery;
import java.util.stream.Collectors;
import org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureDiagnostics;
import org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotification;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.springframework.stereotype.Component;

@Component
public class AnalysisFailureNotificationMapper {
    /** 한 발송의 본문에는 실행 식별자, 각 embed에는 결과와 실제 실패 검사를 담는다. */
    public DiscordWebhookClient.Message toPayload(AnalysisFailureNotificationDelivery delivery, String environment) {
        var events = delivery.notifications();
        var first = events.getFirst();
        String env = "prod".equals(environment) ? "운영" : "local".equals(environment) ? "로컬" : "기타";
        StringBuilder content = new StringBuilder();
        boolean coreStopped = events.stream().anyMatch(e -> isJob(e) && "SETTING_EXTRACTION".equals(e.jobType()));
        long partials = events.stream().filter(AnalysisFailureNotificationMapper::isRelationshipPartial).count();
        String heading = coreStopped ? "회차 분석 중단" : partials > 0 ? "관계도 부분 실패"
                : events.stream().anyMatch(e -> isJob(e) && "RELATIONSHIP_EXTRACTION".equals(e.jobType())) ? "관계도 분석 중단" : "분석 오류";
        line(content, (partials > 0 && !coreStopped ? "⚠️" : "🚨") + " [" + env + "] " + heading + " · " + events.size() + "개 기록");
        long jobs = events.stream().filter(AnalysisFailureNotificationMapper::isJob).count();
        long candidates = events.stream().filter(AnalysisFailureNotificationMapper::isCandidate).mapToLong(e -> Math.max(1, e.failureCount())).sum();
        line(content, "실패 작업: " + jobs + "건" + (partials > 0 ? " / 관계도 부분 실패: " + partials + "건" : "")
                + " / 비교 실패 후보: " + candidates + "개 (별도 집계)");
        optionalLine(content, "실행 Job ID", first.executionJobId());
        optionalLine(content, "실행 시도 ID", delivery.executionAttemptId());
        line(content, "회원 ID: " + known(first.memberId()) + " / 작품 ID: " + known(first.workId()));
        line(content, "묶음 ID: " + delivery.id());
        for (int i = 0; i < events.size(); i++) {
            var event = events.get(i);
            // Older outbox snapshots have no execution/source IDs; keep their recorded provenance.
            if (event.executionJobId() == null) optionalLine(content, (i + 1) + "번 기록 Job ID", event.jobId());
            optionalLine(content, (i + 1) + "번 기록 회차 ID", event.episodeId());
        }
        line(content, "아래 처리 결과는 실패 발생 당시 상태입니다.");
        return new DiscordWebhookClient.Message(content.toString(), events.stream().map(event -> {
            var d = validDiagnostic(event);
            var code = failureCode(event);
            String reason = d == null ? "UNKNOWN" : allowed(d.reasonCode(), AnalysisFailureDiagnostics.REASONS);
            boolean subjectFailure = "WORLD_SUBJECT_REFERENCE_INVALID".equals(reason) && d.subjectName() != null;
            String title = subjectFailure ? targetEpisodes(event) + " ‘" + display(d.subjectName(), 80) + "’ 설정 반영 실패"
                    : title(event) + " · " + targetEpisodes(event);
            StringBuilder detail = new StringBuilder();
            if (subjectFailure) {
                var check = d.checks().stream().filter(c -> "selectedSubjectRefs".equals(c.field())).findFirst().orElse(null);
                detail.append("이유: ").append(check != null && "UNIQUE".equals(check.rule())
                        ? "AI가 같은 대상을 중복 선택했습니다." : "AI가 선택지에 없는 대상을 골랐습니다.").append('\n');
                d.excerpts().stream().filter(e -> "subjectOptions".equals(e.field())).findFirst()
                        .ifPresent(e -> detail.append("선택지: ").append(excerptValue(e)).append('\n'));
                if (check != null) detail.append("AI 선택: ").append(check.actual())
                        .append("IN_SET".equals(check.rule()) ? " — 선택지에 없음" : " — 중복 선택").append('\n');
                detail.append("결과: ").append(isCandidate(event) && "PENDING_REVIEW".equals(event.candidateReviewStatus())
                        ? "자동 반영 중단 · 검토 목록에 보관" : outcome(event)).append('\n');

            } else {
                detail.append("처리 결과: ").append(isRelationshipPartial(event) && coreStopped
                        ? "관계도 부분 실패 · 회차 분석은 별도 오류로 중단" : outcome(event)).append('\n');
                detail.append("실패 단계: ").append(stage(d == null ? null : d.stage())).append('\n');
                detail.append("이유: ").append(code == AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED ? "AI 토큰 부족" : observedExplanation(reason, d)).append('\n');
            }
            if (Boolean.TRUE.equals(event.continuesAfterCandidateFailure())) detail.append(coreStopped
                    ? "영향: 이 설정은 검토 목록에 보관 · 회차 분석은 별도 오류로 중단\n"
                    : "영향: 이 설정만 자동 반영 중단 · 나머지 분석 계속\n");
            detail.append(isRelationshipComparison(event) ? "업데이트 미완료 인물 쌍: " + Math.max(1, event.failureCount()) + "개"
                    : isCandidate(event) ? "실패 후보: " + Math.max(1, event.failureCount()) + "개"
                    : isRelationshipPartial(event) ? "관계도 부분 실패: 1건" : "실패 작업: 1건").append('\n');
            if (d != null) for (var excerpt : d.excerpts()) {
                if (subjectFailure && "subjectOptions".equals(excerpt.field())) continue;
                detail.append(excerptLabel(excerpt)).append(": ").append(excerptValue(excerpt)).append('\n');
            }
            if (d == null || d.checks().isEmpty() && d.excerpts().isEmpty()) detail.append("실제 값: 이 실패 경로에서 검사 값이 기록되지 않음\n");
            else if (!subjectFailure) for (var check : d.checks()) detail.append(checkText(check, !d.excerpts().isEmpty())).append('\n');
            detail.append("원인 코드: ").append(reason).append(" / ").append(code.name()).append('\n');
            detail.append("알림 ID: ").append(event.id()).append('\n');
            int budget = 1450 - title.length();
            appendDetail(detail, "재시도: " + retry(reason, code), budget);
            if (isCandidate(event) && !event.failedCandidateIds().isEmpty())
                appendDetail(detail, "실패 후보 ID (최대 8개): " + event.failedCandidateIds().stream().limit(8).map(Object::toString).collect(Collectors.joining(", ")), budget);
            appendDetail(detail, "발생 시각: " + known(event.occurredAt()) + " (서버 시간)", budget);
            if (d != null) {
                if (d.httpStatus() != null && d.httpStatus() >= 100 && d.httpStatus() <= 599) appendDetail(detail, "HTTP: " + d.httpStatus(), budget);
                String source = allowed(d.sourceErrorCode(), AnalysisFailureDiagnostics.SOURCE_CODES);
                if (!"UNKNOWN".equals(source)) appendDetail(detail, "원천 코드: " + source, budget);
                if (d.sourceEpisodeNo() != null && d.sourceEpisodeNo() > 0) appendDetail(detail, "원인 근거 회차: " + d.sourceEpisodeNo() + "화", budget);
                if (d.sourceCandidateId() != null) appendDetail(detail, "원인 후보 ID: " + d.sourceCandidateId(), budget);
                if (d.sourceAnalysisJobId() != null) appendDetail(detail, "원인 근거 Job ID: " + d.sourceAnalysisJobId(), budget);
                if (d.sourceEpisodeId() != null) appendDetail(detail, "원인 회차 ID: " + d.sourceEpisodeId(), budget);
                String pattern = "PROVIDER".equals(d.origin()) ? "[A-Za-z0-9_-]{1,128}" : "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
                String request = safeIdentifier(d.requestId(), pattern), trace = safeIdentifier(d.traceId(), "[0-9a-f]{32}");
                if (request != null) appendDetail(detail, "요청 ID: " + request, budget);
                if (trace != null) appendDetail(detail, "Trace ID: " + trace, budget);
            }
            if (event.sourceAnalysisJobId() != null) appendDetail(detail, "원본 추출 Job ID: " + event.sourceAnalysisJobId(), budget);
            if (event.comparisonBatchId() != null) appendDetail(detail, "비교 묶음 ID: " + event.comparisonBatchId(), budget);
            return new DiscordWebhookClient.Embed(title, detail.toString());
        }).toList());
    }

    private static void appendDetail(StringBuilder text, String line, int budget) {
        if (text.length() + line.length() + 1 <= budget) text.append(line).append('\n');
    }

    private static String observedExplanation(String reason, AnalysisFailureDiagnostics diagnostic) {
        if (diagnostic != null) {
            for (var check : diagnostic.checks()) {
                if ("WORLD_SUBJECT_REFERENCE_INVALID".equals(reason) && "selectedSubjectRefs".equals(check.field())) {
                    if ("IN_SET".equals(check.rule())) return "AI가 선택할 수 없는 세계관 대상 참조를 반환함";
                    if ("UNIQUE".equals(check.rule())) return "AI가 같은 세계관 대상을 중복 선택함";
                }
                if ("RELATIONSHIP_OBSERVATION_REFS_INVALID".equals(reason) && "observationRefs".equals(check.field())) {
                    if ("IN_SET".equals(check.rule())) return "AI가 입력에 없는 관계 근거를 참조함";
                    if ("UNIQUE".equals(check.rule())) return "AI가 같은 관계 근거를 중복 사용함";
                }
            }
        }
        return explanation(reason);
    }

    private static String outcome(AnalysisFailureNotification event) {
        if (isRelationshipComparison(event)) return "일부 인물 쌍 업데이트 미완료 · 정상 관계 반영 · 후속 분석 계속";
        if (isRelationshipPartial(event)) return "관계도만 실패 · 나머지 분석 계속";
        if (isCandidate(event)) return switch (event.candidateReviewStatus() == null ? "" : event.candidateReviewStatus()) {
            case "PENDING_REVIEW" -> "후보 저장됨 · 비교 실패(FAILED) · 검토 대기(PENDING_REVIEW)";
            case "CONFIRMED" -> "후보 저장됨 · 비교 실패(FAILED) · 검토 상태는 이미 확정(CONFIRMED)";
            case "DISMISSED" -> "후보 저장됨 · 비교 실패(FAILED) · 검토 상태는 제외(DISMISSED)";
            default -> "후보 비교 실패(FAILED) · 당시 검토 상태 미기록";
        };
        if ("RELATIONSHIP_EXTRACTION".equals(event.jobType())) return "관계도 전용 작업 FAILED · 관계도 분석 중단";
        if ("CHARACTER_FACT_COMPARISON".equals(event.jobType()) || "WORLD_SETTING_COMPARISON".equals(event.jobType()))
            return "재비교 작업 FAILED · 재비교 중단";
        return "작업 FAILED · " + ("SETTING_EXTRACTION".equals(event.jobType()) ? "회차 분석 중단" : "작업 중단");
    }

    private static String excerptLabel(AnalysisFailureExcerpt e) {
        if ("SOURCE".equals(e.kind())) return "후보 근거";
        if ("subjectOptions".equals(e.field())) return "선택지";
        if ("EXPECTED".equals(e.kind())) return "relationshipLabel".equals(e.field()) ? "기존 관계 이름" : "기존 관계 설명";
        return switch (e.field()) {
            case "relationshipLabel" -> "AI가 반환한 관계 이름";
            case "relationshipSummary" -> "AI가 반환한 관계 설명";
            case "relationshipReason" -> "AI가 반환한 판단 이유";
            default -> "AI 응답 발췌";
        };
    }

    private static String excerptValue(AnalysisFailureExcerpt e) {
        String value = display(e.text(), 96);
        return value + (e.truncated() || display(e.text(), Integer.MAX_VALUE).length() > 96 ? "… (발췌)" : "");
    }

    /** Escape Discord formatting/mentions; cap the rendered representation without splitting code points. */
    private static String display(String raw, int limit) {
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < raw.length();) {
            int cp = raw.codePointAt(offset);
            offset += Character.charCount(cp);
            String value;
            if (cp == '\n' || cp == '\r') value = "↵";
            else if (cp == '\t') value = "⇥";
            else if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT) value = "�";
            else if (cp == '@') value = "@\u200b";
            else if ("\\`*_~|[]<>".indexOf(cp) >= 0) value = "\\" + new String(Character.toChars(cp));
            else value = new String(Character.toChars(cp));
            if (result.length() + value.length() > limit) break;
            result.append(value);
        }
        return result.toString();
    }

    private static String checkText(AnalysisFailureCheck c, boolean compact) {
        String label = switch (c.field()) {
            case "inputTokens" -> "입력 토큰 추정치";
            case "sourceBytes" -> "원문 바이트 수";
            case "chunkCount" -> "원문 청크 수";
            case "comparisonBatchCount" -> "비교 요청 묶음 수";
            case "distinctNodeCount" -> "관계 양쪽의 서로 다른 인물 수";
            case "observationCount" -> "관계 관측 수";
            case "decisionCount" -> "판단 수";
            case "targetUseCount" -> "같은 관계 변경 횟수";
            case "observationRefs" -> "관계 근거 참조";
            case "newObservationRefs" -> "신규 관계 근거 참조";
            case "candidateRefs" -> "처리할 후보 참조";
            case "selectedSubjectRefs" -> "선택한 세계관 대상 참조";
            case "sourceNodeRef" -> "관계 출발 인물 참조";
            case "targetNodeRef" -> "관계 도착 인물 참조";
            case "relationshipRef" -> "변경 대상 관계 참조";
            case "temporalScope" -> "관계 시점 분류";
            case "targetStatus" -> "기존 관계 상태";
            case "quoteMatchesSource" -> "인용문과 원문 일치 여부";
            case "sourceHashMatches" -> "원문 해시 일치 여부";
            case "quoteLength" -> "인용문 글자 수";
            case "sourceLength" -> "원문 글자 수";
            case "startOffset" -> "원문 시작 위치";
            case "endOffset" -> "원문 끝 위치";
            case "hasIdentityProofs" -> "동일 주체 증명 포함 여부";
            case "ambiguous" -> "주체 연결 모호 여부";
            default -> c.field();
        };
        String rule = switch (c.rule()) {
            case "MAX" -> "최대 "; case "MIN" -> "최소 "; case "IN_SET" -> "선택 가능 목록 ";
            case "ORDERED" -> "순서대로 "; case "UNIQUE" -> "중복 없이 "; case "COVERS" -> "모두 포함 ";
            case "ABSENT" -> "없어야 함 "; case "DIFFERENT" -> "달라야 할 값 "; default -> "";
        };
        String refs = c.field().endsWith("Refs") ? " (관련 참조 일부)" : "";
        String expected = compact ? compactRefs(c.expected()) : c.expected();
        String actual = compact ? compactRefs(c.actual()) : c.actual();
        return "검사: " + label + (c.itemIndex() == null ? "" : " · 응답 항목 " + c.itemIndex())
                + "\n  기대: " + rule + expected + " / 실제: " + actual + refs;
    }

    private static String compactRefs(String value) {
        String[] parts = value.split(",");
        return parts.length <= 3 ? value : String.join(",", java.util.Arrays.copyOf(parts, 3)) + ",…";
    }

    public String toMessage(AnalysisFailureNotificationDelivery delivery, String environment) {
        var events = delivery.notifications();
        if (events.size() == 1) {
            String message = toMessage(events.getFirst(), environment);
            return message + (message.length() < 1850 ? "묶음 ID: " + delivery.id() + "\n"
                    + (delivery.executionAttemptId() == null ? "" : "실행 시도 ID: " + delivery.executionAttemptId() + "\n") : "");
        }
        var first = events.getFirst();
        StringBuilder message = new StringBuilder();
        String env = "prod".equals(environment) ? "운영" : "local".equals(environment) ? "로컬" : "기타";
        line(message, "🚨 [" + env + "] 분석 실패 묶음 (" + events.size() + "개 기록)");
        long jobs = events.stream().filter(AnalysisFailureNotificationMapper::isJob).count();
        long candidates = events.stream().filter(AnalysisFailureNotificationMapper::isCandidate).mapToLong(e -> Math.max(1, e.failureCount())).sum();
        line(message, "실패 작업: " + jobs + "건 / 실패 항목: " + candidates + "개 (작업과 항목은 별도 집계)");
        optionalLine(message, "실행 Job ID", first.executionJobId());
        optionalLine(message, "실행 시도 ID", delivery.executionAttemptId());
        line(message, "회원 ID: " + known(first.memberId()) + " / 작품 ID: " + known(first.workId()));
        line(message, "묶음 ID: " + delivery.id());
        var times = events.stream().map(AnalysisFailureNotification::occurredAt).filter(java.util.Objects::nonNull).sorted().toList();
        if (!times.isEmpty()) line(message, "발생 시각: " + times.getFirst()
                + (times.getFirst().equals(times.getLast()) ? "" : " ~ " + times.getLast()) + " (서버 시간)");
        for (int i = 0; i < events.size(); i++) {
            var event = events.get(i);
            var diagnostic = validDiagnostic(event);
            String reason = diagnostic == null ? "UNKNOWN" : allowed(diagnostic.reasonCode(), AnalysisFailureDiagnostics.REASONS);
            var code = failureCode(event);
            String count = isRelationshipComparison(event) ? "업데이트 미완료 인물 쌍 " + Math.max(1, event.failureCount()) + "개" : isCandidate(event) ? Math.max(1, event.failureCount()) + "개 항목" : isRelationshipPartial(event) ? "관계도 부분 실패 1건" : "작업 1건";
            line(message, "\n" + (i + 1) + ". " + title(event) + " · " + targetEpisodes(event) + " · " + count);
            line(message, stage(diagnostic == null ? null : diagnostic.stage()) + " / "
                    + (code == AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED ? "AI 토큰 부족" : explanation(reason)) + " (" + reason + ")");
            line(message, "실패 코드: " + code.name());
            line(message, "재시도: " + retry(reason, code));
            line(message, "알림 ID: " + event.id());
        }
        // 부가 관측치는 각 원본 알림 번호에 연결한다. 다른 원인의 HTTP/trace를 대표값으로 쓰지 않는다.
        for (int i = 0; i < events.size(); i++) {
            var diagnostic = validDiagnostic(events.get(i));
            if (diagnostic == null) continue;
            String prefix = (i + 1) + "번 ";
            if (diagnostic.httpStatus() != null && diagnostic.httpStatus() >= 100 && diagnostic.httpStatus() <= 599)
                optionalLine(message, prefix + "HTTP", diagnostic.httpStatus());
            String source = allowed(diagnostic.sourceErrorCode(), AnalysisFailureDiagnostics.SOURCE_CODES);
            if (!"UNKNOWN".equals(source)) optionalLine(message, prefix + "원천 코드", source);
            optionalLine(message, prefix + "원인 근거 Job ID", diagnostic.sourceAnalysisJobId());
            optionalLine(message, prefix + "원인 후보 ID", diagnostic.sourceCandidateId());
            optionalLine(message, prefix + "원인 회차 ID", diagnostic.sourceEpisodeId());
            optionalLine(message, prefix + "원인 근거 회차", diagnostic.sourceEpisodeNo() != null && diagnostic.sourceEpisodeNo() > 0
                    ? diagnostic.sourceEpisodeNo() + "화" : null);
            String requestPattern = "PROVIDER".equals(diagnostic.origin()) ? "[A-Za-z0-9_-]{1,128}"
                    : "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
            optionalLine(message, prefix + "요청 ID", safeIdentifier(diagnostic.requestId(), requestPattern));
            optionalLine(message, prefix + "Trace ID", safeIdentifier(diagnostic.traceId(), "[0-9a-f]{32}"));
        }
        return message.toString();
    }

    private static AnalysisFailureCode failureCode(AnalysisFailureNotification event) {
        try { return AnalysisFailureCode.valueOf(event.failureCode()); }
        catch (IllegalArgumentException | NullPointerException ex) { return AnalysisFailureCode.UNEXPECTED_ERROR; }
    }

    private static AnalysisFailureDiagnostics validDiagnostic(AnalysisFailureNotification event) {
        var d = event.failureDiagnostics();
        return d != null && d.schemaVersion() == 1 && AnalysisFailureDiagnostics.ORIGINS.contains(d.origin() == null ? "" : d.origin()) ? d : null;
    }

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
        var sourceJob = event.sourceAnalysisJobId() != null ? event.sourceAnalysisJobId()
                : candidate ? event.jobId() : null;
        var execution = event.executionJobId() != null ? event.executionJobId()
                : "ANALYSIS_JOB".equals(event.failureKind()) ? event.jobId() : null;
        StringBuilder message = new StringBuilder();
        // 식별자·원인·단계를 먼저 확보한다. 선택 진단 때문에 필수 문맥이 잘리지 않는다.
        line(message, "🚨 [" + safeEnvironment + "] " + title(event));
        line(message, "단계: " + stage(diagnostic == null ? null : diagnostic.stage()));
        line(message, "원인: " + (code == AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED ? "AI 토큰 부족" : explanation(reason)) + " (" + reason + ")");
        line(message, "실패 코드: " + code.name() + " / " + code.getPublicMessage());
        line(message, "대상 회차: " + targetEpisodes(event) + " / "
                + (isRelationshipComparison(event) ? "업데이트 미완료 인물 쌍: " + Math.max(1, event.failureCount()) + "개"
                    : candidate ? "실패 항목: " + Math.max(1, event.failureCount()) + "개" : isRelationshipPartial(event) ? "관계도 부분 실패: 1건" : "실패 작업: 1건"));
        if (isRelationshipPartial(event)) line(message, "처리 결과: " + outcome(event));
        line(message, "재시도: " + retry(reason, code));
        line(message, "실행 Job ID: " + known(execution));
        optionalLine(message, "원본 추출 Job ID", sourceJob);
        optionalLine(message, "비교 묶음 ID", event.comparisonBatchId());
        if (event.executionJobId() == null) line(message, "기록 Job ID: " + known(event.jobId()));
        line(message, "회원 ID: " + known(event.memberId()) + " / 작품 ID: " + known(event.workId()));
        line(message, "기록 회차 ID: " + known(event.episodeId()));
        line(message, "발생 시각: " + known(event.occurredAt()) + " (서버 시간)");
        line(message, "알림 ID: " + known(event.id()));
        if (diagnostic != null) {
            line(message, "진단 경계: " + diagnostic.origin());
            String sourceCode = allowed(diagnostic.sourceErrorCode(), AnalysisFailureDiagnostics.SOURCE_CODES);
            if (!"UNKNOWN".equals(sourceCode)) optionalLine(message, "원천 코드", sourceCode);
            if (diagnostic.httpStatus() != null && diagnostic.httpStatus() >= 100 && diagnostic.httpStatus() <= 599) {
                line(message, "HTTP: " + diagnostic.httpStatus());
            }
            String requestPattern = "PROVIDER".equals(diagnostic.origin()) ? "[A-Za-z0-9_-]{1,128}"
                    : "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
            optionalLine(message, "요청 ID", safeIdentifier(diagnostic.requestId(), requestPattern));
            optionalLine(message, "Trace ID", safeIdentifier(diagnostic.traceId(), "[0-9a-f]{32}"));
            optionalLine(message, "원인 근거 회차", diagnostic.sourceEpisodeNo() != null && diagnostic.sourceEpisodeNo() > 0
                    ? diagnostic.sourceEpisodeNo() + "화" : null);
            optionalLine(message, "원인 근거 Job ID", diagnostic.sourceAnalysisJobId());
            optionalLine(message, "원인 후보 ID", diagnostic.sourceCandidateId());
            optionalLine(message, "원인 회차 ID", diagnostic.sourceEpisodeId());
        }
        return message.toString();
    }

    private static boolean isCandidate(AnalysisFailureNotification event) {
        return "CHARACTER_COMPARISON".equals(event.failureKind()) || "WORLD_COMPARISON".equals(event.failureKind());
    }

    private static boolean isRelationshipComparison(AnalysisFailureNotification event) {
        return "RELATIONSHIP_COMPARISON".equals(event.failureKind());
    }

    private static boolean isRelationshipPartial(AnalysisFailureNotification event) {
        return "RELATIONSHIP_PARTIAL".equals(event.failureKind()) || isRelationshipComparison(event);
    }

    private static boolean isJob(AnalysisFailureNotification event) {
        return !isCandidate(event) && !isRelationshipPartial(event);
    }

    private static String title(AnalysisFailureNotification event) {
        if (isRelationshipComparison(event)) return "인물 관계 일부 업데이트 미완료";
        if (isRelationshipPartial(event)) return "관계도 부분 실패";
        if ("CHARACTER_COMPARISON".equals(event.failureKind())) return "캐릭터 비교 항목 실패";
        if ("WORLD_COMPARISON".equals(event.failureKind())) return "세계관 비교 항목 실패";
        if ("CHARACTER_FACT_COMPARISON".equals(event.jobType())) return "캐릭터 재비교 실행 실패";
        if ("WORLD_SETTING_COMPARISON".equals(event.jobType())) return "세계관 재비교 실행 실패";
        if ("RELATIONSHIP_EXTRACTION".equals(event.jobType())) return "인물 관계 분석 실패";
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
            case "RELATIONSHIP_EXTRACTION" -> "인물 관계 추출";
            case "RELATIONSHIP_COMPARE" -> "인물 관계 비교";
            case "RELATIONSHIP_RESUME" -> "저장된 관계 분석 재개";
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
            case "RELATIONSHIP_OBSERVATION_REFS_INVALID" -> "관계 관측 참조가 없거나 중복됨";
            case "RELATIONSHIP_OBSERVATION_ORDER_INVALID" -> "관측 참조 순서가 잘못됐거나 새 근거가 없음";
            case "RELATIONSHIP_DISPOSITION_CONFLICT" -> "보류·제외 판단이 중복되거나 변경 판단과 충돌함";
            case "RELATIONSHIP_DISPLAY_REFERENCE_LEAK" -> "표시 문구에 내부 참조가 포함됨";
            case "RELATIONSHIP_DISPOSITION_CONTENT_INVALID" -> "보류·제외 판단의 내용이 유효하지 않음";
            case "RELATIONSHIP_NONPRESENT_MUTATION" -> "현재로 분류되지 않은 관계를 변경하려 함";
            case "RELATIONSHIP_MUTATION_CONTENT_MISSING" -> "관계 변경에 필요한 내용이 누락됨";
            case "RELATIONSHIP_SELF_RELATIONSHIP" -> "같은 인물끼리의 관계가 생성됨";
            case "RELATIONSHIP_ENDPOINTS_UNGROUNDED" -> "관계 양쪽 인물을 근거에 연결하지 못함";
            case "RELATIONSHIP_DUPLICATE_SLOT" -> "같은 인물 관계를 중복 변경함";
            case "RELATIONSHIP_ADD_SLOT_EXISTS" -> "새 관계 추가에 기존 관계 참조나 중복 관계가 포함됨";
            case "RELATIONSHIP_EXISTING_REF_MISMATCH" -> "변경 대상과 기존 관계 참조가 맞지 않음";
            case "RELATIONSHIP_DUPLICATE_TARGET" -> "여러 판단이 같은 기존 관계를 변경함";
            case "RELATIONSHIP_INACTIVE_TARGET" -> "종료된 관계를 변경하려 함";
            case "RELATIONSHIP_MERGE_CONTENT_CHANGED" -> "근거만 추가해야 하는데 관계 설명까지 바꿨습니다.";
            case "RELATIONSHIP_OBSERVATION_COVERAGE_MISSING" -> "추출한 관계 일부의 판단이 누락됨";
            case "RELATIONSHIP_RESPONSE_JSON_INVALID" -> "관계 응답을 JSON으로 읽지 못함";
            case "RELATIONSHIP_RESPONSE_SCHEMA_INVALID" -> "관계 응답 형식이 계약과 맞지 않음";
            case "RELATIONSHIP_PREVIOUS_FAILURE" -> "이전 관계 분석 실패를 재개함 (당시 세부 원인 미저장)";
            case "WORLD_SUBJECT_RESOLUTION_FAILED" -> "세계관 설정의 대상을 확정하지 못함";
            case "WORLD_SUBJECT_COVERAGE_INVALID" -> "입력 후보와 응답 후보의 개수 또는 순서가 다름";
            case "WORLD_SUBJECT_REFERENCE_INVALID" -> "선택할 수 없는 세계관 대상을 참조했거나 대상을 중복 선택함";
            case "WORLD_SUBJECT_RESPONSE_INVALID" -> "세계관 대상 연결 응답이 현재 분석 방식과 맞지 않음";
            case "WORLD_COMPARISON_INPUT_TOO_LARGE" -> "세계관 비교 입력 한도 초과";
            case "RELATIONSHIP_INPUT_TOO_LARGE" -> "관계 분석 입력 한도 초과";
            case "RELATIONSHIP_CONTEXT_INVALID" -> "고정 관계 문맥 검증 실패";
            case "RELATIONSHIP_PREDECESSOR_INCOMPLETE" -> "앞 회차의 관계 갱신 미완료";
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
        if (code == AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED) return "AI 토큰 충전 후 재시도 가능";
        // The caller has already restricted this value to the diagnostic allowlist.
        if (reason.startsWith("RELATIONSHIP_")) return "원인 확인 후 제한된 복구 여부 판단";
        return switch (reason) {
            case "CURRENT_EVIDENCE_BINDING_INVALID", "CURRENT_EVIDENCE_MANIFEST_INVALID", "CURRENT_EVIDENCE_OFFSET_INVALID",
                    "CURRENT_EVIDENCE_QUOTE_NOT_FOUND", "CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH", "CURRENT_EVIDENCE_STRUCTURE_INVALID"
                    -> "원문 연결 확인 전 재시도 필요 없음";
            case "CURRENT_EVIDENCE_SOURCE_READ_FAILED" -> "저장소 복구 후 재시도 가능";
            case "COMPARISON_RESULT_INVALID" -> "비교 결과 검토 후 재시도 판단";
            case "WORLD_SUBJECT_RESOLUTION_FAILED" -> "대상 해석 결과 검토 후 재시도 판단";
            case "WORLD_COMPARISON_INPUT_TOO_LARGE" -> "비교 입력 범위 확인 후 재시도 판단";
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
        return value != null && value.length() <= 128 && value.matches(pattern) ? value : null;
    }

    private static void optionalLine(StringBuilder message, String label, Object value) {
        if (value != null) line(message, label + ": " + value);
    }

    private static String known(Object value) { return value == null ? "확인 불가" : value.toString(); }

    private static void line(StringBuilder message, String line) {
        if (message.length() + line.length() + 1 <= 2000) message.append(line).append('\n');
    }
}
