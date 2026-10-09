package net.thevpc.naru.api.agent;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;

import java.util.Map;

/**
 * A keyed store of configuration values that can be told apart by visibility.
 *
 * <p>Visibility is not a scope. It is the second axis of the same store, and the two
 * are routinely confused: "where does this live" (task, session, config) and "who is
 * this for" (private, public) are independent questions, and a value answers both at
 * once. Collapsing them produces labels like "project (private)" that read as a scope
 * but are really a store plus a visibility.
 */
public interface NaruEnv {

    /**
     * The value for a key from whichever file holds it, or empty.
     *
     * <p>When both visibilities hold the key, private wins -- the same rule
     * {@link #put(String, NElement, NaruVisibility)} writes with, so a private value
     * is never masked by a public one.
     */
    NOptional<NElement> get(String key);

    /**
     * The value for a key from one specific visibility, or empty.
     *
     * <p>The reason this exists: {@link #get(String)} answers "what is the value" but
     * not "which file did it come from", and a setting that appears not to work is
     * usually exactly that question. Asking per visibility is what lets a caller say
     * which file answered instead of guessing.
     */
    NOptional<NElement> get(String key, NaruVisibility visibility);

    void put(String key, NElement value, NaruVisibility visibility);

    /**
     * Every key in this store, for a listing.
     *
     * <p>For the two config files this is the merged view, private winning, the same way
     * {@link #get(String)} resolves a key. A store that cannot enumerate returns empty
     * rather than failing, so a listing degrades to "nothing to show" instead of erroring.
     */
    default Map<String, NElement> entries() {
        return Map.of();
    }

}