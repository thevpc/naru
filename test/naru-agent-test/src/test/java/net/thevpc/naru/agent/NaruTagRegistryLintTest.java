package net.thevpc.naru.agent;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lints the tag vocabulary against the tools that actually wear it.
 *
 * <p>The tag gate is only as trustworthy as the registry behind it. Three properties
 * are checked here, each of which fails silently in production otherwise:
 *
 * <ul>
 *   <li><b>Every tag a tool wears is registered.</b> {@code addToolTag} throws on an
 *       unknown tag, so an unregistered tag on a tool is a permission that can never
 *       be granted -- the tool is unreachable forever and nothing says so.</li>
 *   <li><b>Every tool is tagged, or explicitly essential.</b> The gate is fail-closed:
 *       an untagged tool that is not {@code isEssential()} is invisible to every task.
 *       That is the correct default for a tool nobody wired up, but it means a newly
 *       added tool that forgot its tags vanishes without an error. This lint turns
 *       that silence into a test failure.</li>
 *   <li><b>Every registered tag has a tool.</b> A tag with no tool is vocabulary a
 *       user can grant that opens nothing -- the mirror image of the first property.</li>
 * </ul>
 *
 * <p>It also pins the {@code java} and {@code semantic} tags back onto the tools they
 * name. Both used to be registered while every Java and semantic tool wore only
 * {@code dev}, so {@code /tags enable java} granted nothing at all -- an unused tag
 * reads as a broken command. The tags are wired <b>additively</b>: the tools keep
 * {@code dev} and gain {@code java}/{@code semantic}, so the grant only widens.
 */
public class NaruTagRegistryLintTest {

    /**
     * Tags whose toolset is populated from configuration rather than from the classpath:
     * an MCP tool only exists once a server is declared in the agent env. Every other
     * tag is backed by tools that are registered unconditionally.
     */
    private static final Set<String> CONFIG_DRIVEN_TAGS = Set.of("mcp");

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
        agent.projectDirectory(NPath.ofTempFolder("naru-tag-lint"));
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

    private Map<String, NaruTool> tools() {
        return session.registry().tools();
    }

    private Set<String> availableTags() {
        return session.registry().availableTags().keySet();
    }

    // ── every tag on a tool must be grantable ────────────────────────────────

    @Test
    public void everyTagAWornIsRegistered() {
        Set<String> available = availableTags();
        List<String> unreachable = new ArrayList<>();
        for (NaruTool tool : tools().values()) {
            for (String tag : tool.tags()) {
                if (!available.contains(tag)) {
                    unreachable.add(tool.name() + " wears unregistered tag '" + tag + "'");
                }
            }
        }
        assertEquals(List.of(), unreachable,
                "these tags gate tools but no provider declares them, so "
                        + "addToolTag() would throw and the tools could never be revealed: "
                        + unreachable);
    }

    // ── every tool must be reachable one way or another ──────────────────────

    @Test
    public void everyToolIsTaggedOrEssential() {
        List<String> invisible = new ArrayList<>();
        for (NaruTool tool : tools().values()) {
            if (tool.tags().isEmpty() && !tool.isEssential()) {
                invisible.add(tool.name());
            }
        }
        assertEquals(List.of(), invisible,
                "these tools wear no tag and did not declare themselves essential: "
                        + "the fail-closed gate hides them from every task, so they are "
                        + "dead weight until they are tagged: " + invisible);
    }

    // ── every registered grant must open something ───────────────────────────

    @Test
    public void everyRegisteredTagHasAtLeastOneTool() {
        Set<String> worn = new TreeSet<>();
        for (NaruTool tool : tools().values()) {
            worn.addAll(tool.tags());
        }
        Set<String> empty = new TreeSet<>(availableTags());
        empty.removeAll(worn);
        empty.removeAll(CONFIG_DRIVEN_TAGS);
        assertEquals(new TreeSet<>(), empty,
                "these tags can be granted but no tool wears them: granting them "
                        + "changes nothing, which reads as a broken /tags command: " + empty);
    }

    // ── the two tags that must stay wired additively ─────────────────────────

    @Test
    public void javaAndSemanticAreRegisteredAndBackedByTools() {
        // both were once declared while their tools wore only 'dev', so enabling
        // them was a no-op. They are now additive: the tools keep 'dev' and wear
        // the specific tag too, so the grant widens visibility.
        assertTrue(availableTags().contains("java"),
                "'java' must stay registered now that Java tools wear it");
        assertTrue(availableTags().contains("semantic"),
                "'semantic' must stay registered now that semantic tools wear it");

        assertTrue(mavenTools().stream()
                        .allMatch(t -> t.tags().contains("java")),
                "every Java (maven) tool must wear the 'java' tag");
        assertTrue(semanticTools().stream()
                        .allMatch(t -> t.tags().contains("semantic")),
                "every semantic tool must wear the 'semantic' tag");
    }

    private List<NaruTool> mavenTools() {
        return tools().values().stream()
                .filter(t -> t.name().startsWith("maven_"))
                .toList();
    }

    private List<NaruTool> semanticTools() {
        return tools().values().stream()
                .filter(t -> t.name().startsWith("semantic_"))
                .toList();
    }

    /**
     * The point of the additive wiring, stated from the user's side: what {@code /tags
     * available} lists is exactly what {@code /tags enable} can act on. A tag that
     * appears there must have tools behind it, or the command lies.
     */
    @Test
    public void everyAvailableTagIsMeaningful() {
        Set<String> worn = new TreeSet<>();
        for (NaruTool tool : tools().values()) {
            worn.addAll(tool.tags());
        }
        Set<String> grantable = new TreeSet<>(availableTags());
        assertTrue(grantable.stream().anyMatch(worn::contains),
                "sanity: at least some available tags are worn by tools");
        for (String tag : grantable) {
            if (CONFIG_DRIVEN_TAGS.contains(tag)) {
                continue;
            }
            assertTrue(worn.contains(tag),
                    "tag '" + tag + "' is offered by /tags available but no tool wears it");
        }
    }

    private static final NaruSessionListener NOOP_LISTENER = new NaruSessionListener() {
        @Override
        public void onEventAppended(NaruEvent newEvent) {
        }

        @Override
        public void sessionStarted(NaruSession s) {
        }

        @Override
        public void sessionStopped(NaruSession s) {
        }

        @Override
        public void onSessionReloaded(NaruSession s) {
        }
    };
}
