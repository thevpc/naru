package net.thevpc.naru.impl.registry;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.mode.NaruStandardMode;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.tool.NaruDirective;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.naru.impl.directive.*;
import net.thevpc.naru.impl.directive.NaruCallDirective;
import net.thevpc.naru.impl.mode.NAruModeRegistry;
import net.thevpc.naru.impl.model.gemini.NaruGeminiProvider;
import net.thevpc.naru.impl.model.ollama.NaruOllamaProvider;
import net.thevpc.naru.impl.tools.*;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.*;

import java.util.*;
import java.util.stream.Collectors;

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
    private final NAruModeRegistry modeRegistry =new NAruModeRegistry();

    public NaruRegistryImpl() {
    }

    @Override
    public List<NaruPromptMode> modes() {
        return modeRegistry.modes();
    }

    @Override
    public List<String> modeNames() {
        return modes().stream().map(x-> NNameFormat.LOWER_KEBAB_CASE.format(x.name())).collect(Collectors.toList());
    }

    @Override
    public List<String> modeNamesAndAliases() {
        List<String> all=new ArrayList<>();
        for (NaruPromptMode mode : modes()) {
            all.add(NNameFormat.LOWER_KEBAB_CASE.format(mode.name()));
            for (String alias : mode.aliases()) {
                all.add(NNameFormat.LOWER_KEBAB_CASE.format(alias));
            }
        }
        return all;
    }

    @Override
    public void declareMode(NaruPromptMode mode) {
        modeRegistry.register(mode);
    }

    @Override
    public NOptional<NaruPromptMode> mode(NaruStandardMode mode) {
        return modeRegistry.mode(mode);
    }

    @Override
    public NOptional<NaruPromptMode> mode(String mode) {
        return modeRegistry.mode(mode);
    }

    /**
     * Register a tool.  Overwrites any previous tool with the same name.
     */
    @Override
    public NaruRegistry registerTool(NaruTool tool) {
        tools.put(tool.name(), tool);
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
        stools.put(tool.name(), tool);
        for (String alias : tool.getAliases()) {
            String old = stoolsAliases.get(alias);
            if (old != null && !old.equals(tool.name())) {
                throw new IllegalArgumentException("alias " + alias + " is already used by " + old);
            }
            stoolsAliases.put(alias, tool.name());
        }
        return this;
    }

    @Override
    public NaruRegistry registerModelProvider(NaruModelProvider provider) {
        modelProviders.put(NStringUtils.trim(provider.name()).toLowerCase(), provider);
        return this;
    }

    public Map<String, NaruModelProvider> modelProviders() {
        return modelProviders;
    }

    @Override
    public List<NaruModelInfo> modelsInfos(NaruSession session) {
        ArrayList<NaruModelInfo> a = new ArrayList<>();
        for (NaruModelProvider p : modelProviders.values()) {
            for (String m : p.findModelIds(session)) {
                NaruModelCapabilities c = p.getProtocol(new NaruModelConfig(
                        p.name(),
                        m
                ),session).get().getCapabilities();
                a.add(new NaruModelInfo(p.name(), m, c));
            }
        }
        a.sort(Comparator.comparing(NaruModelInfo::provider).thenComparing(NaruModelInfo::model));
        return a;
    }

    @Override
    public List<NaruModelKey> modelsKeys(NaruSession session) {
        ArrayList<NaruModelKey> a = new ArrayList<>();
        for (NaruModelProvider p : modelProviders.values()) {
            for (String m : p.findModelIds(session)) {
                a.add(new NaruModelKey(p.name(), m));
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
    public NOptional<NaruModelProtocol> protocol(NaruModelConfig model,NaruSession session) {
        return provider(model.provider())
                .flatMap(p -> p.getProtocol(model,session));
    }

    @Override
    public NOptional<NaruModelKey> findModel(String keyOrName,NaruSession session) {
        List<NaruModelKey> models = modelsKeys(session);
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
    public String dispatch(String name, Map<String, Object> arguments, NaruTask task) {
        NaruTool tool = tools.get(name);
        if (tool == null) {
            return "ERROR: Unknown tool '" + name + "'. Available tools: " + tools.keySet();
        }
        try {
            return tool.execute(new NaruToolCallContextImpl(arguments, task));
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
    public void dispatchSlash(String name, String argument, NaruTask task) {
        NaruDirective tool = findDirective(name).orNull();
        if (tool == null) {
            task.log(NaruLogMode.TRACE, NMsg.ofC("ERROR: Unknown tool '" + name + "'. Available tools: " + stools.keySet()).asError());
            return;
        }
        try {
            tool.execute(new NaruDirectiveCallContextImpl(name, argument, task));
        } catch (NCancelException e) {
            throw e;
        } catch (Exception e) {
            task.log(NaruLogMode.TRACE, NMsg.ofC("ERROR executing tool '" + name + "': " + e.getMessage()).asError());
        }
    }

    /**
     * Dispatch a tool call by name and return its string result.
     *
     * @throws IllegalArgumentException if the tool name is unknown
     */
    @Override
    public String dispatch(NaruToolCall toolCall, NaruTask context) {
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
        this.registerTool(new FileWriteTool());
        this.registerTool(new RunShellTool());
        this.registerTool(new MavenCompileTool());
        this.registerTool(new MavenTestTool());
        this.registerTool(new RoutineAddLineTool());
        this.registerTool(new RoutineRunTool());
        this.registerTool(new SearchWebScriptTool());
        this.registerTool(new DiffFilesTool());
        this.registerTool(new GetWorkingDirTool());
        this.registerTool(new SetWorkingDirTool());
        this.registerTool(new ModelDelegateTool());
        this.registerTool(new RoutineListLinesTool());
        this.registerTool(new FolderFindTool());
        this.registerTool(new FileGrepTool());

        this.registerDirective(new NaruRoutineDirective());
        this.registerDirective(new NaruExitDirective());
        this.registerDirective(new NaruPrintDirective());
        this.registerDirective(new NaruHelpDirective());
        this.registerDirective(new NaruToolsDirective());
        this.registerDirective(new NaruStatDirective());
        this.registerDirective(new NaruModelDirective());
        this.registerDirective(new NaruModeDirective());
        this.registerDirective(new NaruPwdDirective());
        this.registerDirective(new NaruCdDirective());
        this.registerDirective(new NaruCatDirective());
        this.registerDirective(new NaruBufferDirective());
        this.registerDirective(new NaruHistoryDirective());
        this.registerDirective(new NaruSessionDirective());
        this.registerDirective(new NaruShDirective());
        this.registerDirective(new NaruLsDirective());
        this.registerDirective(new NaruSetDirective());
        this.registerDirective(new NaruSkillDirective());
        this.registerDirective(new NaruSystemDirective());
        this.registerDirective(new NaruWhileDirective());
        this.registerDirective(new NaruForDirective());
        this.registerDirective(new NaruIfDirective());
        this.registerDirective(new NaruElseDirective());
        this.registerDirective(new NaruElseIfDirective());
        this.registerDirective(new NaruEndDirective());
        this.registerDirective(new NaruReloadDirective());
        this.registerDirective(new NaruNewDirective());
        this.registerDirective(new NaruRestoreDirective());
        this.registerDirective(new NaruSaveDirective());
        this.registerDirective(new NaruResetDirective());
        this.registerDirective(new NaruContextDirective());
        this.registerDirective(new NaruGoDirective());
        this.registerDirective(new NaruCallDirective());
        this.registerDirective(new NaruSourceDirective());
        this.registerDirective(new NaruStartDirective());
        this.registerDirective(new NaruTaskDirective());

        registerModelProvider(new NaruOllamaProvider());
        registerModelProvider(new NaruGeminiProvider());
        return this;
    }

    // ── Convenience factory ────────────────────────────────────────────────────

}
