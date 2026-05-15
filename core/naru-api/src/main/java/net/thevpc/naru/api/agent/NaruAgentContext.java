package net.thevpc.naru.api.agent;

import net.thevpc.nuts.io.NPath;

public interface NaruAgentContext {
    NPath resolve(String path);

    NPath getProjectDir();

    void setProjectDir(NPath projectDir);

    String getExtraContext();

    void setExtraContext(String extraContext);

    NaruScriptManager getScriptManager();
}
