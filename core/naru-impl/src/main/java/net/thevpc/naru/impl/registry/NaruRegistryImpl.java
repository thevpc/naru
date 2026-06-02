package net.thevpc.naru.impl.registry;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.mode.NaruStandardMode;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.*;
import net.thevpc.naru.impl.registry.builtindirectives.*;
import net.thevpc.naru.impl.mode.NAruModeRegistry;
import net.thevpc.naru.impl.model.gemini.NaruGeminiProvider;
import net.thevpc.naru.impl.model.ollama.NaruOllamaProvider;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NPairElement;
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

    private final Map<String, NaruToolsetProvider> toolsetProviders = new LinkedHashMap<>();
    private final Map<String, NaruDirectiveProvider> directiveProviders = new LinkedHashMap<>();
    private final List<NaruToolset> activeToolsets = new ArrayList<>();
    //    private final Map<String, NaruTool> tools = new LinkedHashMap<>();
    private final Map<String, NaruDirective> availableDirectives = new LinkedHashMap<>();
    private final Map<String, String> directiveAliases = new LinkedHashMap<>();
    private final Map<String, NaruModelProvider> modelProviders = new HashMap<>();
    private final NAruModeRegistry modeRegistry = new NAruModeRegistry();
    private final NaruSession session;

    public NaruRegistryImpl(NaruSession session) {
        this.session = session;
    }

    @Override
    public List<NaruPromptMode> modes() {
        return modeRegistry.modes();
    }

    @Override
    public List<String> modeNames() {
        return modes().stream().map(x -> NNameFormat.LOWER_KEBAB_CASE.format(x.name())).collect(Collectors.toList());
    }

    @Override
    public List<String> modeNamesAndAliases() {
        List<String> all = new ArrayList<>();
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


    @Override
    public NaruRegistry registerDirectiveProvider(NaruDirectiveProvider directiveProvider) {
        if(directiveProviders.containsKey(directiveProvider.name())){
            throw new IllegalArgumentException("directive provider "+directiveProvider.name()+" already registered");
        }
        directiveProviders.put(directiveProvider.name(), directiveProvider);
        for (NaruDirective directive : directiveProvider.directives()) {
            registerDirective(directive);
        }
        return this;
    }

    @Override
    public NaruRegistry registerToolsetProvider(NaruToolsetProvider tool) {
        toolsetProviders.put(tool.name(), tool);
        NObjectElement e = session.agent().env().get("toolset." + tool.name()).map(x -> {
            if (x instanceof NObjectElement) {
                return (NObjectElement) x;
            }
            return NObjectElement.ofEmpty();
        }).orElse(NObjectElement.ofEmpty());
        List<NPairElement> pairs = e.namedPairs();
        for (NPairElement p : pairs) {
            NObjectElement config = p.value().asObject().orElse(NObjectElement.ofEmpty());
            if (tool.accept(config)) {
                registerToolset(tool.createToolset(p.key().asStringValue().orNull(), config));
            }
        }
        if (pairs.isEmpty()) {
            for (String t : tool.supportedTypes()) {
                NObjectElement config = e.get(t).map(x -> x.asObject().orElse(NObjectElement.ofEmpty())).orElse(NObjectElement.ofEmpty());
                if (tool.accept(config)) {
                    registerToolset(tool.createToolset(t, config));
                }
            }
        }
        return this;
    }

    public NaruRegistry registerToolset(NaruToolset toolset) {
        activeToolsets.add(toolset);
        if (session.isRunning()) {
            toolset.open(session);
        }
        return this;
    }

    public NOptional<NaruTool> findTool(String name) {
        for (NaruToolset a : activeToolsets) {
            for (NaruTool tool : a.tools()) {
                if (tool.name().equals(name)) {
                    return NOptional.of(tool);
                }
            }
        }
        return NOptional.ofNamedEmpty(NMsg.ofC("tool '%s'", name));
    }

    public Map<String, NaruTool> tools() {
        Map<String, NaruTool> all = new LinkedHashMap<>();
        for (NaruToolset a : activeToolsets) {
            for (NaruTool tool : a.tools()) {
                all.put(tool.name(), tool);
            }
        }
        return all;
    }

    public Map<String, NaruDirective> directives() {
        return availableDirectives;
    }


    private NaruRegistry registerDirective(NaruDirective tool) {
        availableDirectives.put(tool.name(), tool);
        for (String alias : tool.getAliases()) {
            String old = directiveAliases.get(alias);
            if (old != null && !old.equals(tool.name())) {
                throw new IllegalArgumentException("alias " + alias + " is already used by " + old);
            }
            directiveAliases.put(alias, tool.name());
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
                ), session).get().getCapabilities();
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
    public NOptional<NaruModelProtocol> protocol(NaruModelConfig model, NaruSession session) {
        return provider(model.provider())
                .flatMap(p -> p.getProtocol(model, session));
    }

    @Override
    public NOptional<NaruModelKey> findModel(String keyOrName, NaruSession session) {
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

        NaruTool tool = findTool(name).orNull();
        if (tool == null) {
            return "ERROR: Unknown tool '" + name + "'. Available tools: " + tools().keySet().stream().sorted().collect(Collectors.joining(", "));
        }
        try {
            return tool.execute(new NaruToolCallContextImpl(arguments, task));
        } catch (Exception e) {
            return "ERROR executing tool '" + name + "': " + e.getMessage();
        }
    }

    public NOptional<NaruDirective> findDirective(String name) {
        NaruDirective d = availableDirectives.get(name);
        if (d == null) {
            String s = directiveAliases.get(name);
            if (s != null) {
                d = availableDirectives.get(s);
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
            task.log(NaruLogMode.TRACE, NMsg.ofC("ERROR: Unknown tool '" + name + "'. Available tools: " + availableDirectives.keySet()).asError());
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
        if (!toolsetProviders.isEmpty()) {
            return false;
        }
        if (!activeToolsets.isEmpty()) {
            return false;
        }
        if (!availableDirectives.isEmpty()) {
            return false;
        }
        if (!directiveAliases.isEmpty()) {
            return false;
        }
        if (!modelProviders.isEmpty()) {
            return false;
        }
        return tools().isEmpty();
    }

    @Override
    public Set<String> toolNames() {
        return Collections.unmodifiableSet(tools().keySet());
    }

    public NaruRegistry registerDefaults() {
        this.registerToolsetProvider(new NaruBuiltinToolsetProvider());
        this.registerDirectiveProvider(new NaruBuiltinDirectiveProvider());
        this.registerModelProvider(new NaruOllamaProvider());
        this.registerModelProvider(new NaruGeminiProvider());
        return this;
    }

    // ── Convenience factory ────────────────────────────────────────────────────

}
