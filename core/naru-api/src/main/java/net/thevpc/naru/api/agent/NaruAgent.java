package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;

public interface NaruAgent {
    String getSystemPrompt();

    NaruAgent setSystemPrompt(String systemPrompt);

    NPath getProjectDirectory();

    NaruAgent setProjectDirectory(NPath projectDirectory);

    NaruRegistry registry();

    NaruSession startInteractiveSession(String... preCommands);

    NaruSession startSession(String... preCommands);

    void log(NaruLogMode mode, NMsg message);

    NaruEnv env();
}
