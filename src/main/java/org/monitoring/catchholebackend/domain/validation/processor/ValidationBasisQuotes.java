package org.monitoring.catchholebackend.domain.validation.processor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 판정 근거 문장(basis_quotes, 오류 리포트 가독성 2026-11-02)의 공통 규칙. Worker(`app/validation/judging.py normalize_basis_quotes`)와
 * 같은 규칙이며 공통 계약 `execution.judgeFlow.basisQuotes`의 vector로 맞춘다.
 *
 * <ul>
 *   <li>E 인용 {ref, source_ref, start, end, quote}: 묶음 evidence 행 안의 정확한 code point 구간. 원문 대조는 일반 인용(citation)과 같다.</li>
 *   <li>B 인용 {ref, source_ref:null, start:null, end:null, quote}: 원문이 아니라 저장 설정값이다. 판정이 읽은 모델 설정 행
 *       ({@code toValidationSettings})의 값 leaf 하나에 그대로 들어 있어야 한다. 캐릭터는 {@code state.slots.*.factValue} 문자열,
 *       세계관은 {@code state.propertiesJson} 아래 모든 문자열 leaf다.</li>
 * </ul>
 */
public final class ValidationBasisQuotes {
    public static final int MAX_ITEMS = 3;
    public static final int MAX_QUOTE_CODE_POINTS = 300;
    public static final Pattern EVIDENCE_REF = Pattern.compile("E[1-9][0-9]*");
    public static final Pattern ENTRY_REF = Pattern.compile("B[1-9][0-9]*");

    private ValidationBasisQuotes() {}

    /** 저장 설정값 leaf. 캐릭터 path는 [slotKey], 세계관 path는 propertiesJson 아래 객체 key 순서(배열 index 제외)다. */
    public record Leaf(List<String> path, String value) {}

    public static List<Leaf> settingValueLeaves(JsonNode row) {
        var result = new ArrayList<Leaf>();
        if (row == null) return result;
        var state = row.path("state");
        switch (row.path("domain").asText()) {
            case "characters" -> {
                for (var slot : state.path("slots").properties())
                    if (slot.getValue().path("factValue").isTextual()) result.add(new Leaf(List.of(slot.getKey()), slot.getValue().path("factValue").asText()));
            }
            case "worldSettings" -> collect(state.path("propertiesJson"), new ArrayList<>(), result, 0);
            default -> { }
        }
        return result;
    }

    /** quote를 그대로 담은 값 leaf. 없으면 빈 목록. */
    public static List<Leaf> containing(JsonNode row, String quote) {
        if (quote == null || quote.isEmpty()) return List.of();
        return settingValueLeaves(row).stream().filter(leaf -> leaf.value().contains(quote)).toList();
    }

    public static boolean isSettingQuote(JsonNode item) {
        return ENTRY_REF.matcher(item.path("ref").asText()).matches();
    }

    public static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    /** code point [start,end) 부분 문자열. 범위 밖이면 null. */
    public static String slice(String text, int start, int end) {
        if (text == null || start < 0 || end <= start || end > codePoints(text)) return null;
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
    }

    private static void collect(JsonNode node, List<String> path, List<Leaf> out, int depth) {
        if (depth > 20) return;
        if (node.isTextual()) { out.add(new Leaf(List.copyOf(path), node.asText())); return; }
        if (node.isObject()) {
            for (var field : node.properties()) { path.add(field.getKey()); collect(field.getValue(), path, out, depth + 1); path.removeLast(); }
        } else if (node.isArray()) {
            for (var child : node) collect(child, path, out, depth + 1);
        }
    }
}
