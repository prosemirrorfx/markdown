# prosemirrorfx-markdown

Markdown and clipboard HTML to and from the prosemirrorfx document. Depends on `prosemirrorfx-core`; no JavaFX.

Built on [prosemirror-kotlin](https://github.com/atlassian-labs/prosemirror-kotlin) by Atlassian (Apache License 2.0) through `prosemirrorfx-core`. prosemirrorfx is an independent project and is not affiliated with ProseMirror or Atlassian.

## Status

Not yet published. Snapshots only to begin with: `0.1.0-SNAPSHOT` on `https://central.sonatype.com/repository/maven-snapshots/`. Java 25.

    <dependency>
        <groupId>io.github.prosemirrorfx</groupId>
        <artifactId>prosemirrorfx-markdown</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>

## Three readers and writers

- `Lossless`: for files. **A text that was not edited is written back byte for byte, and an edited text changes only where it was edited.**
- `Markdown`: the normalising reader and writer. Its output is predictable, so it suits copy as Markdown and generated text.
- `Html`: clipboard HTML from Word, Gmail, Google Docs and browsers, mapped onto the editor's schema (headings, paragraphs, nested lists, bold, italic, line breaks). Word's paragraph lists are regrouped into real lists.

## What `Lossless` does

Reading is line by line, in the dialect of notes written by hand: headings, bullet and numbered lists nested by tab or spaces, paragraphs, bold, italic, inline code, links. A line that starts with `>>` is a comment line: an ordinary paragraph that happens to start with those characters, never a block quote, and typing inside it leaves it a comment. A reply under it is the same with leading whitespace, which the editor shows as a left margin. `^^` is ordinary text.

Every block remembers the source and layout it was read from: its own text, marker, indentation, the blank lines before it. The document remembers the line ending (LF or CRLF), a leading byte-order mark, and whatever follows the last line (a missing final newline included). Writing uses what was remembered for every block whose content is unchanged, and checks that a stored line still reads back as its block before using it.

When something is edited:

- the edited paragraph is written in the usual form, and its other lines (in a multi-line paragraph) stay as they were;
- emphasis is written `_like this_`, or `*like this*` if the paragraph already used asterisks, or when `_` cannot work (inside a word);
- text is escaped only where Markdown would read it differently, found by writing it plainly, reading it back, and escaping only if the reading differs; a line that would turn into a heading, list or code fence gets a backslash;
- a new list item takes the marker and indentation of its neighbour; an item moved by Tab or Shift+Tab is re-indented under its new parent, and if the result would not read back as the same structure, that top-level block is written in the usual form instead.

Not interpreted, so kept as lines of text exactly as written: tables, horizontal rules, block quotes, HTML blocks, link reference definitions, setext headings. Fenced code (three backticks or tildes) is kept verbatim as one block. In the first line of a list item, a heading or list marker is plain text (a logseq `- ## Title` stays that).

Known differences from CommonMark, all of them in what is shown, none in what is saved: a line break inside a paragraph stays a line break (it is not folded into a space); a numbered marker other than 1 does not interrupt a paragraph; a less-indented continuation line is a new paragraph, not a lazy continuation; an empty paragraph the user leaves behind is not written.

## Source positions

`SourceMap.of(doc)` says where each part of a document sits in the Markdown text it is written as (`text()` is exactly `Lossless.write(doc)`), and back: `line(pos)`, `column(pos)` and `offset(pos)` for a document position, `docPosition(line, column)` and `docPositionAtOffset(offset)` the other way. Lines and columns count from 0 and offsets index the text. Lines are exact. A column is found by matching the document's characters against the source line in order, skipping marks and escapes the document does not hold (`**`, `` ` ``, a backslash), so it is exact for plain text and lands beside the markup in formatted text. A line that holds no text of its own (a blank line) gives the start of the next block. Every caret position of the test fixtures maps to a line and column and back to itself. Building it writes the whole text once: about 260 ms for 4000 blocks, so build it on demand, not on every keystroke.

## Tests

    mvn test

`LosslessTest` round-trips the fixture files in `src/test/resources/lossless` (a comment thread, an outline, a mixed file, Tamil and Devanagari text) and a list of edge cases (no final newline, CRLF, byte-order mark, blank-line runs, tab nesting), and edits them. `RealPasteTest` checks clipboard HTML in the shapes Word, Chromium and Google Docs write it, with synthetic text.

To run the same round trip over a folder of real notes, without putting them in the repository:

    mvn test -Dtest=LosslessCorpusTest -Dlossless.corpus="dir1;dir2"

It lists every `.md` file that does not come back byte for byte, with the first line that differs, and then types one letter into the middle of each file and reports how many lines changed (the expected answer is one).

Test fixtures are compared byte for byte, so `.gitattributes` stops git from changing their line endings.
