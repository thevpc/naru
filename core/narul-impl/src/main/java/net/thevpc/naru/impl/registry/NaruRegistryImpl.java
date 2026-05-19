package net.thevpc.naru.impl.registry;

import net.thevpc.naru.api.agent.NaruAgentConfig;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.tool.NaruDirective;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.naru.impl.directive.*;
import net.thevpc.naru.impl.model.OllamaProviderNaru;
import net.thevpc.naru.impl.tools.*;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NStringUtils;

import java.util.*;

/**
 * Registry of all tools available to the agent.
 *
 * <p>Tools are kept in insertion order so the model always sees them in a
 * deterministic sequence.
 */
public class NaruRegistryImpl implements NaruRegistry {

    private final Map<String, NaruTool> tools = new LinkedHashMap<>();
    private final Map<String, NaruDirective> stools = new LinkedHashMap<>();
    private final Map<String, String> stoolsAliases = new LinkedHashMap<>();
    private final Map<String, NaruModelProvider> modelProviders = new HashMap<>();
    private final NaruAgentConfig config;

    public NaruRegistryImpl(NaruAgentConfig config) {
        this.config = config;
    }

    /**
     * Register a tool.  Overwrites any previous tool with the same name.
     */
    @Override
    public NaruRegistry registerTool(NaruTool tool) {
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
    public NaruRegistry registerDirective(NaruDirective tool) {
        stools.put(tool.getName(), tool);
        for (String alias : tool.getAliases()) {
            String old = stoolsAliases.get(alias);
            if (old != null && !old.equals(tool.getName())) {
                throw new IllegalArgumentException("alias " + alias + " is already used by " + old);
            }
            stoolsAliases.put(alias, tool.getName());
        }
        return this;
    }

    @Override
    public NaruRegistry registerModelProvider(NaruModelProvider provider) {
        modelProviders.put(NStringUtils.trim(provider.getName()).toLowerCase(), provider);
        return this;
    }

    public Map<String, NaruModelProvider> modelProviders() {
        return modelProviders;
    }

    @Override
    public List<NaruModelInfo> modelsInfos() {
        ArrayList<NaruModelInfo> a = new ArrayList<>();
        for (NaruModelProvider p : modelProviders.values()) {
            for (String m : p.findModelIds()) {
                NaruModelCapabilities c = p.getProtocol(m).get().getCapabilities();
                a.add(new NaruModelInfo(p.getName(), m, c));
            }
        }
        a.sort(Comparator.comparing(NaruModelInfo::provider).thenComparing(NaruModelInfo::model));
        return a;
    }

    @Override
    public List<NaruModelKey> modelsKeys() {
        ArrayList<NaruModelKey> a = new ArrayList<>();
        for (NaruModelProvider p : modelProviders.values()) {
            for (String m : p.findModelIds()) {
                a.add(new NaruModelKey(p.getName(), m));
            }
        }
        a.sort(Comparator.comparing(NaruModelKey::provider).thenComparing(NaruModelKey::model));
        return a;
    }

    @Override
    public NOptional<NaruModelProvider> provider(String provider) {
        return NOptional.ofNamed(modelProviders.get(NStringUtils.trim(provider).toLowerCase()), provider);
    }

    @Override
    public NOptional<NaruModelProtocol> protocol(NaruModelKey model) {
        return provider(model.provider())
                .flatMap(p -> p.getProtocol(model.model()));
    }

    @Override
    public NOptional<NaruModelKey> findModel(String keyOrName) {
        List<NaruModelKey> models = modelsKeys();
        if (keyOrName.contains("/")) {
            NOptional<NaruModelKey> r = NaruModelKey.parse(keyOrName);
            if (r.isPresent()) {
                if (models.contains(r.get())) {
                    return NOptional.of(r.get());
                }
            }
        } else {
            for (NaruModelKey m : models) {
                if (m.model().equals(keyOrName)) {
                    return NOptional.of(m);
                }
            }
        }
        Integer ii = NLiteral.of(keyOrName).asInt().orNull();
        if (ii != null) {
            ii = ii - 1;
            if (ii >= 0 && ii < models.size()) {
                return NOptional.of(models.get(ii));
            }
        }
        return NOptional.ofNamedEmpty(NMsg.ofC("model '%s'", keyOrName));
    }

    /**
     * Dispatch a tool call by name and return its string result.
     *
     * @throws IllegalArgumentException if the tool name is unknown
     */
    @Override
    public String dispatch(String name, Map<String, Object> arguments, NaruSession context) {
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

    public NOptional<NaruDirective> findDirective(String name) {
        NaruDirective d = stools.get(name);
        if (d == null) {
            String s = stoolsAliases.get(name);
            if (s != null) {
                d = stools.get(s);
            }
            if (d == null) {
                return NOptional.ofNamedEmpty(NMsg.ofC("directive '%s'", name));
            }
        }
        return NOptional.of(d);
    }

    @Override
    public void dispatchSlash(String name, String argument, NaruSession context) {
        NaruDirective tool = findDirective(name).orNull();
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
    public String dispatch(NaruToolCall toolCall, NaruSession context) {
        return dispatch(toolCall.getName(), toolCall.getArguments(), context);
    }

    @Override
    public boolean isEmpty() {
        return tools.isEmpty();
    }

    @Override
    public Set<String> names() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    public NaruRegistry registerDefaults() {
        this.registerTool(new FileReadTool());
        this.registerTool(new FileReadTool());
        this.registerTool(new FileAppendTool());
        this.registerTool(new FileEditLinesTool());
        this.registerTool(new WriteFileTool());
        this.registerTool(new RunShellTool());
        this.registerTool(new MavenCompileTool());
        this.registerTool(new MavenTestTool());
        this.registerTool(new RoutineAddLineTool());
        this.registerTool(new RunScriptTool());
        this.registerTool(new SearchWebScriptTool());
        this.registerTool(new DiffFilesTool());
        this.registerTool(new GetWorkingDirTool());
        this.registerTool(new SetWorkingDirTool());
        this.registerTool(new DelegateModelTool());
        this.registerTool(new RoutineListLinesTool());
        this.registerTool(new FolderSearchTool());
        this.registerTool(new FileGrepTool());

        this.registerDirective(new RoutineDirective());
        this.registerDirective(new ExitDirective());
        this.registerDirective(new HelpDirective());
        this.registerDirective(new ToolsDirective());
        this.registerDirective(new StatDirective());
        this.registerDirective(new ModelDirective());
        this.registerDirective(new PwdDirective());
        this.registerDirective(new CdDirective());
        this.registerDirective(new HistoryDirective());
        this.registerDirective(new SessionDirective());
        this.registerDirective(new ShDirective());
        this.registerDirective(new LsDirective());
        this.registerDirective(new SetDirective());
        this.registerDirective(new SkillDirective());
        this.registerDirective(new SystemDirective());

        registerModelProvider(new OllamaProviderNaru(config.getProviderUrl()));
        return this;
    }

    // ── Convenience factory ────────────────────────────────────────────────────

}
