package prosemirrorfx.markdown;

import com.atlassian.prosemirror.model.Fragment;
import com.atlassian.prosemirror.model.Node;
import java.util.*;

import static prosemirrorfx.core.pm.Pm.*;
import static prosemirrorfx.core.schema.EditorSchema.*;

/**
 * An edited paragraph of several lines (pasted code, a table, a log) written back line by line: the lines the edit
 * did not touch come from the original source with their indentation, and only the lines that changed are written anew.
 */
final class LineSplice {

    /** The paragraph's lines, or null when the paragraph was not read from source. pad starts every line after the first. */
    static List<String> lines(String src, Fragment content, boolean plainFirst, String pad, boolean star) {
        if (src == null) return null;
        var old = src.split("\n", -1);
        var lines = new ArrayList<List<Node>>(List.of(new ArrayList<>()));
        var breaks = new ArrayList<Node>();
        for (var c : content.getContent()) {
            if (c.getType() == HARD_BREAK) { breaks.add(c); lines.add(new ArrayList<>()); } else lines.getLast().add(c);
        }
        int o = old.length, n = lines.size();
        var pre = 0;
        while (pre < Math.min(o, n) && same(old, pre, lines, pre)) pre++;
        var suf = 0;
        while (suf < Math.min(o, n) - pre && same(old, o - 1 - suf, lines, n - 1 - suf)) suf++;
        var out = new ArrayList<String>(Arrays.asList(old).subList(0, pre));
        var middle = new ArrayList<Node>();
        for (var k = pre; k < n - suf; k++) {
            middle.addAll(lines.get(k));
            if (k < breaks.size() && (k < n - suf - 1 || suf > 0)) middle.add(breaks.get(k));
        }
        var written = InlineWriter.lines(frag(middle.toArray(Node[]::new)), plainFirst && pre == 0, star);
        if (suf > 0 && written.getLast().isEmpty()) written = written.subList(0, written.size() - 1);
        for (var k = 0; k < written.size(); k++) out.add(indent(old, pre + k, o - suf, pad) + written.get(k));
        out.addAll(Arrays.asList(old).subList(o - suf, o));
        return out;
    }

    /** What line j starts with: the first line nothing, a line that replaces an old one that one's indentation, a new line the pad. */
    static String indent(String[] old, int j, int oldEnd, String pad) {
        if (j == 0) return "";
        if (j >= oldEnd) return pad;
        return old[j].substring(0, old[j].length() - old[j].stripLeading().length());
    }

    static boolean same(String[] old, int i, List<List<Node>> lines, int j) {
        var follows = i < old.length - 1;
        return follows == (j < lines.size() - 1)
                && Sig.inline(InlineParser.content(old[i], follows), false).equals(Sig.inline(frag(lines.get(j).toArray(Node[]::new)), false));
    }

    private LineSplice() {}
}
