package prosemirrorfx.markdown;

import com.atlassian.prosemirror.model.Node;
import java.util.*;
import java.util.regex.Pattern;

import static prosemirrorfx.core.pm.Pm.*;
import static prosemirrorfx.core.schema.EditorSchema.*;

/**
 * Markdown as the user writes it, read line by line: headings, nested bullet and numbered lists (tab or
 * space indented), paragraphs, `>>` comment lines, and fenced blocks kept as they are. Everything is recorded
 * as it was laid out (see Lossless), and nothing is interpreted that is not needed: a table, a rule or a
 * quote is simply lines of text. Reading is by columns, so a tab and four spaces nest alike.
 */
final class LineParser {

    record Line(String ws, int col, String text) {
        boolean blank() { return text.isEmpty(); }
        String raw() { return ws + text; }
    }

    enum Kind { BLANK, FENCE, COMMENT, HEADING, ITEM, PARA }

    static final Pattern HEAD_RE = Pattern.compile("(?s)^(#{1,6})([ \t].*|)$"), ITEM_RE = Pattern.compile("^([-+*]|\\d{1,9}[.)])(?=[ \t]|$)([ \t]*)"),
            FENCE_RE = Pattern.compile("^(`{3,}|~{3,})"), HR_RE = Pattern.compile("^([-*_])([ \t]*\\1){2,}[ \t]*$");

    final Line[] cur;
    /** Where the last list stopped, for its caller. */
    int next;

    LineParser(List<String> lines) {
        cur = lines.stream().map(LineParser::line).toArray(Line[]::new);
    }

    static List<Node> parse(List<String> lines) {
        var p = new LineParser(lines);
        return p.blocks(0, lines.size(), 0, "", false);
    }

    static Line line(String raw) {
        if (raw.isBlank()) return new Line(raw, cols(raw, 0), "");
        var n = 0;
        while (n < raw.length() && (raw.charAt(n) == ' ' || raw.charAt(n) == '\t')) n++;
        return new Line(raw.substring(0, n), cols(raw.substring(0, n), 0), raw.substring(n));
    }

    static int cols(String ws, int from) {
        var c = from;
        for (var ch : ws.toCharArray()) c = ch == '\t' ? (c / 4 + 1) * 4 : c + 1;
        return c - from;
    }

    Kind kind(Line l, int minCol, boolean plain) {
        var t = l.text;
        if (t.isEmpty()) return Kind.BLANK;
        if (FENCE_RE.matcher(t).find()) return Kind.FENCE;
        if (t.startsWith(">>")) return Kind.COMMENT;
        if (plain) return Kind.PARA;
        if (l.col - minCol <= 3 && HEAD_RE.matcher(t).matches()) return Kind.HEADING;
        return ITEM_RE.matcher(t).find() && !HR_RE.matcher(t).matches() ? Kind.ITEM : Kind.PARA;
    }

    /** Blocks of lines [from, to) whose content starts at column minCol; base is the indent of the item they sit in. */
    List<Node> blocks(int from, int to, int minCol, String base, boolean plainFirst) {
        var out = new ArrayList<Node>();
        var gap = "";
        var i = from;
        while (i < to) {
            var l = cur[i];
            var kind = kind(l, minCol, plainFirst && i == from);
            if (kind == Kind.BLANK) { gap += l.raw() + "\n"; i++; continue; }
            switch (kind) {
                case FENCE -> { var end = fenceEnd(i, to); out.add(verbatim(i, end, gap)); i = end; }
                case COMMENT -> { out.add(paragraph(i, i + 1, gap)); i++; }
                case HEADING -> { out.add(heading(l, gap)); i++; }
                case ITEM -> { out.add(list(i, to, minCol, base, gap)); i = next; }
                default -> { var end = paragraphEnd(i, to, minCol); out.add(paragraph(i, end, gap)); i = end; }
            }
            gap = "";
        }
        return out;
    }

    int fenceEnd(int i, int to) {
        var open = FENCE_RE.matcher(cur[i].text);
        open.find();
        var close = Pattern.compile("^" + Pattern.quote(open.group(1).substring(0, 1)) + "{" + open.group(1).length() + ",}[ \t]*$");
        var k = i + 1;
        while (k < to && !close.matcher(cur[k].text).matches()) k++;
        if (k < to) return k + 1;
        while (k > i + 1 && cur[k - 1].blank()) k--;
        return k;
    }

    int paragraphEnd(int i, int to, int minCol) {
        var k = i + 1;
        while (k < to && continues(cur[k], minCol)) k++;
        return k;
    }

    /** A bullet, a heading, a comment or a fence ends a paragraph; a numbered marker does only if it says 1. */
    boolean continues(Line l, int minCol) {
        return switch (kind(l, minCol, false)) {
            case PARA -> true;
            case ITEM -> Character.isDigit(l.text.charAt(0)) && !Pattern.compile("^0*1[.)]").matcher(l.text).find();
            default -> false;
        };
    }

    Node paragraph(int i, int end, String gap) {
        var src = new StringBuilder(cur[i].text);
        for (var k = i + 1; k < end; k++) src.append('\n').append(cur[k].raw());
        return create(PARAGRAPH, attrs("src", src.toString(), "lead", cur[i].ws, "gap", gap), InlineParser.parse(src.toString()));
    }

    Node verbatim(int i, int end, String gap) {
        var kids = new ArrayList<Node>();
        for (var k = i; k < end; k++) {
            if (k > i) kids.add(HARD_BREAK.create(attrs("raw", ""), EMPTY, null));
            if (!cur[k].raw().isEmpty()) kids.add(SCHEMA.text(cur[k].raw(), null));
        }
        return create(PARAGRAPH, attrs("verbatim", true, "lead", "", "gap", gap), frag(kids.toArray(Node[]::new)));
    }

    Node heading(Line l, String gap) {
        var m = HEAD_RE.matcher(l.text);
        m.matches();
        return create(HEADING, attrs("level", m.group(1).length(), "src", m.group(2), "lead", l.ws, "gap", gap), InlineParser.heading(m.group(2)));
    }

    Node list(int i, int to, int minCol, String base, String firstGap) {
        var ordered = Character.isDigit(cur[i].text.charAt(0));
        var col0 = cur[i].col;
        var start = ordered ? Integer.parseInt(ITEM_RE.matcher(cur[i].text).results().findFirst().get().group(1).replaceAll("\\D", "")) : 1;
        var items = new ArrayList<Node>();
        var gap = firstGap;
        var k = i;
        while (true) {
            var l = cur[k];
            var m = ITEM_RE.matcher(l.text);
            m.find();
            var after = l.text.substring(m.end(1));
            var spaces = m.group(2);
            var plain = after.isBlank() || cols(spaces, l.col + m.end(1)) <= 4;
            var content = plain ? l.text.substring(m.end()) : l.text.substring(m.end(1) + 1);
            var marker = plain ? m.group() : m.group(1) + " ";
            var c = l.col + cols(marker, l.col);
            var end = itemEnd(k + 1, to, c);
            items.add(create(LIST_ITEM, attrs("marker", marker, "lead", relative(l.ws, base), "gap", gap), frag(body(k, end, c, l.ws, content).toArray(Node[]::new))));
            var n = end;
            var nextGap = "";
            while (n < to && cur[n].blank()) { nextGap += cur[n].raw() + "\n"; n++; }
            if (n < to && cur[n].col >= col0 && kind(cur[n], minCol, false) == Kind.ITEM && Character.isDigit(cur[n].text.charAt(0)) == ordered) { k = n; gap = nextGap; continue; }
            next = end;
            return create(ordered ? ORDERED_LIST : BULLET_LIST, ordered && start != 1 ? attrs("order", start) : null, frag(items.toArray(Node[]::new)));
        }
    }

    int itemEnd(int from, int to, int c) {
        var e = from;
        while (e < to) {
            if (cur[e].blank()) {
                var n = e;
                while (n < to && cur[n].blank()) n++;
                if (n < to && cur[n].col >= c) { e = n; continue; }
                break;
            }
            if (cur[e].col < c) break;
            e++;
        }
        return e;
    }

    List<Node> body(int k, int end, int c, String lead, String content) {
        if (content.isBlank()) {
            var rest = new ArrayList<>(blocks(k + 1, end, c, lead, false));
            rest.addFirst(create(PARAGRAPH, attrs("src", "", "lead", "", "gap", ""), EMPTY));
            return rest;
        }
        cur[k] = new Line("", c, content);
        return blocks(k, end, c, lead, true);
    }

    /** An item's indent relative to its parent's; when it does not start with the parent's (tabs against spaces), absolute behind a NUL. */
    static String relative(String ws, String base) { return ws.startsWith(base) ? ws.substring(base.length()) : "\0" + ws; }
}
