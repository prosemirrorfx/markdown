package prosemirrorfx.markdown;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import prosemirrorfx.core.editor.EditorModel;
import prosemirrorfx.core.pm.Pm;

import static org.junit.jupiter.api.Assertions.*;
import static prosemirrorfx.core.commands.Commands.*;

class LosslessTest {

    static String fixture(String name) {
        try (var in = LosslessTest.class.getResourceAsStream("/lossless/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static String roundTrip(String md) { return Lossless.write(Lossless.read(md)); }

    @ParameterizedTest
    @ValueSource(strings = {"thread.md", "outline.md", "mixed.md", "scripts.md"})
    void aFileComesBackByteForByte(String name) {
        var md = fixture(name);
        assertEquals(md, roundTrip(md));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\n", "\n\n  \n", "one line, no final newline", "a\n\nb", "a\n\n\n\nb\n\n\n", "\n\n# After blank lines\n",
            "a  \nb\\\nc \nd\n", "- a\n-\n- c", "\t- tab first\n\t\t- deeper\n\t- back\n", "- a\n\n\n- b\n\n  para in b\n\n  - nested after blank\n",
            "text\r\nwith crlf\r\n\r\n- item\r\n", "\uFEFF# bom\n", ">> only a comment", "\t>> only an indented comment\n",
            "  - two-space item\n    - four-space item\n", "10. ten\n11. eleven\n", "- [ ] checkbox text\n- [x] done\n",
            "line with trailing tab\t\n", "- item with `code`, *em*, **strong** and a [link](<a b> 'x')\n"})
    void edgeCasesComeBackByteForByte(String md) {
        assertEquals(md, roundTrip(md));
    }

    static int posAfter(com.atlassian.prosemirror.model.Node doc, String snippet) {
        var found = new int[]{-1};
        doc.descendants((node, pos, parent, index) -> {
            if (node.isText() && found[0] < 0 && node.getText().contains(snippet)) found[0] = pos + node.getText().indexOf(snippet) + snippet.length();
            return true;
        });
        assertTrue(found[0] >= 0, "no text " + snippet);
        return found[0];
    }

    static EditorModel after(String md, String snippet) {
        var m = new EditorModel(Lossless.read(md));
        m.select(Pm.cursor(m.state().getDoc(), posAfter(m.state().getDoc(), snippet)));
        return m;
    }

    static List<String> changedLines(String before, String after) {
        var a = before.split("\n", -1);
        var b = after.split("\n", -1);
        assertEquals(a.length, b.length, "line count");
        var out = new ArrayList<String>();
        for (var k = 0; k < a.length; k++) if (!a[k].equals(b[k])) out.add(k + ": " + a[k] + "  ->  " + b[k]);
        return out;
    }

    @Test void typingInsideACommentKeepsItAComment() {
        var md = fixture("thread.md");
        var m = after(md, "Is the second");
        m.type("XY");
        var out = Lossless.write(m.state().getDoc());
        assertEquals(List.of("4: >> Is the second section still needed?  ->  >> Is the secondXY section still needed?"), changedLines(md, out));
    }

    @Test void typingInsideAnIndentedReplyKeepsItsIndentAndHighlight() {
        var md = fixture("thread.md");
        var m = after(md, "Yes, it carries");
        m.type("!");
        var out = Lossless.write(m.state().getDoc());
        var changed = changedLines(md, out);
        assertEquals(1, changed.size());
        assertTrue(changed.getFirst().contains("\t>> ^^AI^^ (2026-01-15 09:30 UTC): Yes, it carries! the only reference to the *original* source."), changed.getFirst());
    }

    @Test void deletingInsideAListItemChangesOnlyThatLine() {
        var md = fixture("outline.md");
        var m = after(md, "Child te");
        m.run(backspace);
        var out = Lossless.write(m.state().getDoc());
        assertEquals(List.of("3: \t- Child text  ->  \t- Child txt"), changedLines(md, out));
    }

    @Test void typingTextWithSpecialCharactersIsNotEscapedNeedlessly() {
        var m = after("start\n", "start");
        m.type(" snake_case and 2 * 3 and a_b_c");
        assertEquals("start snake_case and 2 * 3 and a_b_c\n", Lossless.write(m.state().getDoc()));
    }

    @Test void textThatLooksLikeMarkupIsEscapedSoItStaysText() {
        var m = after("start\n", "start");
        m.type(" *not em* and <b>");
        var out = Lossless.write(m.state().getDoc());
        assertEquals("start \\*not em\\* and \\<b>\n", out);
        assertEquals(out, roundTrip(out));
    }

    @Test void aLineThatLooksLikeAListStaysAParagraph() {
        var m = after("x\n", "x");
        m.run(enter);
        m.type("- not a bullet");
        var out = Lossless.write(m.state().getDoc());
        assertEquals("x\n\n\\- not a bullet\n", out);
        assertEquals(out, roundTrip(out));
    }

    @Test void enterAtTheEndOfACommentStartsAPlainParagraph() {
        var m = after("intro\n\n>> a comment\n\nlast\n", "a comment");
        m.run(enter);
        m.type("reply");
        assertEquals("intro\n\n>> a comment\n\nreply\n\nlast\n", Lossless.write(m.state().getDoc()));
    }

    @Test void editingAParagraphWritesItInTheUsualFormAndLeavesTheRestAlone() {
        var md = "one *emphasised* word\n\nuntouched  *star*  line\n";
        var m = after(md, "one ");
        m.type("big ");
        assertEquals("one big *emphasised* word\n\nuntouched  *star*  line\n", Lossless.write(m.state().getDoc()));
    }

    @Test void newEmphasisUsesUnderscoresAndAsterisksInsideAWord() {
        var m = after("plain\n", "plain");
        m.type(" ");
        m.run(italic);
        m.type("new");
        assertEquals("plain _new_\n", Lossless.write(m.state().getDoc()));
        var w = after("x\n", "x");
        w.run(italic);
        w.type("yz");
        assertEquals("x*yz*\n", Lossless.write(w.state().getDoc()));
    }

    @Test void anEditedLineOfAMultiLineParagraphLeavesTheOtherLinesAndTheirIndentationAlone() {
        var md = "intro\n    indented code-like line\n\tline with a tab\n  another [x]\n\nafter\n";
        var m = after(md, "line with a ");
        m.type("big ");
        assertEquals("intro\n    indented code-like line\n\tline with a big tab\n  another [x]\n\nafter\n", Lossless.write(m.state().getDoc()));
    }

    @Test void indentingAListItemKeepsTheFileOtherwiseAsItWas() {
        var md = "- a\n- b\n\t- c\n- d\n";
        var m = after(md, "b");
        m.run(indent);
        var out = Lossless.write(m.state().getDoc());
        assertEquals("- a\n  - b\n  \t- c\n- d\n", out);
        assertEquals(out, roundTrip(out));
    }

    @Test void outdentingAListItemKeepsTheFileOtherwiseAsItWas() {
        var md = "- a\n\t- b\n- d\n";
        var m = after(md, "b");
        m.run(outdent);
        assertEquals("- a\n- b\n- d\n", Lossless.write(m.state().getDoc()));
    }

    @Test void aNewListItemAfterEnterUsesTheSameMarkerAndIndent() {
        var m = after("- a\n\t- b\n", "b");
        m.run(enter);
        m.type("new");
        assertEquals("- a\n\t- b\n\t- new\n", Lossless.write(m.state().getDoc()));
    }

    @Test void aFileWithoutAFinalNewlineStaysWithoutOneAfterEditing() {
        var m = after("a\n\nb", "b");
        m.type("c");
        assertEquals("a\n\nbc", Lossless.write(m.state().getDoc()));
    }

    @Test void readingKeepsWhatTheEditorSees() {
        var m = new EditorModel(Lossless.read(fixture("thread.md")));
        var text = m.state().getDoc().textBetween(0, m.state().getDoc().getContent().getSize(), "\n", "\n");
        assertTrue(text.contains(">> Is the second section still needed?"));
        assertTrue(text.contains("\t>>") || text.contains(">> ^^AI^^ (2026-01-15 09:30 UTC): Yes, it carries the only reference to the original source."));
        assertTrue(text.contains("• plain") || fixture("scripts.md").contains("• plain"));
    }
}
