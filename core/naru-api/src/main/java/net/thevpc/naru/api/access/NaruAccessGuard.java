package net.thevpc.naru.api.access;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.task.NaruTask;

/**
 * The one call a domain makes to ask the session's policy about a resource.
 *
 * <p>It lives in the API because every domain needs it: the core tool and
 * directive dispatchers, and the file, web and shell extensions all gate their
 * entries the same way. A domain supplies the {@link NaruResource} it is about to
 * touch and the {@link NaruAction} it is about to take; this resolves the actor
 * and the policy from the task so no caller has to thread either through.
 *
 * <p><b>The actor is the task's current actor.</b> Because the engine swaps a
 * task's actor for the duration of any statement that names its own author
 * (see {@code NaruTask.effectiveActor}), a check made from inside a statement
 * automatically speaks for that statement's author: an agent's tool call is
 * checked as the agent, a user's directive as the user.
 *
 * <p><b>Missing policy means allow.</b> A task with no session, or a session not
 * wired to a policy, is not denied — it is simply un-policed. This keeps the
 * guard safe to call from code that runs before a policy exists (tests, embedded
 * use) while the default-{@code ALLOW} posture already makes an empty policy a
 * no-op.
 */
public final class NaruAccessGuard {

    private NaruAccessGuard() {
    }

    /** The cached decision for {@code actor} acting on {@code resource} with {@code action}. */
    public static NaruAccessDecision check(NaruTask task, NaruResource resource, NaruAction action) {
        NaruAccessPolicy policy = policyOf(task);
        if (policy == null) {
            return NaruAccessDecision.allow(null, "no access policy in effect");
        }
        // The task travels with the request so a path-like rule written
        // project-relative (e.g. `src/**`) resolves against the project directory
        // at check time; without it the policy would only ever see a bare triple.
        return policy.check(NaruAccessRequest.of(resource, action, actorOf(task)).task(task));
    }

    public static boolean isAllowed(NaruTask task, NaruResource resource, NaruAction action) {
        return check(task, resource, action).allowed();
    }

    /** Like {@link #check} but throws {@link NaruAccessDeniedException} on denial. */
    public static void require(NaruTask task, NaruResource resource, NaruAction action) {
        NaruAccessDecision decision = check(task, resource, action);
        if (!decision.allowed()) {
            throw new NaruAccessDeniedException(decision);
        }
    }

    /** The actor accountable for the work in flight, or {@code null} when there is no task. */
    public static NaruActor actorOf(NaruTask task) {
        return task == null ? null : task.currentActor();
    }

    private static NaruAccessPolicy policyOf(NaruTask task) {
        if (task == null) {
            return null;
        }
        NaruSession session = task.session();
        return session == null ? null : session.accessPolicy();
    }

    /**
     * A stable, model-readable denial. The same shape serves a tool result, a
     * directive log line and a thrown {@link NaruAccessDeniedException}, so a
     * denial reads the same wherever it surfaces.
     */
    public static String denyMessage(NaruTask task, NaruResource resource, NaruAction action,
                                     NaruAccessDecision decision) {
        NaruActor actor = actorOf(task);
        StringBuilder sb = new StringBuilder();
        sb.append("ERROR: permission denied: ")
                .append(actor == null ? "actor <unknown>" : actor.toString())
                .append(" may not ").append(action.id())
                .append(' ').append(resource.type().id())
                .append(' ').append(resource.locator());
        NaruPermissionRule rule = decision == null ? null : decision.matchedRule().orNull();
        if (rule != null) {
            sb.append("\n  matched rule: ").append(rule.id())
                    .append(" (source=").append(rule.source())
                    .append(", effect=").append(rule.effect()).append(')');
        } else {
            sb.append("\n  matched rule: <default>")
                    .append(" (effect=").append(decision == null ? NaruAccessEffect.DENY : decision.effect())
                    .append(')');
        }
        return sb.toString();
    }
}
