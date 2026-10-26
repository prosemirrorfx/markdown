package prosemirrorfx.markdown;

import com.atlassian.prosemirror.model.Fragment;
import com.atlassian.prosemirror.model.Node;
import java.util.*;

import static prosemirrorfx.core.pm.Pm.*;
import static prosemirrorfx.core.schema.EditorSchema.*;

/**
 * A node as text that ignores how it was laid out in the file (markers, indentation, blank lines, how a
 * break was written) and keeps what it says. Two nodes with the same signature mean the same thing, which is
 * how the writer knows a stored source line still describes its node and that what it wrote reads back.
 */
public final class Sig {

    public static String of(Node n) { return of(n, false); }

    public static String of(Node n, boolean trim) {
        var name = n.getType().getName();
        if (n.isTextblock()) return name + attrs(n) + "[" + inline(n.getContent(), trim) + "]";
        var kids = new StringJoiner(",", name + attrs(n) + "(", ")");
        for (var c : n.getContent().getContent()) kids.add(of(c, trim));
        return kids.toString();
    }

    static String attrs(Node n) {
        var t = n.getType();
        return t == HEADING ? "" + intAttr(n, "level") : t == ORDERED_LIST ? "" + intAttr(n, "order") : t == PARAGRAPH && Boolean.TRUE.equals(attr(n, "verbatim")) ? "v" : "";
    }

    /** Runs of text with the same marks, breaks as a line feed; trim drops the spaces at each line's ends, which a reader never sees. */
    public static String inline(Fragment f, boolean trim) {
        var lines = new ArrayList<List<String[]>>();
        lines.add(new ArrayList<>());
        for (var c : f.getContent()) {
            if (!c.isText()) { lines.add(new ArrayList<>()); continue; }
            var runs = lines.getLast();
            var key = marks(c);
            if (!runs.isEmpty() && runs.getLast()[0].equals(key)) runs.getLast()[1] += c.getText();
            else runs.add(new String[]{key, c.getText()});
        }
        var sb = new StringBuilder();
        for (var runs : lines) {
            if (trim && !runs.isEmpty()) { runs.getFirst()[1] = runs.getFirst()[1].stripLeading(); runs.getLast()[1] = runs.getLast()[1].stripTrailing(); }
            runs.stream().filter(r -> !r[1].isEmpty()).forEach(r -> sb.append('{').append(r[0]).append(':').append(r[1]).append('}'));
            sb.append('\n');
        }
        return sb.toString();
    }

    static String marks(Node text) {
        var names = new TreeSet<String>();
        text.getMarks().forEach(m -> names.add(m.getType().getName() + (m.getType() == LINK ? m.getAttrs().get("href") + "|" + m.getAttrs().get("title") : "")));
        return String.join("+", names);
    }

    private Sig() {}
}
