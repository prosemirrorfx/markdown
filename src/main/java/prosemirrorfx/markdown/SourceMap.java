package prosemirrorfx.markdown;

import com.atlassian.prosemirror.model.Node;
import java.util.*;

import static prosemirrorfx.core.schema.EditorSchema.HARD_BREAK;

/**
 * Where each part of a document sits in the Markdown it is written as, and back: a document position to a line and
 * column (or an offset into the text), and the other way. Built from the same pass that writes the text, so
 * Lossless.write(doc) is exactly text(). Lines and columns count from 0. Lines are exact. A column is found by matching
 * the document's characters against the source line in order, skipping the marks and escapes the document does not
 * contain (**, `, a backslash), so it is exact for plain text and lands beside the markup for formatted text.
 */
public final class SourceMap {

    /** One textblock: its content in the document, and the source lines it was written as. */
    record Block(int start, int size, int line, String prefix, List<String> lines, String[] docLines, int[] docLineStarts) {}

    final String text;
    final List<Block> blocks;
    final int[] lineStarts;

    SourceMap(String text, List<Block> blocks) {
        this.text = text;
        this.blocks = blocks;
        var starts = new ArrayList<Integer>(List.of(text.startsWith("﻿") ? 1 : 0));
        for (var k = 0; k < text.length(); k++) if (text.charAt(k) == '\n') starts.add(k + 1);
        lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
    }

    public static SourceMap of(Node doc) {
        var placed = new IdentityHashMap<Node, LosslessWriter.Placed>();
        var text = Lossless.write(doc, placed);
        var blocks = new ArrayList<Block>();
        doc.descendants((n, pos, parent, i) -> {
            if (!n.isTextblock()) return true;
            var p = placed.get(n);
            if (p != null) blocks.add(block(n, pos + 1, p));
            return false;
        });
        return new SourceMap(text, blocks);
    }

    static Block block(Node n, int start, LosslessWriter.Placed p) {
        var lines = new ArrayList<String>();
        var starts = new ArrayList<Integer>(List.of(0));
        var line = new StringBuilder();
        var offset = 0;
        for (var c : n.getContent().getContent()) {
            offset += c.getNodeSize();
            if (c.getType() == HARD_BREAK) { lines.add(line.toString()); line.setLength(0); starts.add(offset); }
            else line.append(c.isText() ? c.getText() : "￼");
        }
        lines.add(line.toString());
        return new Block(start, n.getContent().getSize(), p.line(), p.prefix(), p.lines(), lines.toArray(String[]::new), starts.stream().mapToInt(Integer::intValue).toArray());
    }

    /** The Markdown text, as Lossless.write gives it. */
    public String text() { return text; }

    public int lineCount() { return lineStarts.length; }

    /** The line a document position is on. */
    public int line(int docPos) { return where(docPos)[0]; }

    /** The column a document position is at, within its line. */
    public int column(int docPos) { return where(docPos)[1]; }

    /** The offset into text() of a document position. */
    public int offset(int docPos) {
        var at = where(docPos);
        return lineStarts[at[0]] + at[1];
    }

    /** The first source line of the textblock whose content starts at this document position, or -1 when it was not written (an empty paragraph). */
    public int firstLine(int blockStart) {
        var k = indexAtOrBefore(blockStart);
        return k >= 0 && blocks.get(k).start() == blockStart ? blocks.get(k).line() : -1;
    }

    /** The document position nearest a source line and column; a line that holds no text (a blank line) gives the start of the next block. */
    public int docPosition(int line, int column) {
        if (blocks.isEmpty()) return 0;
        var k = lastBlockAtOrBeforeLine(line);
        if (k < 0) return blocks.getFirst().start();
        var b = blocks.get(k);
        var j = line - b.line();
        if (j >= b.lines().size()) return k + 1 < blocks.size() ? blocks.get(k + 1).start() : b.start() + b.size();
        var d = Math.min(j, b.docLines().length - 1);
        var from = d == 0 ? b.prefix().length() : 0;
        var offset = offsetAt(source(b, j), from, b.docLines()[d], column);
        return b.start() + b.docLineStarts()[d] + offset;
    }

    public int docPositionAtOffset(int offset) {
        var line = Arrays.binarySearch(lineStarts, offset);
        if (line < 0) line = -line - 2;
        line = Math.max(0, line);
        return docPosition(line, offset - lineStarts[line]);
    }

    /** {line, column} of a document position. */
    int[] where(int docPos) {
        if (blocks.isEmpty()) return new int[]{0, 0};
        var b = blocks.get(Math.max(0, indexAtOrBefore(docPos)));
        var offset = Math.max(0, Math.min(docPos - b.start(), b.size()));
        var d = Arrays.binarySearch(b.docLineStarts(), offset);
        if (d < 0) d = -d - 2;
        var j = Math.min(d, b.lines().size() - 1);
        var from = j == 0 ? b.prefix().length() : 0;
        return new int[]{b.line() + j, columnOf(source(b, j), from, b.docLines()[d], offset - b.docLineStarts()[d])};
    }

    static String source(Block b, int j) { return (j == 0 ? b.prefix() : "") + b.lines().get(j); }

    int indexAtOrBefore(int docPos) {
        var lo = 0;
        var hi = blocks.size() - 1;
        var found = -1;
        while (lo <= hi) {
            var mid = (lo + hi) >>> 1;
            if (blocks.get(mid).start() <= docPos) { found = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return found;
    }

    int lastBlockAtOrBeforeLine(int line) {
        var lo = 0;
        var hi = blocks.size() - 1;
        var found = -1;
        while (lo <= hi) {
            var mid = (lo + hi) >>> 1;
            if (blocks.get(mid).line() <= line) { found = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return found;
    }

    /** The column of document character offset in a source line: the source characters are matched against the document's, in order, from column from. */
    static int columnOf(String src, int from, String doc, int offset) {
        var j = 0;
        var after = from;
        for (var i = from; i < src.length(); i++) {
            if (j >= doc.length() || src.charAt(i) != doc.charAt(j)) continue;
            if (j == offset) return i;
            j++;
            after = i + 1;
        }
        return offset >= doc.length() ? after : src.length();
    }

    /** The document character offset at a source column: how many of the document's characters come before it. */
    static int offsetAt(String src, int from, String doc, int column) {
        var j = 0;
        for (var i = from; i < Math.min(column, src.length()); i++) if (j < doc.length() && src.charAt(i) == doc.charAt(j)) j++;
        return j;
    }
}
