package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruInput;
import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruTurnResult;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruScriptedModelProvider;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.ext.NExtensions;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * What one turn costs a server: submit, run against an instant model, hibernate. Opt-in
 * ({@code -Dnaru.bench=true}); it prints a table rather than asserting a budget, because the
 * point is to see how the cost grows with the conversation.
 * <p>
 * {@code -Dnaru.bench.turns=200} sets the conversation length, {@code -Dnaru.bench.graceMs} how
 * long a session stays live after a turn (0: hibernate at once).
 */
@EnabledIfSystemProperty(named = "naru.bench", matches = "true")
public class NaruHandleTurnBench {

    @Test
    public void turnCostAsTheConversationGrows() throws Exception {
        Nuts.require();
        int turns = Integer.getInteger("naru.bench.turns", 200);
        NaruWorkspace ws = NaruWorkspace.of();
        NPath dir = NPath.ofTempFolder("naru-bench-" + System.nanoTime());
        dir.mkdirs();
        ws.projectDirectory(dir);
        String id = "bench-" + UUID.randomUUID();

        System.out.printf("%n%6s %10s %12s%n", "turn", "ms/turn", "avg so far");
        long total = 0;
        long window = 0;
        for (int i = 1; i <= turns; i++) {
            long t0 = System.nanoTime();
            NaruTurnResult r = ws.newSession()
                    .id(id)
                    .interactive()
                    .task(NaruTaskSpec.of().name("chat")
                            .model(NaruSessionHandleTurnTest.CountingProvider.NAME + "/m"))
                    .interaction(new Silent())
                    .hibernateAfter(Duration.ofMillis(Long.getLong("naru.bench.graceMs", 0)))
                    .buildHandle()
                    .submit(NaruInput.of("message " + i))
                    .result().get(5, TimeUnit.MINUTES);
            long dt = System.nanoTime() - t0;
            Assertions.assertNull(r.failure(), () -> "turn failed: " + r.failure());
            total += dt;
            window += dt;
            if (i == 1 || i % 25 == 0) {
                System.out.printf("%6d %10.1f %12.1f%n", i,
                        window / 1e6 / (i == 1 ? 1 : 25), total / 1e6 / i);
                window = 0;
            }
        }
    }

    /**
     * Many users at once: {@code -Dnaru.bench.sessions} conversations (500), each running
     * {@code -Dnaru.bench.turns} turns (5) against a model that takes {@code -Dnaru.bench.modelMs}
     * (1000) to answer, all concurrently. Prints throughput, turn latency, what NARU adds on top
     * of the model, and the threads and heap it took. {@code -Dnaru.bench.graceMs} keeps sessions
     * loaded between turns (pooled) instead of hibernating each one.
     */
    @Test
    public void manyConversationsAtOnce() throws Exception {
        Nuts.require();
        int sessions = Integer.getInteger("naru.bench.sessions", 500);
        int turns = Integer.getInteger("naru.bench.turns", 5);
        long modelMs = Long.getLong("naru.bench.modelMs", 1000);
        String provider = "bench-slow-" + System.nanoTime();
        NExtensions.of().registerInstance(NaruModelProvider.class, new NaruScriptedModelProvider(provider)
                .otherwise(r -> {
                    try {
                        Thread.sleep(modelMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return NaruMessage.assistant("ok");
                }));
        NaruWorkspace ws = NaruWorkspace.of();
        NPath dir = NPath.ofTempFolder("naru-bench-many-" + System.nanoTime());
        dir.mkdirs();
        ws.projectDirectory(dir);

        List<Long> latencies = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
        List<String> reasons = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.concurrent.atomic.AtomicBoolean traced = new java.util.concurrent.atomic.AtomicBoolean();
        java.lang.management.ThreadMXBean threads = java.lang.management.ManagementFactory.getThreadMXBean();
        threads.resetPeakThreadCount();
        long t0 = System.nanoTime();
        try (var clients = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int s = 0; s < sessions; s++) {
                String id = "user-" + s;
                clients.submit(() -> {
                    for (int i = 1; i <= turns; i++) {
                        long start = System.nanoTime();
                        try {
                            NaruTurnResult r = ws.newSession()
                                    .id(id)
                                    .interactive()
                                    .task(NaruTaskSpec.of().name("chat").model(provider + "/" + NaruScriptedModelProvider.DEFAULT_MODEL))
                                    .interaction(new Silent())
                                    .hibernateAfter(Duration.ofMillis(Long.getLong("naru.bench.graceMs", 0)))
                                    .buildHandle()
                                    .submit(NaruInput.of("message " + i))
                                    .result().get(10, TimeUnit.MINUTES);
                            if (r.failure() != null) {
                                failures.incrementAndGet();
                                reasons.add(id + " turn " + i + ": " + r.failure());
                                if (traced.compareAndSet(false, true)) {
                                    r.failure().printStackTrace(System.out);
                                }
                            }
                        } catch (Exception e) {
                            failures.incrementAndGet();
                            reasons.add(id + " turn " + i + ": " + e);
                        }
                        latencies.add(System.nanoTime() - start);
                    }
                    return null;
                });
            }
        }
        long wall = System.nanoTime() - t0;
        List<Long> sorted = new java.util.ArrayList<>(latencies);
        java.util.Collections.sort(sorted);
        java.util.function.IntFunction<Double> pct = q -> sorted.get(Math.min(sorted.size() - 1, sorted.size() * q / 100)) / 1e6;
        Runtime rt = Runtime.getRuntime();
        System.out.printf("%nsessions=%d turns=%d modelMs=%d%n", sessions, turns, modelMs);
        System.out.printf("wall %.1f s, %.1f turns/s, failures %d%n", wall / 1e9, sorted.size() / (wall / 1e9), failures.get());
        System.out.printf("turn latency ms: p50 %.0f, p95 %.0f, max %.0f (NARU overhead p50 %.0f, p95 %.0f)%n",
                pct.apply(50), pct.apply(95), pct.apply(100), pct.apply(50) - modelMs, pct.apply(95) - modelMs);
        System.out.printf("peak threads %d, heap used %d MB%n", threads.getPeakThreadCount(),
                (rt.totalMemory() - rt.freeMemory()) >> 20);
        reasons.stream().limit(5).forEach(r -> System.out.println("failed: " + r));
        Assertions.assertEquals(0, failures.get(), "every turn must succeed");
    }

    private static final class Silent implements NaruInteraction {
        @Override
        public void open(NaruSession session) {
        }

        @Override
        public void requestInput(NaruInputRequest request) {
        }

        @Override
        public void write(NaruLogMode mode, NMsg message) {
        }

        @Override
        public void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
        }

        @Override
        public void close() {
        }
    }
}
