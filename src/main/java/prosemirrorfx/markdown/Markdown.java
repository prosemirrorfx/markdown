package prosemirrorfx.markdown;

import com.atlassian.prosemirror.model.Mark;
import com.atlassian.prosemirror.model.Node;
import java.util.*;
import java.util.regex.Pattern;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

import static prosemirrorfx.core.pm.Pm.*;
import static prosemirrorfx.core.schema.EditorSchema.*;

/**
 * The document as Markdown: headings (#..######), paragraphs, nested bullet and numbered
 * lists, **bold**, _italic_, and hard breaks. Writing is ours, so the output is predictable; reading goes
 * through commonmark, and anything outside the schema (quotes, code, links, rules) keeps its text as
 * plain paragraphs rather than being dropped. Empty paragraphs have no Markdown form and are not kept.
 */
public final class Markdown {

    // ---- write

    public static String write(Node doc) {
        var out = new ArrayList<String>();
        blocks(doc, out);
        return String.join("\n", out).replaceAll("\n{3,}", "\n\n").strip() + "\n";
    }

    /** Children of a container, as lines, a blank line between blocks (none between items of a list). */
    static void blocks(Node container, List<String> out) {
        var first = true;
        for (var child : container.getContent().getContent()) {
            var lines = block(child);
            if (lines.isEmpty()) continue;
            if (!first) out.add("");
            out.addAll(lines);
            first = false;
        }
    }

    static List<String> block(Node n) {
        var type = n.getType();
        if (type == PARAGRAPH) {
            var text = inline(n);
            return text.isBlank() ? List.of() : List.of(escapeLineStart(text).split("\n", -1));
        }
        if (type == HEADING) return List.of("#".repeat(intAttr(n, "level")) + " " + inline(n).replace("\\\n", " "));
        if (type == BULLET_LIST || type == ORDERED_LIST) {
            var out = new ArrayList<String>();
            var number = type == ORDERED_LIST ? intAttr(n, "order") : 0;
            for (var item : n.getContent().getContent()) {
                var marker = type == ORDERED_LIST ? (number++) + ". " : "- ";
                var inner = new ArrayList<String>();
                blocks(item, inner);
                if (inner.isEmpty()) inner.add("");
                var pad = " ".repeat(marker.length());
                for (var k = 0; k < inner.size(); k++) out.add(k == 0 ? marker + inner.get(k) : inner.get(k).isEmpty() ? "" : pad + inner.get(k));
            }
            return out;
        }
        var lines = new ArrayList<String>();
        blocks(n, lines);
        return lines;
    }

    /** Inline content with marks; spaces at a mark's edge are moved outside it, as Markdown requires. */
    static String inline(Node textblock) {
        var sb = new StringBuilder();
        var open = new ArrayList<String>();
        var pendingSpace = "";
        for (var child : textblock.getContent().getContent()) {
            if (child.getType() == HARD_BREAK) {
                closeAll(sb, open);
                sb.append(pendingSpace).append("\\\n");
                pendingSpace = "";
                continue;
            }
            if (!child.isText()) continue;
            var want = delimiters(child.getMarks());
            var text = child.getText();
            var core = text.strip();
            var lead = text.substring(0, text.indexOf(core.isEmpty() ? text : core));
            var trail = core.isEmpty() ? "" : text.substring(lead.length() + core.length());
            if (core.isEmpty()) { pendingSpace += text; continue; }
            var keep = 0;
            while (keep < open.size() && keep < want.size() && open.get(keep).equals(want.get(keep))) keep++;
            while (open.size() > keep) sb.append(open.removeLast());
            sb.append(pendingSpace).append(lead);
            for (var k = keep; k < want.size(); k++) { sb.append(want.get(k)); open.add(want.get(k)); }
            sb.append(escape(core));
            pendingSpace = trail;
        }
        closeAll(sb, open);
        return sb.append(pendingSpace).toString();
    }

    static void closeAll(StringBuilder sb, List<String> open) {
        while (!open.isEmpty()) sb.append(open.removeLast());
    }

    static List<String> delimiters(List<Mark> marks) {
        var d = new ArrayList<String>();
        if (marks.stream().anyMatch(m -> m.getType() == STRONG)) d.add("**");
        if (marks.stream().anyMatch(m -> m.getType() == EM)) d.add("_");
        return d;
    }

    static String escape(String s) {
        return s.replaceAll("([\\\\*_`\\[\\]<])", "\\\\$1");
    }

    static final Pattern LINE_START = Pattern.compile("^(#{1,6}\\s|[-+*]\\s|\\d+[.)]\\s|>)");

    /** A paragraph whose text happens to look like a heading or list marker must stay a paragraph. */
    static String escapeLineStart(String text) {
        var m = LINE_START.matcher(text);
        if (!m.find()) return text;
        var first = text.charAt(0);
        if (Character.isDigit(first)) {
            var dot = text.indexOf('.') >= 0 && (text.indexOf(')') < 0 || text.indexOf('.') < text.indexOf(')')) ? text.indexOf('.') : text.indexOf(')');
            return text.substring(0, dot) + "\\" + text.substring(dot);
        }
        return "\\" + text;
    }

    // ---- read

    public static Node read(String markdown) {
        var doc = Parser.builder().build().parse(markdown);
        var blocks = blocksOf(doc);
        if (blocks.isEmpty()) blocks.add(create(PARAGRAPH, EMPTY));
        return create(DOC, frag(blocks.toArray(Node[]::new)));
    }

    static List<Node> blocksOf(org.commonmark.node.Node parent) {
        var out = new ArrayList<Node>();
        for (var c = parent.getFirstChild(); c != null; c = c.getNext()) out.addAll(blockOf(c));
        return out;
    }

    static List<Node> blockOf(org.commonmark.node.Node n) {
        return switch (n) {
            case Heading h -> List.of(create(HEADING, Map.of("level", Math.min(6, h.getLevel())), inlineOf(h)));
            case Paragraph p -> List.of(create(PARAGRAPH, inlineOf(p)));
            case BulletList l -> List.of(create(BULLET_LIST, items(l)));
            case OrderedList l -> List.of(l.getMarkerStartNumber() == null || l.getMarkerStartNumber() == 1 ? create(ORDERED_LIST, items(l)) : create(ORDERED_LIST, Map.of("order", l.getMarkerStartNumber()), items(l)));
            case FencedCodeBlock c -> plain(c.getLiteral());
            case IndentedCodeBlock c -> plain(c.getLiteral());
            case HtmlBlock h -> plain(h.getLiteral());
            case BlockQuote q -> blocksOf(q);
            case ThematicBreak t -> List.of();
            default -> blocksOf(n);
        };
    }

    static com.atlassian.prosemirror.model.Fragment items(ListBlock list) {
        var items = new ArrayList<Node>();
        for (var c = list.getFirstChild(); c != null; c = c.getNext()) {
            var content = blocksOf(c);
            if (content.isEmpty() || content.getFirst().getType() != PARAGRAPH) content.addFirst(create(PARAGRAPH, EMPTY));
            items.add(create(LIST_ITEM, frag(content.toArray(Node[]::new))));
        }
        return frag(items.toArray(Node[]::new));
    }

    static List<Node> plain(String text) {
        var out = new ArrayList<Node>();
        for (var line : text.strip().split("\n")) if (!line.isBlank()) out.add(create(PARAGRAPH, frag(SCHEMA.text(line, null))));
        return out;
    }

    static com.atlassian.prosemirror.model.Fragment inlineOf(org.commonmark.node.Node parent) {
        var out = new ArrayList<Node>();
        inlines(parent, List.of(), out);
        return frag(out.toArray(Node[]::new));
    }

    static void inlines(org.commonmark.node.Node parent, List<Mark> marks, List<Node> out) {
        for (var c = parent.getFirstChild(); c != null; c = c.getNext()) {
            switch (c) {
                case Text t -> add(out, t.getLiteral(), marks);
                case Code code -> add(out, code.getLiteral(), marks);
                case SoftLineBreak s -> add(out, " ", marks);
                case HardLineBreak h -> out.add(HARD_BREAK.create(null, EMPTY, null));
                case StrongEmphasis s -> inlines(s, with(marks, STRONG.create(null)), out);
                case Emphasis e -> inlines(e, with(marks, EM.create(null)), out);
                case HtmlInline h -> add(out, h.getLiteral(), marks);
                default -> inlines(c, marks, out);
            }
        }
    }

    static void add(List<Node> out, String text, List<Mark> marks) {
        if (!text.isEmpty()) out.add(SCHEMA.text(text, marks));
    }

    static List<Mark> with(List<Mark> marks, Mark m) {
        return m.addToSet(marks);
    }

    private Markdown() {}
}
