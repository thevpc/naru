package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruInput;
import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruTurnResult;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
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
