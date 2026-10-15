package net.thevpc.naru.ext.tools.llamacpp;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NaruLlamaCppToolset implements NaruToolset {

    private final String id;
    private final List<NaruTool> tools;

    public NaruLlamaCppToolset(String id) {
        this.id = id;
        List<NaruTool> list = new ArrayList<>();
        // Only read-only diagnostics are exposed to the agent. The state-mutating
        // tools (install/start/stop/pull/rm) are intentionally not registered: they
        // spawn or kill processes, download binaries and multi-GB models, or delete
        // them -- none of which a prompt-injected agent should be able to do. The
        // user keeps full lifecycle control through the /llamacpp directive.
        list.add(new LlamaCppStatusTool());
//        list.add(new LlamaCppInstallTool());
//        list.add(new LlamaCppStartTool());
//        list.add(new LlamaCppStopTool());
        list.add(new LlamaCppListTool());
//        list.add(new LlamaCppPullTool());
//        list.add(new LlamaCppRmTool());
        list.add(new LlamaCppPsTool());
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
        // opened in session
    }

    @Override
    public void close() {
        // stop the NARU-managed llama-server when the toolset closes
        LlamaCppProcessManager.stopIfStartedByNaru();
    }
}
