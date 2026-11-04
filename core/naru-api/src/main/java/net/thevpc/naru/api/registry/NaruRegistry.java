package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.context.NaruCompactors;
import net.thevpc.naru.api.context.NaruContextCompactor;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.model.*;
import net.thevpc.nuts.util.NOptional;

import java.util.*;

public interface NaruRegistry {


    NOptional<NaruTool> findTool(String name);

    Map<String, NaruTool> tools();

    /** Active toolsets, for lifecycle and hibernation coordination. */
    default List<NaruToolset> toolsets() { return List.of(); }
    default void openToolsets() { }
    default void closeToolsets() { }

    Map<String, NaruDirective> directives();

    NaruRegistry registerToolsetProvider(NaruToolsetProvider tool);

    NaruRegistry registerToolTagProvider(NaruToolTagProvider toolTagProvider);

    NaruRegistry registerDirectiveProvider(NaruDirectiveProvider naruDirectiveProvider);

//    NaruRegistry registerDirective(NaruDirective tool);

    NaruRegistry registerModelProvider(NaruModelProvider tool);

    NaruRegistry registerConfigKeyProvider(NaruConfigKeyProvider configKeyProvider);

    /**
     * The configuration keys every registered {@link NaruConfigKeyProvider}
     * declares for this session, sorted by key. This is the vocabulary
     * {@code /settings} and {@code /set} autocomplete against and {@code /settings}
     * lists.
     */
    List<NaruConfigKey> configKeys();

    String dispatch(String name, Map<String, Object> arguments, NaruTask context);

    String dispatch(NaruToolCall toolCall, NaruTask context);

    NOptional<NaruDirective> findDirective(String name);

    void dispatchSlash(String name, String argument, NaruTask task);

    boolean isEmpty();

    Set<String> toolNames();

    Map<String, NaruModelProvider> modelProviders();

    /**
     * The model filter in effect, or null when every registered model is allowed.
     *
     * <p>It narrows what {@link #modelsKeys(NaruSession)} enumerates (so listings,
     * positional indexes and best-fit selection see only allowed models) and what a
     * transport call may use.
     */
    default NaruModelFilter modelFilter() {
        return null;
    }

    /**
     * Whether {@code model} may be used, per {@link #modelFilter()}. A null filter allows
     * everything. {@code task} may be null when the decision is made outside any task.
     */
    default boolean isModelAllowed(NaruModelKey model, NaruTask task) {
        NaruModelFilter filter = modelFilter();
        return filter == null || filter.accept(model, task);
    }

    List<NaruModelInfo> modelsInfos(NaruSession session);

    List<NaruModelKey> modelsKeys(NaruSession session);

    NOptional<NaruModelKey> findModel(String keyOrName, NaruSession session);

    NOptional<NaruModelProvider> provider(String provider);

    NOptional<NaruModelProtocol> protocol(NaruModelConfig model, NaruSession session);

    List<NaruPromptMode> modes();

    List<String> modeNames();

    List<String> modeNamesAndAliases();

    void declareMode(NaruPromptMode mode);

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
     * Installs one session extension by hand, alongside the ones discovery found.
     *
     * <p>The seam an assembly layer needs: a host that ships a fixed feature set says what
     * runs instead of relying on whatever the classpath happens to hold, and a test builds
     * the one feature it is about rather than the whole profile. An extension already
     * registered under the same {@link NaruSessionExtension#name()} is left in place, since
     * two features answering one name would make the lookup depend on discovery order.
     *
     * <p>The instance is brought up like a discovered one: restored from the state it saved
     * under this session, then opened. A registration that arrived after the session loaded
     * and never got its state would silently start a feature from zero -- for a titler, that
     * means retrying attempts the session already spent.
     */
    NaruRegistry registerSessionExtension(NaruSessionExtension extension);

    /**
     * The installed context compactor, if any.
     *
     * <p>Empty is the normal state for a build with no compaction extension, and callers
     * that require compaction must treat it as an error rather than as "nothing to do" --
     * see {@link NaruCompactors#find}.
     */
    NOptional<NaruContextCompactor> compactor();

    /**
     * Closes every session extension. Called when a session terminates.
     */
    void close();
}
