package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.mode.NaruStandardMode;
import net.thevpc.naru.api.model.*;
import net.thevpc.nuts.util.NOptional;

import java.util.*;

public interface NaruRegistry {


    NOptional<NaruTool> findTool(String name);

    Map<String, NaruTool> tools();

    Map<String, NaruDirective> directives();

    NaruRegistry registerToolsetProvider(NaruToolsetProvider tool);

    NaruRegistry registerToolTagProvider(NaruToolTagProvider toolTagProvider);

    NaruRegistry registerDirectiveProvider(NaruDirectiveProvider naruDirectiveProvider);

//    NaruRegistry registerDirective(NaruDirective tool);

    NaruRegistry registerModelProvider(NaruModelProvider tool);

    String dispatch(String name, Map<String, Object> arguments, NaruTask context);

    String dispatch(NaruToolCall toolCall, NaruTask context);

    NOptional<NaruDirective> findDirective(String name);

    void dispatchSlash(String name, String argument, NaruTask task);

    boolean isEmpty();

    Set<String> toolNames();

    Map<String, NaruModelProvider> modelProviders();

    List<NaruModelInfo> modelsInfos(NaruSession session);

    List<NaruModelKey> modelsKeys(NaruSession session);

    NOptional<NaruModelKey> findModel(String keyOrName, NaruSession session);

    NOptional<NaruModelProvider> provider(String provider);

    NOptional<NaruModelProtocol> protocol(NaruModelConfig model, NaruSession session);

    List<NaruPromptMode> modes();

    List<String> modeNames();

    List<String> modeNamesAndAliases();

    void declareMode(NaruPromptMode mode);

    NOptional<NaruPromptMode> mode(NaruStandardMode mode);

    NOptional<NaruPromptMode> mode(String mode);

    NOptional<NaruToolTag> findAvailableTag(String name);

    Map<String, NaruToolTag> availableTags();

    /**
     * The session-scoped feature extensions discovered for this session, in
     * {@link NaruSessionExtension#order()} order. These are the instances the core
     * itself uses, so the same object identity is visible to extensions and tools.
     */
    List<NaruSessionExtension> sessionExtensions();

    /**
     * Finds a session extension by {@link NaruSessionExtension#name()}, optionally
     * narrowed to a known implementation type. Returns empty when the feature is not
     * installed, which is the normal case for an optional feature.
     */
    <T extends NaruSessionExtension> NOptional<T> extension(String name, Class<T> as);

    /**
     * Closes every session extension. Called when a session terminates.
     */
    void close();
}
