package net.thevpc.naru.desktop.spike;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.desktop.host.InteractionHost;
import net.thevpc.naru.desktop.host.TranscriptEvent;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spike 2 -- parking, the kill criterion.
 * <p>
 * An interactive session parks a worker in {@code BLOCKED_ON_INPUT} and asks the host a
 * question. Two things have to be true or the whole GUI plan is wrong:
 * <ol>
 *   <li>the answer must be deliverable from the EDT, from a different thread than the one that
 *       parked, and the task must resume;</li>
 *   <li>closing the host must release a parked task rather than leave it parked forever.</li>
 * </ol>
 */
public class ParkingSpike {

    public static void main(String[] args) throws Exception {
        Nuts.require();

        CountDownLatch firstRequest = new CountDownLatch(1);
        CountDownLatch secondRequest = new CountDownLatch(1);
        AtomicReference<NaruInputRequest> first = new AtomicReference<>();
        AtomicReference<NaruInputRequest> second = new AtomicReference<>();
        AtomicInteger requestsSeen = new AtomicInteger();
        AtomicInteger chunksSeen = new AtomicInteger();

        InteractionHost host = new InteractionHost("spike-parking", 50_000, (batch, h) -> {
            for (TranscriptEvent e : batch) {
                if (e instanceof TranscriptEvent.InputRequested ir) {
                    requestsSeen.incrementAndGet();
                    if (first.compareAndSet(null, ir.request())) {
                        firstRequest.countDown();
                    } else if (second.compareAndSet(null, ir.request())) {
                        secondRequest.countDown();
                    }
                } else if (e instanceof TranscriptEvent.Chunk) {
                    chunksSeen.incrementAndGet();
                }
            }
        });

        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(NPath.ofTempFolder("naru-desktop-park-" + System.nanoTime()));
        NaruSession session = agent.newSession()
                .directory(agent.projectDirectory())
                .interaction(host)
                .configureDefaults(false)
                .interactive()
                .build();

        SwingUtilities.invokeAndWait(host::startFlushTimer);
        session.start();

        boolean got1 = firstRequest.await(20, TimeUnit.SECONDS);
        NaruTask parked = got1 ? first.get().task() : null;
        boolean blockedBefore = parked != null && parked.status() == NaruTaskStatus.BLOCKED_ON_INPUT;

        // answer from the EDT, the way a dialog would after the user presses Enter
        long t0 = System.nanoTime();
        if (got1) {
            SwingUtilities.invokeAndWait(() -> first.get().deliver("/print desktop-spike-answered"));
        }
        long deliverNanos = System.nanoTime() - t0;

        Thread.sleep(1500);
        boolean resumed = parked != null && parked.status() != NaruTaskStatus.BLOCKED_ON_INPUT;
        boolean got2 = secondRequest.await(15, TimeUnit.SECONDS);

        // now close the host while a question is outstanding
        boolean releasedByClose = false;
        String statusAfterClose = "n/a";
        if (got2) {
            NaruTask t2 = second.get().task();
            SwingUtilities.invokeAndWait(host::close);
            Thread.sleep(1200);
            NaruTaskStatus st = t2.status();
            statusAfterClose = String.valueOf(st);
            releasedByClose = st == NaruTaskStatus.FAILED || st == NaruTaskStatus.KILLED
                    || st == NaruTaskStatus.DONE || st.isCompleted();
        } else {
            SwingUtilities.invokeAndWait(host::close);
        }

        boolean noBlockedWorkers = session.tasks().stream()
                .noneMatch(t -> t.status() == NaruTaskStatus.BLOCKED_ON_INPUT);

        System.out.println("SPIKE2 parking");
        System.out.println("  request-reached-host=" + got1);
        System.out.println("  prompt-text=" + (got1 ? abbrev(first.get().prompt().toString()) : "n/a"));
        System.out.println("  task-was-blocked-before-answer=" + blockedBefore);
        System.out.println("  deliver-from-edt-ms=" + (deliverNanos / 1_000_000));
        System.out.println("  task-resumed-after-answer=" + resumed);
        System.out.println("  second-request-seen=" + got2 + " requestsSeen=" + requestsSeen.get()
                + " chunksSeen=" + chunksSeen.get());
        System.out.println("  close-released-parked-task=" + releasedByClose
                + " statusAfterClose=" + statusAfterClose);
        System.out.println("  no-worker-left-BLOCKED_ON_INPUT=" + noBlockedWorkers);
        System.out.println("  host-stats=" + host.statsLine());
        System.out.println("  VERDICT=" + (got1 && blockedBefore && resumed && releasedByClose && noBlockedWorkers
                ? "PASS — the seam works, Phase 1 may proceed"
                : "FAIL — stop and fix the engine before writing widgets"));

        session.terminate();
        System.exit(0);
    }

    private static String abbrev(String s) {
        if (s == null) {
            return "null";
        }
        String x = s.replace('\n', ' ');
        return x.length() > 40 ? x.substring(0, 40) + "…" : x;
    }
}
