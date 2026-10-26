package prosemirrorfx.markdown;

import com.atlassian.prosemirror.model.Fragment;
import com.atlassian.prosemirror.model.Mark;
import java.util.*;
import java.util.regex.Pattern;

import static prosemirrorfx.core.pm.Pm.*;
import static prosemirrorfx.core.schema.EditorSchema.*;

/**
 * Inline content as Markdown source, for text that was typed or edited. Escapes only what is needed: it writes
 * the text plainly, reads that back, and escapes every special character only if the reading differs.
 * A line that would start a heading, list or code fence gets a backslash.
 */
public final class InlineWriter {

    record Delim(String open, String close) {}

    static final Pattern HEAD = Pattern.compile("^#{1,6}([ \t]|$)"), ITEM = Pattern.compile("^([-+*]|\\d{1,9}[.)])([ \t]|$)"),
            FENCE = Pattern.compile("^(```|~~~)"), HR = Pattern.compile("^([-*_])([ \t]*\\1){2,}[ \t]*$");

    /** The lines of the block; plainFirst: the first line follows a list marker, where a heading or list marker is only text. */
    public static List<String> lines(Fragment f, boolean plainFirst) { return lines(f, plainFirst, false); }

    /** star: write emphasis with asterisks, as the text it came from did, rather than underscores. */
    public static List<String> lines(Fragment f, boolean plainFirst, boolean star) {
        var want = Sig.inline(f, true);
        var last = (List<String>) null;
        for (var escape : new boolean[]{false, true})
            for (var asterisks : new boolean[]{star, !star}) {
                last = protect(build(f, escape, asterisks), plainFirst);
                if (Sig.inline(InlineParser.parse(String.join("\n", last)), true).equals(want)) return last;
            }
        return last;
    }

    static List<String> build(Fragment f, boolean escapeAll, boolean star) {
        var sb = new StringBuilder();
        var open = new ArrayList<Delim>();
        var space = "";
        for (var c : f.getContent()) {
            if (!c.isText()) {
                closeAll(sb, open);
                sb.append(c.getType() == HARD_BREAK ? str(c, "raw") : "").append('\n');
                space = "";
                continue;
            }
            var text = c.getText();
            if (RAW.isInSet(c.getMarks()) != null) { closeAll(sb, open); sb.append(space).append(text); space = ""; continue; }
            var code = CODE.isInSet(c.getMarks()) != null;
            var core = code ? text : text.strip();
            var lead = code ? "" : text.substring(0, text.indexOf(core.isEmpty() ? text : core));
            var trail = core.isEmpty() || code ? "" : text.substring(lead.length() + core.length());
            if (core.isEmpty()) { space += text; continue; }
            var want = delimiters(c.getMarks(), star);
            var keep = 0;
            while (keep < open.size() && keep < want.size() && open.get(keep).equals(want.get(keep))) keep++;
            while (open.size() > keep) sb.append(open.removeLast().close());
            sb.append(space).append(lead);
            for (var k = keep; k < want.size(); k++) { sb.append(want.get(k).open()); open.add(want.get(k)); }
            sb.append(code ? span(core) : escapeAll ? escape(core) : core);
            space = trail;
        }
        closeAll(sb, open);
        return List.of(sb.append(space).toString().split("\n", -1));
    }

    static void closeAll(StringBuilder sb, List<Delim> open) {
        while (!open.isEmpty()) sb.append(open.removeLast().close());
    }

    static List<Delim> delimiters(List<Mark> marks, boolean star) {
        var d = new ArrayList<Delim>();
        marks.stream().filter(m -> m.getType() == LINK).findFirst().ifPresent(m -> d.add(new Delim("[", "](" + destination(m) + ")")));
        if (marks.stream().anyMatch(m -> m.getType() == STRONG)) d.add(new Delim("**", "**"));
        if (marks.stream().anyMatch(m -> m.getType() == EM)) d.add(star ? new Delim("*", "*") : new Delim("_", "_"));
        return d;
    }

    static String destination(Mark link) {
        var href = String.valueOf(link.getAttrs().get("href"));
        var title = link.getAttrs().get("title");
        var dest = href.matches(".*[\\s()<>].*") || href.isEmpty() ? "<" + href.replace("<", "\\<").replace(">", "\\>") + ">" : href;
        return title == null ? dest : dest + " \"" + title.toString().replace("\"", "\\\"") + "\"";
    }

    static String span(String text) {
        var run = Pattern.compile("`+").matcher(text).results().mapToInt(r -> r.end() - r.start()).max().orElse(0);
        var fence = "`".repeat(run + 1);
        var pad = text.startsWith("`") || text.endsWith("`") || (text.startsWith(" ") && text.endsWith(" ") && !text.isBlank());
        return fence + (pad ? " " + text + " " : text) + fence;
    }

    static String escape(String s) { return s.replaceAll("([\\\\*_`\\[\\]<&])", "\\\\$1"); }

    static List<String> protect(List<String> lines, boolean plainFirst) {
        var out = new ArrayList<String>();
        for (var k = 0; k < lines.size(); k++) out.add(protect(lines.get(k), k == 0 && plainFirst));
        return out;
    }

    static String protect(String line, boolean plain) {
        var t = line.stripLeading();
        if (FENCE.matcher(t).find()) return "\\" + line;
        if (plain) return line;
        if (HEAD.matcher(t).find()) return "\\" + line;
        var m = ITEM.matcher(t);
        if (!m.find() || HR.matcher(t).matches()) return line;
        var at = line.length() - t.length() + m.group(1).length() - (Character.isDigit(t.charAt(0)) ? 1 : m.group(1).length());
        return line.substring(0, at) + "\\" + line.substring(at);
    }

    private InlineWriter() {}
}
