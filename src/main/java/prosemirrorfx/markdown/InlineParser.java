package prosemirrorfx.markdown;

import com.atlassian.prosemirror.model.Fragment;
import com.atlassian.prosemirror.model.Mark;
import com.atlassian.prosemirror.model.Node;
import java.util.*;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

import static prosemirrorfx.core.pm.Pm.*;
import static prosemirrorfx.core.schema.EditorSchema.*;

/**
 * One textblock's source (its lines joined with \n) as inline content: text, strong, em, code, links. A line
 * break keeps how it was written (nothing, two spaces, a backslash) so it can be written back as it was.
 * Anything else inline (an image, inline HTML) becomes text under the raw mark: written exactly as read.
 */
public final class InlineParser {

    static final Parser INLINE = Parser.builder().enabledBlockTypes(Set.of()).build();

    public static Fragment parse(String src) {
        var out = new ArrayList<Node>();
        var lines = src.split("\n", -1);
        for (var k = 0; k < lines.length; k++) {
            var parts = split(lines[k], k < lines.length - 1);
            line(parts[0], out);
            if (k < lines.length - 1) out.add(HARD_BREAK.create(attrs("raw", parts[1]), EMPTY, null));
        }
        return frag(out.toArray(Node[]::new));
    }

    /** One source line's content, without the break after it (when one follows). */
    public static Fragment content(String sourceLine, boolean breakFollows) {
        var out = new ArrayList<Node>();
        line(split(sourceLine, breakFollows)[0], out);
        return frag(out.toArray(Node[]::new));
    }

    /** A source line as {content, how its break was written: nothing, spaces or a backslash}. */
    static String[] split(String sourceLine, boolean breakFollows) {
        var line = sourceLine.stripLeading();
        if (!breakFollows) return new String[]{line, ""};
        var t = line.stripTrailing();
        return line.endsWith("\\") ? new String[]{line.substring(0, line.length() - 1), "\\"} : new String[]{t, line.substring(t.length())};
    }

    static void line(String line, List<Node> out) {
        var doc = INLINE.parse(line);
        var before = out.size();
        if (doc.getFirstChild() != null) inlines(doc.getFirstChild(), List.of(), out);
        if (out.size() == before) Markdown.add(out, line, List.of(RAW.create(null)));
    }

    /** What an ATX heading's source (the text after its #s) says: closing #s and the spaces around are not content. */
    public static Fragment heading(String src) {
        var s = src.strip();
        if (s.matches("#+")) s = "";
        return parse(s.replaceFirst("\\s+#+$", ""));
    }

    static void inlines(org.commonmark.node.Node parent, List<Mark> marks, List<Node> out) {
        for (var c = parent.getFirstChild(); c != null; c = c.getNext()) {
            switch (c) {
                case Text t -> Markdown.add(out, t.getLiteral(), marks);
                case Code code -> Markdown.add(out, code.getLiteral(), Markdown.with(marks, CODE.create(null)));
                case SoftLineBreak s -> Markdown.add(out, " ", marks);
                case StrongEmphasis s -> inlines(s, Markdown.with(marks, STRONG.create(null)), out);
                case Emphasis e -> inlines(e, Markdown.with(marks, EM.create(null)), out);
                case Link l -> inlines(l, Markdown.with(marks, LINK.create(link(l))), out);
                case HtmlInline h -> Markdown.add(out, h.getLiteral(), List.of(RAW.create(null)));
                case Image i -> Markdown.add(out, image(i), List.of(RAW.create(null)));
                default -> inlines(c, marks, out);
            }
        }
    }

    static Map<String, Object> link(Link l) {
        return l.getTitle() == null ? attrs("href", l.getDestination()) : attrs("href", l.getDestination(), "title", l.getTitle());
    }

    static String image(Image i) {
        var alt = new StringBuilder();
        for (var c = i.getFirstChild(); c != null; c = c.getNext()) if (c instanceof Text t) alt.append(t.getLiteral());
        return "![" + alt + "](" + i.getDestination() + (i.getTitle() == null ? "" : " \"" + i.getTitle() + "\"") + ")";
    }

    private InlineParser() {}
}
