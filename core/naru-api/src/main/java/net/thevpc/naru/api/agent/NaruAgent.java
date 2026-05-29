package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

import java.util.List;

public interface NaruAgent {
    NPath getProjectDirectory();

    NaruAgent setProjectDirectory(NPath projectDirectory);

    NaruRegistry registry();

    NOptional<List<NaruStatement>> parseFile(NPath path);

    NOptional<NaruStatement> parseStatement(String line);

    void invokeStep(NaruSession session);

    void invokeRoutine(NaruSession session, String routineName);

    void invokeDirective(String line, NaruSession session);

    void runInteractive(PreCommand... preCommands);

    void runTasks(PreCommand... preCommands);

    void log(NaruLogMode mode, NMsg message);

    NaruEnv env();
}
