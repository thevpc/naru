package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;

public interface NaruAgent {
    NPath getProjectDirectory();

    NaruAgent setProjectDirectory(NPath projectDirectory);

    NaruModelKey model();

    NaruRegistry registry();

    void invokeStep(NaruSession sessionContext);

    void invokeScript(NaruSession sessionContext, String scriptName);

    void runInteractive();

    void runTask(String task);

    void log(NaruLogMode mode, NMsg message);
    void runDirective(String line, NaruSession sessionContext);
}
