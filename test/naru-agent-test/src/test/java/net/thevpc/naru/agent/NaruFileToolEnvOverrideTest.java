package net.thevpc.naru.agent;

import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.ext.tools.fs.FileToolHelper;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code FileToolHelper} caps ({@code naru.fs.*}) are defaults, not constants:
 * a project can lower or raise each one per task, session, agent or settings file,
 * and a value that is not a strictly positive integer falls back instead of
 * truncating everything to nothing or nothing to everything.
 */
public class NaruFileToolEnvOverrideTest {

    private static final String MAX_LINES_KEY = "naru.fs.read.maxLines";
    private static final String PREVIEW_CHARS_KEY = "naru.fs.read.previewChars";

    private NaruWorkspace agent;
    private NPath folder;
    private NaruSession session;

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();    }

    @BeforeEach
    public void setUp() {
        folder = NPath.ofTempFolder("naru-fsenv");
        agent = NaruWorkspace.of();
        agent.projectDirectory(folder);
    }

    @AfterEach
    public void tearDown() {
        if (session != null) {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Runs a trivial script and hands back its request task with the session still
     * alive, shaped like the documented embedding (a resident interactive host task
     * plus the request). A stopped session would refuse the settings reads below.
     */
    private NaruTask runScript(String... statements) {
        if (session != null) {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
        session = agent.newSession()
                .interaction(new NaruStreamInteraction(o -> {
                }, request -> {
                }))
                .interactive()
                .task(NaruTaskSpec.of().statements("/print 0").name("host"))
                .build();
        session.run();
        NaruTask task = session.run(
                NaruTaskSpec.of().statements(statements).resolveName());
        task.await();
        return task;
    }

    private void writeDoc(int lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            sb.append("line number ").append(i).append('\n');
        }
        folder.resolve("doc.txt").writeString(sb.toString());
    }

    @Test
    public void fileReadMaxLinesIsOverridablePerTask() {
        writeDoc(10);
        NaruTask task = runScript("/print 1");

        String full = FileToolHelper.fileRead(task, "doc.txt", null, null);
        assertTrue(full.contains("line number 10"), "the default read must show the whole file");
        assertTrue(full.contains("(End of file - total 10 lines)"));

        task.setTaskEnv(MAX_LINES_KEY, 3);
        String truncated = FileToolHelper.fileRead(task, "doc.txt", null, null);
        assertFalse(truncated.contains("line number 10"), "the 10th line must not be shown anymore");
        assertTrue(truncated.contains("(Showing lines 1-3 of 10"),
                "the lowered cap must ask the model to continue: " + truncated);
    }

    @Test
    public void anInvalidMaxLinesFallsBackToTheDefault() {
        writeDoc(10);
        NaruTask task = runScript("/print 1");

        task.setTaskEnv(MAX_LINES_KEY, "0");
        assertTrue(FileToolHelper.fileRead(task, "doc.txt", null, null).contains("(End of file - total 10 lines)"),
                "0 must fall back to the default cap instead of hiding the file");

        task.setTaskEnv(MAX_LINES_KEY, "not-a-number");
        assertTrue(FileToolHelper.fileRead(task, "doc.txt", null, null).contains("(End of file - total 10 lines)"),
                "a typo must fall back, not turn a read into an error or an empty answer");
    }

    @Test
    public void maxLinesFollowsTheSettingsChain() {
        writeDoc(10);
        NaruTask task = runScript("/print 1");
        NaruSession taskSession = task.session();

        taskSession.setProjectEnv(MAX_LINES_KEY, NElement.ofString("2"), NaruVisibility.PRIVATE);
        assertTrue(FileToolHelper.fileRead(task, "doc.txt", null, null).contains("(Showing lines 1-2 of 10"),
                "the settings (config) level must apply");

        taskSession.setSessionEnv(MAX_LINES_KEY, 3);
        assertTrue(FileToolHelper.fileRead(task, "doc.txt", null, null).contains("(Showing lines 1-3 of 10"),
                "the session env must beat the settings file");

        task.setTaskEnv(MAX_LINES_KEY, 1);
        assertTrue(FileToolHelper.fileRead(task, "doc.txt", null, null).contains("(Showing lines 1-1 of 10"),
                "the task env must beat the session env");
    }

    @Test
    public void previewCharsLimitsDryRunPreviews() {
        NaruTask task = runScript("/print 1");
        String content = "abcdefghijklmnopqrstuvwxyz0123456789";

        String defaultPreview = FileToolHelper.fileWrite(task, "out.txt", content, true);
        assertFalse(defaultPreview.contains("[preview truncated]"),
                "36 chars must fit the built-in 8k preview");

        task.setTaskEnv(PREVIEW_CHARS_KEY, 8);
        String limited = FileToolHelper.fileWrite(task, "out.txt", content, true);
        assertTrue(limited.contains("... [preview truncated]"),
                "a lowered preview cap must truncate the dry-run preview");
        assertTrue(limited.contains("abcdefgh"),
                "the kept prefix must start at the beginning of the content");
    }
}
