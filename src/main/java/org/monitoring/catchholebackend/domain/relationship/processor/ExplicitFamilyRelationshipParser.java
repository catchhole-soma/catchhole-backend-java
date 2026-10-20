package org.monitoring.catchholebackend.domain.relationship.processor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.monitoring.catchholebackend.domain.relationship.type.FamilyTreeRole;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipAssertion;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;
import org.springframework.stereotype.Component;

/**
 * 현재 PROFILE의 명시적 가족 명제를 해석한다. 원문·근거 JSON·임의 중첩 속성을 읽거나
 * 이름을 fuzzy match하지 않는다. 전체 문장이 지원 문법과 일치하지 않으면 자동 반영을 막는다.
 */
@Component
public class ExplicitFamilyRelationshipParser {

    private static final int MAX_TEXT_LENGTH = 4096;
    private static final int MAX_CLAUSES = 20;
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} .·'’\\-]{0,99}");
    private static final List<Role> ROLES = roles();
    private static final String ROLE_WORDS = ROLES.stream().map(role -> Pattern.quote(role.word()))
            .collect(Collectors.joining("|"));
    private static final Map<String, Role> ROLE_BY_WORD = ROLES.stream()
            .collect(Collectors.toMap(Role::word, role -> role));
    private static final Pattern LABELED = Pattern.compile("^(" + ROLE_WORDS + ")(?:\\s*[:：]\\s*|(?:은|는|이|가)\\s*)(.+)$");
    private static final String CHILD_MODIFIER = "(?:첫째|둘째|셋째|넷째|다섯째|여섯째|일곱째|여덟째|아홉째|열째"
            + "|(?:첫|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\\s*번째"
            + "|제\\s*[1-9][0-9]{0,2}(?:\\s*(?:번째|째))?|[1-9][0-9]{0,2}\\s*(?:번째|째)|외동|막내|맏)";
    private static final Pattern POSSESSIVE = Pattern.compile("^(.+?)의\\s*(?:(" + CHILD_MODIFIER
            + ")\\s*)?(" + ROLE_WORDS + ")(.*)$");
    private static final Pattern NESTED_ROLE = Pattern.compile("의\\s*(?:" + CHILD_MODIFIER + "\\s*)?(?:" + ROLE_WORDS + ")");
    private static final Pattern NUMBERED_DESCENDANT = Pattern.compile("^(.+?)의\\s*([1-9][0-9]{0,9})\\s*대손(.*)$");
    private static final Pattern ANCESTRY_GROUP = Pattern.compile("(?:가문|혈통|혈족|왕조|일족|종족|문파|클랜|부족)(?:$|\\s)");
    private static final Pattern NESTED_POSSESSIVE = Pattern.compile("의\\s");
    private static final Pattern FAMILY_ROLE = Pattern.compile("(?:^|[\\s:：,;]|의\\s*)(?:" + CHILD_MODIFIER + "\\s*)?(?:" + ROLE_WORDS
            + ")(?=$|[\\s:：,;.?!]|은|는|이|가|을|를|의|다|임|였|처럼|같|일|인)");
    private static final Pattern EXTENDED_FAMILY = Pattern.compile("가문|혈통|혈족|직계|친족|친척"
            + "|(?:^|의\\s*)(?:할아버지|할머니|조부모|조부|조모|삼촌|외삼촌|고모|이모|사촌|조카)(?=$|[\\s:：]|은|는|이|가|의)");
    // 관계를 설명하는 뒤 명사구만 허용한다. 임의 문장을 잘라 부정·추측 문맥을 버리지 않는다.
    private static final Pattern ROLE_DESCRIPTION = Pattern.compile("^인\\s+(?:(?:[1-9][0-9]{0,2}\\s*등급"
            + "|[1-9][0-9]{0,2}\\s*레벨|레벨\\s*[1-9][0-9]{0,2})\\s*)?"
            + "(?:탐험가|모험가|전사|기사|검사|마법사|사제|궁수|상인|학자|연금술사|치유사)(?:이다|입니다|임)?$");
    private static final Pattern DESCRIPTIVE_PERSON = Pattern.compile("(?:^|\\s)(?:남성|여성|남자|여자|사람|인물)$");
    private static final Pattern NON_PERSON_CONTEXT = Pattern.compile("(?:^|\\s)(?:사망|실종|출산|사별|생존|부재|고아"
            + "|돌아가|돌아오|살고|버림받|있음|있다|없음|없다|모름|모른|알\\s*수|추정|추측|아님|아니다)"
            + "|(?:에서|에게|한테|라고|처럼)\\s|(?:얼굴|이름|행방)을\\s");
    private static final Set<String> AMBIGUOUS_ROLES = Set.of("형", "오빠", "누나", "언니", "동생", "남동생", "여동생");
    private static final Set<String> FAMILY_DISPLAY_NAMES = Set.of("family", "가족", "가족 관계", "가족관계");
    private static final Pattern DISPLAY_NAME_QUALIFIER = Pattern.compile(
            "추정|추측|불확실|소문|비유|부정|거짓|가능성|가설|의심|믿음|착각|가상|사칭|과거|예전|이전|전생|예정|미래"
                    + "|(?i:\\b(?:uncertain|rumou?r|figurative|false|possible|hypothesis|suspected|belief|former|past|alleged|not|unconfirmed)\\b)");
    private static final Set<String> UNRESOLVED_MENTIONS = Set.of("없음", "없다", "미상", "불명", "모름", "미확인",
            "알 수 없음", "확인되지 않음", "그", "그녀", "그들", "그녀들", "그 사람", "그 남자", "그 여자",
            "이 사람", "저 사람", "누군가", "아무개", "본인", "자신", "자기", "나", "너", "우리", "당신",
            "그쪽", "이쪽", "저쪽", "상대", "누구");
    private static final List<Tail> TAILS = List.of(
            new Tail("인 것으로 추정된다", RelationshipAssertion.UNCERTAIN, false),
            new Tail("으로 추정된다", RelationshipAssertion.UNCERTAIN, false),
            new Tail("로 추정된다", RelationshipAssertion.UNCERTAIN, false),
            new Tail("일지도 모른다", RelationshipAssertion.UNCERTAIN, false),
            new Tail("일 수 있다", RelationshipAssertion.UNCERTAIN, false),
            new Tail("인지 불명", RelationshipAssertion.UNCERTAIN, false),
            new Tail("인 듯하다", RelationshipAssertion.UNCERTAIN, false),
            new Tail("라고 알려져 있다", RelationshipAssertion.UNCERTAIN, false),
            new Tail("이라고 한다", RelationshipAssertion.UNCERTAIN, false),
            new Tail("라고 한다", RelationshipAssertion.UNCERTAIN, false),
            new Tail("(추정)", RelationshipAssertion.UNCERTAIN, false),
            new Tail("(불확실)", RelationshipAssertion.UNCERTAIN, false),
            new Tail("?", RelationshipAssertion.UNCERTAIN, false),
            new Tail("이 아니다", RelationshipAssertion.NEGATED, false),
            new Tail("가 아니다", RelationshipAssertion.NEGATED, false),
            new Tail("이 아님", RelationshipAssertion.NEGATED, false),
            new Tail("가 아님", RelationshipAssertion.NEGATED, false),
            new Tail("아니다", RelationshipAssertion.NEGATED, false),
            new Tail("아님", RelationshipAssertion.NEGATED, false),
            new Tail("같은 존재", RelationshipAssertion.FIGURATIVE, false),
            new Tail("같은 사람", RelationshipAssertion.FIGURATIVE, false),
            new Tail("처럼 여긴다", RelationshipAssertion.FIGURATIVE, false),
            new Tail("처럼 생각한다", RelationshipAssertion.FIGURATIVE, false),
            new Tail("와 같은 존재", RelationshipAssertion.FIGURATIVE, false),
            new Tail("과 같은 존재", RelationshipAssertion.FIGURATIVE, false),
            new Tail("같다", RelationshipAssertion.FIGURATIVE, false),
            new Tail("이었다", RelationshipAssertion.ASSERTED, true),
            new Tail("이었음", RelationshipAssertion.ASSERTED, true),
            new Tail("였음", RelationshipAssertion.ASSERTED, true),
            new Tail("였다", RelationshipAssertion.ASSERTED, true),
            new Tail("입니다", RelationshipAssertion.ASSERTED, false),
            new Tail("이다", RelationshipAssertion.ASSERTED, false)
    ).stream().sorted(Comparator.comparingInt((Tail tail) -> tail.text().length()).reversed()).toList();

    public FamilyRelationshipParseResult parse(JsonNode currentValue) {
        return parse(null, currentValue);
    }

    /** role 자체인 슬롯 key(예: profile.아버지)는 값이 단순 상대명인 경우에만 보완한다. */
    public FamilyRelationshipParseResult parse(String factKey, JsonNode currentValue) {
        return parse(factKey, currentValue, null);
    }

    /** 완전한 명사구의 끝 인명은 현재 프로필 소유자와 정확히 일치할 때만 허용한다. */
    public FamilyRelationshipParseResult parse(String factKey, JsonNode currentValue, String ownerName) {
        if (currentValue == null || !currentValue.isObject()) {
            return new FamilyRelationshipParseResult(List.of(), false, List.of("UNSUPPORTED_CURRENT_VALUE"));
        }
        String displayed = text(currentValue.get("factValue"));
        JsonNode structured = currentValue.get("valueJson");
        String primary = text(structured != null && structured.isObject() ? structured.get("value") : structured);
        LinkedHashSet<String> channels = new LinkedHashSet<>();
        if (displayed != null) {
            channels.add(displayed);
        }
        if (primary != null) {
            channels.add(primary);
        }
        String keyLabel = labelFromKey(factKey);
        Role keyRole = keyLabel == null ? null : ROLE_BY_WORD.get(keyLabel);
        boolean familyKeyLabel = keyLabel != null && mentionsFamily(
                DISPLAY_NAME_QUALIFIER.matcher(keyLabel).replaceAll(" ").replaceAll("[./()\\[\\]]", " "));
        boolean familyMentioned = keyRole != null || isFamilyKey(factKey) || familyKeyLabel
                || channels.stream().anyMatch(this::mentionsFamily);
        if (channels.isEmpty()) {
            return new FamilyRelationshipParseResult(List.of(), familyMentioned,
                    familyMentioned ? List.of("UNSUPPORTED_CURRENT_VALUE") : List.of());
        }
        if (channels.stream().anyMatch(channel -> channel.length() > MAX_TEXT_LENGTH)) {
            return new FamilyRelationshipParseResult(List.of(), familyMentioned, List.of("INPUT_LIMIT_EXCEEDED"));
        }
        Set<String> reasons = new LinkedHashSet<>();
        if (channels.size() > 1) {
            reasons.add("CURRENT_VALUE_CONFLICT");
        }
        // 값이 온전한 문장이어도 키의 과거·추정 문맥이나 미지원 가족 역할을 버리지 않는다.
        if (keyLabel != null && (DISPLAY_NAME_QUALIFIER.matcher(keyLabel).find()
                || familyKeyLabel && keyRole == null && !FAMILY_DISPLAY_NAMES.contains(keyLabel))) {
            reasons.add("UNSUPPORTED_FAMILY_SYNTAX");
        }
        if (structured != null && !structured.isNull()
                && (!structured.isTextual() && !structured.isObject()
                    || structured.isObject() && !isSupportedStructuredValue(factKey, structured, primary))) {
            reasons.add("UNSUPPORTED_STRUCTURED_CONTEXT");
        }
        List<ParsedFamilyRelationship> parsed = new ArrayList<>();
        for (String channel : channels) {
            String[] clauses = channel.split("[;；\\r\\n]+", -1);
            if (clauses.length > MAX_CLAUSES) {
                return new FamilyRelationshipParseResult(List.of(), familyMentioned, List.of("INPUT_LIMIT_EXCEEDED"));
            }
            for (String clause : clauses) {
                if (clause.isBlank()) {
                    continue;
                }
                ParsedFamilyRelationship relationship = parseClause(clause.strip(), keyRole, ownerName);
                if (relationship == null) {
                    reasons.add("UNSUPPORTED_FAMILY_SYNTAX");
                } else {
                    parsed.add(relationship);
                    familyMentioned = true;
                }
            }
        }
        if (!familyMentioned) {
            return new FamilyRelationshipParseResult(List.of(), false, List.of());
        }
        List<ParsedFamilyRelationship> result = parsed.stream().map(value -> holdIfNeeded(value, reasons)).distinct().toList();
        LinkedHashSet<String> allReasons = new LinkedHashSet<>(reasons);
        result.forEach(value -> allReasons.addAll(value.reviewReasons()));
        return new FamilyRelationshipParseResult(result, true, List.copyOf(allReasons));
    }

    private ParsedFamilyRelationship parseClause(String clause, Role keyRole, String ownerName) {
        String value = clause.endsWith(".") ? clause.substring(0, clause.length() - 1).strip() : clause;
        boolean historical = false;
        if (value.startsWith("현재 ")) {
            value = value.substring(3).strip();
        } else if (value.startsWith("과거 ") || value.startsWith("예전 ")) {
            historical = true;
            value = value.substring(3).strip();
        }
        Matcher numbered = NUMBERED_DESCENDANT.matcher(value);
        if (numbered.matches()) {
            String mention = validAncestorMention(numbered.group(1));
            Tail tail = parseAncestryTail(numbered.group(3), ownerName);
            if (mention == null || tail == null || NESTED_POSSESSIVE.matcher(numbered.group(1)).find()) return null;
            try {
                var claim = parsed(ROLE_BY_WORD.get("후손"), new Mention(mention, tail), true, historical);
                return new ParsedFamilyRelationship(claim.objectMention(), claim.kind(), claim.subtype(),
                        claim.temporalScope(), claim.timeLabel(), claim.assertion(), claim.reviewReasons(), null, null,
                        Integer.parseInt(numbered.group(2)));
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        if (keyRole != null) {
            Mention mention = parseMention(value);
            return mention == null ? null : parsed(keyRole, mention, false, historical);
        }
        Matcher labeled = LABELED.matcher(value);
        if (labeled.matches()) {
            Mention mention = parseMention(labeled.group(2));
            if (mention != null) {
                return parsed(ROLE_BY_WORD.get(labeled.group(1)), mention, false, historical);
            }
        }
        Matcher possessive = POSSESSIVE.matcher(value);
        if (possessive.matches()) {
            // '홍길동의 의형제'의 두 번째 '의'를 소유격으로 재해석하지 않는다.
            if (NESTED_POSSESSIVE.matcher(possessive.group(1)).find()) {
                return null;
            }
            Role role = ROLE_BY_WORD.get(possessive.group(3));
            if (possessive.group(2) != null && role.targetKind() != RelationshipKind.PARENT_OF) {
                return null;
            }
            Tail tail = role.targetKind().isAncestry() ? parseAncestryTail(possessive.group(4), ownerName)
                    : parseRoleTail(possessive.group(4));
            String mention = role.targetKind().isAncestry() ? validAncestorMention(possessive.group(1))
                    : validMention(possessive.group(1));
            if (tail != null && mention != null) {
                return parsed(role, new Mention(mention, tail), true, historical);
            }
        }
        return null;
    }

    private Tail parseAncestryTail(String text, String ownerName) {
        if (ownerName != null && text.matches("(?:\\s+|인\\s+)" + Pattern.quote(ownerName.strip()) + "(?:이다|입니다|임)?")) {
            return new Tail("", RelationshipAssertion.ASSERTED, false);
        }
        return parseRoleTail(text);
    }

    private String validAncestorMention(String value) {
        String mention = validMention(value);
        return mention == null || NESTED_POSSESSIVE.matcher(mention).find()
                || ANCESTRY_GROUP.matcher(mention).find() || DESCRIPTIVE_PERSON.matcher(mention).find()
                || mention.matches(".*의\\s*[0-9]+\\s*대손.*") ? null : mention;
    }

    private Mention parseMention(String text) {
        String value = text.strip();
        for (Tail tail : TAILS) {
            if (value.endsWith(tail.text())) {
                String mention = validMention(value.substring(0, value.length() - tail.text().length()));
                if (mention != null) {
                    return new Mention(mention, tail);
                }
            }
        }
        String mention = validMention(value);
        return mention == null ? null : new Mention(mention, new Tail("", RelationshipAssertion.ASSERTED, false));
    }

    private Tail parseRoleTail(String text) {
        String value = text.strip();
        if (value.isEmpty() || value.equals("다") || value.equals("임")) {
            return new Tail(value, RelationshipAssertion.ASSERTED, false);
        }
        if (ROLE_DESCRIPTION.matcher(value).matches()) {
            return new Tail(value, RelationshipAssertion.ASSERTED, false);
        }
        return TAILS.stream().filter(tail -> tail.text().equals(value)).findFirst().orElse(null);
    }

    /** 미등록 인물 도형에는 대명사·미상·가족 호칭만 있는 값을 이름처럼 표시하지 않는다. */
    public boolean isExplicitNamedMention(String value) {
        return value != null && validMention(value) != null && !ROLE_BY_WORD.containsKey(value.strip());
    }

    private String validMention(String value) {
        String mention = value.strip();
        if (!NAME.matcher(mention).matches() || UNRESOLVED_MENTIONS.contains(mention)
                || NON_PERSON_CONTEXT.matcher(mention).find()
                || mention.contains(" 또는 ") || mention.contains(" 혹은 ") || mention.contains(" 및 ")
                || mention.contains(" 아닌 ") || mention.contains(" 아니라 ")) {
            return null;
        }
        if (NESTED_ROLE.matcher(mention).find()) {
            return null;
        }
        return mention;
    }

    private ParsedFamilyRelationship parsed(Role role, Mention mention, boolean inverse, boolean historical) {
        RelationshipKind kind = role.targetKind();
        if (kind.isAncestry() && validAncestorMention(mention.name()) == null) return null;
        if (inverse) {
            kind = switch (kind) {
                case PARENT_OF -> RelationshipKind.CHILD_OF;
                case CHILD_OF -> RelationshipKind.PARENT_OF;
                case ANCESTOR_OF -> RelationshipKind.DESCENDANT_OF;
                case DESCENDANT_OF -> RelationshipKind.ANCESTOR_OF;
                default -> kind;
            };
        }
        RelationshipAssertion assertion = mention.tail().assertion();
        List<String> reasons = assertion == RelationshipAssertion.ASSERTED ? List.of() : List.of(assertion.name());
        if (assertion == RelationshipAssertion.ASSERTED && AMBIGUOUS_ROLES.contains(role.word())
                && (!inverse || DESCRIPTIVE_PERSON.matcher(mention.name()).find())) {
            assertion = RelationshipAssertion.UNCERTAIN;
            reasons = List.of("AMBIGUOUS_FAMILY_ROLE");
        }
        FamilyTreeRole explicitRole = explicitRole(role.word());
        FamilyTreeRole subjectRole = kind == RelationshipKind.PARENT_OF ? FamilyTreeRole.PARENT
                : kind == RelationshipKind.CHILD_OF ? FamilyTreeRole.CHILD : null;
        FamilyTreeRole objectRole = kind == RelationshipKind.PARENT_OF ? FamilyTreeRole.CHILD
                : kind == RelationshipKind.CHILD_OF ? FamilyTreeRole.PARENT : null;
        if (explicitRole != null) {
            if (inverse) subjectRole = explicitRole;
            else objectRole = explicitRole;
        }
        return new ParsedFamilyRelationship(mention.name(), kind, role.subtype(),
                historical || role.historical() || mention.tail().historical()
                        ? RelationshipTemporalScope.HISTORICAL : RelationshipTemporalScope.CURRENT,
                null, assertion, reasons, subjectRole, objectRole, null);
    }

    private ParsedFamilyRelationship holdIfNeeded(ParsedFamilyRelationship value, Set<String> globalReasons) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>(value.reviewReasons());
        reasons.addAll(globalReasons);
        RelationshipAssertion assertion = value.assertion();
        if (globalReasons.contains("CURRENT_VALUE_CONFLICT")) {
            assertion = RelationshipAssertion.CONFLICTING;
        } else if (!globalReasons.isEmpty() && assertion == RelationshipAssertion.ASSERTED) {
            assertion = RelationshipAssertion.UNCERTAIN;
        }
        return new ParsedFamilyRelationship(value.objectMention(), value.kind(), value.subtype(), value.temporalScope(),
                value.timeLabel(), assertion, List.copyOf(reasons), value.subjectRole(), value.objectRole(), value.generationGap());
    }

    private FamilyTreeRole explicitRole(String word) {
        return switch (word) {
            case "아버지", "부친", "아빠", "친아버지", "친부", "양아버지", "양부", "의붓아버지", "계부" -> FamilyTreeRole.FATHER;
            case "어머니", "모친", "엄마", "친어머니", "친모", "양어머니", "양모", "의붓어머니", "계모" -> FamilyTreeRole.MOTHER;
            case "아들", "친아들", "양아들", "의붓아들", "장남", "차남" -> FamilyTreeRole.SON;
            case "딸", "친딸", "양딸", "양녀", "의붓딸", "장녀", "차녀" -> FamilyTreeRole.DAUGHTER;
            default -> null;
        };
    }

    private String labelFromKey(String factKey) {
        if (factKey == null) {
            return null;
        }
        String key = factKey.strip();
        if (key.startsWith("profile.")) {
            key = key.substring("profile.".length());
        } else if (key.startsWith("profile_family/")) {
            key = key.substring("profile_family/".length());
        }
        // 현재 PROFILE 키의 '_'는 항목명 안의 공백이다. '전_남편'에서 '전'을 버리지 않는다.
        return key.replace('_', ' ').replaceAll("\\s+", " ").strip();
    }

    /** 직접 편집한 PROFILE의 name은 검증된 표시명일 때만 무시하며 명제로 해석하지 않는다. */
    private boolean isSupportedStructuredValue(String factKey, JsonNode structured, String primary) {
        if (primary == null) {
            return false;
        }
        if (structured.size() == 1) {
            return true;
        }
        if (structured.size() != 2 || !structured.has("name")) {
            return false;
        }
        String name = text(structured.get("name"));
        if (name == null) {
            return false;
        }
        String displayName = name.replaceAll("\\s+", " ");
        if (DISPLAY_NAME_QUALIFIER.matcher(displayName).find()) {
            return false;
        }
        if (FAMILY_DISPLAY_NAMES.contains(displayName)) {
            return true;
        }
        if (factKey == null) {
            return false;
        }
        // CharacterServiceImpl.keySuffixDisplayName과 같은 표시명 경계다.
        String suffix = factKey.substring(factKey.lastIndexOf('.') + 1);
        return displayName.equals(suffix.replace('_', ' ').replaceAll("\\s+", " ").trim());
    }

    private boolean isFamilyKey(String key) {
        return key != null && (key.contains("가족") || key.equals("profile.family") || key.equals("profile_family"));
    }

    private boolean mentionsFamily(String text) {
        return text.contains("가족") || text.matches(".*[0-9]+\\s*대손.*") || FAMILY_ROLE.matcher(text).find() || EXTENDED_FAMILY.matcher(text).find();
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() && !node.textValue().isBlank() ? node.textValue().strip() : null;
    }

    private static List<Role> roles() {
        Map<String, Role> result = new LinkedHashMap<>();
        add(result, RelationshipKind.CHILD_OF, RelationshipSubtype.UNSPECIFIED,
                "아버지", "어머니", "부친", "모친", "부모", "아빠", "엄마");
        add(result, RelationshipKind.CHILD_OF, RelationshipSubtype.BIOLOGICAL, "친아버지", "친어머니", "친부", "친모");
        add(result, RelationshipKind.CHILD_OF, RelationshipSubtype.ADOPTIVE, "양아버지", "양어머니", "양부", "양모", "양부모");
        add(result, RelationshipKind.CHILD_OF, RelationshipSubtype.STEP, "의붓아버지", "의붓어머니", "계부", "계모");
        add(result, RelationshipKind.PARENT_OF, RelationshipSubtype.UNSPECIFIED, "아들", "딸", "자녀", "자식", "장남", "차남", "장녀", "차녀");
        add(result, RelationshipKind.PARENT_OF, RelationshipSubtype.BIOLOGICAL, "친아들", "친딸", "친자녀");
        add(result, RelationshipKind.PARENT_OF, RelationshipSubtype.ADOPTIVE, "양아들", "양딸", "양자", "양녀", "입양아");
        add(result, RelationshipKind.PARENT_OF, RelationshipSubtype.STEP, "의붓아들", "의붓딸", "의붓자녀");
        add(result, RelationshipKind.DESCENDANT_OF, RelationshipSubtype.UNSPECIFIED, "조상", "선조");
        add(result, RelationshipKind.ANCESTOR_OF, RelationshipSubtype.UNSPECIFIED, "후손");
        add(result, RelationshipKind.SPOUSE, RelationshipSubtype.UNSPECIFIED, "배우자", "남편", "아내", "부인");
        add(result, RelationshipKind.SIBLING, RelationshipSubtype.UNSPECIFIED,
                "형", "오빠", "누나", "언니", "동생", "형제", "자매", "남매", "남동생", "여동생");
        add(result, RelationshipKind.SIBLING, RelationshipSubtype.BIOLOGICAL,
                "친형", "친오빠", "친누나", "친언니", "친동생", "친형제", "친자매", "친남매");
        add(result, RelationshipKind.SIBLING, RelationshipSubtype.HALF,
                "이복형제", "이복자매", "이복남매", "이부형제", "이부자매", "이부남매");
        add(result, RelationshipKind.SIBLING, RelationshipSubtype.STEP, "의붓형제", "의붓자매", "의붓남매");
        for (String word : List.of("전 남편", "전남편", "전 아내", "전아내", "전처", "전 배우자")) {
            result.put(word, new Role(word, RelationshipKind.SPOUSE, RelationshipSubtype.UNSPECIFIED, true));
        }
        return result.values().stream().sorted(Comparator.comparingInt((Role role) -> role.word().length()).reversed()
                .thenComparing(Role::word)).toList();
    }

    private static void add(Map<String, Role> result, RelationshipKind kind, RelationshipSubtype subtype, String... words) {
        for (String word : words) {
            result.put(word, new Role(word, kind, subtype, false));
        }
    }

    private record Role(String word, RelationshipKind targetKind, RelationshipSubtype subtype, boolean historical) {}
    private record Tail(String text, RelationshipAssertion assertion, boolean historical) {}
    private record Mention(String name, Tail tail) {}
}
