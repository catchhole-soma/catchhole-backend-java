package org.monitoring.catchholebackend.domain.validation.processor;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.monitoring.catchholebackend.domain.validation.type.ValidationBranch;
import org.monitoring.catchholebackend.domain.validation.type.ValidationFindingBlockKind;
import org.monitoring.catchholebackend.domain.validation.type.ValidationQuoteSourceKind;
import org.springframework.stereotype.Component;

/**
 * 오류 리포트 가독성(2026-11-02): 상세 화면이 내부 참조(T1·E·B·R) 없이 읽을 수 있는 작가용 표기를 서버에서 만든다.
 * report의 고정 출처(baselineSeed)·고정 기준(baselineEntries)과 저장된 판정 payload(evidence_bundle 포함)만 읽고 현재 설정값을 읽지 않는다.
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

    public View describe(ValidationReport report, ValidationFinding finding, List<ValidationEvidence> stored) {
        var payload = finding.getPayload();
        // 무효화로 고정 출처가 지워진 report도 상세는 읽을 수 있어야 한다. 이때 이전 회차 표기는 '비교 자료'로 남는다.
        Map<String, ? extends JsonNode> sources = report.getBaselineSeed() == null ? Map.of() : dataMapper.toSourceReferences(report);
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
        var quoted = basisQuotes(report, payload, sources, titles);
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

    private List<Quoted> basisQuotes(ValidationReport report, JsonNode payload, Map<String, ? extends JsonNode> sources, Map<String, String> titles) {
        if (payload == null || !payload.path("basis_quotes").isArray() || payload.path("basis_quotes").isEmpty()) return List.of();
        Map<String, JsonNode> settings = null;
        List<CharacterSettingSchema> active = null;
        var result = new ArrayList<Quoted>();
        for (var item : payload.path("basis_quotes")) {
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
            if (report.getBaselineEntries() == null || !report.getBaselineEntries().isArray()) continue;
            if (settings == null) settings = dataMapper.toValidationSettings(report.getBaselineEntries(), sources);
            String entryRef = item.path("ref").asText();
            var row = settings.get(entryRef);
            if (row == null) continue;
            String subject = row.path("name").isTextual() ? row.path("name").asText() : null;
            Set<String> names = new LinkedHashSet<>();
            var leaves = ValidationBasisQuotes.containing(row, quote);
            for (var leaf : leaves) {
                String name;
                if ("characters".equals(row.path("domain").asText())) {
                    if (active == null) active = schemas.findAllActiveForWork(report.getWork().getId());
                    name = characterSettingName(report, entryRef, leaf.path().getFirst(), active);
                } else name = leaf.path().isEmpty() ? null : leaf.path().size() == 1 ? leaf.path().getFirst() : leaf.path().get(0) + " / " + leaf.path().get(1);
                names.add(name == null ? "" : name);
            }
            String settingName = names.size() == 1 && !names.iterator().next().isEmpty() ? names.iterator().next() : null;
            String value = leaves.size() == 1 && ValidationBasisQuotes.codePoints(leaves.getFirst().value()) <= MAX_SETTING_VALUE_CODE_POINTS ? leaves.getFirst().value() : null;
            result.add(new Quoted(new BasisQuote(ValidationQuoteSourceKind.SETTING, join("설정", subject, settingName), quote, null, null, null, null, subject, settingName, null), value, entryRef));
        }
        return List.copyOf(result);
    }

    /** 캐릭터 slot의 표시명. 기존 schema 표시명 규칙(ValidationFindingTargets와 같다)을 쓰고 해석할 수 없으면 factKey다. */
    private String characterSettingName(ValidationReport report, String entryRef, String slotKey, List<CharacterSettingSchema> active) {
        int index = Integer.parseInt(entryRef.substring(1)) - 1;
        var entries = report.getBaselineEntries();
        var slot = entries != null && index < entries.size() ? entries.get(index).path("state").path("slots").path(slotKey) : null;
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
