package net.thevpc.naru.api.plan;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruSessionExtension;

import java.util.List;

/**
 * The read-only view of the session's plans, for a host that displays them.
 *
 * <p><b>Why an interface in the API.</b> Plans belong to the optional {@code naru-plan} extension.
 * A host — the desktop's Plan panel, or the cmdline REPL's {@code /plan} output — must not put that
 * jar on its compile classpath: Nuts resolves extensions in their own classloaders, and pinning one
 * there has produced a provider compiled against an older {@code naru-api}. So the host asks the
 * registry for this type by name, which is the door
 * {@link net.thevpc.naru.api.registry.NaruRegistry#extension(String, Class)} already opens. The
 * extension implements it; a profile without the extension simply has no reader.
 *
 * <p><b>Read-only.</b> Writing a plan belongs to the model-facing tools and to {@code /plan}, both
 * of which live beside the graph and can validate it. A host that edited plans through this seam
 * would bypass the dependency and validator rules that keep a plan honest.
 *
 * <p>It extends {@link NaruSessionExtension} for the lookup alone — that registry query is typed
 * over session extensions, and the implementing extension already is one. Nothing here is a new
 * lifecycle hook.
 */
public interface NaruPlanReader extends NaruSessionExtension {

    /** The {@link #name()} of the extension that provides this, for the registry lookup. */
    String EXTENSION = "plan";

    /** Every plan the session holds, in creation order. Empty, never null, when there are none. */
    List<NaruPlanSnapshot> allPlans();

    /**
     * The plan the session is working through, or {@code null} when none is active. Absent is the
     * normal state of a fresh session, not an error.
     */
    NaruPlanSnapshot activePlan();

    /**
     * The session's reader, or {@code null} when the {@code naru-plan} extension is not installed. A
     * host must then say that planning is unavailable rather than read the extension's own state
     * file with a parser of its own, because two parsers of one format drift.
     */
    static NaruPlanReader of(NaruSession session) {
        if (session == null || session.registry() == null) {
            return null;
        }
        try {
            return session.registry().extension(EXTENSION, NaruPlanReader.class).orNull();
        } catch (RuntimeException e) {
            // an extension that is present but half-initialised must not cost the host its session
            return null;
        }
    }
}
