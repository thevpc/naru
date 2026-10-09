package net.thevpc.naru.ext.tools.tasks;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruOutput;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives /start and /spawn-policy the way the REPL does, so a WP3 flag or the policy
 * application that "works in the planner" but is wired up wrong at the call site is caught
 * here: the policy define/apply round trip, {@code --explain} resolving without spawning,
 * last-strategy-flag-wins, the target cases (routine, routine with a contract, agent
 * {@code .md}), and the failure paths that must not spawn a child anyway.
 */
@Timeout(60)
public class NaruSpawnDirectiveTest {

    private static final NaruSessionListener NOOP_LISTENER = new NaruSessionListener() {
        @Override
        public void onEventAppended(NaruEvent newEvent) {
        }

        @Override
        public void sessionStarted(NaruSession session) {
        }

        @Override
        public void sessionStopped(NaruSession session) {
        }

        @Override
        public void onSessionReloaded(NaruSession naruSession) {
        }
    };

    private NPath projectDir;
    private NaruSessionImpl session;
    private final List<NaruOutput> outputs = new ArrayList<>();

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
        projectDir = NPath.ofTempFolder("naru-spawn-directive-" + System.nanoTime());
        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(projectDir);
        outputs.clear();
        session = new NaruSessionImpl(agent, projectDir,
                new NaruStreamInteraction(o -> outputs.add(o)), true,
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

    // ── fixtures ────────────────────────────────────────────────────────────

    private NaruTask parent() {
        return session.newTask(NaruTaskSpec.of());
    }

    private NaruStmtResult call(NaruTask task, String directive, String argument) {
        NaruDirective d = session.registry().findDirective(directive)
                .orElseThrow(() -> new AssertionError("no /" + directive + " directive registered"));
        NaruDirectiveCallContext ctx = new NaruDirectiveCallContextImpl(directive, argument, task);
        return d.execute(ctx);
    }

    private void writeRoutine(String name, String contract, String... body) {
        StringBuilder sb = new StringBuilder();
        if (contract != null) {
            sb.append("---\n").append("contract: ").append(contract).append("\n---\n");
        }
        if (body.length == 0) {
            sb.append("/nop\n");
        }
        for (String line : body) {
            sb.append(line).append('\n');
        }
        NPath f = projectDir.resolve(name);
        f.mkParentDirs();
        f.writeString(sb.toString());
    }

    private void writeAgent(String name, String frontMatter, String body) {
        StringBuilder sb = new StringBuilder();
        if (frontMatter != null) {
            sb.append("---\n").append(frontMatter).append("\n---\n");
        }
        sb.append(body == null ? "" : body).append('\n');
        NPath f = projectDir.resolve(".naru").resolve("agent").resolve(name);
        f.mkParentDirs();
        f.writeString(sb.toString());
    }

    private NaruEvent lastSpawned(long fromSeq) {
        return session.eventLog().scan(fromSeq, e -> NaruEvent.TASK_SPAWNED.equals(e.name()))
                .stream().reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no TaskSpawned event after seq " + fromSeq));
    }

    // ── /spawn-policy plus /start --policy ──────────────────────────────────

    @Test
    public void spawnPolicyIsDefinedAndStartAppliesIt() {
        writeRoutine("probe.naru", null, "/return 1");
        NaruTask parent = parent();
        parent.addToolTag("write").addToolTag("exec");

        NaruStmtResult def = call(parent, "spawn-policy",
                "review-safe --inherit=tags --revoke-tags=write --add-tags=network --exclude-tools=run_shell");
        assertEquals("review-safe", def.successValue());
        assertTrue(session.findSpawnPolicy("review-safe").isPresent());

        // no scheduler start: these assertions read the spawn-time resolution, so the child
        // must stay in the tasks map instead of running to completion and being pruned
        long fromSeq = session.eventLog().currentSeq() + 1;
        NaruStmtResult r = call(parent, "start", "probe.naru --policy=review-safe");
        NaruTask child = session.findTask((Long) r.successValue()).orElseThrow(() -> new AssertionError("no child " + r.successValue()));
        assertEquals(Set.of("exec", "network"), child.findToolTagNames(),
                () -> "policy inherit+revoke+add not applied by /start: " + child.findToolTagNames());
        assertEquals(Set.of("run_shell"), child.findToolExclusions());

        NaruEvent spawned = lastSpawned(fromSeq);
        assertEquals("review-safe", spawned.payload().get("policy"));
        assertEquals("routine", spawned.payload().get("kind"));
    }

    // ── --explain ───────────────────────────────────────────────────────────

    @Test
    public void explainResolvesAndPrintsProvenanceWithoutSpawning() {
        NaruTask parent = parent();
        long fromSeq = session.eventLog().currentSeq() + 1;

        NaruStmtResult r = call(parent, "start", "--explain --fork --add-tags=exec");
        assertNull(r.errorValue(), () -> "--explain must not error, got " + r);
        assertEquals(0, r.exitCode());
        // --explain resolves and prints instead of spawning; its success carries no value
        assertNull(r.successValue());

        assertTrue(session.eventLog().scan(fromSeq, e -> NaruEvent.TASK_SPAWNED.equals(e.name())).isEmpty(),
                "--explain must not spawn");
        assertTrue(outputs.stream().anyMatch(o -> o.message().toString().contains("strategy=fork")),
                () -> "the printed resolution must carry the resolved strategy: " + outputs);
        assertTrue(outputs.stream().anyMatch(o -> o.message().toString().contains("tags=[exec]")),
                () -> "the printed resolution must carry the resolved tags: " + outputs);
    }

    // ── strategy flags ──────────────────────────────────────────────────────

    @Test
    public void lastStrategyFlagWins() {
        writeRoutine("probe.naru", null, "/return 1");
        NaruTask parent = parent();
        session.start();
        long fromSeq = session.eventLog().currentSeq() + 1;

        NaruStmtResult r = call(parent, "start", "probe.naru --fork --summary");
        NaruEvent spawned = lastSpawned(fromSeq);
        assertEquals("summary (flag)", spawned.payload().get("strategy"),
                "of two strategy flags the last one must win");
        assertNotNull(r.successValue());
    }

    @Test
    public void windowFlagCarriesItsTurnCount() {
        NaruTask parent = parent();
        long fromSeq = session.eventLog().currentSeq() + 1;

        call(parent, "start", "--explain --window=6turns");
        assertTrue(outputs.stream().anyMatch(o -> o.message().toString().contains("strategy=window(6)")),
                () -> "the resolution must render window(6): " + outputs);
        assertTrue(session.eventLog().scan(fromSeq, e -> NaruEvent.TASK_SPAWNED.equals(e.name())).isEmpty());
    }

    // ── target cases ────────────────────────────────────────────────────────

    @Test
    public void startRunsARoutineTargetToCompletion() {
        writeRoutine("probe.naru", null, "/return 40+2");
        NaruTask parent = parent();

        // spawn while the scheduler is not running, so the child is still in the tasks map
        // when we look it up; then start the session and let it run to completion
        NaruStmtResult r = call(parent, "start", "probe.naru");
        Long childId = (Long) r.successValue();
        assertNotNull(childId, () -> "/start must return the spawned child id, got " + r);

        NaruTask child = session.findTask(childId).orElseThrow(() -> new AssertionError("no task " + childId));
        session.start();
        child.await();
        assertEquals(42L, ((Number) child.value().orNull()).longValue());
        assertEquals(0, child.exitCode());
    }

    @Test
    public void routineContractIsValidatedAndTheFixHintIsReported() {
        writeRoutine("guarded.naru", "{ requires: \"fs\" }", "/return 1");
        NaruTask parent = parent();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> call(parent, "start", "guarded.naru"));
        assertTrue(e.getMessage().contains("--add-tags=fs"),
                () -> "the contract failure must name the fixing flag: " + e.getMessage());

        // once the spawn grants fs the same target spawns and completes
        NaruTask holder = parent();
        NaruStmtResult r = call(holder, "start", "guarded.naru --add-tags=fs");
        NaruTask child = session.findTask((Long) r.successValue()).orElseThrow(() -> new AssertionError("no child " + r.successValue()));
        session.start();
        child.await();
        assertEquals(Set.of("fs"), child.findToolTagNames());
    }

    @Test
    public void agentMdWithAContractSpawnsAsAnAgent() {
        writeAgent("hero.md", "{ requires: \"write\" }", "hero body");
        NaruTask parent = parent();
        parent.addToolTag("write");
        long fromSeq = session.eventLog().currentSeq() + 1;

        NaruStmtResult r = call(parent, "start", "hero --inherit=tags");
        assertNotNull(r.successValue());

        NaruEvent spawned = lastSpawned(fromSeq);
        assertEquals("agent", spawned.payload().get("kind"));
        NaruTask child = session.findTask((Long) r.successValue()).orElseThrow(() -> new AssertionError("no child " + r.successValue()));
        assertEquals(Set.of("write"), child.findToolTagNames());
    }

    // ── failure paths must not spawn a child anyway ─────────────────────────

    @Test
    public void missingTargetFailsWithoutSpawning() {
        NaruTask parent = parent();
        long fromSeq = session.eventLog().currentSeq() + 1;

        NaruStmtResult r = call(parent, "start", "no-such-routine.naru");
        assertNotNull(r.errorValue(), () -> "a missing target must be an error, got " + r);
        assertTrue(session.eventLog().scan(fromSeq, e -> NaruEvent.TASK_SPAWNED.equals(e.name())).isEmpty(),
                "a missing target must not spawn a child");
    }

    @Test
    public void agentMdWithoutAContractIsRejected() {
        writeAgent("bare.md", null, "just a body");
        NaruTask parent = parent();
        long fromSeq = session.eventLog().currentSeq() + 1;

        NaruStmtResult r = call(parent, "start", "bare");
        assertNotNull(r.errorValue(), () -> "an agent without a contract must be an error, got " + r);
        assertTrue(outputs.stream().anyMatch(o -> o.message().toString().contains("no spawn contract")),
                () -> "the missing-contract problem must be reported: " + outputs);
        assertTrue(session.eventLog().scan(fromSeq, e -> NaruEvent.TASK_SPAWNED.equals(e.name())).isEmpty());
    }
}