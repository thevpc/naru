package net.thevpc.naru.api.registry;

import net.thevpc.nuts.cmdline.NArgCompleteCandidate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One configuration key a feature reads, together with the words that describe it.
 *
 * <p>This is the unit of the "settings surface" of NARU: the vocabulary that
 * {@code /settings} and {@code /set} autocomplete and that {@code /settings}
 * lists. Keys are declared by a {@link NaruConfigKeyProvider} next to the code
 * that consumes them, so the interactive shell can offer exactly the keys a
 * build understands instead of a hand-maintained copy that drifts.
 *
 * <p>A key carries:
 *
 * <ul>
 *   <li>{@link #key()} -- the dotted name resolved through the
 *       {@link net.thevpc.naru.api.agent.NaruTaskConfig} chain;</li>
 *   <li>{@link #description()} -- one line saying what the key controls;</li>
 *   <li>optionally {@link #values()} -- the fixed vocabulary for the value part
 *       ({@code true}/{@code false}, an enum, ...); null means free-form.</li>
 * </ul>
 *
 * <p>Dynamic keys are allowed: a provider gets the session and may emit concrete
 * keys that only exist because of what is in it, such as
 * {@code model.qwen.readTimeout} for a model family that is actually installed.
 * That is what makes completion useful where the surface depends on the machine,
 * not just on the build.
 */
public final class NaruConfigKey {

    private final String key;
    private final String description;
    private final List<String> values;

    /** A key with no fixed value vocabulary, e.g. {@code model.timeout}. */
    public static NaruConfigKey of(String key, String description) {
        return new NaruConfigKey(key, description, null);
    }

    private NaruConfigKey(String key, String description, List<String> values) {
        this.key = Objects.requireNonNull(key, "key");
        this.description = description == null ? "" : description;
        this.values = values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }

    /** This key with a fixed value vocabulary, e.g. {@code true}/{@code false}. */
    public NaruConfigKey withValues(String... values) {
        return withValues(values == null ? List.of() : Arrays.asList(values));
    }

    /** This key with a fixed value vocabulary, e.g. {@code true}/{@code false}. */
    public NaruConfigKey withValues(List<String> values) {
        return new NaruConfigKey(key, description, values);
    }

    /** The dotted key as written at the prompt, e.g. {@code model.thinking}. */
    public String key() {
        return key;
    }

    /** One line describing what the key controls, for completion and listing. */
    public String description() {
        return description;
    }

    /**
     * The fixed values the key accepts, or null when the value is free-form.
     *
     * <p>Completion uses it to offer {@code key=value} once the text reaches the
     * {@code =}; the keys that have one are the booleans and the small enums.
     */
    public List<String> values() {
        return values;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NaruConfigKey)) {
            return false;
        }
        NaruConfigKey that = (NaruConfigKey) o;
        return key.equals(that.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key;
    }

    // ── completion ────────────────────────────────────────────────────────────

    /**
     * Adds the completion candidates each key can produce against the text
     * already typed, in the same shape the directives have always used:
     *
     * <ul>
     *   <li>while the user types the key: {@code key} and {@code key=}, each
     *       annotated with {@code "key — description"};</li>
     *   <li>once the text reaches the {@code =}: {@code key=value} for every
     *       {@linkplain #values() fixed value}, still annotated.</li>
     * </ul>
     *
     * <p>A key whose declared values are null offers only the {@code key=} hole;
     * the value itself is free-form and cannot be completed.
     */
    public static void addCandidates(List<NArgCompleteCandidate> out,
                                     String current,
                                     Collection<NaruConfigKey> keys) {
        for (NaruConfigKey key : keys) {
            addCandidates(out, current, key);
        }
    }

    /** Completion candidates of a single key, see {@link #addCandidates(List, String, Collection)}. */
    public static void addCandidates(List<NArgCompleteCandidate> out,
                                     String current,
                                     NaruConfigKey key) {
        String k = key.key();
        String withEq = k + "=";
        if (current.indexOf('=') >= 0) {
            // the user already typed (part of) "key=": only the same key can answer
            if (!current.startsWith(withEq)) {
                return;
            }
            if (key.values() == null) {
                return; // free-form value: nothing fixed to complete past the '='
            }
            for (String v : key.values()) {
                String candidate = withEq + v;
                if (candidate.startsWith(current)) {
                    out.add(NArgCompleteCandidate.of(candidate, v + " — " + key.description()));
                }
            }
            return;
        }
        String annotated = k + " — " + key.description();
        if (k.startsWith(current)) {
            out.add(NArgCompleteCandidate.of(k, annotated));
        }
        if (withEq.startsWith(current)) {
            out.add(NArgCompleteCandidate.of(withEq, k + "= — " + key.description()));
        }
    }
}