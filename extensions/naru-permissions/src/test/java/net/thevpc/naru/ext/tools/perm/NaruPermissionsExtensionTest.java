package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruAccessPolicy;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extension as a real session sees it: discovered through the SPI, opened
 * before any task runs, installing its built-ins and subscribing to events with
 * no manual wiring.
 */
public class NaruPermissionsExtensionTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NaruSessionImpl newSession(String name) {
        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-perm-ext-" + name));
        // configureDefaults=true: this is how a user session is built, and it is the
        // only path that proves SPI discovery and open() ordering together
        return new NaruSessionImpl(agent, agent.projectDirectory(), null, true, null, null, null, null);
    }

    private static List<String> coreRuleIds(NaruSessionImpl session) {
        return session.accessPolicy().rules().stream()
                .filter(r -> NaruAccessPolicy.SOURCE_CORE.equals(r.source()))
                .map(NaruPermissionRule::id)
                .toList();
    }

    @Test
    public void theExtensionIsDiscoverableThroughTheRegistry() {
        NaruSessionImpl session = newSession("registry");
        assertTrue(session.registry().extension(
                        NaruPermissionsExtension.NAME, NaruPermissionsExtension.class).isPresent(),
                "the extension must be registered under its SPI name");
    }

    @Test
    public void builtinsAreInstalledWithoutBeingAsked() {
        NaruSessionImpl session = newSession("builtins");
        List<String> core = coreRuleIds(session);

        assertTrue(core.contains(NaruPermissionsBuiltins.NO_AGENT_POLICY_DIRECTIVE));
        assertTrue(core.contains(NaruPermissionsBuiltins.NO_AGENT_SECRET_FILES));
    }

    @Test
    public void firedEventsReachTheExtensionWithoutManualWiring() {
        NaruSessionImpl session = newSession("events");

        session.fireEvent(NaruPermissionsEvents.GRANT, Map.of(
                "id", "evt-allow", "type", "tool", "pattern", "echo_tool",
                "actions", "call", "effect", "allow"));

        assertNotNull(session.accessPolicy().rule("evt-allow").orNull(),
                "the extension subscribed itself as a session listener in open()");
    }
}
