package org.monitoring.catchholebackend.domain.character.processor;

/**
 * JPQL {@code like ... escape '\'} 검색어의 와일드카드를 literal 문자로 바꾼다.
 * 설정 이력 검색과 캐릭터 이름 검색이 같은 규칙으로 {@code %}, {@code _}, {@code \}를 처리하게 한다.
 */
public final class LikePatternEscaper {

    private LikePatternEscaper() {
    }

    public static String escape(String query) {
        return query
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
