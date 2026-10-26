package prosemirrorfx.markdown;

import com.atlassian.prosemirror.model.Mark;
import com.atlassian.prosemirror.model.Node;
import com.atlassian.prosemirror.model.Slice;
import java.util.*;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

import static prosemirrorfx.core.pm.Pm.*;
import static prosemirrorfx.core.schema.EditorSchema.*;

/**
 * Clipboard HTML in: what Word, Gmail, Google Docs and browsers put on the clipboard, mapped onto the
 * editor schema. Headings, paragraphs, bullet and numbered lists (nested), bold, italic and line breaks
 * survive; everything else keeps its text. Word's lists are paragraphs carrying "mso-list: l0 level2", with
 * the bullet as literal text, so they are regrouped into real lists here. Styles decide marks as well as
 * tags do: Google Docs wraps a whole copy in &lt;b style="font-weight:normal"&gt; and makes bold with a span.
 */
public final class Html {

    /** The blocks of a clipboard HTML string; empty when it carries no text. */
    public static List<Node> read(String html) {
        if (html == null) return List.of();
        // Windows "HTML Format" may arrive with its header (Version:0.9 StartHTML:...) still on.
        if (html.startsWith("Version:") && html.indexOf('<') > 0) html = html.substring(html.indexOf('<'));
        var reader = new Reader(List.of());
        reader.children(Jsoup.parse(html).body());
        return reader.finish();
    }

    /**
     * The blocks as a slice to paste. A single textblock goes in as text on the current line; otherwise
     * the first and last paragraphs join the lines they land on, while headings and lists keep their shape.
     */
    public static Slice pasteSlice(List<Node> blocks) {
        var first = blocks.getFirst();
        var last = blocks.getLast();
        if (blocks.size() == 1 && first.isTextblock()) return slice(frag(first), 1, 1);
        return slice(frag(blocks.toArray(Node[]::new)), first.getType() == PARAGRAPH ? 1 : 0, last.getType() == PARAGRAPH ? 1 : 0);
    }

    static final Set<String> SKIP = Set.of("head", "style", "script", "title", "meta", "link", "template", "noscript",
            "img", "svg", "button", "input", "select", "textarea", "iframe", "object", "o:p");
    static final Set<String> BLOCKS = Set.of("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre",
            "section", "article", "header", "footer", "main", "aside", "nav", "table", "thead", "tbody", "tfoot", "tr",
            "td", "th", "caption", "dl", "dt", "dd", "figure", "figcaption", "address", "hr", "center", "body");
    static final Pattern MSO_LEVEL = Pattern.compile("mso-list:\\s*l\\d+\\s+level(\\d+)");
    static final Pattern ORDERED_MARKER = Pattern.compile("^\\s*[0-9a-zA-Z]{1,5}[.)]");
    // Anchored at a declaration start: Word puts mso-bidi-font-weight:normal on its own bold, which is not font-weight.
    static final Pattern WEIGHT = Pattern.compile("(?:^|;)\\s*font-weight:\\s*([a-z0-9]+)");
    static final Pattern FONT_STYLE = Pattern.compile("(?:^|;)\\s*font-style:\\s*([a-z]+)");

    /** Blocks found at one level; level 0 is ordinary content, 1+ is the content of a list item at that depth. */
    record Entry(List<Node> blocks, int level, boolean ordered) {}

    static final class Reader {
        final List<Mark> outerMarks;
        final List<Entry> entries = new ArrayList<>();
        List<Node> inline = new ArrayList<>();
        int heading, listLevel;
        boolean ordered, pre;

        Reader(List<Mark> outerMarks) { this.outerMarks = outerMarks; }

        void children(Element e) { children(e, outerMarks); }

        void children(Element e, List<Mark> marks) {
            for (var child : e.childNodes()) {
                if (child instanceof TextNode t) text(t.getWholeText(), marks);
                else if (child instanceof Element el) element(el, marks);
            }
        }

        void element(Element e, List<Mark> marks) {
            var tag = e.normalName();
            var style = e.attr("style").toLowerCase(Locale.ROOT);
            if (SKIP.contains(tag) || style.replace(" ", "").contains("mso-list:ignore") || style.replace(" ", "").contains("display:none")) return;
            marks = marks(tag, style, marks);
            switch (tag) {
                case "br" -> inline.add(HARD_BREAK.create(null, EMPTY, null));
                case "ul", "ol" -> {
                    flush();
                    entries.add(new Entry(List.of(list(e, tag.equals("ol"), marks)), 0, false));
                }
                case "li" -> {
                    // an item without its list around it, as browsers copy a few bullets
                    flush();
                    entries.add(new Entry(itemContent(e, marks), 1, e.parent() != null && e.parent().normalName().equals("ol")));
                }
                default -> {
                    if (!BLOCKS.contains(tag)) { children(e, marks); return; }
                    flush();
                    int savedHeading = heading, savedLevel = listLevel;
                    boolean savedOrdered = ordered, savedPre = pre;
                    if (tag.matches("h[1-6]")) heading = tag.charAt(1) - '0';
                    if (e.hasClass("MsoTitle")) heading = 1;
                    if (tag.equals("pre")) pre = true;
                    var mso = MSO_LEVEL.matcher(style);
                    if (mso.find()) {
                        listLevel = Integer.parseInt(mso.group(1));
                        ordered = ORDERED_MARKER.matcher(wordMarker(e)).find();
                    }
                    children(e, marks);
                    flush();
                    heading = savedHeading;
                    listLevel = savedLevel;
                    ordered = savedOrdered;
                    pre = savedPre;
                }
            }
        }

        void text(String raw, List<Mark> marks) {
            if (pre) {
                var lines = raw.split("\r?\n", -1);
                for (var k = 0; k < lines.length; k++) {
                    if (k > 0) flush();
                    add(lines[k].replace('\t', ' '), marks);
                }
                return;
            }
            var text = raw.replaceAll("[ \\t\\n\\r\\f\\u00a0]+", " ");
            if (text.startsWith(" ") && (inline.isEmpty() || endsWithSpace(inline.getLast()))) text = text.substring(1);
            add(text, marks);
        }

        void add(String text, List<Mark> marks) {
            if (text.isEmpty()) return;
            var last = inline.isEmpty() ? null : inline.getLast();
            if (last != null && last.isText() && Mark.Companion.sameSet(last.getMarks(), marks))
                inline.set(inline.size() - 1, SCHEMA.text(last.getText() + text, marks));
            else inline.add(SCHEMA.text(text, marks));
        }

        static boolean endsWithSpace(Node n) {
            return n.getType() == HARD_BREAK || (n.isText() && n.getText().endsWith(" "));
        }

        /** The inline content collected so far becomes one textblock (dropped if it has no text). */
        void flush() {
            while (!inline.isEmpty() && inline.getLast().getType() == HARD_BREAK) inline.removeLast();
            if (!inline.isEmpty() && inline.getLast().isText()) {
                var last = inline.getLast();
                var t = last.getText().stripTrailing();
                if (t.isEmpty()) inline.removeLast(); else inline.set(inline.size() - 1, SCHEMA.text(t, last.getMarks()));
            }
            var hasText = inline.stream().anyMatch(n -> n.isText() && !n.getText().isBlank());
            if (hasText) {
                // Headings are bold already; Google Docs marks their text font-weight:700, which is not emphasis.
                if (heading > 0) inline.replaceAll(n -> n.isText() ? SCHEMA.text(n.getText(), without(n.getMarks(), STRONG)) : n);
                var content = frag(inline.toArray(Node[]::new));
                var block = heading > 0 ? create(HEADING, Map.of("level", heading), content) : create(PARAGRAPH, content);
                entries.add(new Entry(List.of(block), listLevel, ordered));
            }
            inline = new ArrayList<>();
        }

        Node list(Element e, boolean ordered, List<Mark> marks) {
            var items = new ArrayList<List<Node>>();
            for (var child : e.children()) {
                var tag = child.normalName();
                if (tag.equals("ul") || tag.equals("ol")) {
                    // Google Docs nests a list as a sibling of the items, not inside the item above it
                    if (items.isEmpty()) items.add(new ArrayList<>());
                    items.getLast().add(list(child, tag.equals("ol"), marks));
                } else if (!SKIP.contains(tag)) {
                    items.add(new ArrayList<>(itemContent(child, marks)));
                }
            }
            var nodes = items.stream().map(Html::item).toArray(Node[]::new);
            if (nodes.length == 0) nodes = new Node[] {item(List.of())};
            var start = ordered ? parseStart(e.attr("start")) : 1;
            return start != 1 ? create(ORDERED_LIST, Map.of("order", start), frag(nodes)) : create(ordered ? ORDERED_LIST : BULLET_LIST, frag(nodes));
        }

        List<Node> itemContent(Element li, List<Mark> marks) {
            var sub = new Reader(marks);
            sub.children(li);
            return sub.finish();
        }

        List<Node> finish() {
            flush();
            var out = new ArrayList<Node>();
            var i = 0;
            while (i < entries.size()) {
                var entry = entries.get(i);
                if (entry.level == 0) { out.addAll(entry.blocks); i++; continue; }
                var at = new int[] {i};
                out.add(group(entries, at, entry.level));
                i = at[0];
            }
            return out;
        }
    }

    /** Consecutive list entries become one list; deeper ones nest in the item above, a change of kind starts a new list. */
    static Node group(List<Entry> entries, int[] at, int level) {
        var ordered = entries.get(at[0]).ordered;
        var items = new ArrayList<List<Node>>();
        while (at[0] < entries.size()) {
            var e = entries.get(at[0]);
            if (e.level < level || e.level == 0) break;
            if (e.level > level) {
                if (items.isEmpty()) items.add(new ArrayList<>());
                items.getLast().add(group(entries, at, e.level));
                continue;
            }
            if (e.ordered != ordered && !items.isEmpty()) break;
            items.add(new ArrayList<>(e.blocks));
            at[0]++;
        }
        return create(ordered ? ORDERED_LIST : BULLET_LIST, frag(items.stream().map(Html::item).toArray(Node[]::new)));
    }

    /** A list item must start with a paragraph: a heading becomes one, anything else gets an empty one in front. */
    static Node item(List<Node> content) {
        var c = new ArrayList<>(content);
        if (!c.isEmpty() && c.getFirst().getType() == HEADING) c.set(0, create(PARAGRAPH, c.getFirst().getContent()));
        if (c.isEmpty() || c.getFirst().getType() != PARAGRAPH) c.addFirst(create(PARAGRAPH, EMPTY));
        return create(LIST_ITEM, frag(c.toArray(Node[]::new)));
    }

    static List<Mark> marks(String tag, String style, List<Mark> marks) {
        if (tag.equals("b") || tag.equals("strong")) marks = STRONG.create(null).addToSet(marks);
        if (tag.equals("i") || tag.equals("em") || tag.equals("cite") || tag.equals("dfn")) marks = EM.create(null).addToSet(marks);
        var w = WEIGHT.matcher(style);
        if (w.find()) {
            var v = w.group(1);
            var bold = v.equals("bold") || v.equals("bolder") || (v.matches("\\d+") && Integer.parseInt(v) >= 600);
            marks = bold ? STRONG.create(null).addToSet(marks) : without(marks, STRONG);
        }
        var s = FONT_STYLE.matcher(style);
        if (s.find()) {
            var italic = s.group(1).equals("italic") || s.group(1).equals("oblique");
            marks = italic ? EM.create(null).addToSet(marks) : without(marks, EM);
        }
        return marks;
    }

    /** MarkType.removeFromSet is broken in prosemirror-kotlin 1.1.21 (see Pm.removeStoredMark). */
    static List<Mark> without(List<Mark> marks, com.atlassian.prosemirror.model.MarkType type) {
        return marks.stream().filter(m -> m.getType() != type).toList();
    }

    /** The literal bullet or number Word writes in front of a list paragraph ("·", "1.", "a)"). */
    static String wordMarker(Element p) {
        for (var el : p.getAllElements())
            if (el.attr("style").toLowerCase(Locale.ROOT).replace(" ", "").contains("mso-list:ignore")) return el.text();
        return "";
    }

    static int parseStart(String s) {
        try { return Math.max(1, Integer.parseInt(s.strip())); } catch (NumberFormatException e) { return 1; }
    }

    private Html() {}
}
