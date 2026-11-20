package net.thevpc.naru.ext.tools.ollama;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NaruOllamaToolset implements NaruToolset {

    private final String id;
    private final List<NaruTool> tools;

    public NaruOllamaToolset(String id) {
        this.id = id;
        List<NaruTool> list = new ArrayList<>();
        // Only read-only diagnostics are exposed to the agent. The state-mutating
        // tools (start/stop/install) are intentionally not registered: they spawn or
        // kill processes and download OS-level installers, which no prompt-injected
        // agent should be able to do. The user keeps full lifecycle control through
        // the /ollama directive (start, stop, install, pull, rm, ...).
        list.add(new OllamaStatusTool());
//        list.add(new OllamaStartTool());
//        list.add(new OllamaStopTool());
//        list.add(new OllamaInstallTool());
        list.add(new OllamaPsTool());
        this.tools = Collections.unmodifiableList(list);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<NaruTool> tools() {
        return tools;
    }

    @Override
    public void open(NaruSession session) {
        // Toolset opened in session
    }

    @Override
    public void close() {
        // Automatically stop Ollama if it was started by NARU
        OllamaProcessManager.stopIfStartedByNaru();
    }
}
