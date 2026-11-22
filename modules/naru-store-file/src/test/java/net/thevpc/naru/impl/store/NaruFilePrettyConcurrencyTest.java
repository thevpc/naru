package net.thevpc.naru.impl.store;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.store.NaruSessionData;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link NaruFileSessionStore#pretty} runs unlocked, from every session's thread at once.
 * <p>
 * The shared pretty formatter fills an option cache on first use with no lock, and
 * concurrent saves threw from inside it; a global lock used to hide that, at the cost of
 * being the store's worst contention point with hundreds of sessions. This formats the
 * store's own kinds of element from many fresh threads at once and fails if any output
 * differs from the same element formatted alone, or if the formatter throws.
 */
@Timeout(120)
public class NaruFilePrettyConcurrencyTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static List<NElement> elements() {
        List<NElement> out = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            out.add(NaruMessage.user("message " + i + " with \"quotes\", ##{p9:markup}## and "
                    + "x".repeat(i * 20)).toElement());
            out.add(new NaruSessionData().uuid("u-" + i).name("Session " + i)
                    .creationInstant(Instant.ofEpochSecond(1_700_000_000L + i))
                    .modificationInstant(Instant.ofEpochSecond(1_700_000_100L + i))
                    .env(Map.of("mode", NElement.of("test-" + i))).toElement());
            out.add(NElement.ofObjectBuilder()
                    .set("id", (long) i)
                    .set("status", "BLOCKED_ON_INPUT")
                    .set("creationDate", NElement.ofInstant(Instant.ofEpochSecond(1_700_000_000L + i)))
                    .set("labels", NElement.ofObjectBuilder().set("k", "v" + i).build())
                    .set("history", NElement.ofArray(NElement.ofString("0000000000000001"), NElement.ofString("0000000000000002")))
                    .build());
        }
        return out;
    }

    @Test
    public void formattingFromManyThreadsAtOnceGivesWhatFormattingAloneGives() throws Exception {
        List<NElement> elements = elements();
        // the reference comes from a formatter nobody else uses, so the threads below start
        // cold: the race was in a cache that fills on first use
        net.thevpc.nuts.elem.NElementFormatter reference =
                net.thevpc.nuts.elem.NElementFormatter.ofPretty().builder().build();
        List<String> expected = new ArrayList<>();
        for (NElement e : elements) {
            expected.add(net.thevpc.nuts.elem.NElementWriter.ofTson().ntf(false).formatter(reference).formatPlain(e));
        }
        AtomicInteger wrong = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        for (int t = 0; t < 32; t++) {
            pool.submit(() -> {
                for (int k = 0; k < 2000; k++) {
                    int i = k % elements.size();
                    try {
                        if (!expected.get(i).equals(NaruFileSessionStore.pretty(elements.get(i)))) {
                            wrong.incrementAndGet();
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                }
            });
        }
        pool.shutdown();
        Assertions.assertTrue(pool.awaitTermination(2, TimeUnit.MINUTES), "formatting did not finish");
        Assertions.assertNull(failure.get(), () -> "the formatter failed under concurrency: " + failure.get());
        Assertions.assertEquals(0, wrong.get(), "concurrent formatting produced different output");
    }
}
