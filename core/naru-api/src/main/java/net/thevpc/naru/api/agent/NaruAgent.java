package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.tool.NaruToolRegistry;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;

public interface NaruAgent {
    String model();
    NaruModelProvider provider();
    NaruToolRegistry registry();
    void run(String task, NPath pwd);
    void invokeScript(NaruSessionContext sessionContext, String scriptName);
    void log(NaruLogMode mode,NMsg message);
}
