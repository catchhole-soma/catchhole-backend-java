package org.monitoring.catchholebackend.domain.validation.processor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationSourceContextResponse;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationFinding;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper;
import org.monitoring.catchholebackend.domain.validation.type.ValidationBranch;
import org.monitoring.catchholebackend.domain.validation.type.ValidationSourceContextStatus;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.exception.CommonErrorCode;
import org.springframework.stereotype.Component;

/**
 * 오류 리포트 문맥 블록(2026-11-03): 발견 항목이 인용한 고정 출처의 앞뒤 줄을 읽는다.
 * 읽을 수 있는 출처는 report 고정 manifest(T1·R#) 중 그 항목이 실제로 인용한 것(이번 원고 위치, 판정 근거 문장의 원문)뿐이다.
 * 원문은 {@link ValidationSourceTexts}로만 읽어 hash가 고정값과 다르면 보여 주지 않는다. 한 요청 안에서는 출처마다 한 번만 읽는다.
 */
@Component
@RequiredArgsConstructor
public class ValidationFindingContexts {
    private final ValidationSourceTexts texts;
    private final ValidationDataMapper dataMapper;

    public record Context(ValidationSourceContextResponse context, ValidationSourceContextStatus status) {}

    /** 같은 출처 안 인용 하나(원문 전체 기준 code point [start,end)). */
    public record Range(String quote, int start, int end) {}

    /** 한 블록이 될 인용 묶음. start·end는 묶음 인용 전체를 덮는 구간이고 context는 그 구간 둘레의 창이다. */
    public record Group(List<Range> ranges, int start, int end, Context context) {}

    /** 한 상세 요청 동안 같은 출처를 다시 읽지 않는 reader. */
    public final class Reader {
        private final ValidationReport report;
        private final Map<String, ? extends JsonNode> sources;
        private final Map<String, ValidationSourceTexts.Read> cache = new HashMap<>();

        private Reader(ValidationReport report, Map<String, ? extends JsonNode> sources) {this.report = report; this.sources = sources;}

        /** 인용 [start,end) 둘레 before·after줄. 읽지 못하면 context 없이 상태만 준다. */
        public Context around(String ref, int start, int end, int before, int after) {
            var read = read(ref);
            if (read == null) return new Context(null, ValidationSourceContextStatus.SOURCE_UNAVAILABLE);
            if (!read.ready()) return new Context(null, ValidationSourceContextStatus.valueOf(read.status()));
            if (start < 0 || end < start || end > read.text().codePointCount(0, read.text().length()))
                return new Context(null, ValidationSourceContextStatus.SOURCE_CHANGED);
            return new Context(toResponse(ref, ValidationSourceWindow.window(read.text(), start, end, before, after)), ValidationSourceContextStatus.READY);
        }

        /**
         * 같은 출처 인용들의 블록 묶음(판정 작성 제목·문맥 블록 병합, 2026-11-03). 인용은 원문 위치 순으로 정렬한다.
         * 원문을 읽을 수 있으면 각 인용의 처음 창(before·after줄)이 앞 묶음의 창과 겹치거나 맞닿을 때({@link ValidationSourceWindow#touching})
         * 그 묶음에 넣고, 묶음의 창은 묶음 전체 구간 [가장 작은 start, 가장 큰 end) 둘레의 창이다. 그래서 같은 줄이 두 블록에 나오지 않는다.
         * 읽지 못했거나 범위가 원문 밖이면 합치지 않고 인용마다 상태만 가진 묶음을 준다.
         */
        public List<Group> grouped(String ref, List<Range> ranges, int before, int after) {
            var sorted = ranges.stream().sorted(Comparator.comparingInt(Range::start).thenComparingInt(Range::end)).toList();
            var read = read(ref);
            if (read == null || !read.ready()) {
                var status = read == null ? ValidationSourceContextStatus.SOURCE_UNAVAILABLE : ValidationSourceContextStatus.valueOf(read.status());
                return sorted.stream().map(r -> new Group(List.of(r), r.start(), r.end(), new Context(null, status))).toList();
            }
            String text = read.text();
            int total = text.codePointCount(0, text.length());
            var groups = new ArrayList<Group>();
            List<Range> current = null;
            int start = 0, end = 0;
            ValidationSourceWindow.Window window = null;
            for (var range : sorted) {
                if (range.start() < 0 || range.end() < range.start() || range.end() > total) {
                    if (current != null) { groups.add(group(ref, current, start, end, window)); current = null; }
                    groups.add(new Group(List.of(range), range.start(), range.end(), new Context(null, ValidationSourceContextStatus.SOURCE_CHANGED)));
                    continue;
                }
                var own = ValidationSourceWindow.window(text, range.start(), range.end(), before, after);
                if (current != null && ValidationSourceWindow.touching(text, window, own)) {
                    current.add(range);
                    end = Math.max(end, range.end());
                    window = ValidationSourceWindow.window(text, start, end, before, after);
                } else {
                    if (current != null) groups.add(group(ref, current, start, end, window));
                    current = new ArrayList<>(List.of(range));
                    start = range.start();
                    end = range.end();
                    window = own;
                }
            }
            if (current != null) groups.add(group(ref, current, start, end, window));
            return List.copyOf(groups);
        }

        private ValidationSourceTexts.Read read(String ref) {
            var source = sources.get(ref);
            if (source == null) return null;
            return cache.computeIfAbsent(ref, key -> texts.read(report.getWork().getId(), report.getSourceEpisodeNo(), source, "T1".equals(key), () -> { }));
        }

        private Group group(String ref, List<Range> ranges, int start, int end, ValidationSourceWindow.Window window) {
            return new Group(List.copyOf(ranges), start, end, new Context(toResponse(ref, window), ValidationSourceContextStatus.READY));
        }
    }

    public Reader reader(ValidationReport report, Map<String, ? extends JsonNode> sources) {return new Reader(report, sources);}

    /**
     * GET .../source-context. 범위·줄 수는 호출 전에 검사한다. 이 항목이 인용하지 않은 출처는 400, 원문이 고정값과 다르거나 읽지 못하면
     * 409 VALIDATION_SOURCE_CONTEXT_UNAVAILABLE(context.reason = 상태)이다.
     */
    public ValidationSourceContextResponse window(ValidationReport report, ValidationFinding finding, String sourceRef, int anchorStart, int anchorEnd, int before, int after) {
        if (sourceRef == null || !citedSourceRefs(finding).contains(sourceRef)) throw new AppException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
        if (report.getBaselineSeed() == null) throw unavailable(ValidationSourceContextStatus.SOURCE_UNAVAILABLE);
        var sources = dataMapper.toSourceReferences(report);
        var source = sources.get(sourceRef);
        if (source == null) throw new AppException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
        var read = texts.read(report.getWork().getId(), report.getSourceEpisodeNo(), source, "T1".equals(sourceRef), () -> { });
        if (!read.ready()) throw unavailable(ValidationSourceContextStatus.valueOf(read.status()));
        if (anchorEnd > read.text().codePointCount(0, read.text().length())) throw new AppException(CommonErrorCode.REQUEST_VALIDATION_FAILED);
        return toResponse(sourceRef, ValidationSourceWindow.window(read.text(), anchorStart, anchorEnd, before, after));
    }

    /** 항목이 인용한 원문 출처: 이번 원고 위치(설정 target_spans·오탈자 span)와 원문 근거 문장(basis_quotes 중 위치가 있는 것). */
    public static Set<String> citedSourceRefs(ValidationFinding finding) {
        var payload = finding.getPayload();
        var refs = new LinkedHashSet<String>();
        if (payload == null) return refs;
        if (finding.getBranch() == ValidationBranch.TYPO) add(refs, payload.path("span"));
        else payload.path("target_spans").forEach(span -> add(refs, span));
        payload.path("basis_quotes").forEach(quote -> { if (quote.path("start").isIntegralNumber()) add(refs, quote); });
        return refs;
    }

    private static void add(Set<String> refs, JsonNode node) {
        if (node.path("source_ref").isTextual() && node.path("start").isIntegralNumber() && node.path("end").isIntegralNumber()) refs.add(node.path("source_ref").asText());
    }

    private static ValidationSourceContextResponse toResponse(String ref, ValidationSourceWindow.Window window) {
        return new ValidationSourceContextResponse(ref, window.start(), window.end(), window.totalCodePoints(), window.hasMoreBefore(), window.hasMoreAfter(),
                window.lines().stream().map(line -> new ValidationSourceContextResponse.Line(line.start(), line.end(), line.text())).toList());
    }

    private static AppException unavailable(ValidationSourceContextStatus status) {
        return new AppException(ValidationErrorCode.VALIDATION_SOURCE_CONTEXT_UNAVAILABLE, Map.of("reason", status.name()));
    }
}
