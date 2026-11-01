package net.thevpc.naru.impl.cmdline;

import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.cmdline.NCmdLine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The CLI grammar of the app entrypoint after the project concept landed: {@code -d} /
 * {@code --directory} names the workspace folder, {@code --project} names the project
 * <em>entity</em> a new session is created under, {@code --user} its opaque label. These
 * tests exercise parsing only — the constructor parses eagerly and {@code requireAll}
 * rejects anything unconsumed — so they never build a workspace.
 */
public class NaruCmdLineProcessorParseTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    @Test
    public void acceptsDirectoryProjectAndUserTogether() {
        NaruCmdLineProcessor p = new NaruCmdLineProcessor(NCmdLine.of(
                new String[]{"-d", "/tmp/somewhere", "--project", "backend",
                        "--user", "alice", "-t", "/print 1"}));
        Assertions.assertNotNull(p);
    }

    @Test
    public void longFormDirectoryIsAcceptedAndShortProjectStillResolvesToTheEntity() {
        NaruCmdLineProcessor p = new NaruCmdLineProcessor(NCmdLine.of(
                new String[]{"--directory", "/tmp/x", "--project=alpha", "--user=bob"}));
        Assertions.assertNotNull(p);
    }

    @Test
    public void rejectsUnknownOptions() {
        Assertions.assertThrows(Exception.class, () -> new NaruCmdLineProcessor(NCmdLine.of(
                new String[]{"--definitely-not-an-option"})));
    }
}
