package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruSessionExtension;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

import java.util.Map;

/**
 * What a model call may know about where it runs.
 *
 * <p>Providers and protocols used to receive the whole {@link NaruTask} or {@link NaruSession},
 * which tied every model call to a running workspace and let the model layer reach anywhere
 * into the engine. They need much less: settings, credentials, a slot for per-session state,
 * a way to report rate limits and to tell the user something, and labels for the audit
 * trail. This is that, and nothing else; in particular there is deliberately no getter for
 * the task or the session behind it.
 *
 * <p>Inside the engine a context wraps the calling task ({@link #of(NaruTask)}) or session
 * ({@link #of(NaruSession)}) and inherits everything they configure. Outside it,
 * {@link #standalone()} lets a program call a model with no workspace and no session; only
 * nuts itself has to be running.
 */
public interface NaruModelContext {

    /**
     * A setting from the configuration chain: task env, session env, workspace env, project
     * config, then the JVM system property. Standalone: the values given, then the system
     * property.
     */
    NOptional<NElement> config(String key);

    /**
     * A credential or endpoint variable: session env, workspace env, then the process
     * environment. Null when no layer sets it; a blank value counts as unset.
     */
    String envValue(String name);

    /**
     * The session extension registered under {@code name}: per-session state a provider keeps
     * between calls, such as prompt-cache bookkeeping. Empty outside a session.
     */
    <T extends NaruSessionExtension> NOptional<T> sessionExtension(String name, Class<T> type);

    /** Reports the rate limits a provider announced with its last response. */
    void reportRateLimits(NaruProviderRateLimitInfo info);

    /**
     * Tells the user something about this call that is not part of the answer, such as a tool
     * call that could not be read. Within a task it joins the task's history as an engine
     * message; standalone it is dropped.
     */
    void notice(NMsg message);

    /** The directory of the session this call belongs to, for its audit trail. */
    NOptional<NPath> sessionDir();

    /** The id of the calling session, or null outside a session. */
    String sessionId();

    /** The id of the calling task, or 0 outside a task. */
    long taskId();

    /** The name of the calling task, or null. */
    String taskName();

    /** The context of a call made by {@code task}. */
    static NaruModelContext of(NaruTask task) {
        return task == null ? standalone() : new NaruTaskModelContext(task);
    }

    /** The context of a call made on behalf of {@code session}, with no task. */
    static NaruModelContext of(NaruSession session) {
        return session == null ? standalone() : new NaruSessionModelContext(session);
    }

    /** A call outside any workspace, configured by system properties and the process environment. */
    static NaruModelContext standalone() {
        return standalone(Map.of());
    }

    /**
     * A call outside any workspace. {@code values} answers both {@link #config} and
     * {@link #envValue} first, so a program can pass an API key or a setting without exporting
     * it.
     */
    static NaruModelContext standalone(Map<String, String> values) {
        return new NaruStandaloneModelContext(values);
    }
}
