package net.thevpc.naru.ext.tools.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
