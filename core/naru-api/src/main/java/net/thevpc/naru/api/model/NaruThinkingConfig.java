package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruTaskConfig;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NOptional;

/**
 * Resolves whether reasoning ("thinking") is switched on for a task.
 *
 * <p>Tri-state on purpose. Unset is not the same as {@code false}: unset means
 * "let the model decide", which for a provider that reports a reasoning model is
 * {@code think:true} and for one that does not is no flag at all. Collapsing that
 * into a boolean would either make reasoning impossible to turn on for a capable
 * model, or make it impossible to turn off for one that reasons regardless.
 *
 * <p>So the setting only speaks when it is set, and the two consumers each have a
 * different fallback:
 *
 * <ul>
 *   <li>the wire -- {@link #find(NaruTask)} and let the protocol decide what
 *       explicit {@code false} means on that provider's API;</li>
 *   <li>the {@code think} tool -- {@link #isEnabled(NaruTask)}, where unset means
 *       enabled and only an explicit {@code false} hides the tool.</li>
 * </ul>
 */
public final class NaruThinkingConfig {

    /**
     * Configuration key, read through {@link NaruTaskConfig} so it obeys the same
     * task/session/project/system-property precedence as every other setting.
     */
    public static final String THINKING_KEY = "model.thinking";

    private NaruThinkingConfig() {
    }

    /**
     * The configured value, or empty when no scope sets it.
     *
     * <p>A value that is present but not a boolean is reported as absent rather
     * than guessed at: {@code model.thinking=maybe} must not silently read as
     * {@code false} and quietly switch reasoning off.
     */
    public static NOptional<Boolean> find(NaruTask task) {
        NOptional<Object> raw = NaruTaskConfig.find(task, THINKING_KEY);
        if (raw == null || !raw.isPresent()) {
            return NOptional.ofNamedEmpty(NMsg.ofC("'%s' is not set in any scope", THINKING_KEY));
        }
        Boolean parsed = NLiteral.of(NaruTaskConfig.toText(raw.orNull())).asBoolean().orElse(null);
        if (parsed == null) {
            // Present but not a boolean: model.thinking=maybe must not silently
            // read as false and quietly switch reasoning off.
            return NOptional.ofNamedEmpty(NMsg.ofC("'%s' is not a boolean", THINKING_KEY));
        }
        return NOptional.of(parsed);
    }

    /** Whether thinking is on, counting "unset" as on. */
    public static boolean isEnabled(NaruTask task) {
        return find(task).orElse(Boolean.TRUE);
    }
}