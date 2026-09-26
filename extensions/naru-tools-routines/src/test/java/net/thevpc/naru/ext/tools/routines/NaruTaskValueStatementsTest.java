package net.thevpc.naru.ext.tools.routines;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;

/**
 * What a task reports from the routine statements that carry a result: {@code /return} for the
 * value, and {@code /set} for the exit code.
 * <p>
 * These live here rather than in the engine module because {@code /set} and {@code /return}
 * are optional directives, and the engine cannot run a script that needs them. The pairing
 * matters though: it is the only place where a value and a non-zero exit code arrive
 * together, which is the case a tagged union cannot represent.
 */
public class NaruTaskValueStatementsTest {

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

    private static NaruTask run(String name, String... statements) {
        NaruAgentImpl agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-result-" + name));
        NaruTask task = agent.newSession()
                .interaction(new NaruStreamInteraction(o -> {
                }))
                .task(NaruTaskSpec.of().statements(statements).resolveName())
                .build()
                .run();
        task.await();
        return task;
    }

    /**
     * {@code /return} is the only statement that says what a task produced, and until it set
     * the task's return value a top-level return left nothing behind: popping the last frame
     * has no frame to propagate into. So this is the statement a host's whole result contract
     * rests on.
     */
    @Test
    @Timeout(60)
    public void returnBecomesTheResultValue() {
        NaruTask result = run("return", "/return 40+2");

        Assertions.assertEquals(42L, asLong(result.value()));
        Assertions.assertTrue(result.isSuccess(), () -> "expected success but got " + result);
        Assertions.assertEquals(0, result.exitCode());
    }

    @Test
    @Timeout(60)
    public void returnWithoutAnExpressionIsStillASuccessfulNull() {
        NaruTask result = run("bare", "/return");

        Assertions.assertTrue(result.isSuccess(), () -> "expected success but got " + result);
        Assertions.assertFalse(result.hasValue(), "a bare return produces no value, and that is fine");
        Assertions.assertNull(result.value());
    }

    @Test
    @Timeout(60)
    public void returnReadsVariablesSeededOnTheSpec() {
        NaruAgentImpl agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-result-seeded"));
        NaruTask result = agent.newSession()
                .interaction(new NaruStreamInteraction(o -> {
                }))
                .task(NaruTaskSpec.of().statements("/return topic + \"-done\"")
                        .vars(Map.of("topic", "caching"))
                        .resolveName())
                .build()
                .run();
        result.await();

        Assertions.assertEquals("caching-done", result.value());
    }

    /**
     * The case the whole result type exists for. {@code /set} publishes a non-zero exit code
     * when it is handed a false boolean, and the task still completes normally -- so the
     * caller receives an answer <i>and</i> an objection, instead of having to choose one and
     * lose the other.
     */
    @Test
    @Timeout(60)
    public void aValueAndANonZeroExitCodeCanArriveTogether() {
        NaruTask result = run("soft", "/set --task ok = (1 == 2)", "/return 99");

        Assertions.assertEquals("DONE", result.status().name());
        Assertions.assertEquals(99L, asLong(result.value()),
                "the value must not be sacrificed to report the problem");
        Assertions.assertNotEquals(0, result.exitCode(),
                "the objection must be visible as an exit code");
        Assertions.assertFalse(result.isSuccess());
    }

    @Test
    @Timeout(60)
    public void aTrueConditionStaysASuccess() {
        NaruTask result = run("green", "/set --task ok = (1 == 1)", "/return 99");

        Assertions.assertEquals(99L, asLong(result.value()));
        Assertions.assertTrue(result.isSuccess(), () -> "expected success but got " + result);
    }

    /**
     * A task that has already finished is still answerable, and still finishable: the object
     * is the only thing left to ask once the session has forgotten it.
     */
    @Test
    @Timeout(60)
    public void aFinishedTaskKeepsAnsweringWithoutBeingAwaitedAgain() {
        NaruTask result = run("repeat", "/return 7");

        for (int i = 0; i < 3; i++) {
            Assertions.assertTrue(result.isCompleted());
            Assertions.assertEquals(7L, asLong(result.value()));
            Assertions.assertNotNull(result.endTime());
            Assertions.assertNotNull(result.duration());
        }
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(value).trim());
    }
}
