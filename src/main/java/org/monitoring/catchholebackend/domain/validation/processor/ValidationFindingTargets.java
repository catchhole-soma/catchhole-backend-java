package org.monitoring.catchholebackend.domain.validation.processor;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSettingDisplayNameResolver;
import org.monitoring.catchholebackend.domain.character.repository.CharacterSettingSchemaRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper;
import org.monitoring.catchholebackend.domain.validation.type.ValidationImpact;
import org.monitoring.catchholebackend.domain.validation.type.ValidationSubjectType;
import org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingPropertyView;
import org.springframework.stereotype.Component;

/**
 * 설정 finding의 대상·설정명(#314 B2). report의 고정 기준(baselineEntries)과 그 revision의 질문 상태만 읽고
 * 현재 캐릭터·세계관 값을 조회하지 않는다. 캐릭터 설정 표시명만 기존 schema 표시명 규칙(CharacterSettingDisplayNameResolver)을 쓴다.
 *
 * <ul>
 *   <li>대표 대상: subject_refs의 첫 참조(없으면 BASELINE 조회 항목)의 항목 중 정정 가능한 domain(characters·worldSettings)을 먼저, 그다음 기준 순서.</li>
 *   <li>설정: 질문의 property_or_rule이 대표 대상의 고정 slot/path 하나와 정확히(NFC·소문자·공백 무시) 맞을 때만. 캐릭터는 slot key·factKey·표시명,
 *       세계관은 설정명·"범위/설정명"과 비교한다. 0개 또는 2개 이상이면 추정하지 않고 null이다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ValidationFindingTargets {
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> CORRECTABLE = Set.of("characters", "worldSettings");
    private final CharacterSettingSchemaRepository schemas;
    private final CharacterSettingDisplayNameResolver displayNames;

    /** 저장 시점의 대상. 질문 대상이 고정 기준 항목으로 이어지지 않으면 모두 null이다. */
    public record Target(ValidationSubjectType subjectType, UUID subjectId, String subjectName, String settingKey, String settingName) {
        public static final Target NONE = new Target(null, null, null, null, null);
    }
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

    /** 판정 저장 시점에 finding에 기록할 대상·설정. */
    public Target describe(ValidationReport report, JsonNode checkState) {
        var entries = entries(report.getBaselineEntries());
        var primary = checkState == null ? Optional.<Entry>empty() : primary(entries, checkState);
        if (primary.isEmpty()) return Target.NONE;
        var entry = primary.get();
        String property = text(checkState.path("draft"), "property_or_rule");
        Setting setting = property == null ? null
                : "characters".equals(entry.domain()) ? characterSetting(entry, property, report.getWork().getId())
                : "worldSettings".equals(entry.domain()) ? worldSetting(entry, property) : null;
        return new Target(type(entry), targetId(entry), name(entry), setting == null ? null : setting.key(), setting == null ? null : setting.name());
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
    private static Optional<Entry> primary(List<Entry> entries, JsonNode state) {
        for (var subject : state.path("draft").path("subject_refs")) {
            if (!subject.isTextual()) continue;
            var found = preferred(entries.stream().filter(e -> subject.asText().equals(e.subjectRef())).toList());
            if (found.isPresent()) return found;
        }
        var refs = baselineRefs(state);
        return preferred(entries.stream().filter(e -> refs.contains(e.ref())).toList());
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
    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
    private static String text(JsonNode node, String field) {
        return node != null && node.path(field).isTextual() && !node.path(field).asText().isBlank() ? node.path(field).asText() : null;
    }
}
