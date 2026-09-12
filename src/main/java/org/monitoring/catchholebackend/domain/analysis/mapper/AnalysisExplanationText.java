package org.monitoring.catchholebackend.domain.analysis.mapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** 공개 판단 문장만 읽기 시 정리한다. 원문·설정값·저장된 AI 판단은 변경하지 않는다. */
public final class AnalysisExplanationText {
    private static final Pattern INTERNAL_IDENTIFIER = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}_])(?:[CPTQS]\\d+(?:\\.P\\d+)?|"
                    + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?![A-Za-z0-9_])"
                    + "|(?:provisional-(?:world|character):|[a-z]+(?:_[a-z]+){2,})"
                    + "|(?<![A-Za-z0-9_])(?:target_?ref|candidate_?ref|snapshot_?version|"
                    + "matchedCharacterId|provisionalSubjectKey|worldSettingId|factKey|"
                    + "sourceCandidateRefs|dependencyCandidateRefs)(?![A-Za-z0-9_])");
    private static final String FALLBACK = "추출된 내용과 기존 설정을 비교한 결과입니다. 제안된 내용을 확인해 주세요.";

    private AnalysisExplanationText() {}

    public static String forReader(String text, String... actualNames) {
        if (text == null || text.isBlank()) return text;
        if (text.indexOf('\uE000') >= 0 || text.indexOf('\uE001') >= 0) return FALLBACK;
        // 실제 이름·표시값이 Root, MERGE 같은 단어여도 작품의 내용을 고치지 않는다.
        List<String> names = java.util.Arrays.stream(actualNames)
                .filter(name -> name != null && !name.isBlank() && !name.matches("[0-9\\s.,+%−-]+"))
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
        List<String> protectedNames = new ArrayList<>();
        String result = text;
        for (String name : names) {
            String marker = "\uE000" + protectedNames.size() + "\uE001";
            if (result.contains(name)) {
                result = result.replace(name, marker);
                protectedNames.add(name);
            }
        }
        // 알려진 개발 표현만 바꾼다. 문장을 해석할 수 없는 식별자는 이름을 추측해 채우지 않는다.
        if (INTERNAL_IDENTIFIER.matcher(result).find()) return FALLBACK;
        result = result.replaceAll("(?i)(?<![\\p{L}\\p{N}_])(?:root|루트)에\\s*포함", "별도 구분 없이 정리")
                .replaceAll("(?i)(?<![\\p{L}\\p{N}_])(?:root|루트)에", "별도 구분 없이")
                .replaceAll("(?i)(?<![\\p{L}\\p{N}_])root(?:\\s*(?:property|속성|설정))?(?![A-Za-z0-9_])", "개별 설정")
                .replace("루트 설정", "개별 설정").replace("루트 속성", "개별 설정");
        String[][] vocabulary = {
                {"snapshot", "현재 설정"}, {"스냅샷", "현재 설정"}, {"슬롯", "설정 항목"}, {"canonical", "대표"}, {"slot", "설정 항목"},
                {"scope", "적용 범위"}, {"property", "설정"}, {"key", "항목 이름"},
                {"value", "내용"}, {"version", "저장 시점"}, {"schema", "설정 작성 기준"},
                {"UPDATE", "수정"}, {"MERGE", "병합"}, {"ADD", "추가"}, {"EXCLUDE", "제외"},
                {"REMOVE", "해제"}, {"HISTORY_ONLY", "이력에만 저장"}, {"REVIEW_REQUIRED", "직접 확인 필요"},
                {"NULL", "없음"}, {"LLM", "AI"}, {"enum", "구분 값"}
        };
        for (String[] entry : vocabulary) {
            result = result.replaceAll("(?i)(?<![\\p{L}\\p{N}_])" + entry[0]
                    + "(?![A-Za-z0-9_])", entry[1]);
        }
        result = result.replace("기존 현재 설정", "기존 설정");
        for (int index = 0; index < protectedNames.size(); index++) {
            result = result.replace("\uE000" + index + "\uE001", protectedNames.get(index));
        }
        return result;
    }
}
