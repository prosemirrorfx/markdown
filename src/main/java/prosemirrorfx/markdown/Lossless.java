package prosemirrorfx.markdown;

import com.atlassian.prosemirror.model.Node;
import java.util.*;

import static prosemirrorfx.core.pm.Pm.*;
import static prosemirrorfx.core.schema.EditorSchema.*;

/**
 * Markdown the way the user writes it, read into the document and written back unchanged: load a file, edit
 * nothing, and the bytes that come out are the bytes that went in. Each block remembers its own source and layout
 * (see LosslessWriter), and the document remembers the line ending, a leading byte-order mark and what trails
 * the last line. Edit a block and that block is written in the usual form; the rest of the file is not touched.
 * For output that should look the same every time, use Markdown, the normalising reader and writer.
 */
public final class Lossless {

    public static Node read(String text) {
        var bom = text.startsWith("﻿");
        if (bom) text = text.substring(1);
        var crlf = text.contains("\r\n") && !text.replace("\r\n", "").contains("\n");
        if (crlf) text = text.replace("\r\n", "\n");
        var all = List.of(text.split("\n", -1));
        var last = all.size() - 1;
        while (last >= 0 && all.get(last).isBlank()) last--;
        var end = 0;
        for (var k = 0; k <= last; k++) end += all.get(k).length() + (k < last ? 1 : 0);
        var blocks = LineParser.parse(all.subList(0, last + 1));
        if (blocks.isEmpty()) blocks = List.of(create(PARAGRAPH, EMPTY));
        return create(DOC, attrs("tail", text.substring(end), "eol", crlf ? "\r\n" : "\n", "bom", bom), frag(blocks.toArray(Node[]::new)));
    }

    public static String write(Node doc) {
        var lines = new ArrayList<String>();
        Node prev = null;
        for (var child : doc.getContent().getContent()) {
            var block = block(child, prev);
            if (block.isEmpty()) continue;
            lines.addAll(block);
            prev = child;
        }
        var text = String.join("\n", lines) + str(doc, "tail");
        if ("\r\n".equals(str(doc, "eol"))) text = text.replace("\n", "\r\n");
        return Boolean.TRUE.equals(attr(doc, "bom")) ? "﻿" + text : text;
    }

    /** The block as it was; if that does not read back as the same block (after structure edits), as the usual form. */
    static List<String> block(Node n, Node prev) {
        var lines = new LosslessWriter(false).top(n, prev);
        return lines.isEmpty() || readsBack(lines, n) ? lines : new LosslessWriter(true).top(n, prev);
    }

    static boolean readsBack(List<String> lines, Node n) {
        var read = LineParser.parse(lines);
        return read.size() == 1 && Sig.of(read.getFirst(), true).equals(Sig.of(n, true));
    }

    private Lossless() {}
}
