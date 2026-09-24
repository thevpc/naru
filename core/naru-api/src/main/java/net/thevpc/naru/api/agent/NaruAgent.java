package net.thevpc.naru.api.agent;

import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;

import java.io.InputStream;

public interface NaruAgent {
    NPath getProjectDirectory();

    NaruAgent setProjectDirectory(NPath projectDirectory);

    NaruSession startInteractiveSession(String... preCommands);

    NaruSession startSession(String... preCommands);

    /**
     * Start a session whose script is read from the given stream (UTF-8). Each
     * line of the stream becomes one script line (blank lines are no-ops, except
     * inside a {@code /buffer on ... /buffer off} block where they are part of a
     * multi-line prompt).
     *
     * @param in script source stream
     * @return the started session
     */
    NaruSession startSession(InputStream in);

    void log(NaruLogMode mode, NMsg message);

    NaruEnv env();
}
