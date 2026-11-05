package org.monitoring.catchholebackend.domain.validation.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSettingDisplayNameResolver;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationFinding;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper;
import org.monitoring.catchholebackend.domain.validation.type.ValidationImpact;
import org.monitoring.catchholebackend.domain.validation.type.ValidationSubjectType;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingPropertyView;
import org.springframework.stereotype.Component;

/**
 * 설정 finding의 대상·설정명과 정정 연결 기준 항목(#314 B2). 기준 항목 행({@code {domain, subjectRef, state}})과 그 revision의 질문 상태만 읽고
 * 캐릭터·세계관을 따로 조회하지 않는다. 판정 저장 때({@link #describe}·{@link #quotedSubjects})의 행은 게시 검증 문맥의 기준 항목이다:
 * legacy report는 report 고정 기준(baselineEntries), #381 live report는 잠금 아래에서 읽은 현재 확정 설정(게시가 가리키는 주체만 전체 상태,
 * 나머지는 색인 행)이다. 캐릭터 설정 표시명만 기존 schema 표시명 규칙(CharacterSettingDisplayNameResolver)을 쓴다.
 *
 * <ul>
 *   <li>관련 기준 항목: 질문 subject_refs의 모든 항목과 BASELINE 조회 의무의 evidence_refs. grid v4(#349 개편)의 판정은 현재값 domain(characters·worldSettings)만
 *       evidence_bundle.settings로 받고 BASELINE 의무는 언제나 비어 있다. 이 계산은 worldHistory 항목도 후보로 두지만 아래 대표 대상·{@link #basis}는
 *       정정 가능한 domain을 먼저 고르므로 결과가 같다.</li>
 *   <li>대표 대상(2026-11-03 판정 작성 제목): 판정 근거 문장(basis_quotes) 중 저장 설정값 인용(B 참조)이 있으면 그 첫 인용이 가리키는 고정 기준 항목
 *       (정정 가능한 domain일 때). 판정이 실제로 어긋난다고 옮긴 값의 대상이므로 서술자나 장면에 이름만 나온 대상보다 우선한다. 없으면(근거 문장 없음·
 *       원문 인용만·이전 Worker) 이전 규칙: subject_refs의 첫 참조(없으면 BASELINE 조회 항목)의 항목 중 정정 가능한 domain(characters·worldSettings)을 먼저,
 *       그다음 기준 순서. 저장된 finding의 대상은 판정 저장 때 한 번 기록한 값이며 이 규칙이 바뀌어도 다시 계산하지 않는다.</li>
 *   <li>설정: 질문의 property_or_rule이 대표 대상의 고정 slot/path 하나와 정확히(NFC·소문자·공백 무시) 맞을 때만. 캐릭터는 slot key·factKey·표시명,
 *       세계관은 설정명·"범위/설정명"과 비교한다. 0개 또는 2개 이상이면 추정하지 않고 null이다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ValidationFindingTargets {
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> CORRECTABLE = Set.of("characters", "worldSettings");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CharacterSettingSchemaRepository schemas;
    private final CharacterSettingDisplayNameResolver displayNames;
    private final AnalysisStateJournal journal;
    private final ValidationDataMapper mapper;

    /** 저장 시점의 대상. 질문 대상이 고정 기준 항목으로 이어지지 않으면 모두 null이다. */
    public record Target(ValidationSubjectType subjectType, UUID subjectId, String subjectName, String settingKey, String settingName) {
        public static final Target NONE = new Target(null, null, null, null, null);
    }
    /** 정정 요청에 그대로 쓰는 고정 기준 항목. slot/path는 finding의 settingKey가 이 항목을 가리킬 때만 채운다. */
    public record Basis(String entryRef, String entryHash, ValidationSubjectType subjectType, UUID targetId, String subjectName,
            CharacterFactType factType, String factKey, String scopeName, String settingName, Long currentVersion) {}
    private record Entry(int index, String ref, String subjectRef, JsonNode row) {
        String domain() { return row.path("domain").asText(); }
        JsonNode state() { return row.path("state"); }
        boolean correctable() { return CORRECTABLE.contains(domain()); }
    }
    private record Setting(String key, String name) {}

    /** 설정 판정 payload의 영향 등급. 세 값 밖은 null(검증기가 이미 거절한다). */
    public static ValidationImpact impact(JsonNode payload) {
        String value = payload == null ? null : text(payload, "impact");
        if (value == null) return null;
        for (var impact : ValidationImpact.values()) if (impact.name().equals(value)) return impact;
        return null;
    }

    /**
     * 판정 저장 시점에 finding에 기록할 대상·설정. baselineEntries는 게시 검증 문맥의 기준 항목 행, payload는 저장할 판정(basis_quotes로 대표 대상을 정한다).
     * 대표 대상은 B 인용·질문 subject_refs·BASELINE 조회 항목에서만 고르므로 live 문맥에서는 언제나 전체 상태로 읽은 주체다.
     */
    public Target describe(JsonNode baselineEntries, UUID workId, JsonNode checkState, JsonNode payload) {
        var entries = entries(baselineEntries);
        var primary = checkState == null ? Optional.<Entry>empty() : primary(entries, checkState, payload);
        if (primary.isEmpty()) return Target.NONE;
        var entry = primary.get();
        String property = text(checkState.path("draft"), "property_or_rule");
        Setting setting = property == null ? null
                : "characters".equals(entry.domain()) ? characterSetting(entry, property, workId)
                : "worldSettings".equals(entry.domain()) ? worldSetting(entry, property) : null;
        return new Target(type(entry), targetId(entry), name(entry), setting == null ? null : setting.key(), setting == null ? null : setting.name());
    }

    /**
     * #381 판정 근거 문장 중 저장 설정값 인용(B 인용)마다의 이름표. basis_quotes의 B 인용 순서대로 하나씩(검증기가 근거 문장을 최대 3개로 막는다)
     * {@code {ref, subjectType, subjectId, subjectName, settingKey, settingName}}을 만들고, B 인용이 없으면 빈 배열이다. 이름·key만 담고 설정값은 담지 않는다.
     * <ul>
     *   <li>설정은 인용을 그대로 담은 값 leaf({@link ValidationBasisQuotes#containing}, 검증기·상세 표기와 같은 모델 행)에서 정한다.</li>
     *   <li>settingKey: 모든 leaf가 한 key로 모이면 그 key(캐릭터 slot key "AGE:age", 세계관 저장 경로 키 ["범위","설정명"]·[null,"설정명"]), 아니면 null.</li>
     *   <li>settingName: 상세 label({@code ValidationFindingLabels})과 같은 규칙으로 모든 leaf의 표시명이 같을 때만 그 이름(캐릭터 schema 표시명,
     *       세계관 "범위 / 설정명"·"설정명"), 아니면 null.</li>
     *   <li>인용이 가리키는 정정 가능 항목을 찾지 못하면 ref만 두고 나머지는 null이다(검증을 통과한 판정에서는 생기지 않는다).</li>
     * </ul>
     */
    public ArrayNode quotedSubjects(JsonNode baselineEntries, UUID workId, JsonNode payload) {
        var result = JsonNodeFactory.instance.arrayNode();
        if (payload == null || !payload.path("basis_quotes").isArray()) return result;
        var entries = entries(baselineEntries);
        Map<String, JsonNode> models = null;
        List<CharacterSettingSchema> active = null;
        for (var quote : payload.path("basis_quotes")) {
            if (quote.path("start").isIntegralNumber() || !ValidationBasisQuotes.isSettingQuote(quote)) continue;
            String ref = quote.path("ref").asText();
            ObjectNode item = result.addObject().put("ref", ref);
            var entry = entries.stream().filter(e -> e.ref().equals(ref)).findFirst().orElse(null);
            if (entry == null || !entry.correctable()) {
                item.putNull("subjectType").putNull("subjectId").putNull("subjectName").putNull("settingKey").putNull("settingName");
                continue;
            }
            // 출처 목록은 값 leaf(slots.*.factValue·propertiesJson 문자열)에 source_ref를 더하지 않으므로 비워 둔다.
            if (models == null) models = mapper.toValidationSettings(baselineEntries, Map.of());
            var leaves = quote.path("quote").isTextual() ? ValidationBasisQuotes.containing(models.get(ref), quote.path("quote").asText()) : List.<ValidationBasisQuotes.Leaf>of();
            Set<String> keys = new LinkedHashSet<>(), names = new LinkedHashSet<>();
            for (var leaf : leaves) {
                Setting setting;
                if ("characters".equals(entry.domain())) {
                    if (active == null) active = schemas.findAllActiveForWork(workId);
                    setting = new Setting(leaf.path().getFirst(), slotDisplayName(entry, leaf.path().getFirst(), active));
                } else setting = worldLeaf(leaf.path());
                keys.add(setting.key() == null ? "" : setting.key());
                names.add(setting.name() == null ? "" : setting.name());
            }
            UUID target = targetId(entry);
            item.put("subjectType", type(entry).name()).put("subjectId", target == null ? null : target.toString()).put("subjectName", name(entry))
                    .put("settingKey", single(keys)).put("settingName", single(names));
        }
        return result;
    }

    /** 상세 조회 시점의 정정 연결 항목. 실제 대상에 연결된 정정 가능 항목만 기준 순서로 반환한다. */
    public List<Basis> basis(ValidationReport report, JsonNode checkState, ValidationFinding finding) {
        var entries = entries(report.getBaselineEntries());
        if (entries.isEmpty() || checkState == null) return List.of();
        var primary = primary(entries, checkState, finding.getPayload()).orElse(null);
        var result = new ArrayList<Basis>();
        for (var entry : related(entries, checkState)) {
            UUID target = targetId(entry);
            if (!entry.correctable() || target == null) continue;
            CharacterFactType factType = null; String factKey = null, scopeName = null, settingName = null;
            if (entry == primary && finding.getSettingKey() != null && target.equals(finding.getSubjectId())) {
                if ("characters".equals(entry.domain())) {
                    int split = finding.getSettingKey().indexOf(':');
                    factType = split < 0 ? null : factType(finding.getSettingKey().substring(0, split));
                    factKey = factType == null ? null : finding.getSettingKey().substring(split + 1);
                } else {
                    var path = path(finding.getSettingKey());
                    if (path != null) { scopeName = path[0]; settingName = path[1]; }
                }
            }
            JsonNode version = "characters".equals(entry.domain()) ? entry.state().path("snapshotVersion") : entry.state().path("version");
            result.add(new Basis(entry.ref(), journal.hash(entry.row()), type(entry), target, name(entry), factType, factKey, scopeName, settingName,
                    version.isIntegralNumber() ? version.asLong() : null));
        }
        return List.copyOf(result);
    }

    private static List<Entry> entries(JsonNode baselineEntries) {
        if (baselineEntries == null || !baselineEntries.isArray()) return List.of();
        var subjects = ValidationDataMapper.toSubjectRefs(baselineEntries);
        var result = new ArrayList<Entry>(); int index = 0;
        for (var row : baselineEntries) { String ref = "B" + (++index); result.add(new Entry(index, ref, subjects.get(ref), row)); }
        return result;
    }
    private static Set<String> baselineRefs(JsonNode state) {
        var refs = new LinkedHashSet<String>();
        for (var intent : state.path("lookup_obligations"))
            if ("BASELINE".equals(intent.path("operation").asText())) for (var ref : intent.path("evidence_refs")) if (ref.isTextual()) refs.add(ref.asText());
        return refs;
    }
    private static List<Entry> related(List<Entry> entries, JsonNode state) {
        var subjects = new HashSet<String>(); for (var subject : state.path("draft").path("subject_refs")) if (subject.isTextual()) subjects.add(subject.asText());
        var refs = baselineRefs(state);
        return entries.stream().filter(e -> subjects.contains(e.subjectRef()) || refs.contains(e.ref())).toList();
    }
    private static Optional<Entry> primary(List<Entry> entries, JsonNode state, JsonNode payload) {
        var quoted = quotedEntry(entries, payload);
        if (quoted.isPresent()) return quoted;
        for (var subject : state.path("draft").path("subject_refs")) {
            if (!subject.isTextual()) continue;
            var found = preferred(entries.stream().filter(e -> subject.asText().equals(e.subjectRef())).toList());
            if (found.isPresent()) return found;
        }
        var refs = baselineRefs(state);
        return preferred(entries.stream().filter(e -> refs.contains(e.ref())).toList());
    }
    /** 판정 근거 문장 중 고정 기준에 있고 정정 가능한 항목을 가리키는 첫 저장 설정값 인용(B 참조)의 항목. */
    private static Optional<Entry> quotedEntry(List<Entry> entries, JsonNode payload) {
        if (payload == null || !payload.path("basis_quotes").isArray()) return Optional.empty();
        for (var quote : payload.path("basis_quotes")) {
            String ref = quote.path("ref").asText("");
            if (!ValidationBasisQuotes.ENTRY_REF.matcher(ref).matches()) continue;
            var found = entries.stream().filter(e -> e.ref().equals(ref) && e.correctable()).findFirst();
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }
    private static Optional<Entry> preferred(List<Entry> candidates) {
        return candidates.stream().min(Comparator.comparing((Entry e) -> !e.correctable()).thenComparingInt(Entry::index));
    }

    private Setting characterSetting(Entry entry, String property, UUID workId) {
        var slots = entry.state().path("slots");
        if (!slots.isObject() || slots.isEmpty()) return null;
        String wanted = normalize(property);
        List<CharacterSettingSchema> active = schemas.findAllActiveForWork(workId);
        var matches = new ArrayList<Setting>();
        for (var slot : slots.properties()) {
            String key = slot.getKey(); int split = key.indexOf(':');
            String typeName = text(slot.getValue(), "factType") != null ? text(slot.getValue(), "factType") : split < 0 ? null : key.substring(0, split);
            String factKey = text(slot.getValue(), "factKey") != null ? text(slot.getValue(), "factKey") : split < 0 ? null : key.substring(split + 1);
            CharacterFactType type = factType(typeName);
            if (type == null || factKey == null) continue;
            String display;
            try { display = displayNames.resolve(type, factKey, slot.getValue().get("valueJson"), active); }
            catch (RuntimeException unreadable) { display = null; }
            if (wanted.equals(normalize(key)) || wanted.equals(normalize(factKey)) || display != null && wanted.equals(normalize(display)))
                matches.add(new Setting(CharacterAnalysisStateMapper.slotKey(new org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot(type, factKey)),
                        display == null || display.isBlank() ? factKey : display));
        }
        return matches.size() == 1 ? matches.getFirst() : null;
    }
    private static Setting worldSetting(Entry entry, String property) {
        List<org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting.Property> properties;
        try { properties = new WorldSettingPropertyView(entry.state().path("propertiesJson")).properties(); }
        catch (RuntimeException unreadable) { return null; }
        String wanted = normalize(property);
        var matches = new ArrayList<Setting>();
        for (var p : properties) {
            boolean match = wanted.equals(normalize(p.settingName())) || p.scopeName() != null && wanted.equals(normalize(p.scopeName() + "/" + p.settingName()));
            if (match) matches.add(new Setting(WorldSettingAnalysisStateMapper.pathKey(p.scopeName(), p.settingName()),
                    p.scopeName() == null ? p.settingName() : p.scopeName() + " / " + p.settingName()));
        }
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    /** 모델 행 slot 값 leaf의 표시명. 상세 label과 같은 규칙: schema 표시명, 해석할 수 없거나 비어 있으면 factKey다. */
    private String slotDisplayName(Entry entry, String slotKey, List<CharacterSettingSchema> active) {
        var slot = entry.state().path("slots").path(slotKey);
        int split = slotKey.indexOf(':');
        String typeName = text(slot, "factType") != null ? text(slot, "factType") : split < 0 ? null : slotKey.substring(0, split);
        String factKey = text(slot, "factKey") != null ? text(slot, "factKey") : split < 0 ? slotKey : slotKey.substring(split + 1);
        CharacterFactType type = factType(typeName);
        if (type == null) return factKey;
        try {
            String display = displayNames.resolve(type, factKey, slot.get("valueJson"), active);
            return display == null || display.isBlank() ? factKey : display;
        } catch (RuntimeException unreadable) {
            return factKey;
        }
    }
    /** propertiesJson 아래 값 leaf 경로의 저장 경로 키와 표시명. 표시명은 상세 label과 같고, 경로가 1·2단계가 아니면 key는 null이다. */
    private static Setting worldLeaf(List<String> path) {
        if (path.isEmpty()) return new Setting(null, null);
        String key = path.size() == 1 ? WorldSettingAnalysisStateMapper.pathKey(null, path.getFirst())
                : path.size() == 2 ? WorldSettingAnalysisStateMapper.pathKey(path.get(0), path.get(1)) : null;
        return new Setting(key, path.size() == 1 ? path.getFirst() : path.get(0) + " / " + path.get(1));
    }
    /** 값이 하나로 모이고 비어 있지 않으면 그 값, 아니면 null. */
    private static String single(Set<String> values) {
        return values.size() == 1 && !values.iterator().next().isEmpty() ? values.iterator().next() : null;
    }

    private static ValidationSubjectType type(Entry entry) {
        return "characters".equals(entry.domain()) ? ValidationSubjectType.CHARACTER : ValidationSubjectType.WORLD_SETTING;
    }
    /** 고정 항목 identity가 실제 캐릭터·세계관 ID일 때만. 임시 대상이나 대상 없는 이력은 null이다. */
    private static UUID targetId(Entry entry) {
        String identity = ValidationDataMapper.subjectIdentity(entry.row(), entry.ref());
        String prefix = "characters".equals(entry.domain()) ? "character:" : "world:";
        if (!identity.startsWith(prefix)) return null;
        String id = identity.substring(prefix.length());
        return UUID_TEXT.matcher(id).matches() ? UUID.fromString(id) : null;
    }
    private static String name(Entry entry) {
        String name = text(entry.state(), "name");
        return name != null ? name : text(entry.state(), "subjectName");
    }
    private static CharacterFactType factType(String name) {
        if (name == null) return null;
        for (var type : CharacterFactType.values()) if (type.name().equals(name)) return type;
        return null;
    }
    /** WorldSettingAnalysisStateMapper.pathKey 형식 ["범위","설정명"] / [null,"설정명"]. */
    private static String[] path(String key) {
        try {
            var node = JSON.readTree(key);
            if (!node.isArray() || node.size() != 2 || !node.get(1).isTextual() || !(node.get(0).isNull() || node.get(0).isTextual())) return null;
            return new String[]{node.get(0).isNull() ? null : node.get(0).asText(), node.get(1).asText()};
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) { return null; }
    }
    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
    private static String text(JsonNode node, String field) {
        return node != null && node.path(field).isTextual() && !node.path(field).asText().isBlank() ? node.path(field).asText() : null;
    }
}
