package prosemirrorfx.markdown;

import com.atlassian.prosemirror.model.Node;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static prosemirrorfx.core.pm.Pm.EMPTY;
import static prosemirrorfx.core.schema.Build.*;
import static prosemirrorfx.core.schema.EditorSchema.*;

/** Clipboard HTML as the common sources write it, cut down to the parts that matter. */
class HtmlTest {

    @Test void wordWithItsParagraphLists() {
        var html = """
                Version:0.9
                StartHTML:0000000105
                <html xmlns:o="urn:schemas-microsoft-com:office:office"><head><style>p.MsoNormal{margin:0in;}</style></head>
                <body lang=EN-US><!--StartFragment-->
                <h1>Report<o:p></o:p></h1>
                <p class=MsoNormal>Some <b>bold</b> and <i>italic</i>
                text.<o:p></o:p></p>
                <p class=MsoListParagraphCxSpFirst style='text-indent:-.25in;mso-list:l0 level1 lfo1'><![if !supportLists]><span
                style='font-family:Symbol'><span style='mso-list:Ignore'>·<span style='font:7.0pt "Times New Roman"'>&nbsp;&nbsp;&nbsp;
                </span></span></span><![endif]>First bullet<o:p></o:p></p>
                <p class=MsoListParagraphCxSpMiddle style='margin-left:1.0in;mso-list:l0 level2 lfo1'><![if !supportLists]><span
                style='font-family:"Courier New"'><span style='mso-list:Ignore'>o<span style='font:7.0pt "Times New Roman"'>&nbsp;&nbsp;
                </span></span></span><![endif]>Nested<o:p></o:p></p>
                <p class=MsoListParagraphCxSpLast style='mso-list:l0 level1 lfo1'><![if !supportLists]><span style='mso-list:Ignore'>·<span>&nbsp;
                </span></span><![endif]>Second bullet<o:p></o:p></p>
                <p class=MsoNormal><o:p>&nbsp;</o:p></p>
                <p class=MsoListParagraph style='mso-list:l1 level1 lfo2'><![if !supportLists]><span style='mso-list:Ignore'>1.<span>&nbsp;&nbsp;
                </span></span><![endif]>Step one<o:p></o:p></p>
                <p class=MsoListParagraph style='mso-list:l1 level1 lfo2'><![if !supportLists]><span style='mso-list:Ignore'>2.<span>&nbsp;&nbsp;
                </span></span><![endif]>Step two<o:p></o:p></p>
                <!--EndFragment--></body></html>""";
        assertBlocks(html,
                h(1, "Report"),
                p("Some ", b("bold"), " and ", i("italic"), " text."),
                ul(li(p("First bullet"), ul(li(p("Nested")))), li(p("Second bullet"))),
                ol(li(p("Step one")), li(p("Step two"))));
    }

    @Test void googleDocsBoldByStyleInsideANormalWeightWrapper() {
        var html = """
                <meta charset="utf-8"><b style="font-weight:normal;" id="docs-internal-guid-1a2b"><h2 dir="ltr" style="line-height:1.38;">\
                <span style="font-size:16pt;font-weight:400;">Agenda</span></h2><p dir="ltr"><span style="font-weight:400;">Plain </span>\
                <span style="font-weight:700;">bold</span><span style="font-weight:400;font-style:italic;"> slanted</span></p>\
                <ul><li dir="ltr" aria-level="1"><p dir="ltr" role="presentation"><span>One</span></p></li>\
                <ul><li aria-level="2"><p><span>Inner</span></p></li></ul>\
                <li aria-level="1"><p><span>Two</span></p></li></ul><br></b>""";
        assertBlocks(html,
                h(2, "Agenda"),
                p("Plain ", b("bold"), i(" slanted")),
                ul(li(p("One"), ul(li(p("Inner")))), li(p("Two"))));
    }

    @Test void gmailDivsAndBreaks() {
        var html = """
                <div dir="ltr"><div>Hello team,</div><div><br></div><div>Please see <b>this</b>:</div>\
                <ul><li>first</li><li>second</li></ul><div>Line one<br>Line two</div></div>""";
        assertBlocks(html,
                p("Hello team,"),
                p("Please see ", b("this"), ":"),
                ul(li(p("first")), li(p("second"))),
                p("Line one", HARD_BREAK.create(null, EMPTY, null), "Line two"));
    }

    @Test void bulletsCopiedWithoutTheirList() {
        assertBlocks("<li>alpha</li><li>beta</li>", ul(li(p("alpha")), li(p("beta"))));
    }

    @Test void numberedListKeepsItsStart() {
        assertBlocks("<ol start=\"3\"><li>three</li><li>four</li></ol>",
                ORDERED_LIST.create(Map.of("order", 3), ol(li(p("three")), li(p("four"))).getContent(), null));
    }

    @Test void whitespaceCollapsesAsInABrowser() {
        assertBlocks("<p>  a\n   <b>b</b>  c&nbsp;\n</p>", p("a ", b("b"), " c"));
    }

    @Test void inlineOnlyFragmentIsOneParagraph() {
        assertBlocks("<span style=\"font-weight:bold\">just</span> words", p(b("just"), " words"));
    }

    @Test void nothingButMarkupIsEmpty() {
        assertTrue(Html.read("<p><br></p><div>&nbsp;</div><img src=x>").isEmpty());
    }

    static void assertBlocks(String html, Node... expected) {
        assertEquals(doc(expected).toJSON().toString(), doc(Html.read(html).toArray(Node[]::new)).toJSON().toString());
    }
}
