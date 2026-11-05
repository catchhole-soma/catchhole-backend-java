package org.monitoring.catchholebackend.domain.validation.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.*;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSettingDisplayNameResolver;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationFindingDetailResponse.BasisQuote;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationFindingDetailResponse.Block;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationFindingDetailResponse.BlockQuote;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationFindingDetailResponse.Evidence;
import org.monitoring.catchholebackend.domain.validation.dto.response.ValidationFindingDetailResponse.Highlight;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationEvidence;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationFinding;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper;
import org.monitoring.catchholebackend.domain.validation.type.ValidationApplicationStatus;
import org.monitoring.catchholebackend.domain.validation.type.ValidationBranch;
import org.monitoring.catchholebackend.domain.validation.type.ValidationFindingBlockKind;
import org.monitoring.catchholebackend.domain.validation.type.ValidationQuoteSourceKind;
import org.monitoring.catchholebackend.domain.validation.type.ValidationReportStatus;
import org.springframework.stereotype.Component;

/**
 * 오류 리포트 가독성(2026-11-02): 상세 화면이 내부 참조(T1·E·B·R) 없이 읽을 수 있는 작가용 표기를 서버에서 만든다.
 * report의 고정 출처({@link ValidationFindingContexts#fixedSources})와 저장된 판정 payload(evidence_bundle 포함)를 읽는다. 저장 설정값 근거 문장(B 인용)은
 * legacy(seed) report면 고정 기준(baselineEntries)에서, #381 live report면 finding에 저장한 이름표(quoted_subjects, 없으면 V102 대표 대상)에서
 * 대상·설정명을 정하고, report가 유효하고 회차 전체 확정(APPLIED) 전일 때만 현재 확정 설정 행(id로 읽음)에서 값 전체(settingValue)를 읽는다.
 * 그 기간에는 잠금과 무효화 규칙 때문에 현재값이 판정 때 값과 같다. APPLIED 뒤에는 설정이 바뀔 수 있어 값을 보이지 않는다(null).
 * 설정집 이름만 업로드 파일의 원래 파일명(확장자 제외)을 조회 시점에 읽는다.
 * 오류 리포트 문맥 블록(2026-11-03): 상세 비교 영역의 블록(이번 원고 위치 → 저장 설정값 → 이전 원문 근거 문장)과
 * 원문 블록의 처음 앞뒤 2줄도 여기서 만든다. 앞뒤 줄은 {@link ValidationFindingContexts}가 hash를 확인한 고정 원문에서만 읽는다.
 * 판정 작성 제목(2026-11-03): 판정 payload의 headline을 작가용 제목으로 그대로 주고, 같은 출처의 인용끼리 처음 앞뒤 줄이 겹치거나
 * 맞닿으면 블록 하나로 합친다(같은 줄이 두 블록에 나오지 않는다).
 */
@Component
@RequiredArgsConstructor
public class ValidationFindingLabels {
    /** 화면에 그대로 보이면 안 되는 내부 참조(T1·TSEG·E/B/R/S/L/Q 번호)와 조회 용어. */
    private static final Pattern INTERNAL = Pattern.compile("(?<![A-Za-z0-9])(T1|TSEG[1-9][0-9]*|[EBRSLQ][1-9][0-9]*)(?![0-9])"
            + "|(?i)(?<![A-Za-z])(limited|receipts?|readiness|vector|baseline|target_context|keyword|evidence_refs|receipt_refs|source_ref|evidence_bundle|rag)(?![A-Za-z])|영수증");
    private static final Pattern FILE_EXTENSION = Pattern.compile("\\.(?i)(txt|docx|hwp|hwpx)$");
    private static final int MAX_TOPIC_CODE_POINTS = 200;
    /** 설정값 블록에 값 전체를 함께 보이는 상한. 더 길면 근거 문장만 보인다. */
    private static final int MAX_SETTING_VALUE_CODE_POINTS = 1_000;

    private final ValidationDataMapper dataMapper;
    private final UploadFileRepository uploadFiles;
    private final CharacterSettingSchemaRepository schemas;
    private final CharacterSettingDisplayNameResolver displayNames;
    private final ValidationFindingContexts contexts;
    private final org.monitoring.catchholebackend.domain.validation.repository.ValidationSettingsApplicationRepository applications;

    public record View(String topic, String headline, Integer targetEpisodeNo, List<BasisQuote> basisQuotes, List<Evidence> evidence, List<Block> blocks) {}

    /** 근거 문장과, 설정값이면 그 문장을 담은 값 전체(하나의 값에만 들어 있을 때)·고정 기준 항목 참조. */
    private record Quoted(BasisQuote quote, String settingValue, String entryRef) {}

    /** 질문이 확인한 항목. 판정 payload의 evidence_bundle.question.property_or_rule이며 내부 참조처럼 보이면 null이다. */
    public static String topic(JsonNode payload) {
        if (payload == null) return null;
        var value = payload.path("evidence_bundle").path("question").path("property_or_rule");
        if (!value.isTextual()) return null;
        String text = value.asText().strip();
        return text.isEmpty() || ValidationBasisQuotes.codePoints(text) > MAX_TOPIC_CODE_POINTS || INTERNAL.matcher(text).find() ? null : text;
    }

    /**
     * 판정 작성 제목(2026-11-03, 공통 계약 {@code execution.judgeFlow.headline}). 판정 payload의 headline이 문자열이고 앞뒤 공백을 뗀 뒤
     * 비어 있지 않으며 {@value ValidationDecisionValidator#MAX_HEADLINE_CODE_POINTS} code point 이하이고 내부 참조·조회 용어가 없을 때만 그 글이다.
     * 이전 Worker 결과(headline 없음)·Worker가 비운 제목·오탈자는 null이며 화면이 대상·확인 항목으로 제목을 만든다.
     */
    public static String headline(JsonNode payload) {
        if (payload == null || !payload.path("headline").isTextual()) return null;
        String text = payload.path("headline").asText().strip();
        return text.isEmpty() || ValidationBasisQuotes.codePoints(text) > ValidationDecisionValidator.MAX_HEADLINE_CODE_POINTS || INTERNAL.matcher(text).find() ? null : text;
    }

    /** live는 #381 live report finding의 저장 대상 현재 행({@link ValidationFindingTargets#storedSubjects}). legacy report는 빈 map이다. */
    public View describe(ValidationReport report, ValidationFinding finding, List<ValidationEvidence> stored,
            Map<ValidationLiveSettings.Key, ValidationLiveSettings.Row> live) {
        var payload = finding.getPayload();
        // 무효화로 고정 출처가 지워진 report도 상세는 읽을 수 있어야 한다. 이때 이전 회차 표기는 '비교 자료'로 남는다.
        Map<String, ? extends JsonNode> sources = contexts.fixedSources(report);
        Integer target = report.getSourceEpisodeNo();
        var titles = settingBookTitles(sources);
        var bundle = payload == null ? null : payload.path("evidence_bundle");
        // 판정이 쓴 E 조각: evidence_refs·근거 문장·불일치 참조. 묶음의 E 행으로 실제 출처 위치를 찾는다.
        Map<String, JsonNode> bundleRows = new HashMap<>();
        if (bundle != null) for (var row : bundle.path("evidence")) if (row.path("ref").isTextual()) bundleRows.put(row.path("ref").asText(), row);
        Set<String> usedRefs = new HashSet<>();
        if (payload != null) {
            payload.path("evidence_refs").forEach(ref -> usedRefs.add(ref.asText()));
            payload.path("basis_quotes").forEach(q -> usedRefs.add(q.path("ref").asText()));
            payload.path("disagreements").forEach(d -> usedRefs.add(d.path("evidence_ref").asText()));
        }
        Set<String> usedKeys = new HashSet<>(), bundleKeys = new HashSet<>();
        for (var row : bundleRows.values()) bundleKeys.add(key(row.path("source_ref").asText(), row.path("start").asInt(), row.path("end").asInt()));
        for (String ref : usedRefs) {
            var row = bundleRows.get(ref);
            if (row != null && !"T1".equals(row.path("source_ref").asText()))
                usedKeys.add(key(row.path("source_ref").asText(), row.path("start").asInt(), row.path("end").asInt()));
        }
        var quoted = basisQuotes(report, finding, payload, sources, titles, live);
        var quotes = quoted.stream().map(Quoted::quote).toList();
        Set<String> quoteKeys = new HashSet<>();
        for (var q : quotes) if (q.sourceRef() != null) quoteKeys.add(key(q.sourceRef(), q.start(), q.end()));
        var evidence = new ArrayList<Evidence>();
        for (var e : stored) {
            String key = key(e.getSourceRef(), e.getStart(), e.getEnd());
            // 근거 문장 자체의 인용은 basisQuotes로 보여 주고 조각 목록에는 넣지 않는다(같은 위치의 검색 조각이면 남긴다).
            if (quoteKeys.contains(key) && !bundleKeys.contains(key)) continue;
            var source = sources.get(e.getSourceRef());
            var kind = kind(source);
            Integer episodeNo = "T1".equals(e.getSourceRef()) ? target : kind == ValidationQuoteSourceKind.EPISODE && source.path("episodeNo").isIntegralNumber() ? source.path("episodeNo").asInt() : null;
            String position = (e.getStart() + 1) + "~" + e.getEnd() + "자";
            String label = kind == ValidationQuoteSourceKind.SETTING_BOOK ? join("설정집", titles.get(e.getSourceRef()), position)
                    : episodeNo != null ? episodeNo + "화 · " + position : "비교 자료 · " + position;
            var highlights = new ArrayList<Highlight>();
            for (var q : quotes)
                if (e.getSourceRef().equals(q.sourceRef()) && q.start() >= e.getStart() && q.end() <= e.getEnd() && !(q.start() == e.getStart() && q.end() == e.getEnd()))
                    highlights.add(new Highlight(q.start() - e.getStart(), q.end() - e.getStart()));
            evidence.add(new Evidence(e.getSourceRef(), e.getStart(), e.getEnd(), e.getQuote(), kind == null ? ValidationQuoteSourceKind.EPISODE : kind, episodeNo, label,
                    !"T1".equals(e.getSourceRef()) && usedKeys.contains(key), List.copyOf(highlights)));
        }
        return new View(topic(payload), finding.getBranch() == ValidationBranch.TYPO ? null : headline(payload), target, quotes, List.copyOf(evidence),
                blocks(report, finding, sources, target, quoted));
    }

    /**
     * 비교 영역 블록. 이번 원고 위치(원고 위치 순, 오탈자는 span 하나) → 저장 설정값(근거 문장 순서) → 이전 원문 근거 문장
     * (회차 오름차순·같은 회차는 위치 순, 그다음 설정집·같은 설정집은 위치 순). 원문 블록은 인용 위아래 {@value ValidationSourceWindow#INITIAL_LINES}줄을 함께 준다.
     * 병합(2026-11-03): 같은 출처의 원문 인용끼리 처음 창이 겹치거나 맞닿으면({@link ValidationFindingContexts.Reader#grouped}) 블록 하나로 합치고
     * label 위치는 묶음 전체 구간, quotes는 묶음의 인용 전부다. 원문을 읽지 못하면 합치지 않는다. 설정값 블록은 같은 고정 기준 항목·같은 label의
     * 같은 값 전체(settingValue)를 가리킬 때만 합친다.
     */
    private List<Block> blocks(ValidationReport report, ValidationFinding finding, Map<String, ? extends JsonNode> sources, Integer target, List<Quoted> quoted) {
        var payload = finding.getPayload();
        if (payload == null) return List.of();
        var reader = contexts.reader(report, sources);
        var result = new ArrayList<Block>();
        var spans = new ArrayList<JsonNode>();
        if (finding.getBranch() == ValidationBranch.TYPO) spans.add(payload.path("span"));
        else payload.path("target_spans").forEach(spans::add);
        Map<String, List<ValidationFindingContexts.Range>> targetRanges = new LinkedHashMap<>();
        for (var span : spans) {
            if (!span.path("start").isIntegralNumber() || !span.path("end").isIntegralNumber() || !span.path("quote").isTextual()) continue;
            targetRanges.computeIfAbsent(span.path("source_ref").asText("T1"), key -> new ArrayList<>())
                    .add(new ValidationFindingContexts.Range(span.path("quote").asText(), span.path("start").asInt(), span.path("end").asInt()));
        }
        String episode = target == null ? "이번 원고" : target + "화";
        targetRanges.forEach((ref, ranges) -> {
            for (var group : reader.grouped(ref, ranges, ValidationSourceWindow.INITIAL_LINES, ValidationSourceWindow.INITIAL_LINES))
                result.add(textBlock(ValidationFindingBlockKind.TARGET, episode + " · " + position(group.start(), group.end()), ref, group, target));
        });
        settingBlocks(quoted, result);
        // 이전 원문 근거는 회차 오름차순(같은 회차는 원문 위치 순), 설정집은 그 뒤에 근거 문장 순서로 둔다. 정렬은 안정적이다.
        var ordered = quoted.stream().map(Quoted::quote)
                .filter(q -> q.kind() != ValidationQuoteSourceKind.SETTING && q.sourceRef() != null && q.start() != null && q.end() != null)
                .sorted(Comparator.comparing((BasisQuote q) -> q.kind() == ValidationQuoteSourceKind.EPISODE ? 0 : 1)
                        .thenComparing(q -> q.kind() == ValidationQuoteSourceKind.EPISODE && q.episodeNo() != null ? q.episodeNo() : Integer.MAX_VALUE)
                        .thenComparing(q -> q.kind() == ValidationQuoteSourceKind.EPISODE ? q.start() : 0))
                .toList();
        Map<String, List<BasisQuote>> bySource = new LinkedHashMap<>();
        for (var q : ordered) bySource.computeIfAbsent(q.sourceRef(), key -> new ArrayList<>()).add(q);
        bySource.forEach((ref, list) -> {
            var first = list.getFirst();
            var kind = first.kind() == ValidationQuoteSourceKind.EPISODE ? ValidationFindingBlockKind.EPISODE : ValidationFindingBlockKind.SETTING_BOOK;
            var ranges = list.stream().map(q -> new ValidationFindingContexts.Range(q.quote(), q.start(), q.end())).toList();
            for (var group : reader.grouped(ref, ranges, ValidationSourceWindow.INITIAL_LINES, ValidationSourceWindow.INITIAL_LINES)) {
                String label = first.kind() == ValidationQuoteSourceKind.EPISODE
                        ? (first.episodeNo() != null ? first.episodeNo() + "화" : "비교 자료") + " · " + position(group.start(), group.end())
                        : join("설정집", first.settingBookTitle(), position(group.start(), group.end()));
                result.add(textBlock(kind, label, ref, group, first.episodeNo()));
            }
        });
        return List.copyOf(result);
    }

    /** 설정값 블록. 같은 고정 기준 항목·label·값 전체를 가리키는 근거 문장은 한 블록에 모으고 값 안의 위치 순으로 강조한다. */
    private static void settingBlocks(List<Quoted> quoted, List<Block> result) {
        record Key(String entryRef, String label, String value) {}
        Map<Key, List<String>> merged = new LinkedHashMap<>();
        var order = new ArrayList<Object>();
        for (var q : quoted) {
            if (q.quote().kind() != ValidationQuoteSourceKind.SETTING) continue;
            if (q.settingValue() == null || q.entryRef() == null) { order.add(q); continue; }
            var key = new Key(q.entryRef(), q.quote().label(), q.settingValue());
            if (!merged.containsKey(key)) order.add(key);
            merged.computeIfAbsent(key, k -> new ArrayList<>()).add(q.quote().quote());
        }
        for (var item : order) {
            if (item instanceof Quoted q) {
                result.add(new Block(ValidationFindingBlockKind.SETTING, q.quote().label(), q.quote().quote(), List.of(new BlockQuote(q.quote().quote(), null, null)),
                        null, null, null, null, q.settingValue(), null, null));
                continue;
            }
            var key = (Key) item;
            var texts = merged.get(key).stream().distinct().sorted(Comparator.comparingInt((String text) -> key.value().indexOf(text))).toList();
            result.add(new Block(ValidationFindingBlockKind.SETTING, key.label(), texts.getFirst(), texts.stream().map(text -> new BlockQuote(text, null, null)).toList(),
                    null, null, null, null, key.value(), null, null));
        }
    }

    private static Block textBlock(ValidationFindingBlockKind kind, String label, String ref, ValidationFindingContexts.Group group, Integer episodeNo) {
        var quotes = group.ranges().stream().map(r -> new BlockQuote(r.quote(), r.start(), r.end())).toList();
        return new Block(kind, label, quotes.getFirst().quote(), quotes, ref, group.start(), group.end(), episodeNo, null, group.context().context(), group.context().status());
    }

    private static String position(int start, int end) {return (start + 1) + "~" + end + "자";}

    private List<Quoted> basisQuotes(ValidationReport report, ValidationFinding finding, JsonNode payload, Map<String, ? extends JsonNode> sources, Map<String, String> titles,
            Map<ValidationLiveSettings.Key, ValidationLiveSettings.Row> liveRows) {
        if (payload == null || !payload.path("basis_quotes").isArray() || payload.path("basis_quotes").isEmpty()) return List.of();
        boolean legacy = report.getBaselineSeed() != null;
        Map<String, JsonNode> settings = null;
        List<CharacterSettingSchema> active = null;
        var live = legacy ? null : liveQuotes(report, finding, payload, liveRows);
        var result = new ArrayList<Quoted>();
        int settingIndex = 0;
        for (var item : payload.path("basis_quotes")) {
            // B 인용 순서(quoted_subjects와 같은 규칙: 위치 없는 B 참조)는 빈 인용을 건너뛰기 전에 센다.
            int position = !item.path("start").isIntegralNumber() && ValidationBasisQuotes.isSettingQuote(item) ? settingIndex++ : -1;
            String quote = item.path("quote").asText(null);
            if (quote == null || quote.isBlank()) continue;
            if (item.path("start").isIntegralNumber()) {
                String ref = item.path("source_ref").asText();
                var source = sources.get(ref);
                var kind = kind(source);
                if (kind == null) continue;
                Integer episodeNo = kind == ValidationQuoteSourceKind.EPISODE && source.path("episodeNo").isIntegralNumber() ? source.path("episodeNo").asInt() : null;
                String title = kind == ValidationQuoteSourceKind.SETTING_BOOK ? titles.get(ref) : null;
                String label = kind == ValidationQuoteSourceKind.SETTING_BOOK ? join("설정집", title) : episodeNo != null ? episodeNo + "화" : "비교 자료";
                result.add(new Quoted(new BasisQuote(kind, label, quote, ref, item.path("start").asInt(), item.path("end").asInt(), episodeNo, null, null, title), null, null));
                continue;
            }
            if (!ValidationBasisQuotes.isSettingQuote(item)) continue;
            String entryRef = item.path("ref").asText();
            if (!legacy) {
                var labelled = live.get(position);
                result.add(new Quoted(new BasisQuote(ValidationQuoteSourceKind.SETTING, join("설정", labelled.subjectName(), labelled.settingName()), quote, null, null, null, null,
                        labelled.subjectName(), labelled.settingName(), null), labelled.settingValue(), entryRef));
                continue;
            }
            if (report.getBaselineEntries() == null || !report.getBaselineEntries().isArray()) continue;
            if (settings == null) settings = dataMapper.toValidationSettings(report.getBaselineEntries(), sources);
            var row = settings.get(entryRef);
            if (row == null) continue;
            String subject = row.path("name").isTextual() ? row.path("name").asText() : null;
            if ("characters".equals(row.path("domain").asText()) && active == null) active = schemas.findAllActiveForWork(report.getWork().getId());
            var named = leafNames(row, quote, slotKey -> legacySlot(report, entryRef, slotKey), active);
            result.add(new Quoted(new BasisQuote(ValidationQuoteSourceKind.SETTING, join("설정", subject, named.settingName()), quote, null, null, null, null, subject, named.settingName(), null),
                    named.settingValue(), entryRef));
        }
        return List.copyOf(result);
    }

    /** B 인용 하나의 대상·설정명과 값 전체(하나의 값에만 들어 있고 상한 이하일 때). */
    private record Named(String subjectName, String settingName, String settingValue) {}

    /** 모델 설정 행의 값 leaf 중 인용을 담은 것의 표시명(모두 같을 때만)과 값 전체(leaf가 하나이고 상한 이하일 때만). */
    private Named leafNames(JsonNode row, String quote, java.util.function.Function<String, JsonNode> slots, List<CharacterSettingSchema> active) {
        Set<String> names = new LinkedHashSet<>();
        var leaves = ValidationBasisQuotes.containing(row, quote);
        for (var leaf : leaves) {
            String name;
            if ("characters".equals(row.path("domain").asText())) name = characterSettingName(slots.apply(leaf.path().getFirst()), leaf.path().getFirst(), active);
            else name = leaf.path().isEmpty() ? null : leaf.path().size() == 1 ? leaf.path().getFirst() : leaf.path().get(0) + " / " + leaf.path().get(1);
            names.add(name == null ? "" : name);
        }
        String settingName = names.size() == 1 && !names.iterator().next().isEmpty() ? names.iterator().next() : null;
        String value = leaves.size() == 1 && ValidationBasisQuotes.codePoints(leaves.getFirst().value()) <= MAX_SETTING_VALUE_CODE_POINTS ? leaves.getFirst().value() : null;
        return new Named(row.path("name").isTextual() ? row.path("name").asText() : null, settingName, value);
    }

    private static JsonNode legacySlot(ValidationReport report, String entryRef, String slotKey) {
        int index = Integer.parseInt(entryRef.substring(1)) - 1;
        var entries = report.getBaselineEntries();
        return entries != null && index < entries.size() ? entries.get(index).path("state").path("slots").path(slotKey) : null;
    }

    /**
     * #381 live report finding의 B 인용별 표기(basis_quotes의 B 인용 순서). 판정 때의 B 번호로 현재 행을 찾지 않는다.
     * <ol>
     *   <li>대상·설정명: quoted_subjects의 같은 순서 항목을 그대로 쓴다. 없으면(quoted_subjects 없는 finding) 첫 B 인용과 같은 참조의 인용만 V102 대표 대상
     *       (대상명·설정명)을 쓰고, 그 밖의 인용은 '설정'과 인용만 보인다.</li>
     *   <li>값 전체(settingValue)와 빈 이름 보충: report가 유효(무효화·퇴역 전)하고 회차 전체 확정(APPLIED) 전일 때만 그 대상의 현재 행을 id로 읽어
     *       인용을 담은 값 leaf로 정한다(상세 label과 같은 규칙). 그 밖에는 값이 null이다.</li>
     * </ol>
     */
    private List<Named> liveQuotes(ValidationReport report, ValidationFinding finding, JsonNode payload, Map<ValidationLiveSettings.Key, ValidationLiveSettings.Row> liveRows) {
        record Plan(String subjectName, String settingName, boolean fromLabel, ValidationLiveSettings.Key key, String quote) {}
        var quoted = finding.getQuotedSubjects() != null && finding.getQuotedSubjects().isArray() ? finding.getQuotedSubjects() : null;
        String primaryRef = null;
        var plans = new ArrayList<Plan>();
        for (var item : payload.path("basis_quotes")) {
            if (item.path("start").isIntegralNumber() || !ValidationBasisQuotes.isSettingQuote(item)) continue;
            String ref = item.path("ref").asText(), quote = item.path("quote").asText("");
            if (primaryRef == null) primaryRef = ref;
            var label = quoted != null && plans.size() < quoted.size() ? quoted.get(plans.size()) : null;
            if (label != null) plans.add(new Plan(text(label, "subjectName"), text(label, "settingName"), true, key(text(label, "subjectType"), text(label, "subjectId")), quote));
            else if (quoted == null && ref.equals(primaryRef) && finding.getSubjectType() != null)
                plans.add(new Plan(finding.getSubjectName(), finding.getSettingName(), false, key(finding.getSubjectType().name(), finding.getSubjectId() == null ? null : finding.getSubjectId().toString()), quote));
            else plans.add(new Plan(null, null, true, null, quote));
        }
        Map<ValidationLiveSettings.Key, ValidationLiveSettings.Row> rows = Map.of();
        var keys = plans.stream().map(Plan::key).filter(Objects::nonNull).distinct().toList();
        if (!keys.isEmpty() && report.getStatus() != ValidationReportStatus.INVALIDATED && report.getBaselineHash() != null
                && !applications.existsByReportIdAndStatus(report.getId(), ValidationApplicationStatus.APPLIED))
            rows = liveRows;
        List<CharacterSettingSchema> active = null;
        var result = new ArrayList<Named>();
        for (var plan : plans) {
            var row = plan.key() == null ? null : rows.get(plan.key());
            if (row == null) { result.add(new Named(plan.subjectName(), plan.settingName(), null)); continue; }
            var model = dataMapper.toValidationSettings(JsonNodeFactory.instance.arrayNode().add(row.row()), Map.of()).values().iterator().next();
            if (ValidationLiveSettings.CHARACTERS.equals(plan.key().domain()) && active == null) active = schemas.findAllActiveForWork(report.getWork().getId());
            var named = leafNames(model, plan.quote(), slotKey -> row.row().path("state").path("slots").path(slotKey), active);
            // 저장 이름표가 있으면 그 이름이 판정 때 표기다. V102만 있을 때 비어 있는 설정명·대상명만 현재 행으로 채운다.
            result.add(new Named(plan.subjectName() != null || plan.fromLabel() ? plan.subjectName() : named.subjectName(),
                    plan.settingName() != null || plan.fromLabel() ? plan.settingName() : named.settingName(), named.settingValue()));
        }
        return result;
    }
    private static ValidationLiveSettings.Key key(String type, String id) {
        if (type == null || id == null) return null;
        UUID uuid;
        try { uuid = UUID.fromString(id); } catch (IllegalArgumentException malformed) { return null; }
        return switch (type) {
            case "CHARACTER" -> new ValidationLiveSettings.Key(ValidationLiveSettings.CHARACTERS, uuid);
            case "WORLD_SETTING" -> new ValidationLiveSettings.Key(ValidationLiveSettings.WORLD_SETTINGS, uuid);
            default -> null;
        };
    }
    private static String text(JsonNode node, String field) {return node.path(field).isTextual() && !node.path(field).asText().isBlank() ? node.path(field).asText() : null;}

    /** 캐릭터 slot의 표시명. 기존 schema 표시명 규칙(ValidationFindingTargets와 같다)을 쓰고 해석할 수 없으면 factKey다. slot은 기준 항목 행의 그 slot이다. */
    private String characterSettingName(JsonNode slot, String slotKey, List<CharacterSettingSchema> active) {
        int split = slotKey.indexOf(':');
        String typeName = slot != null && slot.path("factType").isTextual() ? slot.path("factType").asText() : split < 0 ? null : slotKey.substring(0, split);
        String factKey = slot != null && slot.path("factKey").isTextual() ? slot.path("factKey").asText() : split < 0 ? slotKey : slotKey.substring(split + 1);
        CharacterFactType type = null;
        for (var value : CharacterFactType.values()) if (value.name().equals(typeName)) type = value;
        if (type == null) return factKey;
        try {
            String display = displayNames.resolve(type, factKey, slot == null ? null : slot.get("valueJson"), active);
            return display == null || display.isBlank() ? factKey : display;
        } catch (RuntimeException unreadable) {
            return factKey;
        }
    }

    private Map<String, String> settingBookTitles(Map<String, ? extends JsonNode> sources) {
        Map<String, UUID> ids = new HashMap<>();
        for (var entry : sources.entrySet())
            if ("SETTING_BOOK".equals(entry.getValue().path("sourceKind").asText())) {
                try { ids.put(entry.getKey(), UUID.fromString(entry.getValue().path("settingBookId").asText())); }
                catch (IllegalArgumentException malformed) { /* 이름 없이 '설정집'으로 표시한다. */ }
            }
        if (ids.isEmpty()) return Map.of();
        Map<UUID, String> names = new HashMap<>();
        for (UploadFile file : uploadFiles.findAllByIdIn(new HashSet<>(ids.values())))
            if (file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank())
                names.put(file.getId(), FILE_EXTENSION.matcher(file.getOriginalFilename().strip()).replaceFirst(""));
        Map<String, String> result = new HashMap<>();
        ids.forEach((ref, id) -> { if (names.containsKey(id)) result.put(ref, names.get(id)); });
        return result;
    }

    private static ValidationQuoteSourceKind kind(JsonNode source) {
        if (source == null) return null;
        return switch (source.path("sourceKind").asText()) {
            case "EPISODE" -> ValidationQuoteSourceKind.EPISODE;
            case "SETTING_BOOK" -> ValidationQuoteSourceKind.SETTING_BOOK;
            default -> null;
        };
    }

    private static String key(String ref, int start, int end) {return ref + ":" + start + ":" + end;}

    private static String join(String... parts) {
        var out = new StringJoiner(" · ");
        for (String part : parts) if (part != null && !part.isBlank()) out.add(part);
        return out.toString();
    }
}
