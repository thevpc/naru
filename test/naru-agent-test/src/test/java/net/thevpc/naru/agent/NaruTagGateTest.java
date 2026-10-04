package net.thevpc.naru.agent;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.task.NaruTask;
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

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tools that change tool tags must not be visible to a task that was never given
 * the right to change them.
 *
 * <p>This was broken in two independent ways that cancelled each other's symptoms:
 *
 * <ul>
 *   <li>{@code tag_remove} declared the {@code tags} tag and then overrode
 *       {@code tags()} to return the empty set, which is exactly the case
 *       {@code findTools()} treats as "always include" -- so it was offered to every
 *       task, including ones holding no tags at all.</li>
 *   <li>no {@code NaruToolTagProvider} declared the {@code tags} tag, so it was never
 *       registered: {@code /tags enable tags} threw on an empty lookup, and
 *       {@code tag_add} was unreachable for the opposite reason.</li>
 * </ul>
 *
 * <p>So "the tag is on the tool" was never actually enforced anywhere: the one tool
 * that bypassed the gate was the only one visible, and the gate itself could not be
 * opened. Both halves are pinned below, because either one alone reproduces the leak.
 */
public class NaruTagGateTest {

    private NaruAgent agent;
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
        agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-tag-gate"));
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

    private NaruTask newTask() {
        return session.newTask(NaruTaskSpec.of());
    }

    private List<String> toolNames(NaruTask task) {
        return task.findTools().stream().map(NaruToolDefinition::getName).toList();
    }

    // ── the tag has to exist before it can gate anything ─────────────────────

    @Test
    public void theTagsTagIsRegistered() {
        assertTrue(session.registry().availableTags().containsKey("tags"),
                () -> "no provider declares the 'tags' tag, so it can never be granted: "
                        + session.registry().availableTags().keySet());
    }

    @Test
    public void theTagsTagCanBeGrantedWithoutThrowing() {
        NaruTask task = newTask();
        task.addToolTag("tags");
        assertTrue(task.findToolTags().stream().anyMatch(t -> "tags".equals(t.name())));
    }

    // ── and nothing wearing it may be offered to an ungranted task ───────────

    @Test
    public void tagToolsAreHiddenFromATaskThatWasNotGrantedTheTag() {
        NaruTask task = newTask();
        List<String> names = toolNames(task);
        assertFalse(names.contains("tag_remove"),
                () -> "tag_remove was visible to a task holding no tags: " + names);
        assertFalse(names.contains("tag_add"),
                () -> "tag_add was visible to a task that was never granted the tags tag: " + names);
    }

    @Test
    public void grantingTheTagRevealsBothTagTools() {
        NaruTask task = newTask();
        task.addToolTag("tags");
        List<String> names = toolNames(task);
        assertTrue(names.contains("tag_add"), () -> "tag_add stays hidden once the tag is granted: " + names);
        assertTrue(names.contains("tag_remove"), () -> "tag_remove stays hidden once the tag is granted: " + names);
    }

    /**
     * Not one tool wearing a tag is offered to a task that has granted no tag. Worth
     * pinning: it means the tag gate is what decides the whole schema, so a regression
     * that made the gate permissive shows up here as a long list rather than as one
     * surprising tool.
     *
     * <p>Deliberately not "the list is empty". {@code think} is the one untagged tool,
     * and it answers to {@code model.thinking} and the model's declared capability
     * rather than to a tag -- so whether it belongs in the list depends on which model
     * the machine running this has configured. Asserting emptiness would pin the test
     * to one developer's setup.
     */
    @Test
    public void anUngrantedTaskIsOfferedNoTaggedTool() {
        NaruTask task = newTask();
        Set<String> tagged = session.registry().tools().values().stream()
                .filter(t -> !t.tags().isEmpty())
                .map(NaruTool::name)
                .collect(Collectors.toSet());
        assertFalse(tagged.isEmpty(), "sanity: there are tagged tools to leak");
        List<String> leaked = toolNames(task).stream()
                .filter(tagged::contains)
                .collect(Collectors.toList());
        assertEquals(List.of(), leaked,
                () -> "tools wearing a tag the task was never granted: " + leaked);
    }

    @Test
    public void grantingATagRevealsTheToolsWearingIt() {
        NaruTask task = newTask();
        task.addToolTag("tags");
        assertTrue(toolNames(task).contains("tag_add"));
        task.addToolTag("fs");
        List<String> names = toolNames(task);
        assertTrue(names.contains("file_read"), () -> "the fs tag was granted but fs tools are absent: " + names);
        assertFalse(names.contains("git_status"),
                () -> "an ungranted tag's tools leaked in alongside a granted one: " + names);
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