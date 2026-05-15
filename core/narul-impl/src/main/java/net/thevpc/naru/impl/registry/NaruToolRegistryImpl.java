package net.thevpc.naru.impl.registry;

import net.thevpc.naru.api.agent.NaruAgentConfig;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSessionContext;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruDirective;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolRegistry;
import net.thevpc.naru.impl.model.OllamaProviderNaru;
import net.thevpc.naru.impl.registry.directives.*;
import net.thevpc.naru.impl.registry.tools.*;
import net.thevpc.nuts.text.NMsg;

import java.util.*;

/**
 * Registry of all tools available to the agent.
 *
 * <p>Tools are kept in insertion order so the model always sees them in a
 * deterministic sequence.
 */
public class NaruToolRegistryImpl implements NaruToolRegistry {

    private final Map<String, NaruTool> tools = new LinkedHashMap<>();
    private final Map<String, NaruDirective> stools = new LinkedHashMap<>();
    private final Map<String, NaruModelProvider> modelProviders = new HashMap<>();
    private final NaruAgentConfig config;

    public NaruToolRegistryImpl(NaruAgentConfig config) {
        this.config = config;
    }

    /**
     * Register a tool.  Overwrites any previous tool with the same name.
     */
    @Override
    public NaruToolRegistry registerTool(NaruTool tool) {
        tools.put(tool.getName(), tool);
        return this;
    }

    public Map<String, NaruTool> tools() {
        return tools;
    }

    public Map<String, NaruDirective> directives() {
        return stools;
    }

    @Override
    public NaruToolRegistry registerDirective(NaruDirective tool) {
        stools.put(tool.getName(), tool);
        return this;
    }

    @Override
    public NaruToolRegistry registerModelProvider(NaruModelProvider tool) {
        modelProviders.put(tool.getName(), tool);
        return this;
    }

    public Map<String, NaruModelProvider> modelProviders() {
        return modelProviders;
    }

    /**
     * Dispatch a tool call by name and return its string result.
     *
     * @throws IllegalArgumentException if the tool name is unknown
     */
    @Override
    public String dispatch(String name, Map<String, Object> arguments, NaruSessionContext context) {
        NaruTool tool = tools.get(name);
        if (tool == null) {
            return "ERROR: Unknown tool '" + name + "'. Available tools: " + tools.keySet();
        }
        try {
            return tool.execute(new NaruToolCallContextImpl(arguments, context));
        } catch (Exception e) {
            return "ERROR executing tool '" + name + "': " + e.getMessage();
        }
    }

    @Override
    public void dispatchSlash(String name, String argument, NaruSessionContext context) {
        NaruDirective tool = stools.get(name);
        if (tool == null) {
            context.log(NaruLogMode.TRACE, NMsg.ofC("ERROR: Unknown tool '" + name + "'. Available tools: " + stools.keySet()).asError());
            return;
        }
        try {
            tool.execute(new NaruDirectiveCallContextImpl(name, argument, context));
        } catch (Exception e) {
            context.log(NaruLogMode.TRACE, NMsg.ofC("ERROR executing tool '" + name + "': " + e.getMessage()).asError());
        }
    }

    /**
     * Dispatch a tool call by name and return its string result.
     *
     * @throws IllegalArgumentException if the tool name is unknown
     */
    @Override
    public String dispatch(NaruToolCall toolCall, NaruSessionContext context) {
        return dispatch(toolCall.getName(), toolCall.getArguments(), context);
    }

    /**
     * Returns all registered tool definitions to be sent to the model.
     */
    @Override
    public List<NaruToolDefinition> getDefinitions() {
        List<NaruToolDefinition> defs = new ArrayList<>();
        for (NaruTool t : tools.values()) {
            defs.add(t.getDefinition());
        }
        return defs;
    }

    @Override
    public boolean isEmpty() {
        return tools.isEmpty();
    }

    @Override
    public Set<String> names() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    public NaruToolRegistry registerDefaults() {
        this.registerTool(new ReadFileTool());
        this.registerTool(new WriteFileTool());
        this.registerTool(new ListFilesTool());
        this.registerTool(new RunShellTool());
        this.registerTool(new MavenCompileTool());
        this.registerTool(new MavenTestTool());
        this.registerTool(new WriteScriptLineTool());
        this.registerTool(new RunScriptTool());
        this.registerTool(new SearchWebScriptTool());
        this.registerTool(new DiffFilesTool());
        this.registerTool(new GetWorkingDirTool());
        this.registerTool(new SetWorkingDirTool());

        this.registerDirective(new RunDirective());
        this.registerDirective(new ListDirective());
        this.registerDirective(new UnloadScriptDirective());
        this.registerDirective(new LoadScriptDirective());
        this.registerDirective(new ExitDirective());
        this.registerDirective(new HelpDirective(this));
        this.registerDirective(new ToolsDirective(this));
        this.registerDirective(new ModelsDirective());
        this.registerDirective(new ModelDirective());
        this.registerDirective(new PwdDirective());
        this.registerDirective(new CdDirective());
        this.registerDirective(new HistoryDirective());

        registerModelProvider(new OllamaProviderNaru(config.getProviderUrl()));
        return this;
    }

    // ── Convenience factory ────────────────────────────────────────────────────

}
