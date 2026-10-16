package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruAccessPolicy;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
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

    private static NaruSession newSession(String name) {
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(NPath.ofTempFolder("naru-perm-ext-" + name));
        // configureDefaults=true: this is how a user session is built, and it is the
        // only path that proves SPI discovery and open() ordering together
        return agent.newSession()
                .directory(agent.projectDirectory())
                .interaction(null)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
    }

    private static List<String> coreRuleIds(NaruSession session) {
        return session.accessPolicy().rules().stream()
                .filter(r -> NaruAccessPolicy.SOURCE_CORE.equals(r.source()))
                .map(NaruPermissionRule::id)
                .toList();
    }

    @Test
    public void theExtensionIsDiscoverableThroughTheRegistry() {
        NaruSession session = newSession("registry");
        assertTrue(session.registry().extension(
                        NaruPermissionsExtension.NAME, NaruPermissionsExtension.class).isPresent(),
                "the extension must be registered under its SPI name");
    }

    @Test
    public void builtinsAreInstalledWithoutBeingAsked() {
        NaruSession session = newSession("builtins");
        List<String> core = coreRuleIds(session);

        assertTrue(core.contains(NaruPermissionsBuiltins.NO_AGENT_POLICY_DIRECTIVE));
        assertTrue(core.contains(NaruPermissionsBuiltins.NO_AGENT_SECRET_FILES));
    }

    @Test
    public void firedEventsReachTheExtensionWithoutManualWiring() {
        NaruSession session = newSession("events");

        session.fireEvent(NaruPermissionsEvents.GRANT, Map.of(
                "id", "evt-allow", "type", "tool", "pattern", "echo_tool",
                "actions", "call", "effect", "allow"));

        assertNotNull(session.accessPolicy().rule("evt-allow").orNull(),
                "the extension subscribed itself as a session listener in open()");
    }
}
