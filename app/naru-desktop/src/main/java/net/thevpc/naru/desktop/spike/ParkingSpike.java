//package net.thevpc.naru.desktop.spike;
//
//import net.thevpc.naru.api.agent.NaruInputRequest;
//import net.thevpc.naru.api.agent.NaruLogMode;
//import net.thevpc.naru.api.agent.NaruSession;
//import net.thevpc.naru.api.agent.NaruWorkspace;
//import net.thevpc.naru.api.task.NaruTask;
//import net.thevpc.naru.api.scheduler.NaruTaskMode;
//import net.thevpc.naru.api.task.NaruTaskSpec;
//import net.thevpc.naru.api.scheduler.NaruTaskStatus;
//import net.thevpc.naru.desktop.host.InteractionHost;
//import net.thevpc.naru.desktop.host.TranscriptEvent;
//import net.thevpc.nuts.Nuts;
//import net.thevpc.nuts.io.NPath;
//import net.thevpc.nuts.text.NMsg;
//
//import javax.swing.SwingUtilities;
//import java.util.concurrent.CountDownLatch;
//import java.util.concurrent.TimeUnit;
//import java.util.concurrent.atomic.AtomicInteger;
//import java.util.concurrent.atomic.AtomicReference;
//
///**
// * Spike 2 -- parking, the kill criterion.
// * <p>
// * A worker parks in {@code BLOCKED_ON_INPUT} and asks the host a question. Two things have to be
// * true or the whole GUI plan is wrong:
// * <ol>
// *   <li>the answer must be deliverable from the EDT, from a different thread than the one that
// *       parked, and the task must resume;</li>
// *   <li>closing the host must release a parked task rather than leave it parked forever.</li>
// * </ol>
// * <p>
// * The question comes from the real production path: an INTERACTIVE task with nothing to run gets
// * a {@code NaruReadlineStmt} injected ({@code NaruTaskImpl:2228-2238}), which calls
// * {@code task.requestInput}. That is what the terminal REPL does at its prompt, so this spike
// * exercises the same sequence a desktop prompt dialog will hit -- and it needs no model, so the
// * measurement is not confounded by the agent.
// * <p>
// * Note the held-task trap found on the first run: {@code NaruSchedulerImpl.requeue} returns early
// * when {@code task.isHeld()}, so a task parked while held never notifies the interaction. The
// * task must be unheld for the question to reach the host.
// */
//public class ParkingSpike {
//
//    public static void main(String[] args) throws Exception {
//        Nuts.require();
//
//        CountDownLatch firstRequest = new CountDownLatch(1);
//        CountDownLatch secondRequest = new CountDownLatch(1);
//        AtomicReference<NaruInputRequest> first = new AtomicReference<>();
//        AtomicReference<NaruInputRequest> second = new AtomicReference<>();
//        AtomicInteger requestsSeen = new AtomicInteger();
//        AtomicInteger chunksSeen = new AtomicInteger();
//
//        InteractionHost host = new InteractionHost("spike-parking", 50_000, (batch, h) -> {
//            for (TranscriptEvent e : batch) {
//                if (e instanceof TranscriptEvent.InputRequested ir) {
//                    requestsSeen.incrementAndGet();
//                    if (first.compareAndSet(null, ir.request())) {
//                        firstRequest.countDown();
//                    } else if (second.compareAndSet(null, ir.request())) {
//                        secondRequest.countDown();
//                    }
//                } else if (e instanceof TranscriptEvent.Chunk) {
//                    chunksSeen.incrementAndGet();
//                }
//            }
//        });
//
//        NaruWorkspace agent = NaruWorkspace.of();
//        agent.projectDirectory(NPath.ofTempFolder("naru-desktop-park-" + System.nanoTime()));
//        NaruSession session = agent.newSession()
//                .directory(agent.projectDirectory())
//                .interaction(host)
//                .configureDefaults(false)
//                .build();
//
//        SwingUtilities.invokeAndWait(() -> {
//            host.startFlushTimer();
//            host.write(NaruLogMode.SCHEDULER, NMsg.ofC("spike: session started"));
//        });
//        session.start();
//
//        NaruTask asker = session.newTask(NaruTaskSpec.of().name("desktop-parking"));
//        asker.taskMode(NaruTaskMode.INTERACTIVE);
//        asker.fg().unhold();
//
//        boolean got1 = firstRequest.await(20, TimeUnit.SECONDS);
//        NaruTask parked = got1 ? first.get().task() : null;
//        boolean blockedBefore = parked != null && parked.status() == NaruTaskStatus.BLOCKED_ON_INPUT;
//        String promptText = got1 ? abbrev(first.get().prompt().toString()) : "n/a";
//
//        // answer from the EDT, the way a dialog would after the user presses Enter
//        long t0 = System.nanoTime();
//        if (got1) {
//            SwingUtilities.invokeAndWait(() -> first.get().deliver("42"));
//        }
//        long deliverMillis = (System.nanoTime() - t0) / 1_000_000;
//
//        Thread.sleep(1500);
//        String statusAfterAnswer = parked == null ? "n/a" : String.valueOf(parked.status());
//
//        // the same interactive task asks again; this one is outstanding when the host closes
//        boolean got2 = secondRequest.await(15, TimeUnit.SECONDS);
//        NaruTask pending = got2 ? second.get().task() : asker;
//        // A readline task re-parks the moment it consumes an answer, so the status alone cannot
//        // show the resume: another question from the same task id is the proof that the first
//        // answer unblocked the worker.
//        boolean sameTaskAskedAgain = got2 && parked != null
//                && second.get().task().id() == parked.id();
//        boolean resumed = sameTaskAskedAgain;
//
//        SwingUtilities.invokeAndWait(host::close);
//        Thread.sleep(1500);
//        NaruTaskStatus statusAfterClose;
//        try {
//            statusAfterClose = pending.status();
//        } catch (Exception gone) {
//            statusAfterClose = null;
//        }
//        boolean releasedByClose = statusAfterClose != null && statusAfterClose.isCompleted();
//
//        // host.close() cancels the outstanding question, the task fails, and a session with no
//        // live task stops -- so tasks() can throw here. Read the task list before it goes.
//        boolean noBlockedWorkers;
//        String taskListAfterClose;
//        try {
//            java.util.List<String> statuses = new java.util.ArrayList<>();
//            for (NaruTask t : session.tasks()) {
//                statuses.add(t.name() + "=" + t.status());
//            }
//            taskListAfterClose = String.join(" ", statuses);
//            noBlockedWorkers = statuses.stream().noneMatch(s -> s.endsWith("=" + NaruTaskStatus.BLOCKED_ON_INPUT));
//        } catch (Exception stopped) {
//            taskListAfterClose = "session already stopped (" + stopped.getMessage() + ")";
//            noBlockedWorkers = statusAfterClose != NaruTaskStatus.BLOCKED_ON_INPUT;
//        }
//
//        System.out.println("SPIKE2 parking");
//        System.out.println("  request-reached-host=" + got1 + " prompt-text=" + promptText);
//        System.out.println("  task-was-blocked-before-answer=" + blockedBefore);
//        System.out.println("  deliver-from-edt-ms=" + deliverMillis);
//        System.out.println("  task-resumed-after-answer=" + resumed
//                + " (sameTaskAskedAgain=" + sameTaskAskedAgain
//                + ") statusAfterAnswer=" + statusAfterAnswer);
//        System.out.println("  second-request-seen=" + got2 + " requestsSeen=" + requestsSeen.get()
//                + " chunksSeen=" + chunksSeen.get());
//        System.out.println("  close-released-parked-task=" + releasedByClose
//                + " statusAfterClose=" + statusAfterClose);
//        System.out.println("  no-worker-left-BLOCKED_ON_INPUT=" + noBlockedWorkers
//                + " tasks-after-close=[" + taskListAfterClose + "]");
//        System.out.println("  host-stats=" + host.statsLine());
//        System.out.println("  VERDICT=" + (got1 && blockedBefore && resumed && releasedByClose && noBlockedWorkers
//                ? "PASS — the seam works, Phase 1 may proceed"
//                : "FAIL — stop and fix the engine before writing widgets"));
//
//        try {
//            session.terminate();
//        } catch (Exception ignored) {
//            // the session may already have stopped when its last task failed
//        }
//
//        System.exit(0);
//    }
//
//    private static String abbrev(String s) {
//        if (s == null) {
//            return "null";
//        }
//        String x = s.replace('\n', ' ');
//        return x.length() > 40 ? x.substring(0, 40) + "…" : x;
//    }
//}
