package org.monitoring.catchholebackend.domain.relationship.processor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * GH385: 관계 끝점 이름을 캐릭터 추출과 같은 규칙으로 프로필에 해소한다.
 *
 * <p>정규화·제외 단어는 AI {@code character_name_resolver}의 {@code normalize_character_name}·
 * {@code is_usable_subject_resolution_name}, 포함 판정은 {@code ordered_character_subjects._names_overlap}을
 * 옮겼다. 설정 후보끼리의 경쟁 규칙(원문 표기 교차 확인, 같은 청크 신규 발견 확장)은 관계 끝점에 없는 입력이라 옮기지 않는다.
 * 상태가 없는 정적 규칙만 둔다.
 */
public final class RelationshipNameRules {
    /** 특정 인물을 가리킨다고 보기 어려운 지칭어. AI {@code AMBIGUOUS_MENTIONS}와 같다. */
    static final Set<String> AMBIGUOUS_MENTIONS = Set.of(
            "나", "나는", "내가", "나를", "나에게", "내", "내게", "나의", "내 캐릭터",
            "저", "저는", "제가", "저를", "저에게", "제", "주인공",
            "그", "그는", "그가", "그를", "그에게", "그의",
            "그녀", "그녀는", "그녀가", "그녀를", "그녀에게", "그녀의",
            "그 남자", "그 여자", "그 아이", "그 사람", "이 사람", "저 사람");

    /** AI {@code PLACEHOLDER_ENTITY_NAMES}와 같다. */
    static final Set<String> PLACEHOLDER_ENTITY_NAMES =
            Set.of("미상", "불명", "불명확", "알 수 없음", "추론 불가", "unknown");

    /** AI {@code AMBIGUOUS_MENTION_PARTICLES}와 같은 순서다. */
    static final List<String> PARTICLES = List.of(
            "에게서", "한테서", "에게", "한테", "께", "으로", "로", "은", "는", "이", "가", "을", "를", "의", "도");

    private static final String EDGE_MARKS = "\"'`“”‘’()[]{}<>〈〉《》";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private RelationshipNameRules() {}

    /**
     * 앞뒤 공백 → 앞뒤 따옴표·괄호 → 공백 뭉치 한 칸 → 소문자. 원본처럼 괄호를 떼어 낸 뒤 다시 trim하지 않는다.
     * 알려진 편차: Python {@code casefold}와 달리 {@code toLowerCase}는 ß·합자를 펼치지 않는다(한글·라틴 이름은 같다).
     */
    public static String normalize(String value) {
        if (value == null) return "";
        String trimmed = trim(value);
        int start = 0, end = trimmed.length();
        while (start < end && EDGE_MARKS.indexOf(trimmed.charAt(start)) >= 0) start++;
        while (end > start && EDGE_MARKS.indexOf(trimmed.charAt(end - 1)) >= 0) end--;
        return WHITESPACE.matcher(trimmed.substring(start, end)).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    /** 정규화한 이름이 비교에 쓸 수 있는 구체 이름인지 본다. "나은"·"그로"처럼 지칭어와 우연히 겹치는 이름은 남긴다. */
    public static boolean usable(String normalized) {
        if (concrete(normalized)) return true;
        if (normalized.isEmpty()
                || PLACEHOLDER_ENTITY_NAMES.contains(normalized)
                || AMBIGUOUS_MENTIONS.contains(normalized)) return false;
        for (String particle : PARTICLES) {
            if (length(normalized) > length(particle) && normalized.endsWith(particle)) {
                String stem = normalized.substring(0, normalized.length() - particle.length());
                if (length(stem) > 1 && ambiguous(stem)) return false;
            }
        }
        return true;
    }

    /** 둘 다 정규화한 이름. 같거나, 둘 다 2글자 이상이고 한쪽이 다른 쪽을 포함하면 겹친다. */
    public static boolean overlaps(String left, String right) {
        return left.equals(right)
                || Math.min(length(left), length(right)) >= 2
                        && (left.contains(right) || right.contains(left));
    }

    /**
     * 관측 이름과 확정 이름 목록(이름 → 프로필 ID)을 받아 해소 결과(이름 → 프로필 ID)를 돌려준다.
     * ID 하나는 해소, 둘 이상은 보류이고 결과에 없는 이름은 해소되지 않은 것이다.
     *
     * <ol>
     *   <li>정확 일치: 모든 확정 이름과 관측 이름은 정규화 결과가 같은 확정 이름의 프로필을 모은다.
     *   <li>포함: 정확 일치가 없는 관측 이름만 확정 이름과 {@link #overlaps}로 비교한다. 겹치는 프로필이 하나면 해소하고,
     *       둘 이상이면 보류한다. 대명사·placeholder는 양쪽 모두 비교하지 않는다.
     * </ol>
     */
    public static Map<String, List<UUID>> resolve(
            Map<String, ? extends Collection<UUID>> confirmed, Collection<String> observed) {
        Map<String, Set<UUID>> exact = new HashMap<>();
        List<Spelling> spellings = new ArrayList<>();
        confirmed.forEach((name, ids) -> {
            String normalized = normalize(name);
            exact.computeIfAbsent(exactKey(name, normalized), k -> new LinkedHashSet<>()).addAll(ids);
            if (usable(normalized)) ids.forEach(id -> spellings.add(new Spelling(normalized, id)));
        });
        Map<String, List<UUID>> resolved = new LinkedHashMap<>();
        for (String name : confirmed.keySet())
            resolved.put(name, List.copyOf(exact.get(exactKey(name, normalize(name)))));
        for (String name : observed) {
            if (resolved.containsKey(name)) continue;
            String normalized = normalize(name);
            Set<UUID> same = exact.get(exactKey(name, normalized));
            List<UUID> ids = same != null ? List.copyOf(same) : contained(normalized, spellings);
            if (!ids.isEmpty()) resolved.put(name, ids);
        }
        return resolved;
    }

    private static List<UUID> contained(String normalized, List<Spelling> spellings) {
        if (!usable(normalized)) return List.of();
        Set<UUID> ids = new LinkedHashSet<>();
        for (Spelling spelling : spellings)
            if (overlaps(normalized, spelling.normalized())) ids.add(spelling.characterId());
        return List.copyOf(ids);
    }

    /** 정규화 결과가 빈 이름은 원문 문자열 그대로만 같은 이름으로 본다. */
    private static String exactKey(String raw, String normalized) {
        return normalized.isEmpty() ? "\u0000" + raw : normalized;
    }

    private static boolean concrete(String normalized) {
        return !normalized.isEmpty()
                && !PLACEHOLDER_ENTITY_NAMES.contains(normalized)
                && !ambiguous(normalized);
    }

    /** "주인공에게는"처럼 조사가 여럿 붙은 지칭어도 조사를 하나씩 떼며 확인한다. */
    private static boolean ambiguous(String mention) {
        if (AMBIGUOUS_MENTIONS.contains(mention)) return true;
        for (String particle : PARTICLES)
            if (length(mention) > length(particle)
                    && mention.endsWith(particle)
                    && ambiguous(mention.substring(0, mention.length() - particle.length())))
                return true;
        return false;
    }

    /** Python {@code str.strip()}처럼 NBSP를 포함한 유니코드 공백을 앞뒤에서 지운다. */
    private static String trim(String value) {
        int start = 0, end = value.length();
        while (start < end && space(value.charAt(start))) start++;
        while (end > start && space(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean space(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    private record Spelling(String normalized, UUID characterId) {}
}
