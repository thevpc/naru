package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The in-memory agent env: values shared by every session in this run, sitting between the
 * session env and the on-disk config files.
 *
 * <p>It exists so a value that should apply to every session of one process -- a runtime
 * override set with {@code /set --agent} -- has a home that is neither per-task, per-session,
 * nor a file. Nothing here survives a restart, which is the point: writing a temporary
 * override to {@code .naru/config/env.tson} would leak it into the project.
 *
 * <p>Visibility is accepted for {@link NaruEnv} symmetry but there is only one store. A
 * private/public split only means something for the two config files, where it decides
 * which file holds a value; an in-memory value has no file to be private about.
 */
public class NaruMemoryEnv implements NaruEnv {
    private final Map<String, NElement> values = new LinkedHashMap<>();

    @Override
    public synchronized NOptional<NElement> get(String key) {
        NElement value = values.get(key);
        return value == null
                ? NOptional.ofNamedEmpty(NMsg.ofC("'%s' is not set in the agent env", key))
                : NOptional.of(value);
    }

    @Override
    public synchronized NOptional<NElement> get(String key, NaruVisibility visibility) {
        // one store, so asking per visibility is the same question
        return get(key);
    }

    @Override
    public synchronized void put(String key, NElement value, NaruVisibility visibility) {
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
    }

    @Override
    public synchronized Map<String, NElement> entries() {
        return new LinkedHashMap<>(values);
    }
}
