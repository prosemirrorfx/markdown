package prosemirrorfx.markdown;

import com.atlassian.prosemirror.model.Node;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static prosemirrorfx.core.schema.Build.doc;

/**
 * Clipboard HTML captured from the real applications (resources/paste/NAME.html, the fragment exactly as JavaFX
 * hands it to the editor on Windows) against the Markdown the paste must produce (NAME.md).
 * Captured 2026-10-09: Word 365 via COM copy; Chromium (Edge) copying a news-style article and a Gmail-style message;
 * Google Docs (the article pasted into a new doc, then select all + copy in Docs).
 */
class RealPasteTest {

    @ParameterizedTest
    @ValueSource(strings = {"word", "chromium-article", "chromium-gmail", "gdocs"})
    void pastesAsTheSourceShowedIt(String name) throws Exception {
        var blocks = Html.read(resource(name + ".html"));
        assertEquals(resource(name + ".md").strip(), Markdown.write(doc(blocks.toArray(Node[]::new))).strip());
    }

    static String resource(String name) throws Exception {
        try (var in = RealPasteTest.class.getResourceAsStream("/paste/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
