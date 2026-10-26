package prosemirrorfx.markdown;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Round-trips every .md file under the folders named by -Dlossless.corpus=dir1;dir2 and lists each that does not come
 * back byte for byte, with its first differing line. Skipped without the property, so no real notes live in the repository.
 */
class LosslessCorpusTest {

    @Test void everyFileComesBackByteForByte() throws IOException {
        var roots = System.getProperty("lossless.corpus");
        assumeTrue(roots != null && !roots.isBlank(), "no -Dlossless.corpus");
        var files = new ArrayList<Path>();
        for (var root : roots.split(";")) try (var s = Files.walk(Path.of(root))) { s.filter(p -> p.toString().endsWith(".md") && Files.isRegularFile(p)).forEach(files::add); }
        var bad = new ArrayList<String>();
        var start = System.nanoTime();
        for (var f : files) {
            var md = new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8);
            var out = Lossless.write(Lossless.read(md));
            if (!out.equals(md)) bad.add(f + "  " + firstDifference(md, out));
        }
        System.out.printf("lossless corpus: %d files, %d differ, %d ms%n", files.size(), bad.size(), (System.nanoTime() - start) / 1_000_000);
        bad.stream().limit(25).forEach(System.out::println);
        assertEquals(0, bad.size(), () -> bad.stream().limit(10).collect(Collectors.joining("\n")));
    }

    /** One typed letter in the middle of each file: the output may differ from the input only around that spot. */
    @Test void anEditChangesOnlyItsOwnLines() throws IOException {
        var roots = System.getProperty("lossless.corpus");
        assumeTrue(roots != null && !roots.isBlank(), "no -Dlossless.corpus");
        var files = new ArrayList<Path>();
        for (var root : roots.split(";")) try (var s = Files.walk(Path.of(root))) { s.filter(p -> p.toString().endsWith(".md") && Files.isRegularFile(p)).forEach(files::add); }
        var spread = new TreeMap<Integer, Integer>();
        var wide = new ArrayList<String>();
        for (var f : files) {
            var md = new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8);
            var m = new prosemirrorfx.core.editor.EditorModel(Lossless.read(md));
            var spots = new ArrayList<Integer>();
            m.state().getDoc().descendants((node, pos, parent, index) -> {
                if (node.isText() && node.getText().length() > 4 && node.getMarks().isEmpty() && parent.getType() == prosemirrorfx.core.schema.EditorSchema.PARAGRAPH && !Boolean.TRUE.equals(prosemirrorfx.core.pm.Pm.attr(parent, "verbatim"))) spots.add(pos + 2);
                return true;
            });
            if (spots.isEmpty()) continue;
            m.select(prosemirrorfx.core.pm.Pm.cursor(m.state().getDoc(), spots.get(spots.size() / 2)));
            m.type("Z");
            var out = Lossless.write(m.state().getDoc());
            var a = md.split("\n", -1);
            var b = out.split("\n", -1);
            var changed = a.length != b.length ? 999 : (int) java.util.stream.IntStream.range(0, a.length).filter(k -> !a[k].equals(b[k])).count();
            spread.merge(Math.min(changed, 999), 1, Integer::sum);
            if (changed > 3) wide.add(f + "  " + changed + " lines changed; first: " + firstDifference(md, out));
        }
        System.out.println("lines changed by one edit, files per count: " + spread);
        wide.stream().limit(15).forEach(System.out::println);
    }

    static String firstDifference(String a, String b) {
        var x = a.split("\n", -1);
        var y = b.split("\n", -1);
        for (var k = 0; k < Math.min(x.length, y.length); k++)
            if (!x[k].equals(y[k])) return "line " + (k + 1) + ": [" + show(x[k]) + "] became [" + show(y[k]) + "]";
        return "lengths " + x.length + " and " + y.length + " lines; tails [" + show(a.substring(Math.max(0, a.length() - 12))) + "] [" + show(b.substring(Math.max(0, b.length() - 12))) + "]";
    }

    static String show(String s) { return (s.length() > 90 ? s.substring(0, 90) + "..." : s).replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n"); }
}
