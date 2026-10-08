package net.thevpc.naru.agent;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.engine.scheduler.NaruTaskImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A task's granted tags and tool exclusions are standing permissions: they decide what the
 * model is offered on every request. They used to be session-scoped only -- written into no
 * element and restored by nothing -- so a saved session came back with a task that could see
 * nothing it had been granted and everything it had banned. These pin the round trip, the
 * tolerance for a tag whose provider has gone missing, and the versioned element that makes
 * the change detectable.
 */
public class NaruTaskStatePersistenceTest {

    private NaruAgent agent;
    private NaruSession session;
    private final List<String> warnings = new ArrayList<>();

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
        agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-task-state"));
        NaruStreamInteraction interaction = new NaruStreamInteraction(output -> {
            if (output.mode() == NaruLogMode.SCRIPT) {
                warnings.add(output.message().toString());
            }
        });
        session = new NaruSessionImpl(agent, agent.projectDirectory(), interaction, true,
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

    private static List<String> names(List<NaruToolTag> tags) {
        return tags.stream().map(NaruToolTag::name).sorted().collect(Collectors.toList());
    }

    private static List<String> toolNames(NaruTask task) {
        return task.findTools().stream().map(NaruToolDefinition::getName).collect(Collectors.toList());
    }

    // ── the round trip ───────────────────────────────────────────────────────

    @Test
    public void grantedTagsAndExclusionsSurviveASaveReloadRoundTrip() {
        NaruTask task = session.newTask(NaruTaskSpec.of().toolTags("fs", "network"));
        task.addToolExclusion("run_shell");
        task.addToolExclusion("cd");
        long id = task.id();

        session.save();
        session.restoreFromStore();

        NaruTask reloaded = session.findTask(id).orElseThrow(
                () -> new AssertionError("the task did not come back after the reload"));
        assertEquals(new TreeSet<>(Set.of("fs", "network")), new TreeSet<>(names(reloaded.findToolTags())),
                "granted tags were not restored");
        assertEquals(new TreeSet<>(Set.of("run_shell", "cd")), new TreeSet<>(reloaded.findToolExclusions()),
                "tool exclusions were not restored");
    }

    @Test
    public void restoredExclusionsStillHideTheirToolsAfterAReload() {
        NaruTask task = session.newTask(NaruTaskSpec.of().toolTags("fs", "network"));
        // sanity: both are actually offered before the ban takes effect
        List<String> before = toolNames(task);
        assertTrue(before.contains("search_web"), () -> before.toString());
        assertTrue(before.contains("file_read"), () -> before.toString());
        task.addToolExclusion("search_web");
        task.addToolExclusion("file_read");

        long id = task.id();
        session.save();
        session.restoreFromStore();

        List<String> tools = toolNames(session.findTask(id)
                .orElseThrow(() -> new AssertionError("the task did not come back after the reload")));
        assertFalse(tools.contains("search_web"), () -> "the restored task still sees a banned tool: " + tools);
        assertFalse(tools.contains("file_read"), () -> "the restored task still sees a banned tool: " + tools);
    }

    @Test
    public void theTaskElementCarriesASchemaVersionAndTheGrantedNames() {
        NaruTaskImpl task = (NaruTaskImpl) session.newTask(NaruTaskSpec.of().toolTags("fs"));
        task.addToolExclusion("cd");

        NElement element = task.toElement();
        assertEquals(NaruTaskImpl.TASK_SCHEMA_VERSION,
                element.asObject().get().getIntValue("schemaVersion").orElse(-1),
                "the task element must be versioned so the added fields are auditable");
        assertEquals(List.of("fs"), stringArray(element, "toolTags"));
        assertEquals(List.of("cd"), stringArray(element, "excludedTools"));
    }

    // ── an unknown tag is kept, and warns ────────────────────────────────────

    @Test
    public void anUnknownTagSurvivesAReloadWithAWarning() {
        NaruTaskImpl task = (NaruTaskImpl) session.newTask(NaruTaskSpec.of().toolTags("fs"));
        warnings.clear();

        // an element as it would have been written by a session where the tag's provider
        // was installed, then loaded again after that provider was removed
        task.load(withToolTags(task.toElement(), "fs", "no-such-tag"));

        // it is kept, so a later save does not silently drop a capability
        assertEquals(List.of("fs", "no-such-tag"), stringArray(task.toElement(), "toolTags"),
                "an unknown tag name must survive the load rather than be dropped");
        // ... but it resolves to no definition, because no provider declares it
        assertEquals(List.of("fs"), names(task.findToolTags()),
                "an unknown tag must not invent a definition");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("no-such-tag")),
                () -> "loading an unknown tag must warn, got: " + warnings);
    }

    @Test
    public void knownTagsDoNotWarnOnReload() {
        NaruTask task = session.newTask(NaruTaskSpec.of().toolTags("fs", "network"));
        warnings.clear();
        ((NaruTaskImpl) task).load(task.toElement());
        assertTrue(warnings.stream().noneMatch(w -> w.contains("fs") || w.contains("network")),
                () -> "a known tag must not warn, got: " + warnings);
    }

    // ── O4: reset keeps the standing permissions ─────────────────────────────

    @Test
    public void resetKeepsGrantedTagsAndExclusions() {
        NaruTask task = session.newTask(NaruTaskSpec.of().toolTags("fs"));
        task.addToolExclusion("cd");

        task.reset();

        assertEquals(List.of("fs"), names(task.findToolTags()),
                "reset() must not wipe the tags a task was granted");
        assertEquals(Set.of("cd"), task.findToolExclusions(),
                "reset() must not wipe the tool exclusions a task was given");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static List<String> stringArray(NElement element, String key) {
        return element.asObject().get().getArray(key)
                .map(a -> a.children().stream()
                        .map(c -> c.asStringValue().orNull())
                        .collect(Collectors.toList()))
                .orElse(List.of());
    }

    /**
     * Returns a copy of {@code element} whose {@code toolTags} array is exactly {@code tags}.
     */
    private static NElement withToolTags(NElement element, String... tags) {
        NObjectElementBuilder b = NObjectElementBuilder.of();
        for (NElement child : element.asObject().get().children()) {
            if (child.isNamedPair()) {
                String key = child.asPair().get().key().asStringValue().orNull();
                if ("toolTags".equals(key)) {
                    continue;
                }
                b.add(child.asPair().get());
            }
        }
        NArrayElementBuilder array = NArrayElementBuilder.of();
        for (String tag : tags) {
            array.add(tag);
        }
        b.set("toolTags", array.build());
        return b.build();
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
