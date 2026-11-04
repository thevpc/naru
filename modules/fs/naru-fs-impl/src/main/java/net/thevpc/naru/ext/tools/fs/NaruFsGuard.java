package net.thevpc.naru.ext.tools.fs;

import net.thevpc.naru.api.access.NaruAccessDecision;
import net.thevpc.naru.api.access.NaruAccessGuard;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruResource;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.io.NPath;

/**
 * The filesystem domain's single call into the session access policy (design
 * hooks #5/#6 in {@code documentation/architecture/permissions.md}).
 *
 * <p>Every concrete filesystem entry — the {@code file_*} tools, the
 * {@code /file}, {@code /cat}, {@code /ls} and {@code /cd} directives — funnels
 * through {@link FileToolHelper} or one of this class's {@code deny} overloads,
 * so a rule written against a {@code file}/{@code dir} pattern bites no matter
 * which entry a task takes.
 *
 * <p>{@code deny} returns {@code null} when the actor may proceed and a
 * model-readable denial string otherwise, matching the error convention of the
 * file helpers ({@code "ERROR: ..."}) so a denial surfaces exactly like any
 * other refused file operation.
 *
 * <p>The resource locator is the <b>resolved</b> path, and the {@link NaruTask}
 * rides along on the request so a project-relative rule pattern such as
 * {@code src/**} is resolved against the task's project directory at check time.
 */
public final class NaruFsGuard {

    private NaruFsGuard() {
    }

    /**
     * The resource type is inferred from the path itself: a directory becomes a
     * {@code dir} resource, anything else (including a file being created, which
     * does not exist yet) a {@code file}.
     */
    public static String deny(NaruTask task, NPath path, NaruAction action) {
        if (path == null) {
            return null;
        }
        return deny(task, path, path.isDirectory() ? NaruResourceType.DIR : NaruResourceType.FILE, action);
    }

    /**
     * The explicit-type overload, for a caller that knows the intent before the
     * path exists (a directory search, a create).
     */
    public static String deny(NaruTask task, NPath path, NaruResourceType type, NaruAction action) {
        if (task == null || path == null) {
            return null;
        }
        NaruResource resource = NaruResource.of(type, path.toString());
        NaruAccessDecision decision = NaruAccessGuard.check(task, resource, action);
        return decision.allowed() ? null : NaruAccessGuard.denyMessage(task, resource, action, decision);
    }
}
