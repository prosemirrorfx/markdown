package prosemirrorfx.markdown;

import com.atlassian.prosemirror.model.Node;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SourceMapTest {

    /** Every position a caret can be at: inside a textblock, from its start to its end. */
    static List<Integer> caretPositions(Node doc) {
        var out = new ArrayList<Integer>();
        doc.descendants((n, pos, parent, i) -> {
            if (!n.isTextblock()) return true;
            for (var k = 0; k <= n.getContent().getSize(); k++) out.add(pos + 1 + k);
            return false;
        });
        return out;
    }

    @ParameterizedTest
    @ValueSource(strings = {"thread.md", "outline.md", "mixed.md", "scripts.md"})
    void theTextIsWhatLosslessWrites(String name) {
        var md = LosslessTest.fixture(name);
        assertEquals(md, SourceMap.of(Lossless.read(md)).text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"thread.md", "outline.md", "mixed.md", "scripts.md"})
    void everyCaretPositionMapsToALineAndColumnAndBackToItself(String name) {
        var doc = Lossless.read(LosslessTest.fixture(name));
        var map = SourceMap.of(doc);
        var wrong = new ArrayList<String>();
        for (var p : caretPositions(doc)) {
            var back = map.docPosition(map.line(p), map.column(p));
            if (back != p) wrong.add(p + " -> line " + map.line(p) + " col " + map.column(p) + " -> " + back);
        }
        assertTrue(wrong.isEmpty(), name + ": " + wrong.stream().limit(8).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"thread.md", "outline.md", "mixed.md", "scripts.md"})
    void everyCaretPositionMapsToAnOffsetThatMapsBack(String name) {
        var doc = Lossless.read(LosslessTest.fixture(name));
        var map = SourceMap.of(doc);
        var wrong = new ArrayList<String>();
        for (var p : caretPositions(doc)) {
            var back = map.docPositionAtOffset(map.offset(p));
            if (back != p) wrong.add(p + " -> offset " + map.offset(p) + " -> " + back);
        }
        assertTrue(wrong.isEmpty(), name + ": " + wrong.stream().limit(8).toList());
    }

    @Test void aHeadingIsOnItsLineAfterTheHashes() {
        var map = SourceMap.of(Lossless.read("# One\n\nsecond paragraph\n"));
        assertEquals(0, map.line(1));
        assertEquals(2, map.column(1), "the caret before O is after the # and its space");
        assertEquals(2, map.line(8), "the paragraph is on line 2, past the blank line");
    }

    @Test void aListItemsTextStartsAfterItsMarkerAndNestingCountsItsIndent() {
        var doc = Lossless.read("- first\n\t- nested one\n- last\n");
        var map = SourceMap.of(doc);
        var nested = caretPositions(doc).stream().filter(p -> map.line(p) == 1).findFirst().orElseThrow();
        assertEquals(3, map.column(nested), "a tab, the marker and its space come before the text");
        assertEquals(2, map.column(caretPositions(doc).getFirst()));
    }

    @Test void theColumnSkipsMarkupTheDocumentDoesNotHave() {
        var doc = Lossless.read("some **bold** and `code` text\n");
        var map = SourceMap.of(doc);
        var inBold = SourceMapTest.caretPositions(doc).get(5);
        assertEquals("some ".length() + 2, map.column(inBold), "before the b of bold, past the two asterisks");
        assertEquals(inBold, map.docPosition(0, map.column(inBold)));
    }

    @Test void theOffsetIndexesTheCharacterAtThatPosition() {
        var md = "alpha\n\n- beta gamma\n";
        var doc = Lossless.read(md);
        var map = SourceMap.of(doc);
        var gamma = caretPositions(doc).stream().filter(p -> map.offset(p) == md.indexOf("gamma")).findFirst().orElseThrow();
        assertEquals(md.indexOf("gamma"), map.offset(gamma));
        assertEquals('g', md.charAt(map.offset(gamma)));
    }

    @Test void aBlankLineGivesTheStartOfTheNextBlock() {
        var doc = Lossless.read("one\n\n\ntwo\n");
        var map = SourceMap.of(doc);
        var two = caretPositions(doc).stream().filter(p -> map.line(p) == 3).findFirst().orElseThrow();
        assertEquals(two, map.docPosition(1, 0));
        assertEquals(two, map.docPosition(2, 0));
    }

    @Test void aLineBeyondTheEndGivesTheEndOfTheLastBlock() {
        var doc = Lossless.read("one\ntwo\n");
        var map = SourceMap.of(doc);
        var last = caretPositions(doc).getLast();
        assertEquals(last, map.docPosition(50, 0));
    }

    @Test void aParagraphThatWrapsInTheSourceHasALineForEachSourceLine() {
        var doc = Lossless.read("first line\nsecond line\nthird line\n\nafter\n");
        var map = SourceMap.of(doc);
        var second = caretPositions(doc).stream().filter(p -> map.line(p) == 1).findFirst().orElseThrow();
        assertEquals(0, map.column(second));
        assertEquals(6, map.lineCount(), "five lines of text and the empty one after the final newline");
        var third = caretPositions(doc).stream().filter(p -> map.line(p) == 2).findFirst().orElseThrow();
        assertEquals(0, map.column(third), "the third source line starts at its own column 0");
    }

    @Test void firstLineIsTheLineOfAnyTextblocksFirstSourceLine() {
        var doc = Lossless.read("# Title\n\ntext\n\n- item\n");
        var map = SourceMap.of(doc);
        assertEquals(0, map.firstLine(1));
        assertEquals(2, map.firstLine(map.docPosition(2, 0)));
        assertEquals(4, map.firstLine(map.docPosition(4, 2)));
        assertEquals(-1, map.firstLine(9999));
    }

    @Test void afterAnEditTheMapDescribesTheEditedText() {
        var m = LosslessTest.after("alpha\n\nbeta\n", "alpha");
        m.type(" and more");
        var map = SourceMap.of(m.state().getDoc());
        assertEquals(Lossless.write(m.state().getDoc()), map.text());
        assertEquals("alpha and more\n\nbeta\n", map.text());
        assertEquals(0, map.line(m.state().getSelection().getHead()));
        assertEquals(14, map.column(m.state().getSelection().getHead()));
    }

    @Test void aLongNoteIsMappedInReasonableTime() {
        var md = new StringBuilder();
        for (var k = 0; k < 4000; k++) md.append(k % 10 == 0 ? "## Heading " + k + "\n\n" : "- item number " + k + " with *some* text\n");
        var doc = Lossless.read(md.toString());
        SourceMap.of(doc);
        var t0 = System.nanoTime();
        var map = SourceMap.of(doc);
        var ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("SourceMap of 4000 blocks: " + ms + " ms");
        assertEquals(md.toString(), map.text());
        assertTrue(ms < 3000, "took " + ms + " ms");
    }

    @Test void aByteOrderMarkAndCrlfAreCountedInTheOffsets() {
        var md = "﻿one\r\n\r\ntwo\r\n";
        var doc = Lossless.read(md);
        var map = SourceMap.of(doc);
        assertEquals(md, map.text());
        var two = caretPositions(doc).stream().filter(p -> map.line(p) == 2).findFirst().orElseThrow();
        assertEquals(md.indexOf("two"), map.offset(two));
        assertEquals(two, map.docPositionAtOffset(md.indexOf("two")));
    }
}
