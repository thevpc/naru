package net.thevpc.naru.api.agent;

import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;

public interface NaruAgent {
    NPath getProjectDirectory();

    NaruAgent setProjectDirectory(NPath projectDirectory);

    NaruSession startInteractiveSession(String... preCommands);

    NaruSession startSession(String... preCommands);

    void log(NaruLogMode mode, NMsg message);

    NaruEnv env();
}
