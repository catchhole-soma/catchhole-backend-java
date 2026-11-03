package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationSourceWindow;
import org.monitoring.catchholebackend.domain.validation.processor.ValidationSourceWindow.Line;

@DisplayName("오류 리포트 문맥 블록: 원문 줄 나누기와 앞뒤 줄 창")
class ValidationSourceWindowTest {
    /** 합성 원고. 문단 사이 빈 줄과 CRLF는 웹소설 원고 파일의 실제 모양을 따른다. */
    static final String TEXT = String.join("\r\n", "첫째 줄", "", "둘째 줄", "", "셋째 줄", "", "넷째 줄 인용 문장", "", "다섯째 줄", "", "여섯째 줄", "", "일곱째 줄");

    static int at(String text, String part) {return text.codePointCount(0, text.indexOf(part));}
    static int cp(String text) {return text.codePointCount(0, text.length());}
    static List<String> texts(ValidationSourceWindow.Window window) {return window.lines().stream().map(Line::text).toList();}

    @Test @DisplayName("CRLF·LF·CR을 줄 끝으로 보고 줄 글에 줄 끝 문자를 넣지 않으며 공백만 있는 줄은 세지 않는다")
    void splitsLinesAcrossLineEndingsAndSkipsBlankLines() {
        String text = "가\r\n나\n\n 　\t\r\n다\r라\r\n";
        var lines = ValidationSourceWindow.lines(text.codePoints().toArray());
        assertThat(lines).extracting(Line::text).containsExactly("가", "나", "다", "라");
        assertThat(lines).extracting(Line::start).containsExactly(0, 3, 11, 13);
        for (var line : lines) {
            assertThat(line.text()).doesNotContain("\r", "\n");
            assertThat(text.codePoints().skip(line.start()).limit(line.end() - line.start())
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString()).isEqualTo(line.text());
        }
    }

    @Test @DisplayName("500자보다 긴 줄은 그 안쪽 마지막 공백 뒤에서 나눠 여러 줄로 센다")
    void splitsLongLinesAtWhitespace() {
        String word = "가나다라마바사아자차 ";
        String text = word.repeat(120); // 1,320자 한 줄
        var lines = ValidationSourceWindow.lines(text.codePoints().toArray());
        assertThat(lines.size()).isGreaterThanOrEqualTo(3);
        int at = 0;
        for (var line : lines) {
            assertThat(line.start()).isEqualTo(at);
            assertThat(line.end() - line.start()).isLessThanOrEqualTo(ValidationSourceWindow.MAX_LINE_CODE_POINTS);
            if (line.end() < cp(text)) assertThat(line.text()).endsWith(" ");
            at = line.end();
        }
        assertThat(at).isEqualTo(cp(text));
        // 공백이 없는 긴 줄은 500자에서 자른다.
        var hard = ValidationSourceWindow.lines("가".repeat(1001).codePoints().toArray());
        assertThat(hard).extracting(line -> line.end() - line.start()).containsExactly(500, 500, 1);
    }

    @Test @DisplayName("처음 창은 인용이 걸친 줄과 위아래 2줄이고 원문 처음·끝에 닿으면 더 보기 표시를 끈다")
    void initialWindowHasTwoLinesAroundTheQuote() {
        int start = at(TEXT, "인용 문장"), end = start + cp("인용 문장");
        var middle = ValidationSourceWindow.window(TEXT, start, end, 2, 2);
        assertThat(texts(middle)).containsExactly("둘째 줄", "셋째 줄", "넷째 줄 인용 문장", "다섯째 줄", "여섯째 줄");
        assertThat(middle.hasMoreBefore()).isTrue();
        assertThat(middle.hasMoreAfter()).isTrue();
        assertThat(middle.start()).isEqualTo(at(TEXT, "둘째 줄"));
        assertThat(middle.end()).isEqualTo(at(TEXT, "여섯째 줄") + cp("여섯째 줄"));
        assertThat(middle.totalCodePoints()).isEqualTo(cp(TEXT));

        var first = ValidationSourceWindow.window(TEXT, 0, 2, 2, 2);
        assertThat(texts(first)).containsExactly("첫째 줄", "둘째 줄", "셋째 줄");
        assertThat(first.hasMoreBefore()).isFalse();
        assertThat(first.hasMoreAfter()).isTrue();

        int last = at(TEXT, "일곱째 줄");
        var tail = ValidationSourceWindow.window(TEXT, last, last + 2, 2, 2);
        assertThat(texts(tail)).containsExactly("다섯째 줄", "여섯째 줄", "일곱째 줄");
        assertThat(tail.hasMoreAfter()).isFalse();
    }

    @Test @DisplayName("창 경계(길이 0 anchor)에서 위·아래로 더 읽으면 이미 보인 줄 없이 새 줄만 주고 끝에 닿으면 멈춘다")
    void expandingFromWindowEdgesReturnsOnlyNewLines() {
        int start = at(TEXT, "인용 문장");
        var initial = ValidationSourceWindow.window(TEXT, start, start + 2, 2, 2);
        var up = ValidationSourceWindow.window(TEXT, initial.start(), initial.start(), 5, 0);
        assertThat(texts(up)).containsExactly("첫째 줄");
        assertThat(up.hasMoreBefore()).isFalse();
        assertThat(up.hasMoreAfter()).isTrue();
        assertThat(up.end()).isLessThanOrEqualTo(initial.start());
        var down = ValidationSourceWindow.window(TEXT, initial.end(), initial.end(), 0, 5);
        assertThat(texts(down)).containsExactly("일곱째 줄");
        assertThat(down.hasMoreAfter()).isFalse();
        assertThat(down.start()).isGreaterThanOrEqualTo(initial.end());
        // 0줄 요청은 줄 없이 경계만 돌려준다.
        var none = ValidationSourceWindow.window(TEXT, initial.start(), initial.start(), 0, 0);
        assertThat(none.lines()).isEmpty();
        assertThat(none.start()).isEqualTo(initial.start());
        assertThat(none.end()).isEqualTo(initial.start());
        assertThat(none.hasMoreBefore()).isTrue();
        assertThat(none.hasMoreAfter()).isTrue();
    }

    @Test @DisplayName("여러 줄에 걸친 인용은 그 줄을 모두 넣고 빈 줄에 놓인 anchor는 앞뒤 줄만 준다")
    void spanningAndGapAnchors() {
        int start = at(TEXT, "셋째"), end = at(TEXT, "넷째 줄") + 2;
        assertThat(texts(ValidationSourceWindow.window(TEXT, start, end, 0, 0))).containsExactly("셋째 줄", "넷째 줄 인용 문장");
        int gap = at(TEXT, "셋째 줄") + cp("셋째 줄") + 2; // 셋째 줄 뒤 빈 줄 위치
        assertThat(texts(ValidationSourceWindow.window(TEXT, gap, gap, 1, 1))).containsExactly("셋째 줄", "넷째 줄 인용 문장");
    }

    @Test @DisplayName("줄 글 합계는 3,000자를 넘기지 않고 넘칠 방향은 멈춘 채 더 보기 표시를 남긴다")
    void capsTheWindowAtThreeThousandCodePoints() {
        var lines = new java.util.ArrayList<String>();
        for (int i = 0; i < 40; i++) lines.add(String.valueOf((char) ('가' + i)).repeat(400));
        String text = String.join("\n", lines);
        int anchor = 20 * 401;
        var window = ValidationSourceWindow.window(text, anchor, anchor + 1, 30, 30);
        int total = window.lines().stream().mapToInt(line -> line.end() - line.start()).sum();
        assertThat(total).isLessThanOrEqualTo(ValidationSourceWindow.MAX_WINDOW_CODE_POINTS);
        assertThat(window.lines()).hasSize(7);
        assertThat(window.hasMoreBefore()).isTrue();
        assertThat(window.hasMoreAfter()).isTrue();
        // 위아래를 번갈아 채우므로 인용 줄이 가운데 있다.
        assertThat(window.lines().get(3).start()).isEqualTo(anchor);
    }

    @Test @DisplayName("위치는 UTF-16이 아니라 code point이고 빈 원문·공백뿐인 원문은 줄 없이 끝난다")
    void usesCodePointsAndHandlesEmptyText() {
        String text = "😀가\n나😀다\n라";
        var window = ValidationSourceWindow.window(text, 3, 4, 1, 1);
        assertThat(texts(window)).containsExactly("😀가", "나😀다", "라");
        assertThat(window.lines().get(1).start()).isEqualTo(3);
        assertThat(window.lines().get(1).end()).isEqualTo(6);
        assertThat(window.totalCodePoints()).isEqualTo(8);
        for (String empty : List.of("", "\r\n\r\n  \n")) {
            var w = ValidationSourceWindow.window(empty, 0, 0, 2, 2);
            assertThat(w.lines()).isEmpty();
            assertThat(w.hasMoreBefore()).isFalse();
            assertThat(w.hasMoreAfter()).isFalse();
        }
    }

    @Test @DisplayName("두 창은 겹치거나 사이에 공백 줄만 있으면 맞닿은 것이고 글 줄이 하나라도 끼면 떨어진 것이다(블록 병합)")
    void touchingWindowsHaveNoTextLineBetween() {
        // 셋째 줄 창(첫째~다섯째)과 일곱째 줄 창(다섯째~일곱째)은 겹친다.
        var third = ValidationSourceWindow.window(TEXT, at(TEXT, "셋째 줄"), at(TEXT, "셋째 줄") + 4, 2, 2);
        var seventh = ValidationSourceWindow.window(TEXT, at(TEXT, "일곱째 줄"), at(TEXT, "일곱째 줄") + 5, 2, 2);
        assertThat(ValidationSourceWindow.touching(TEXT, third, seventh)).isTrue();
        // 앞뒤 0줄 창: 둘째·셋째 줄은 사이에 빈 줄(CRLF)만 있어 맞닿고, 둘째·넷째 줄 사이에는 셋째 줄이 끼어 떨어져 있다.
        var second = ValidationSourceWindow.window(TEXT, at(TEXT, "둘째 줄"), at(TEXT, "둘째 줄") + 4, 0, 0);
        var thirdOnly = ValidationSourceWindow.window(TEXT, at(TEXT, "셋째 줄"), at(TEXT, "셋째 줄") + 4, 0, 0);
        var fourth = ValidationSourceWindow.window(TEXT, at(TEXT, "넷째 줄"), at(TEXT, "넷째 줄") + 4, 0, 0);
        assertThat(ValidationSourceWindow.touching(TEXT, second, thirdOnly)).isTrue();
        assertThat(ValidationSourceWindow.touching(TEXT, second, fourth)).isFalse();
        // 공백·전각 공백·BOM만 있는 줄도 글 줄이 아니다.
        String spaced = "가 줄\n \u3000\uFEFF\n나 줄";
        var a = ValidationSourceWindow.window(spaced, 0, 1, 0, 0);
        var b = ValidationSourceWindow.window(spaced, at(spaced, "나"), at(spaced, "나") + 1, 0, 0);
        assertThat(ValidationSourceWindow.touching(spaced, a, b)).isTrue();
    }

    @Test @DisplayName("원문 밖 anchor·음수·줄 수 상한 초과는 거절한다")
    void rejectsInvalidRequests() {
        int length = cp(TEXT);
        for (int[] bad : new int[][] {{-1, 0, 0, 0}, {2, 1, 0, 0}, {0, length + 1, 0, 0}, {0, 0, -1, 0}, {0, 0, 0, 31}, {0, 0, 31, 0}})
            assertThatThrownBy(() -> ValidationSourceWindow.window(TEXT, bad[0], bad[1], bad[2], bad[3])).isInstanceOf(IllegalArgumentException.class);
        assertThat(ValidationSourceWindow.window(TEXT, length, length, 30, 0).lines()).isNotEmpty();
    }
}
