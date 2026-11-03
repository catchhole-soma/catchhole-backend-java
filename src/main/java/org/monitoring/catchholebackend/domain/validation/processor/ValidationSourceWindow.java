package org.monitoring.catchholebackend.domain.validation.processor;

import java.util.ArrayList;
import java.util.List;

/**
 * 오류 리포트 문맥 블록(2026-11-03): 고정 출처 원문 한 편에서 인용 위치 앞뒤의 줄을 GitHub diff의 문맥 줄처럼 잘라 준다.
 * 위치는 모두 원문 전체 기준 Unicode code point [start,end)다.
 *
 * <ul>
 *   <li>줄 끝은 {@code \r\n}·{@code \n}·{@code \r}이며 줄 글에는 줄 끝 문자를 넣지 않는다.</li>
 *   <li>공백만 있는 줄은 세지도 돌려주지도 않는다. 웹소설 원고는 문단 사이에 빈 줄을 두므로 빈 줄을 세면 "위아래 2줄"이 문단 하나가 된다.</li>
 *   <li>{@value #MAX_LINE_CODE_POINTS}자보다 긴 줄은 그 안쪽의 마지막 공백 뒤(없으면 {@value #MAX_LINE_CODE_POINTS}자)에서 나눠 여러 줄로 센다.
 *       한 응답의 크기를 줄 수와 글자 수로 함께 묶기 위함이다.</li>
 *   <li>기준 줄(anchor가 걸친 줄)은 항상 넣고, 앞뒤 줄은 한 줄씩 번갈아 더하다가 합계가 {@value #MAX_WINDOW_CODE_POINTS}자를 넘기면 그 방향을 멈춘다.</li>
 *   <li>길이 0인 anchor는 줄 사이의 경계다. 창의 첫 줄 시작에 두고 before만 주면 그 위의 새 줄만, 마지막 줄 끝에 두고 after만 주면 그 아래의 새 줄만 돌려준다.</li>
 * </ul>
 */
public final class ValidationSourceWindow {
    /** 상세 응답이 처음 보여 주는 앞뒤 줄 수. */
    public static final int INITIAL_LINES = 2;
    /** 한 요청이 한 방향으로 더 읽을 수 있는 줄 수 상한. */
    public static final int MAX_LINES_PER_SIDE = 30;
    /** 한 응답 줄 글 합계 상한(기준 줄 포함). */
    public static final int MAX_WINDOW_CODE_POINTS = 3_000;
    /** 요청 anchor 길이 상한. 인용 문장(최대 300자)과 창 경계(0자)만 쓰므로 넉넉히 둔다. */
    public static final int MAX_ANCHOR_CODE_POINTS = 1_000;
    /** 이보다 긴 줄은 나눠 센다. */
    public static final int MAX_LINE_CODE_POINTS = 500;

    private ValidationSourceWindow() {}

    public record Line(int start, int end, String text) {}

    public record Window(int start, int end, int totalCodePoints, boolean hasMoreBefore, boolean hasMoreAfter, List<Line> lines) {}

    /** 공백만 있는 줄을 뺀 줄 목록. 긴 줄은 나눈 조각이 각각 한 줄이다. */
    public static List<Line> lines(int[] text) {
        var result = new ArrayList<Line>();
        int start = 0;
        for (int i = 0; i <= text.length; i++) {
            boolean end = i == text.length || text[i] == '\n' || text[i] == '\r';
            if (!end) continue;
            split(text, start, i, result);
            if (i < text.length && text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++;
            start = i + 1;
        }
        return result;
    }

    /**
     * anchor [anchorStart, anchorEnd) 둘레의 창. before·after는 0 이상 {@link #MAX_LINES_PER_SIDE} 이하, anchor는 원문 안이어야 한다.
     * 호출자가 범위를 먼저 검증한다. 어긋나면 IllegalArgumentException이다.
     */
    public static Window window(String source, int anchorStart, int anchorEnd, int before, int after) {
        int[] text = source.codePoints().toArray();
        if (anchorStart < 0 || anchorEnd < anchorStart || anchorEnd > text.length || before < 0 || after < 0
                || before > MAX_LINES_PER_SIDE || after > MAX_LINES_PER_SIDE) throw new IllegalArgumentException("invalid window request");
        var lines = lines(text);
        int n = lines.size();
        // first: anchor 시작 뒤에서 끝나는 첫 줄, last: anchor 끝 앞에서 시작하는 마지막 줄. 빈 anchor·줄 사이 anchor는 last = first - 1이다.
        int first = 0;
        while (first < n && lines.get(first).end() <= anchorStart) first++;
        int last = n - 1;
        while (last >= 0 && lines.get(last).start() >= anchorEnd) last--;
        if (anchorEnd == anchorStart) last = Math.min(last, first - 1);
        int lo = first, hi = last, used = 0;
        for (int k = first; k <= last; k++) used += length(lines.get(k));
        int addedBefore = 0, addedAfter = 0;
        boolean up = before > 0, down = after > 0;
        while (up || down) {
            if (up) {
                if (lo == 0 || used + length(lines.get(lo - 1)) > MAX_WINDOW_CODE_POINTS) up = false;
                else { lo--; used += length(lines.get(lo)); up = ++addedBefore < before; }
            }
            if (down) {
                if (hi == n - 1 || used + length(lines.get(hi + 1)) > MAX_WINDOW_CODE_POINTS) down = false;
                else { hi++; used += length(lines.get(hi)); down = ++addedAfter < after; }
            }
        }
        var picked = lo <= hi ? List.copyOf(lines.subList(lo, hi + 1)) : List.<Line>of();
        int start = picked.isEmpty() ? anchorStart : picked.getFirst().start();
        int end = picked.isEmpty() ? anchorStart : picked.getLast().end();
        return new Window(start, end, text.length, lo > 0, hi < n - 1, picked);
    }

    /**
     * 같은 원문의 두 창(a가 앞)이 겹치거나 맞닿는지. b가 a의 끝 이전에 시작하거나, a 끝과 b 시작 사이 원문이 공백·줄바꿈뿐이면 true다.
     * 공백만 있는 줄은 세지 않으므로 그때 두 창 사이에는 보여 줄 줄이 하나도 없다. 문맥 블록은 이런 창을 한 블록으로 합쳐 같은 줄을 두 번 보이지 않는다.
     */
    public static boolean touching(String source, Window a, Window b) {
        if (b.start() <= a.end()) return true;
        int total = source.codePointCount(0, source.length());
        if (a.end() < 0 || b.start() > total) return false;
        String between = source.substring(source.offsetByCodePoints(0, a.end()), source.offsetByCodePoints(0, b.start()));
        return between.codePoints().allMatch(ValidationSourceWindow::blank);
    }

    private static boolean blank(int c) {return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0xFEFF;}

    private static void split(int[] text, int start, int end, List<Line> out) {
        int at = start;
        while (end - at > MAX_LINE_CODE_POINTS) {
            int cut = at + MAX_LINE_CODE_POINTS;
            for (int p = at + MAX_LINE_CODE_POINTS; p > at + MAX_LINE_CODE_POINTS / 2; p--)
                if (Character.isWhitespace(text[p - 1]) || Character.isSpaceChar(text[p - 1])) { cut = p; break; }
            add(text, at, cut, out);
            at = cut;
        }
        add(text, at, end, out);
    }

    private static void add(int[] text, int start, int end, List<Line> out) {
        for (int p = start; p < end; p++)
            if (!blank(text[p])) {
                out.add(new Line(start, end, new String(text, start, end - start)));
                return;
            }
    }

    private static int length(Line line) {return line.end() - line.start();}
}
