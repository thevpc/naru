package net.thevpc.naru.ext.tools.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code file_edit_lines} must count lines the way {@code file_read} prints them: 1-based, with
 * {@code to} the last line affected. It used to take 0-based, end-exclusive indices, so a model
 * that read lines 52-58 and replaced "52 to 58" removed the wrong lines and left the file
 * uncompilable. A capable coding model failed an assessment task for exactly this reason.
 */
public class FileEditLinesNumberingTest {
    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NaruTask task() {
        NPath dir = NPath.ofTempFolder("naru-edit-lines-" + System.nanoTime());
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(dir);
        NaruSession s = agent.newSession()
                .sessionDir(dir)
                .interaction(new NaruStreamInteraction(o -> {
                }))
                .configureDefaults(false)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
        return s.newTask(NaruTaskSpec.of());
    }

    private static String edit(NaruTask t, Long from, Long to, String content) {
        t.resolve("f.txt").writeString("l1\nl2\nl3\nl4\nl5\n");
        String r = FileToolHelper.fileEdit(t, "f.txt", from, to, content, null);
        assertTrue(r.startsWith("SUCCESS"), r);
        return t.resolve("f.txt").readString();
    }

    @Test
    public void replacingLinesTwoToThreeAsReadReplacesThoseLines() {
        assertEquals("l1\nX\nl4\nl5\n", edit(task(), 2L, 3L, "X"));
    }

    @Test
    public void replacingOneLineUsesTheSameNumberForFromAndTo() {
        assertEquals("l1\nl2\nX\nl4\nl5\n", edit(task(), 3L, 3L, "X"));
    }

    @Test
    public void omittingToInsertsBeforeTheGivenLine() {
        assertEquals("l1\nX\nl2\nl3\nl4\nl5\n", edit(task(), 2L, null, "X"));
    }

    @Test
    public void emptyContentDeletesTheRange() {
        assertEquals("l1\nl5\n", edit(task(), 2L, 4L, ""));
    }

    @Test
    public void negativeNumbersCountFromTheEnd() {
        assertEquals("l1\nl2\nl3\nl4\nX\n", edit(task(), -1L, -1L, "X"));
    }

    @Test
    public void appendingAfterTheLastLineIsAnInsertAtLinePlusOne() {
        assertEquals("l1\nl2\nl3\nl4\nl5\nX\n", edit(task(), 6L, null, "X"));
    }

    /**
     * Many edits in a row on one file, checked against an independent model of the contract:
     * the line numbers of every call are those of the file as it stands after the previous
     * call. A single wrong off-by-one shows up as divergence after a few operations.
     */
    @Test
    public void aLongSequenceOfEditsMatchesTheContract() {
        NaruTask t = task();
        Random rnd = new Random(42);
        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            expected.add("line" + i);
        }
        t.resolve("seq.txt").writeString(String.join("\n", expected) + "\n");

        for (int step = 0; step < 300; step++) {
            int n = expected.size();
            int kind = rnd.nextInt(4); // 0 replace range, 1 insert, 2 delete range, 3 negative single replace
            Long from;
            Long to;
            String content;
            List<String> add = new ArrayList<>();
            int cnt = 1 + rnd.nextInt(3);
            for (int k = 0; k < cnt; k++) {
                add.add("s" + step + "_" + k);
            }
            if (n < 3) { // keep the file from vanishing
                kind = 1;
            }
            switch (kind) {
                case 0: {
                    int a = 1 + rnd.nextInt(n);
                    int b = a + rnd.nextInt(n - a + 1);
                    from = (long) a;
                    to = (long) b;
                    content = String.join("\n", add);
                    for (int i = b; i >= a; i--) {
                        expected.remove(i - 1);
                    }
                    expected.addAll(a - 1, add);
                    break;
                }
                case 1: {
                    int a = 1 + rnd.nextInt(n + 1); // n+1 appends
                    from = (long) a;
                    to = null;
                    content = String.join("\n", add);
                    expected.addAll(a - 1, add);
                    break;
                }
                case 2: {
                    int a = 1 + rnd.nextInt(n - 1);
                    int b = a + rnd.nextInt(Math.min(2, n - 1 - a) + 1); // never empties the file
                    from = (long) a;
                    to = (long) b;
                    content = "";
                    for (int i = b; i >= a; i--) {
                        expected.remove(i - 1);
                    }
                    break;
                }
                default: {
                    int back = 1 + rnd.nextInt(n);
                    from = (long) -back;
                    to = (long) -back;
                    content = add.get(0);
                    expected.set(n - back, add.get(0));
                    break;
                }
            }
            String r = FileToolHelper.fileEdit(t, "seq.txt", from, to, content, null);
            assertTrue(r.startsWith("SUCCESS"), "step " + step + ": " + r);
            assertEquals(String.join("\n", expected) + "\n", t.resolve("seq.txt").readString(),
                    "diverged at step " + step + " (kind " + kind + ", from=" + from + ", to=" + to + ", content=[" + content + "])");
        }
    }
}
