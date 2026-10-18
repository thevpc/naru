package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAccessPolicy;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruPermissionStore;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * The door a host that cannot compile against this jar uses: the {@link NaruPermissionStore}
     * interface in {@code naru-api}, resolved by type token through the registry. The desktop's
     * Permissions tab reads and writes the committed file this way, so the test has to build nothing
     * from this module — if it imported {@code NaruPermissionsExtension} it would not be proving the
     * same thing.
     */
    @Test
    public void aHostThatNeverSeesThisClassReachesTheStoreThroughTheApi() {
        NaruSession session = newSession("api-surface");
        NaruPermissionStore store = NaruPermissionStore.of(session);
        assertNotNull(store, "the committed-file surface must be reachable from naru-api alone");
        assertEquals(NaruPermissionsExtension.NAME, store.name());

        NaruPermissionRule rule = new NaruPermissionRule().id("gui-save")
                .resourceType(NaruResourceType.FILE).patterns("src/**")
                .actions(NaruAction.WRITE).effect(NaruAccessEffect.DENY);
        assertNull(store.writeProjectRules(List.of(rule), NaruAccessEffect.ALLOW));
        assertTrue(store.projectFile().isRegularFile());
        assertEquals(List.of("gui-save"),
                store.readProjectRules().stream().map(NaruPermissionRule::id).toList(),
                "what the API writes, the API reads back in order");
        assertNull(store.reloadProjectRules());
        assertEquals(NaruPermissionStore.SOURCE_PROJECT,
                session.accessPolicy().rule("gui-save").get().source(),
                "a reload through the API tags rules with the same origin the directive uses");
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
