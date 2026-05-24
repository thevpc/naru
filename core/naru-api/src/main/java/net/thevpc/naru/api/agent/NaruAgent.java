package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

public interface NaruAgent {
    NPath getProjectDirectory();

    NaruAgent setProjectDirectory(NPath projectDirectory);

//    NaruModelConfig model();

    NaruRegistry registry();

    NOptional<NaruStatement> parseStatement(String line);

    void invokeStep(NaruSession session);

    void invokeRoutine(NaruSession session, String scriptName);

    void invokeDirective(String line, NaruSession session);

    void runInteractive();

    void runTask(String task);

    void log(NaruLogMode mode, NMsg message);

    NaruEnv env();
}
