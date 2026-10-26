package prosemirrorfx.markdown;

import com.atlassian.prosemirror.model.Node;
import org.junit.jupiter.api.Test;
import prosemirrorfx.core.schema.EditorSchema;

import static org.junit.jupiter.api.Assertions.*;
import static prosemirrorfx.core.schema.Build.*;

class MarkdownTest {


    @Test void writesTheSchema() {
        var md = Markdown.write(doc(
                h(1, "Title"),
                p("Plain ", b("bold"), " and ", i("italic"), " text."),
                ul(li(p("one")), li(p("two"), ul(li(p("nested"))))),
                ol(li(p("first")), li(p("second")))));
        assertEquals("""
                # Title

                Plain **bold** and _italic_ text.

                - one
                - two

                  - nested

                1. first
                2. second
                """, md);
    }

    @Test void roundTripsAwkwardText() {
        roundTrip(doc(
                p("- not a bullet"),
                p("1. not a number"),
                p("# not a heading"),
                p("stars * and _underscores_ and [brackets] stay text"),
                p(b("bold"), " after a mark"),
                p(b("both "), EditorSchema.SCHEMA.text("nested", java.util.List.of(EditorSchema.STRONG.create(null), EditorSchema.EM.create(null))), " marks"),
                h(4, "Level four"),
                EditorSchema.ORDERED_LIST.create(java.util.Map.of("order", 10), ol(li(p("from ten"))).getContent(), null)));
    }

    @Test void readsWhatOtherToolsWrite() {
        var d = Markdown.read("""
                Intro line
                continues here.

                * star bullet
                * **strong** and *em*

                > a quote

                ```
                some code
                ```
                """);
        assertSameDoc(doc(
                p("Intro line continues here."),
                ul(li(p("star bullet")), li(p(b("strong"), " and ", i("em")))),
                p("a quote"),
                p("some code")), d);
    }

    @Test void spaceAtAMarksEdgeMovesOutsideIt() {
        assertEquals("**bold** after\n", Markdown.write(doc(p(b("bold "), "after"))));
    }

    @Test void emptyMarkdownIsAnEmptyParagraph() {
        assertSameDoc(doc(p()), Markdown.read(""));
    }

    static void roundTrip(Node d) {
        var md = Markdown.write(d);
        var back = Markdown.read(md);
        var expected = Markdown.read(Markdown.write(back));
        assertSameDoc(stripEmpty(d), back, md);
        assertSameDoc(back, expected, "stable on a second pass");
    }

    static void assertSameDoc(Node expected, Node actual) { assertSameDoc(expected, actual, ""); }

    static void assertSameDoc(Node expected, Node actual, String context) {
        assertEquals(expected.toJSON().toString(), actual.toJSON().toString(), context);
    }

    static Node stripEmpty(Node d) {
        var kids = new java.util.ArrayList<Node>();
        for (var c : d.getContent().getContent()) if (!(c.isTextblock() && c.getContent().getSize() == 0)) kids.add(c);
        return prosemirrorfx.core.pm.Pm.copy(d, prosemirrorfx.core.pm.Pm.frag(kids.toArray(Node[]::new)));
    }
}
