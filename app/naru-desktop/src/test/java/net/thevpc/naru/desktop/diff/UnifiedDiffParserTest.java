package net.thevpc.naru.desktop.diff;

import net.thevpc.naru.desktop.diff.DiffModel.FileDiff;
import net.thevpc.naru.desktop.diff.DiffModel.Kind;
import net.thevpc.naru.desktop.diff.DiffModel.Row;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnifiedDiffParserTest {

    private static final String TWO_FILES = """
            diff --git a/src/App.java b/src/App.java
            index d3b0738..5a3a14b 100644
            --- a/src/App.java
            +++ b/src/App.java
            @@ -10,6 +10,7 @@ class App {
             static void main() {
            -    oldCall();
            +    newCall();
            +    extra();
                 run();
                 done();
                 more();
            diff --git a/src/Util.java b/src/Util.java
            --- a/src/Util.java
            +++ b/src/Util.java
            @@ -1,2 +1,1 @@
            -first
            -second
            +only
            """;

    @Test
    void parsesHunksWithLineNumbers() {
        List<FileDiff> files = UnifiedDiffParser.parse(TWO_FILES);
        assertEquals(2, files.size());
        FileDiff app = files.get(0);
        assertEquals("src/App.java", app.newPath);
        assertEquals(1, app.deleted());
        assertEquals(2, app.added());
        List<DiffModel.Line> lines = app.allLines();
        assertEquals(Kind.HUNK, lines.get(0).kind());
        assertEquals("static void main() {", lines.get(1).text());
        assertEquals(10, lines.get(1).oldNumber());
        assertEquals(10, lines.get(1).newNumber());
        assertEquals(Kind.DELETED, lines.get(2).kind());
        assertEquals(11, lines.get(2).oldNumber());
        assertEquals(Kind.ADDED, lines.get(3).kind());
        assertEquals(11, lines.get(3).newNumber());
        assertEquals(Kind.ADDED, lines.get(4).kind());
        assertEquals(12, lines.get(4).newNumber());
    }

    @Test
    void alignsChangesSideBySideWithBlanks() {
        FileDiff util = UnifiedDiffParser.parse(TWO_FILES).get(1);
        List<Row> rows = util.sideBySide();
        // header row, then two deletes facing one add plus a blank on the right.
        assertEquals(3, rows.size());
        Row.Paired header = (Row.Paired) rows.get(0);
        assertEquals(Kind.HUNK, header.shared().kind());
        Row.Changed second = (Row.Changed) rows.get(2);
        assertEquals("second", second.left().text());
        assertEquals(DiffModel.Side.BLANK, second.right());
    }

    @Test
    void decodesQuotedPaths() {
        String diff = """
                diff --git "a/dir-\\345\\216\\237.txt" "b/dir-\\345\\216\\237b.txt"
                similarity index 90%
                rename from "dir-\\345\\216\\237.txt"
                rename to "dir-\\345\\216\\237b.txt"
                """;
        FileDiff renamed = UnifiedDiffParser.parse(diff).get(0);
        assertEquals("dir-原.txt", renamed.oldPath);
        assertEquals("dir-原b.txt", renamed.newPath);
        assertEquals(true, renamed.renamed());
    }

    @Test
    void readsBinaryAndModeOnlyChanges() {
        List<FileDiff> files = UnifiedDiffParser.parse("""
                diff --git "a/bin \\346\\226\\207.bin" "b/bin \\346\\226\\207.bin"
                Binary files "a/bin \\346\\226\\207.bin" and "b/bin \\346\\226\\207.bin" differ
                diff --git a/run.sh b/run.sh
                old mode 100644
                new mode 100755
                """);
        assertEquals("bin 文.bin", files.get(0).newPath);
        assertEquals(true, files.get(0).binary);
        assertEquals(0, files.get(0).hunks.size());
        assertEquals("run.sh", files.get(1).newPath);
        assertEquals(true, files.get(1).headers.contains("new mode 100755"));
    }

    @Test
    void emptyInputYieldsNothing() {
        assertEquals(0, UnifiedDiffParser.parse("").size());
        assertEquals(0, UnifiedDiffParser.parse("Working tree clean\n").size());
    }
}
