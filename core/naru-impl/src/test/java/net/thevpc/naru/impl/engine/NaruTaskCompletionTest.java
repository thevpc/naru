package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.scheduler.NaruTaskMode;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spawning a run, waiting for it, and reading what it produced.
 * <p>
 * The behaviour being pinned down is the contract a host embeds against: that a handle
 * survives its task being deregistered from the session, that a soft failure can be reported
 * alongside a value instead of instead of it, and that the two ways of waiting -- blocking
 * and callback -- agree with each other.
 */
public class NaruTaskCompletionTest {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

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

    private static NaruAgentImpl newAgent(String name) {
        NaruAgentImpl agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-run-" + name));
        return agent;
    }

    private static NaruStreamInteraction silent() {
        return new NaruStreamInteraction(o -> {
        });
    }

    /**
     * Silent, but willing to be asked for input and never answering.
     * <p>
     * A session with no input listener at all <i>cancels</i> a task that asks for input, and
     * that is the right behaviour -- a blocked task nobody will ever answer is a leak. So
     * keeping a task alive on purpose means supplying a listener that simply never replies.
     */
    private static NaruStreamInteraction deaf() {
        return new NaruStreamInteraction(o -> {
        }, request -> {
        });
    }

    private static NaruTaskSpec spec(String... statements) {
        return NaruTaskSpec.of().statements(statements).resolveName();
    }

    /**
     * Wait for a task and hand it back, so a test can read what it produced in one expression.
     * <p>
     * {@link NaruTask#await()} deliberately returns void -- there is nothing to return that the
     * caller does not already have, since the task is its own handle. This keeps the assertions
     * reading as one thought: wait, then ask.
     */
    private static NaruTask await(NaruTask task) {
        task.await();
        return task;
    }

    private static NaruTask await(NaruTask task, Duration timeout) {
        Assertions.assertTrue(task.await(timeout), "timed out waiting for task " + task.id());
        return task;
    }

    // ── the basic await path ──────────────────────────────────────────────────

    @Test
    @Timeout(60)
    public void runReturnsTheValueItProduced() {
        NaruAgent agent = newAgent("value");
        NaruTask result = await(agent.newSession()
                .interaction(silent())
                .task(spec("/print 40+2"))
                .build()
                .run());

        Assertions.assertEquals(42L, asLong(result.value()));
        Assertions.assertTrue(result.isSuccess(), () -> "expected success but got " + result);
        Assertions.assertEquals(0, result.exitCode());
        Assertions.assertNull(result.error());
        Assertions.assertEquals("DONE", result.status().name());
    }

    /**
     * The reason this class exists at all: a terminated task is dropped from the session, so
     * the handle has to keep working after the lookup stops resolving.
     */
    @Test
    @Timeout(60)
    public void handleOutlivesTheTasksRegistration() {
        NaruAgent agent = newAgent("outlive");
        NaruSession session = agent.newSession()
                .interaction(silent())
                .task(spec("/print 7").name("seven"))
                .build();
        NaruTask run = session.run();
        long id = run.id();
        Assertions.assertTrue(session.findTask(id).isPresent(), "precondition: still registered");

        run.await();

        Assertions.assertFalse(session.findTask(id).isPresent(),
                "precondition: deregistered once terminated");
        // everything below reads state that is now only reachable through the handle
        Assertions.assertEquals(7L, asLong(run.value()));
        Assertions.assertEquals(id, run.id());
        Assertions.assertEquals("seven", run.name());
        Assertions.assertNotNull(run.session());
        Assertions.assertNotNull(run.projectDir());
        Assertions.assertTrue(run.isTerminal());
        Assertions.assertNotNull(run.endTime());
        Assertions.assertNotNull(run.duration());
    }

    /**
     * A session stops itself once its last task finishes, so a session meant to serve several
     * requests needs one long-lived task holding it open. This is the shape a server embedding
     * Naru actually has: a resident task, plus a stream of short request tasks.
     */
    @Test
    @Timeout(60)
    public void runOnAnAlreadyStartedSession() {
        NaruAgent agent = newAgent("run2");
        NaruSession session = agent.newSession()
                .interaction(deaf())
                .interactive()
                .task(spec("/print 0").name("host"))
                .build();
        // the resident task: an unanswered question parks it, and that is what holds the session
        // open long enough for the request tasks below to run
        NaruTask host = session.run();

        NaruTask first = await(session.run(spec("/print 1")));
        NaruTask second = await(session.run(spec("/print 2")));

        Assertions.assertEquals(1L, asLong(first.value()));
        Assertions.assertEquals(2L, asLong(second.value()));
        Assertions.assertNotEquals(first.id(), second.id());
        Assertions.assertTrue(session.isRunning(),
                "the session must still be serving because the resident task is alive");
        Assertions.assertFalse(host.isCompleted());
        host.cancel();
    }

    @Test
    @Timeout(60)
    public void runOnAnUnstartedSessionIsRefused() {
        NaruAgent agent = newAgent("unstarted");
        NaruSession session = agent.newSession().interaction(silent()).build();

        Assertions.assertThrows(IllegalStateException.class, () -> session.run(spec("/print 1")));
    }

    @Test
    @Timeout(60)
    public void runTwiceIsRefused() {
        NaruAgent agent = newAgent("twice");
        NaruSession session = agent.newSession()
                .interaction(silent())
                .task(spec("/print 1"))
                .build();
        session.run();

        Assertions.assertThrows(IllegalStateException.class, session::run);
    }

    @Test
    @Timeout(60)
    public void runWithoutATaskIsRefused() {
        NaruAgent agent = newAgent("notask");
        NaruSession session = agent.newSession().interaction(silent()).build();

        Assertions.assertThrows(IllegalStateException.class, session::run);
    }

    // ── variables ─────────────────────────────────────────────────────────────

    @Test
    @Timeout(60)
    public void specVarsSeedTheTaskEnvWithoutStringifying() {
        NaruAgent agent = newAgent("vars");
        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("count", 41);
        seed.put("label", "hello world");
        NaruTaskSpec withVars = spec("/print count+1").vars(seed);

        NaruTask result = await(agent.newSession()
                .interaction(silent())
                .task(withVars)
                .build()
                .run());

        // the seeded var is visible to expression evaluation, and "hello world" went in as
        // one value rather than two shell-ish words
        Assertions.assertEquals(42L, asLong(result.value()));
        Assertions.assertEquals(41, result.asLong("count"));
        Assertions.assertEquals("hello world", result.var("label").orElse(null));
        Assertions.assertTrue(result.vars().containsKey("label"));
    }

    @Test
    @Timeout(60)
    public void varsAreReadableWhileTheTaskIsStillRunning() {
        NaruAgent agent = newAgent("livevars");
        // interactive never terminates, so the handle is still open when we look
        NaruTask run = agent.newSession()
                .interaction(deaf())
                .interactive()
                .task(spec("/print 1").vars(Map.of("seeded", 5)).name("live"))
                .build()
                .run();

        Assertions.assertFalse(run.isCompleted(), "must not have completed");
        Assertions.assertEquals(NaruTaskMode.INTERACTIVE, run.taskMode());
        Assertions.assertNull(run.endTime());
        Assertions.assertNull(run.duration());
        Assertions.assertNull(run.error());
        Assertions.assertEquals(5L, asLong(run.vars().get("seeded")),
                "a host UI must be able to watch a task's inputs before it ends");
        run.cancel();
    }

    @Test
    @Timeout(60)
    public void resultVarsAreAnUnmodifiableSnapshot() {
        NaruAgent agent = newAgent("frozen");
        NaruTask result = await(agent.newSession()
                .interaction(silent())
                .task(spec("/print 1"))
                .build()
                .run());

        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> result.vars().put("b", 2));
    }

    // ── the wide result: value, exit code and error together ───────────────────

    /**
     * The case a tagged union cannot express: the task finished normally, yet still has
     * something to report. {@code /assert} publishes a non-zero exit code for a false
     * condition without aborting, so the lifecycle says DONE while the payload says "no" --
     * and the variables it was given survive alongside the complaint.
     * <p>
     * The stricter case, where a soft failure carries a <i>value</i> as well, needs
     * {@code /set} and {@code /return} and is covered in the routines extension, which is
     * where those statements live.
     */
    @Test
    @Timeout(60)
    public void doneWithANonZeroCodeIsNotSuccess() {
        NaruAgent agent = newAgent("soft");
        NaruTask result = await(agent.newSession()
                .interaction(silent())
                .task(spec("/assert 1 == 2").vars(Map.of("input", "kept")))
                .build()
                .run());

        Assertions.assertEquals("DONE", result.status().name(),
                "the lifecycle verdict and the payload verdict are different things");
        Assertions.assertNotEquals(0, result.exitCode(),
                "the soft failure must be visible as an exit code");
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertNotNull(result.error());
        Assertions.assertEquals("kept", result.var("input").orElse(null),
                "data handed to the task must not be lost just because it complained");
        // opting in to the exception is a choice, not something forced on the caller
        Assertions.assertThrows(IllegalStateException.class, result::throwIfFailed);
    }

    /**
     * A clean run that merely carried variables through must not be penalised for it: seeded
     * and derived values are not warnings.
     */
    @Test
    @Timeout(60)
    public void carryingVariablesDoesNotMakeARunFail() {
        NaruAgent agent = newAgent("clean2");
        NaruTask result = await(agent.newSession()
                .interaction(silent())
                .task(spec("/print 5").vars(Map.of("noise", "harmless")))
                .build()
                .run());

        Assertions.assertTrue(result.isSuccess(), () -> "expected success but got " + result);
        Assertions.assertEquals(5L, asLong(result.value()));
        Assertions.assertEquals(0, result.exitCode());
        Assertions.assertEquals(5L, result.throwIfFailed().exitCode() + 5L);
    }

    /**
     * A directive that throws does not kill the task: it publishes exit code 127 and execution
     * continues. Worth pinning, because a caller reading exit codes needs to know 127 means
     * "this statement blew up" and not "the task died".
     */
    @Test
    @Timeout(60)
    public void aBrokenDirectiveIsReportedAsAStatementErrorNotATaskFailure() {
        NaruAgent agent = newAgent("brokendir");
        NaruTask result = await(agent.newSession()
                .interaction(silent())
                .task(spec("/print 1", "/assert --set green count == \"nope\""))
                .build()
                .run());

        Assertions.assertEquals("DONE", result.status().name(),
                "a bad statement must not end the task");
        Assertions.assertNotEquals(0, result.exitCode());
    }

    // ── the two ways of waiting ───────────────────────────────────────────────

    @Test
    @Timeout(60)
    public void callbackAndAwaitAgree() throws Exception {
        NaruAgent agent = newAgent("cb");
        CountDownLatch fired = new CountDownLatch(1);
        AtomicReference<NaruTask> seen = new AtomicReference<>();

        NaruTask run = agent.newSession()
                .interaction(silent())
                .task(spec("/print 123"))
                .build()
                .run();
        run.onComplete(r -> {
            seen.set(r);
            fired.countDown();
        });

        NaruTask awaited = await(run);

        Assertions.assertTrue(fired.await(PATIENCE.toSeconds(), TimeUnit.SECONDS),
                "callback never fired");
        Assertions.assertEquals(asLong(awaited.value()), asLong(seen.get().value()));
        Assertions.assertSame(awaited, seen.get(), "every waiter must see the same result");
    }

    /**
     * A task that finishes before the caller gets round to subscribing is the normal case for
     * anything fast, so a late subscriber must still be told -- otherwise the callback
     * silently never runs and the bug only shows up on small inputs.
     */
    @Test
    @Timeout(60)
    public void callbackRegisteredAfterCompletionStillFires() throws Exception {
        NaruAgent agent = newAgent("late");
        NaruTask run = agent.newSession()
                .interaction(silent())
                .task(spec("/print 1"))
                .build()
                .run();
        run.await();

        CountDownLatch fired = new CountDownLatch(1);
        run.onComplete(r -> fired.countDown());

        Assertions.assertTrue(fired.await(PATIENCE.toSeconds(), TimeUnit.SECONDS),
                "a late callback must fire immediately");
    }

    /**
     * Each subscriber is told once, and a second one is not a replay of the first. A callback
     * firing twice would double-count work in a host that appends to a list; one that never
     * fires would lose it.
     */
    @Test
    @Timeout(60)
    public void eachSubscriberIsNotifiedExactlyOnce() throws Exception {
        NaruAgent agent = newAgent("once");
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        CountDownLatch fired = new CountDownLatch(1);

        NaruTask run = agent.newSession()
                .interaction(silent())
                .task(spec("/print 1"))
                .build()
                .run();
        run.onComplete(r -> {
            first.incrementAndGet();
            fired.countDown();
        });
        run.await();
        run.onComplete(r -> second.incrementAndGet());
        Thread.sleep(200);

        Assertions.assertTrue(fired.await(PATIENCE.toSeconds(), TimeUnit.SECONDS));
        Assertions.assertEquals(1, first.get(), "the original subscriber fired more than once");
        Assertions.assertEquals(1, second.get(), "the late subscriber fires once, for itself");
    }

    @Test
    @Timeout(60)
    public void aThrowingCallbackDoesNotBreakTheOthers() throws Exception {
        NaruAgent agent = newAgent("throwing");
        CountDownLatch good = new CountDownLatch(1);

        NaruTask run = agent.newSession()
                .interaction(silent())
                .task(spec("/print 1"))
                .build()
                .run();
        run.onComplete(r -> {
            throw new IllegalStateException("callback bug");
        });
        run.onComplete(r -> good.countDown());

        Assertions.assertEquals(1L, asLong(await(run).value()),
                "the result must still be delivered");
        Assertions.assertTrue(good.await(PATIENCE.toSeconds(), TimeUnit.SECONDS),
                "one bad callback must not starve the next");
    }

    @Test
    @Timeout(60)
    public void callbacksDoNotRunOnTheSchedulerThread() {
        NaruAgent agent = newAgent("thread");
        AtomicReference<String> thread = new AtomicReference<>();
        CountDownLatch fired = new CountDownLatch(1);

        NaruTask run = agent.newSession()
                .interaction(silent())
                .task(spec("/print 1"))
                .build()
                .run();
        run.onComplete(r -> {
            thread.set(Thread.currentThread().getName());
            fired.countDown();
        });

        try {
            run.await();
            Assertions.assertTrue(fired.await(PATIENCE.toSeconds(), TimeUnit.SECONDS));
            // completion is signalled from inside the task's own tick; a slow consumer
            // running there would stall the engine
            Assertions.assertNotEquals(Thread.currentThread().getName(), thread.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Assertions.fail(e);
        }
    }

    @Test
    @Timeout(60)
    public void futureCompletesWithTheResult() throws Exception {
        NaruAgent agent = newAgent("future");
        NaruTask run = agent.newSession()
                .interaction(silent())
                .task(spec("/print 8"))
                .build()
                .run();

        CompletableFuture<NaruTask> future = run.toFuture();
        // the same instance every time, so it is safe to hand to several consumers
        Assertions.assertSame(future, run.toFuture());

        NaruTask result = future.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        Assertions.assertEquals(8L, asLong(result.value()));
    }

    // ── interactive and cancellation ──────────────────────────────────────────

    /**
     * An interactive task waits for a human by design, so an untimed wait on one would pin a
     * server thread for the life of the process. Refusing loudly beats hanging silently.
     */
    @Test
    @Timeout(60)
    public void untimedAwaitRejectsInteractiveTasks() {
        NaruAgent agent = newAgent("interactive");
        NaruTask run = agent.newSession()
                .interaction(deaf())
                .interactive()
                .task(spec("/print 1"))
                .build()
                .run();

        IllegalStateException e =
                Assertions.assertThrows(IllegalStateException.class, run::await);
        Assertions.assertTrue(e.getMessage().contains("INTERACTIVE"), e.getMessage());
        run.cancel();
    }

    @Test
    @Timeout(60)
    public void timedAwaitGivesUpWithoutFailingTheTask() {
        NaruAgent agent = newAgent("timeout");
        NaruTask run = agent.newSession()
                .interaction(deaf())
                .interactive()
                .task(spec("/print 1"))
                .build()
                .run();

        Assertions.assertFalse(run.await(Duration.ofMillis(200)),
                "a timeout is not a result, and must not read like one");
        Assertions.assertFalse(run.isCompleted(), "the task keeps running");
        // still usable afterwards
        Assertions.assertDoesNotThrow(() -> {
            run.await(Duration.ofMillis(50));
        });
        run.cancel();
    }

    @Test
    @Timeout(60)
    public void awaitRejectsANonPositiveTimeout() {
        NaruAgent agent = newAgent("badtimeout");
        NaruTask run = agent.newSession()
                .interaction(silent())
                .task(spec("/print 1"))
                .build()
                .run();

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> {
                    run.await(Duration.ZERO);
                });
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> {
                    run.await(Duration.ofSeconds(-1));
                });
        run.await();
    }

    /**
     * Cancelling is an outcome, not an error: it settles the run with KILLED and the shell's
     * interrupt code rather than throwing, so a host can tell "the user pressed stop" apart
     * from "it broke".
     */
    @Test
    @Timeout(60)
    public void cancelSettlesTheRunAsKilled() {
        NaruAgent agent = newAgent("cancel");
        NaruTask run = agent.newSession()
                .interaction(deaf())
                .interactive()
                .task(spec("/print 1"))
                .build()
                .run();

        run.cancel("user pressed stop");

        NaruTask result = await(run, Duration.ofSeconds(20));
        Assertions.assertNotNull(result, "cancel must settle the run rather than hang it");
        Assertions.assertEquals("KILLED", result.status().name());
        Assertions.assertEquals(NaruTask.EXIT_INTERRUPTED, result.exitCode());
        Assertions.assertTrue(result.error().contains("user pressed stop"), result.error());
        Assertions.assertFalse(result.isSuccess());
    }

    @Test
    @Timeout(60)
    public void cancelIsIdempotentAndSafeToRepeat() {
        NaruAgent agent = newAgent("cancel2");
        NaruTask run = agent.newSession()
                .interaction(deaf())
                .interactive()
                .task(spec("/print 1"))
                .build()
                .run();

        Assertions.assertSame(run, run.cancel());
        Assertions.assertSame(run, run.cancel());
        NaruTask result = await(run, Duration.ofSeconds(20));
        Assertions.assertNotNull(result);
        Assertions.assertEquals("KILLED", result.status().name());
    }

    // ── concurrency ───────────────────────────────────────────────────────────

    @Test
    @Timeout(120)
    public void manyWaitersAllSeeTheSameResult() throws Exception {
        NaruAgent agent = newAgent("many");
        int waiters = 8;
        CountDownLatch ready = new CountDownLatch(waiters);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger mismatches = new AtomicInteger();
        AtomicReference<NaruTask> canonical = new AtomicReference<>();
        java.util.List<Thread> threads = new java.util.ArrayList<>();

        NaruTask run = agent.newSession()
                .interaction(silent())
                .task(spec("/print 314"))
                .build()
                .run();

        for (int i = 0; i < waiters; i++) {
            Thread t = new Thread(() -> {
                try {
                    ready.countDown();
                    go.await();
                    NaruTask r = await(run, Duration.ofSeconds(20));
                    if (canonical.compareAndSet(null, r)) {
                        // first one wins the race to be the reference
                    } else if (canonical.get() != r) {
                        mismatches.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "waiter-" + i);
            threads.add(t);
            t.start();
        }
        Assertions.assertTrue(ready.await(PATIENCE.toSeconds(), TimeUnit.SECONDS));
        go.countDown();
        for (Thread t : threads) {
            t.join(PATIENCE.toMillis());
        }

        Assertions.assertEquals(0, mismatches.get(), "waiters disagreed about the result");
        Assertions.assertEquals(314L, asLong(canonical.get().value()));
    }

    @Test
    @Timeout(60)
    public void concurrentSubscribersAllFireExactlyOnce() throws Exception {
        NaruAgent agent = newAgent("race");
        AtomicInteger fired = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(16);
        NaruTask run = agent.newSession()
                .interaction(silent())
                .task(spec("/print 1"))
                .build()
                .run();

        // subscribe concurrently with completion, which is the interleaving most likely to
        // drop a callback or deliver one twice
        for (int i = 0; i < 16; i++) {
            new Thread(() -> {
                run.onComplete(r -> {
                    fired.incrementAndGet();
                    done.countDown();
                });
            }).start();
        }
        run.await();
        Assertions.assertTrue(done.await(PATIENCE.toSeconds(), TimeUnit.SECONDS));
        Thread.sleep(200);
        Assertions.assertEquals(16, fired.get());
    }

    /**
     * Stopping a session does not drive its tasks to a terminal state, so without explicit
     * settling a run would never complete and anything awaiting it would hang for the life of
     * the host. A shutdown path has to release its waiters.
     */
    @Test
    @Timeout(60)
    public void stoppingTheSessionSettlesRunsStillInFlight() {
        NaruAgent agent = newAgent("sessionstop");
        NaruSession session = agent.newSession()
                .interaction(deaf())
                .interactive()
                .task(spec("/print 1"))
                .build();
        NaruTask run = session.run();

        session.stop();

        NaruTask result = await(run, Duration.ofSeconds(20));
        Assertions.assertNotNull(result, "a stopped session must not strand its waiters");
        Assertions.assertEquals("KILLED", result.status().name());
        Assertions.assertTrue(result.error().contains("session stopped"), result.error());
    }

    /**
     * The wind-down path must not be confused by the ordinary one. A session stops itself the
     * instant its last task finishes, so stopping is also how a normal completion unwinds --
     * and that task must still be reported as DONE, not as interrupted.
     */
    @Test
    @Timeout(60)
    public void aTaskThatFinishedBeforeTheStopIsStillReportedAsDone() {
        NaruAgent agent = newAgent("normalstop");
        NaruSession session = agent.newSession()
                .interaction(silent())
                .task(spec("/print 6"))
                .build();
        NaruTask run = session.run();

        NaruTask result = await(run);

        // the session stopped itself once the task ended; that must not rewrite the verdict
        Assertions.assertEquals("DONE", result.status().name());
        Assertions.assertEquals(6L, asLong(result.value()));
        Assertions.assertTrue(result.isSuccess(), () -> "expected success but got " + result);
    }

    // ── failure and empty-result semantics ──────────────────────────────────

    /**
     * The engine reaches FAILED in exactly one place -- an input request nobody can answer --
     * so this covers the shape through that real path rather than contriving a record.
     * <p>
     * Worth pinning because the engine is the one failing the task: no statement ever wrote an
     * error, so if the reason were only logged, the task would end FAILED and report nothing
     * about why.
     */
    @Test
    @Timeout(60)
    public void aTaskFailedByAnUnanswerableQuestionSaysWhy() {
        NaruAgent agent = newAgent("unanswerable");
        // An interactive task with nothing to do asks a question -- that is how a REPL works.
        // silent() has no input listener at all, which the interaction reports as a
        // cancellation: the right outcome for a question nobody is ever going to answer, and
        // the engine's only route to FAILED.
        NaruTask run = agent.newSession()
                .interaction(silent())
                .interactive()
                .task(spec())
                .build()
                .run();

        Assertions.assertTrue(run.await(Duration.ofSeconds(20)),
                "the task should end rather than block forever");
        Assertions.assertEquals("FAILED", run.status().name());
        Assertions.assertFalse(run.isSuccess());
        Assertions.assertFalse(run.hasValue(), "a task that never ran has nothing to report");
        Assertions.assertEquals(NaruTask.EXIT_FAILURE, run.exitCode(),
                "a failure must never report a clean exit code");
        Assertions.assertNotNull(run.error(), "a task that failed must say why");
        Assertions.assertTrue(run.error().contains("no input listener"), run.error());
        // the reason survives the throw, because a caller catching this still needs to know
        // which task failed and why
        IllegalStateException thrown =
                Assertions.assertThrows(IllegalStateException.class, run::throwIfFailed);
        Assertions.assertTrue(thrown.getMessage().contains(run.error()), thrown.getMessage());
    }

    /**
     * A task that produced nothing is a normal outcome, and must be distinguishable from one
     * that produced null: {@code /return} with no expression is the case that makes the
     * difference real.
     */
    @Test
    @Timeout(60)
    public void aTaskWithNothingToReportIsNotAFailure() {
        NaruTask run = await(newAgent("empty").newSession()
                .interaction(silent())
                .task(spec("/print 1").name("noop"))
                .build()
                .run());

        Assertions.assertNotNull(run.vars(), "an absent variable map would be a trap to walk into");
        Assertions.assertFalse(run.vars().isEmpty(), "this task does set lastResult");
        Assertions.assertEquals(1L, asLong(run.value()));
        Assertions.assertTrue(run.isSuccess());
    }

    @Test
    @Timeout(60)
    public void anUnknownVariableFallsBackWithoutThrowing() {
        NaruTask run = await(newAgent("novars").newSession()
                .interaction(silent())
                .task(spec("/return 1").vars(Map.of()))
                .build()
                .run());

        Assertions.assertEquals("fallback", run.varOrDefault("missing", "fallback"));
        Assertions.assertTrue(run.var("missing").isEmpty(), "absent is not the same as null-valued");
        Assertions.assertThrows(NumberFormatException.class, () -> run.asLong("missing"),
                "asLong on an absent variable is a caller error, not a default");
    }

    /**
     * Naru stores expression values as whatever the evaluator produced, which may be a
     * narrower numeric type than the literal in the script suggests, so compare numerically
     * rather than by boxed type.
     */
    private static long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(value).trim());
    }

    // ── what a finished task is allowed to forget ──────────────────────────────

    @Test
    @Timeout(60)
    public void aFinishedTaskKeepsWhatItProducedEvenIfItsVariablesAreLaterChanged() {
        NaruAgent agent = newAgent("frozen");
        NaruTask run = await(agent.newSession()
                .interaction(silent())
                .task(spec("/return 7").vars(Map.of("x", 1L)))
                .build()
                .run());

        Object value = run.value();
        int exit = run.exitCode();
        Map<String, Object> vars = run.vars();
        Assertions.assertEquals(7L, asLong(value));
        Assertions.assertEquals(0, exit);
        Assertions.assertEquals(1L, asLong(vars.get("x")));

        // A finished task is the only remaining record of what happened -- the session has let it
        // go by now -- so writing to its variables afterwards must not quietly rewrite that. The
        // setters stay live because they describe the task rather than its run; the outcome
        // readers do not move.
        run.setTaskEnv("x", 999L).setTaskEnv("lastResult", "tampered").setTaskEnv("lastExitCode", 5L);

        Assertions.assertEquals(7L, asLong(run.value()), "value must not follow a later write");
        Assertions.assertEquals(0, run.exitCode(), "exit code must not follow a later write");
        Assertions.assertEquals(1L, asLong(run.vars().get("x")), "vars must be the ones it ended with");
        Assertions.assertEquals(vars.keySet(), run.vars().keySet(), "and the same set of names");
    }

    @Test
    @Timeout(120)
    public void everyCallbackFiresEvenWhenItArrivesWhileTheTaskIsFinishing() throws Exception {
        // A subscriber can turn up in the gap between a task claiming its callbacks and its
        // future reporting done. That is a real window, not a theoretical one, and getting it
        // wrong costs a caller a callback that never runs and never complains -- so hammer the
        // moment of completion rather than hoping to land in it by hand.
        int rounds = 60;
        int perRound = 8;
        for (int round = 0; round < rounds; round++) {
            NaruTask run = newAgent("callback-race-" + round).newSession()
                    .interaction(silent())
                    .task(spec("/return 3"))
                    .build()
                    .run();

            AtomicInteger fired = new AtomicInteger();
            CountDownLatch registered = new CountDownLatch(perRound);
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> racers = new ArrayList<>();
            for (int i = 0; i < perRound; i++) {
                Thread t = new Thread(() -> {
                    try {
                        registered.countDown();
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    run.onComplete(r -> fired.incrementAndGet());
                });
                t.setDaemon(true);
                t.start();
                racers.add(t);
            }
            Assertions.assertTrue(registered.await(20, TimeUnit.SECONDS));
            go.countDown();
            for (Thread t : racers) {
                t.join(20_000);
            }
            run.await();
            // give any callback that was wrongly dropped its chance to show up
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (fired.get() < perRound && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            Assertions.assertEquals(perRound, fired.get(),
                    "a callback registered during completion was dropped, in round " + round);
        }
    }

    // ── what a stopped session is still allowed to be asked ───────────────────

    @Test
    @Timeout(60)
    public void aStoppedSessionStillAnswersTheQuestionsNeededToFinishItsWork() {
        // Stopping asks the scheduler workers to stop; it does not wait for them, so one can
        // still be part way through a tick when stop() returns. Everything that worker needs on
        // the way out therefore has to keep working, and throwing from any of it kills the
        // worker mid-drain -- which used to surface as a stray "session is stopped" on a
        // scheduler thread and a task whose cancellation never landed.
        NaruAgent agent = newAgent("stopped-lookup");
        NaruSession session = agent.newSession()
                .interaction(silent())
                .task(spec("/print 1"))
                .build();
        NaruTask run = session.run();
        run.await();
        session.stop();

        Assertions.assertNotNull(session.eventLog(), "the log is how a stopped session is inspected");
        Assertions.assertNotNull(session.registry(), "the registry outlives the session that used it");
        Assertions.assertDoesNotThrow(() -> {
            session.foregroundTaskId();
        });
        // and the task the stop cancelled is still a readable record
        Assertions.assertTrue(run.isTerminal());
        Assertions.assertNotNull(run.status());
    }

    @Test
    @Timeout(60)
    public void stoppingASessionLeavesItsTaskReadableRatherThanHalfSettled() {
        NaruAgent agent = newAgent("stop-settles");
        NaruTask run = agent.newSession()
                .interaction(deaf())
                .interactive()
                .task(spec("/print 1"))
                .build()
                .run();

        // deaf() never answers, so this task is parked on a question; stopping is the only way
        // out, and it has to leave a task that reports a verdict rather than hanging.
        run.session().stop();

        Assertions.assertTrue(run.await(Duration.ofSeconds(20)), "a stopped session must settle its tasks");
        Assertions.assertTrue(run.isTerminal());
        Assertions.assertTrue(run.exitCode() != 0, "a task killed by the stop must not look clean");
        Assertions.assertNotNull(run.endTime());
    }
}
