package prosemirrorfx.markdown;

import com.atlassian.prosemirror.model.Node;
import java.util.*;

import static prosemirrorfx.markdown.LineParser.cols;
import static prosemirrorfx.core.pm.Pm.*;
import static prosemirrorfx.core.schema.EditorSchema.*;

/**
 * Writes one top-level block. A block that was read and not changed is written from the source and layout
 * recorded when it was read, so it comes out byte for byte as it went in; one that was typed or edited is
 * written in the usual form, keeping the layout around it. In canonical mode nothing recorded is used.
 */
final class LosslessWriter {

    /** pad: what continuation lines start with; base: the indent of the item we are in; minRel: the least a nested marker may be indented. */
    record Ctx(String pad, String base, int minRel, boolean nested) {}

    static final Ctx TOP = new Ctx("", "", 0, false);

    /** Where a textblock was written: its first line in this writer's output, the marker or indent before its text, and its lines after that. */
    record Placed(int line, String prefix, List<String> lines) {
        Placed shift(int base) { return new Placed(line + base, prefix, lines); }
    }

    /** A single asterisk opening emphasis: the text this paragraph came from writes it that way. */
    static final java.util.regex.Pattern STAR = java.util.regex.Pattern.compile("(?<![*\\\\])\\*(?![*\\s])");

    final boolean canonical;
    /** Every textblock written, by identity, for SourceMap; the lines are relative to this writer's own output. */
    final Map<Node, Placed> placed = new IdentityHashMap<>();
    List<String> result = List.of();

    LosslessWriter(boolean canonical) { this.canonical = canonical; }

    List<String> top(Node n, Node prev) {
        var out = new ArrayList<String>();
        block(n, prev, null, TOP, out);
        result = out;
        return out;
    }

    void children(Node container, Ctx c, String marker, List<String> out) {
        Node prev = null;
        for (var j = 0; j < container.getChildCount(); j++) {
            var child = container.child(j);
            block(child, prev, j == 0 ? marker : null, c, out);
            prev = child;
        }
    }

    void block(Node n, Node prev, String marker, Ctx c, List<String> out) {
        var type = n.getType();
        if (type == BULLET_LIST || type == ORDERED_LIST) { list(n, prev, c, out); return; }
        var verbatim = Boolean.TRUE.equals(attr(n, "verbatim"));
        if (type == PARAGRAPH && !verbatim && n.getContent().getSize() == 0 && marker == null) return;
        if (marker == null) out.addAll(gap(n, prev == null ? "" : "\n"));
        var lines = type == HEADING ? heading(n, c) : verbatim ? raw(n) : text(n, c, marker != null);
        var prefix = marker != null ? marker : verbatim ? "" : lead(n, c);
        placed.put(n, new Placed(out.size(), prefix, lines));
        out.add(prefix + lines.getFirst());
        out.addAll(lines.subList(1, lines.size()));
    }

    List<String> gap(Node n, String unset) {
        var g = canonical ? null : str(n, "gap");
        g = g == null ? unset : g;
        return g.isEmpty() ? List.of() : List.of(g.substring(0, g.length() - 1).split("\n", -1));
    }

    String lead(Node n, Ctx c) {
        var lead = canonical ? "" : str(n, "lead");
        return !lead.isEmpty() && cols(lead, 0) >= cols(c.pad, 0) ? lead : c.pad;
    }

    List<String> text(Node n, Ctx c, boolean afterMarker) {
        var src = canonical ? null : str(n, "src");
        if (src != null && Sig.inline(InlineParser.parse(src), false).equals(Sig.inline(n.getContent(), false))) return List.of(src.split("\n", -1));
        var star = src != null && STAR.matcher(src).find();
        var spliced = LineSplice.lines(src, n.getContent(), afterMarker, c.pad, star);
        if (spliced != null) return spliced;
        var lines = InlineWriter.lines(n.getContent(), afterMarker, star);
        var out = new ArrayList<String>(List.of(lines.getFirst()));
        lines.stream().skip(1).forEach(l -> out.add(c.pad + l));
        return out;
    }

    List<String> heading(Node n, Ctx c) {
        var hashes = "#".repeat(intAttr(n, "level"));
        var src = canonical ? null : str(n, "src");
        if (src != null && Sig.inline(InlineParser.heading(src), false).equals(Sig.inline(n.getContent(), false))) return List.of(hashes + src);
        return List.of(hashes + " " + String.join(" ", InlineWriter.lines(n.getContent(), true)).strip());
    }

    List<String> raw(Node n) {
        var sb = new StringBuilder();
        for (var c : n.getContent().getContent()) sb.append(c.isText() ? c.getText() : "\n");
        return List.of(sb.toString().split("\n", -1));
    }

    void list(Node list, Node prev, Ctx c, List<String> out) {
        var ordered = list.getType() == ORDERED_LIST;
        var start = ordered ? intAttr(list, "order") : 0;
        for (var k = 0; k < list.getChildCount(); k++) {
            var item = list.child(k);
            out.addAll(gap(item, k == 0 && prev != null && !c.nested ? "\n" : ""));
            var abs = indent(item, c);
            var marker = marker(item, ordered, start + k);
            var width = cols(marker, 0);
            children(item, new Ctx(abs + " ".repeat(width), abs, width, true), abs + marker, out);
        }
    }

    /** The item's whole indent: its own under its parent's, or as recorded when that did not start with the parent's. */
    String indent(Node item, Ctx c) {
        var rel = canonical ? "" : str(item, "lead");
        var abs = rel.startsWith("\0") ? rel.substring(1) : c.base + rel;
        return cols(abs, 0) < cols(c.base, 0) + c.minRel ? c.base + " ".repeat(c.minRel) : abs;
    }

    String marker(Node item, boolean ordered, int number) {
        var m = canonical ? null : str(item, "marker");
        var ok = m != null && m.matches(ordered ? "\\d{1,9}[.)][ \t]*" : "[-+*][ \t]*");
        var marker = ok ? m : ordered ? number + ". " : "- ";
        var first = item.getChildCount() == 0 ? null : item.child(0);
        var filled = first != null && (first.getType() != PARAGRAPH || first.getContent().getSize() > 0);
        return filled && !marker.endsWith(" ") && !marker.endsWith("\t") ? marker + " " : marker;
    }
}
