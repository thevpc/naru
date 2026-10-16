package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.io.NPath;

/**
 * A real session for the permission tests.
 *
 * <p>The policy under test is the production one ({@code NaruAccessPolicyImpl}),
 * reached through {@code NaruSession.accessPolicy()}, rather than a stub: the
 * store, the directive and the tools all speak to the session's policy, and a
 * stub would let a wiring mistake pass.
 */
final class NaruPermissionsTestSupport {

    private NaruPermissionsTestSupport() {
    }

    static NaruSessionImpl session() {
        NPath dir = NPath.ofTempFolder("naru-perm-" + System.nanoTime());
        NaruWorkspace agent = NaruWorkspace.of();
        // the store config resolves through the agent's project env, so the project
        // directory must be set before the session is constructed
        agent.projectDirectory(dir);
        return (NaruSessionImpl) agent.newSession()
                .directory(dir)
                .interaction(new NaruStreamInteraction(o -> {
                }))
                .configureDefaults(false)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
    }

    static NaruTask task(NaruSession session) {
        return session.newTask(NaruTaskSpec.of());
    }
}
