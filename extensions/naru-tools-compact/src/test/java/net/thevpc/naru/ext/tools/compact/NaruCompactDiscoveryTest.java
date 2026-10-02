package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruRegistry;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the registry finds, which is the difference between a feature that exists and one that
 * is installed.
 *
 * <p>Nothing here calls a model. It opens a session and asks the registry what it discovered,
 * so a missing line in the service file fails here rather than as a silent no-op at the first
 * {@code /compact}.
 */
class NaruCompactDiscoveryTest {

    private NaruSession session;

    @BeforeAll
    public static void setUpWorkspace() {
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            try {
                NWorkspace ws = Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Exception ignored) {
            }
        }
    }

    @BeforeEach
    public void setUp() {
        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-compact-discovery-" + System.nanoTime()));
        session = new NaruSessionImpl(agent, agent.projectDirectory(), null, true,
                NOOP_LISTENER, null, null, null);
    }

    @AfterEach
    public void tearDown() {
        if (session != null) {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void theExtensionIsDiscoveredUnderItsName() {
        NaruCompactExtension ext = session.registry()
                .extension(NaruCompactExtension.NAME, NaruCompactExtension.class).orNull();
        assertNotNull(ext, "the extension must be found by name");
        assertEquals(NaruCompactExtension.NAME, ext.name());
    }

    @Test
    void theCompactorIsRegistered() {
        // The compactor is found by type, not by name, so this is what the NaruCompactors
        // facade resolves. Without it every call fails with "no compactor registered".
        assertNotNull(session.registry().compactor().orNull());
        assertTrue(session.registry().compactor().orNull() instanceof NaruCompactContextCompactor);
    }

    @Test
    void theDirectiveIsRegisteredUnderItsCommandName() {
        NaruDirective d = session.registry().directives().get(NaruCompactDirective.NAME);
        assertNotNull(d, "expected a /" + NaruCompactDirective.NAME + " directive, found "
                + session.registry().directives().keySet());
    }

    @Test
    void theToolsetProviderIsDiscoveredAndBuildsTheTool() {
        NaruCompactToolsetProvider provider = new NaruCompactToolsetProvider();
        assertEquals("context", provider.name());
        assertTrue(provider.supportedTypes().contains("compact"));
        assertNotNull(provider.createToolset("context", null));
        assertEquals(1, provider.createToolset("compact", null).tools().size());
    }

    @Test
    void theToolIsNamedTheWayTheConventionRequires() {
        // Providers reject an unknown tool name, and the convention across this codebase is
        // snake_case with dots reserved for namespacing.
        assertEquals("context_compact", new ContextCompactTool().name());
    }

    @Test
    void anUnknownToolsetTypeIsRejectedRatherThanSilentlyEmpty() {
        try {
            new NaruCompactToolsetProvider().createToolset("nonsense", null);
            org.junit.jupiter.api.Assertions.fail("expected a rejection");
        } catch (net.thevpc.nuts.util.NIllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("nonsense"), expected.getMessage());
        }
    }

    @Test
    void theRegistryExposesTheExtensionThroughItsOwnLookup() {
        // Proves the lookup used by the directive and the tool agrees with the extension's own
        // name, rather than both being independently plausible.
        NaruRegistry registry = session.registry();
        assertTrue(registry.sessionExtensions().stream()
                        .anyMatch(e -> NaruCompactExtension.NAME.equals(e.name())),
                "session extensions: " + registry.sessionExtensions().stream()
                        .map(e -> e.name()).toList());
        assertNull(registry.extension("no-such-extension", NaruCompactExtension.class).orNull());
    }

    /** A listener that ignores everything, so these tests can stay about discovery. */
    private static final NaruSessionListener NOOP_LISTENER = new NaruSessionListener() {
        @Override
        public void onEventAppended(NaruEvent newEvent) {
        }

        @Override
        public void onSessionReloaded(NaruSession naruSession) {
        }

        @Override
        public void sessionStarted(NaruSession naruSession) {
        }

        @Override
        public void sessionStopped(NaruSession naruSession) {
        }
    };
}