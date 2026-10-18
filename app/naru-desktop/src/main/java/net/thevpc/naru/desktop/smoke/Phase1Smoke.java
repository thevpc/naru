package net.thevpc.naru.desktop.smoke;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.desktop.MainWindow;
import net.thevpc.naru.desktop.model.InputPrompt;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.model.TranscriptBlock;
import net.thevpc.naru.desktop.spike.FakeModel;
import net.thevpc.naru.desktop.util.MsgText;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.swing.NSwingUtils;

import javax.swing.SwingUtilities;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;

/**
 * Phase 1 gate run (plan §5, "a real task runs end-to-end without freezing").
 * <p>
 * This drives the actual widgets — {@code MainWindow}, {@code TranscriptPane}, {@code InputPane},
 * the real {@code InteractionHost} and a real local model — through a scripted conversation, and
 * reports measurements rather than impressions: EDT stall while the model thinks, flush cost,
 * block counts, and whether the parked task resumed each time.
 * <p>
 * It submits through {@code NaruSessionModel.submit}, which is the same call the Send button
 * makes, so the harness exercises the production path rather than a test-only one.
 *
 * <pre>
 * usage: Phase1Smoke &lt;projectDir&gt; &lt;model&gt; [secondsToWait]
 *   -Dnaru.smoke.prefill=&lt;lines&gt;  add a long tool-reading turn to the script
 *   -Dnaru.smoke.stream=&lt;seconds&gt; stream a deterministic fake-provider turn of that length
 *                                 through the same widgets after the script ends
 *   -Dnaru.smoke.dump=&lt;file&gt;      write the whole transcript, verbose rows included
 * </pre>
 */
public final class Phase1Smoke {

    public static void main(String[] args) throws Exception {
        // the workspace comes before anything that resolves a path or an extension
        Nuts.require();
        NSwingUtils.prepareUI(new String[0]);
        NPath project = NPath.of(args.length > 0 ? args[0] : System.getProperty("java.io.tmpdir"))
                .toAbsolute();
        String model = args.length > 1 ? args[1] : "qwen2.5-coder:14b";
        long waitSeconds = args.length > 2 ? Long.parseLong(args[2]) : 240;

        // the harness owns its scratch project: a fresh gate run should not fail because the
        // directory the operator named does not exist yet
        project.mkdirs();
        // a couple of files so a tool call has something real to do
        project.resolve("alpha.txt").writeString("one\ntwo\nthree\n");
        project.resolve("beta.txt").writeString("x".repeat(400) + "\n");

        AtomicLong maxEdtPingNanos = new AtomicLong();
        AtomicInteger pings = new AtomicInteger();
        AtomicInteger stallsOver16 = new AtomicInteger();
        AtomicInteger stallsOver250 = new AtomicInteger();
        AtomicInteger stallsOver1000 = new AtomicInteger();
        Thread pinger = new Thread(() -> {
            while (true) {
                long t0 = System.nanoTime();
                try {
                    SwingUtilities.invokeAndWait(() -> {
                    });
                } catch (Exception e) {
                    return;
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                maxEdtPingNanos.accumulateAndGet(ms, Math::max);
                pings.incrementAndGet();
                if (ms > 16) {
                    stallsOver16.incrementAndGet();
                }
                if (ms > 250) {
                    stallsOver250.incrementAndGet();
                }
                if (ms > 1000) {
                    stallsOver1000.incrementAndGet();
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "edt-pinger");
        pinger.setDaemon(true);
        pinger.start();

        MainWindow[] win = new MainWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            MainWindow w = new MainWindow();
            w.setSize(1400, 900);
            w.setLocation(40, 40);
            w.setVisible(true);
            w.openProject(project);
            win[0] = w;
        });
        // openProject returns before the session is up: the boot runs on a worker thread, which
        // is the point of the fix, so give it time and measure how long the EDT stayed free
        long bootWait = System.currentTimeMillis();
        NaruSessionModel session = awaitSession(win[0], 60);
        long bootMillis = System.currentTimeMillis() - bootWait;
        if (session == null) {
            report(false, "the session never started", project, model, pings.get(),
                    maxEdtPingNanos.get(), stallsOver16.get(), stallsOver250.get(),
                    stallsOver1000.get(), null, 0, 0, 0);
            return;
        }

        List<String> script = new ArrayList<>(List.of(
                "/model use " + model,
                "List the files in this project, say which one is largest, and say hello.",
                "Read alpha.txt and count its lines.",
                // the one engine tool that parks on purpose. ask_user only produces numbered
                // choice lines when it is given its `choices` argument, so the wording names it:
                // that is the shape InputPrompt turns into buttons
                "Call the ask_user tool with question \"Which colour should I use?\" and choices "
                        + "[\"red\", \"blue\"]. Then tell me the colour you were told."));
        List<String> log = new ArrayList<>();
        log.add("session booted in " + bootMillis / 1000 + " s (off the EDT)");
        // -Dnaru.smoke.prefill=<lines> adds the long turn the Phase 1 gate asks for: a real local
        // model streaming for minutes is the load that would expose an EDT stall, and no synthetic
        // replay proves the same thing. The volume comes from tool output on a file the harness
        // wrote, because asking a small coder model for a 3000-word essay only earns a refusal.
        String prefill = System.getProperty("naru.smoke.prefill");
        if (prefill != null) {
            int lines = Integer.parseInt(prefill);
            StringBuilder inventory = new StringBuilder();
            for (int i = 1; i <= lines; i++) {
                inventory.append("item ").append(String.format("%04d", i))
                        .append(" : widget-").append(i).append(" : qty ").append(i % 97).append('\n');
            }
            project.resolve("inventory.txt").writeString(inventory.toString());
            log.add("wrote inventory.txt with " + lines + " lines for the long turn");
            // name the tool the way the ask_user line names its tool: this model will not
            // discover file_read on its own, and an unnamed "read this file" turn ends in a
            // refusal to use "a tool it does not have" rather than in a long stream
            script.add("Call the file_read tool with path inventory.txt and read the whole file. "
                    + "Then, from that one read, write one short paragraph for every hundred "
                    + "lines, from line 1 to line " + lines + ", one paragraph after another. "
                    + "Do not call any other tool and do not stop until line " + lines + ".");
        }
        // show everything, including the verbose rows, so the dump and the counters describe the
        // same transcript the harness is reasoning about
        SwingUtilities.invokeAndWait(() -> session.transcript().setVerbose(true));
        log.add("verbose transcript rows enabled for the run");
        boolean ok = true;
        long deadline = System.currentTimeMillis() + waitSeconds * 1000;
        // the generation counter is what makes this loop honest: every park of the readline or
        // an ask_user is a new request, and an answered one is a request we have already seen
        long[] seen = {0};
        int nextLine = 0;
        int askUserAnswers = 0;
        int askUserChoices = 0;
        int turns = 0;
        while (nextLine < script.size()) {
            NaruInputRequest r = awaitNewRequest(session, seen, deadline);
            if (r == null) {
                ok = false;
                log.add("no new prompt reached the host before the deadline; next unsent line: "
                        + script.get(nextLine));
                break;
            }
            String text = MsgText.plain(r.prompt() == null ? "" : r.prompt().toString());
            List<String> choices = InputPrompt.parse(text).choices();
            boolean askUser = !choices.isEmpty();
            String answer = askUser ? firstChoice(text) : script.get(nextLine++);
            if (askUser) {
                askUserAnswers++;
                askUserChoices = choices.size();
                log.add("agent question: " + abbrev(InputPrompt.parse(text).question())
                        + " with " + choices.size() + " choices " + choices);
            } else {
                turns++;
            }
            long t0 = System.currentTimeMillis();
            final String toSend = answer;
            SwingUtilities.invokeAndWait(() -> session.submit(toSend));
            log.add((askUser ? "answered agent question with " : "sent ") + abbrev(toSend)
                    + " [" + abbrev(text) + "]");
            if (!askUser) {
                // the turn runs asynchronously; wait until the transcript stops growing (or the
                // agent asks) so the per-turn duration is real rather than assumed
                long settled = awaitQuiet(session, seen, deadline);
                int[] c = counts(session);
                log.add("turn took " + (settled - t0) / 1000 + " s; blocks=" + c[0]
                        + " chars=" + c[1]);
            }
        }
        // whatever the last turn left behind is either the idle prompt or a real agent question;
        // an ask_user with choices is exactly the request the choice buttons exist for, so answer
        // it through the same submit path and require the session to park again afterwards
        NaruInputRequest after = awaitNewRequest(session, seen, Math.min(deadline,
                System.currentTimeMillis() + 90_000));
        if (after == null) {
            ok = false;
            log.add("the session never parked again after the last turn");
        } else {
            String afterText = MsgText.plain(after.prompt() == null ? "" : after.prompt().toString());
            List<String> afterChoices = InputPrompt.parse(afterText).choices();
            if (!afterChoices.isEmpty()) {
                askUserAnswers++;
                askUserChoices = afterChoices.size();
                log.add("agent question from a real tool call: "
                        + abbrev(InputPrompt.parse(afterText).question()) + " with "
                        + afterChoices.size() + " choices " + afterChoices
                        + " (the buttons the user would click)");
                long t0 = System.currentTimeMillis();
                SwingUtilities.invokeAndWait(() -> session.submit("1"));
                NaruInputRequest resumed = awaitNewRequest(session, seen, Math.min(deadline,
                        System.currentTimeMillis() + 120_000));
                if (resumed == null) {
                    ok = false;
                    log.add("the task never parked again after the agent question was answered");
                } else {
                    log.add("the parked task resumed on the answer and parked again after "
                            + (System.currentTimeMillis() - t0) / 1000 + " s");
                }
            } else {
                log.add("parked on the idle prompt after the last script line (requests seen="
                        + seen[0] + ", turns=" + turns + ")");
            }
        }
        log.add("totals: requests=" + seen[0] + " turns=" + turns
                + " agent questions answered=" + askUserAnswers
                + " choices on the last one=" + askUserChoices);
        if (askUserAnswers == 0) {
            log.add("note: this run never received a numbered agent question, so the "
                    + "choice-button path was only exercised by its unit tests");
        }
        int[] finalCounts = counts(session);
        log.add("transcript ended with blocks=" + finalCounts[0] + " chars=" + finalCounts[1]
                + " trimmed=" + trimmed(session));
        SwingUtilities.invokeAndWait(() -> {
            if (!session.transcript().visible().isEmpty()) {
                TranscriptBlock b = session.transcript().visible()
                        .get(session.transcript().visible().size() - 1);
                log.add("last block mode=" + b.mode() + " closed=" + b.isClosed()
                        + " text=" + abbrev(b.text()));
            }
        });
        // -Dnaru.smoke.dump=<file> writes the whole transcript: when a turn goes quiet this is
        // the only way to see what the engine actually said rather than guess from counters
        String dumpPath = System.getProperty("naru.smoke.dump");
        if (dumpPath != null) {
            dump(session, NPath.of(dumpPath));
        }

        // -Dnaru.smoke.stream=<seconds>: the sustained long turn the gate asks for. Asking a
        // 14B coder model to ramble for minutes does not work — it refuses — so the volume comes
        // from a deterministic provider instead. It is still the real engine loop, the real host,
        // the real coalescer and the real widgets, so any EDT stall it exposes is one the user
        // would feel; only the model is replaced.
        String stream = System.getProperty("naru.smoke.stream");
        if (stream != null) {
            long before = maxEdtPingNanos.get();
            int beforeStalls16 = stallsOver16.get();
            int beforeStalls250 = stallsOver250.get();
            int beforePings = pings.get();
            int[] c0 = counts(session);
            long t0 = System.currentTimeMillis();
            String outcome = runStreamLoad(win[0], session, Integer.parseInt(stream), c0, log);
            long dt = System.currentTimeMillis() - t0;
            int[] c1 = counts(session);
            log.add("stream load " + (outcome == null ? "ok" : "FAILED: " + outcome) + " in "
                    + dt / 1000 + " s; blocks=" + c0[0] + "->" + c1[0] + " chars=" + c0[1] + "->"
                    + c1[1] + " added=" + (c1[1] - c0[1]));
            log.add("stream EDT: pings=" + (pings.get() - beforePings)
                    + " max-ping-before=" + before + "ms max-ping-now=" + maxEdtPingNanos.get()
                    + "ms stalls>16ms=+" + (stallsOver16.get() - beforeStalls16)
                    + " stalls>250ms=+" + (stallsOver250.get() - beforeStalls250)
                    + " stats=" + (win[0].host() == null ? "n/a" : win[0].host().statsLine()));
            if (outcome != null) {
                ok = false;
            }
        }

        File shot = capture(win[0]);
        String stats = win[0].host() == null ? "n/a" : win[0].host().statsLine();
        int[] counts = counts(session);
        String stuck = awaitCloseReleases(session, win[0]);
        boolean released = stuck == null;
        if (!released) {
            log.add("closing the window left a task parked: " + stuck);
        } else {
            log.add("closing the window released every parked task");
        }
        report(ok && released, String.join("\n  ", log), project, model, pings.get(),
                maxEdtPingNanos.get(), stallsOver16.get(), stallsOver250.get(),
                stallsOver1000.get(), stats, counts[0], counts[1],
                shot == null ? 0 : shot.length());
        System.exit(ok && released ? 0 : 1);
    }

    /**
     * Stream {@code seconds} of model output through the running session, on a worker thread,
     * while the widgets and the coalescer do their job on the EDT.
     *
     * @return {@code null} when the stream completed, otherwise a description of what went wrong
     */
    private static String runStreamLoad(MainWindow w, NaruSessionModel session, int seconds,
                                        int[] before, List<String> log) throws Exception {
        net.thevpc.naru.api.agent.NaruSession s = session.session();
        if (s == null) {
            return "the session is not up, so there is no registry to add a provider to";
        }
        // 18 ms between 20-char fragments is the pacing Phase 0 spike 1 used, so the two
        // measurements are comparable; only the duration is parameterised. FakeModel streams
        // its chunk count twice (a thinking run and an answer run), hence the /2.
        long gap = 18;
        int chunks = (int) (seconds * 1000L / (2 * gap));
        FakeModel model = new FakeModel(new FakeModel.Profile(chunks, 20, gap, true, 0));
        s.registry().registerModelProvider(model.provider());
        NaruTask task = s.newTask(NaruTaskSpec.of().statements("smoke-stream"));
        task.setModel(new NaruModelConfig("fake", "fake-model"));
        log.add("stream load: fake provider, " + chunks + " chunks x " + model.profile().chunkChars()
                + " chars every " + gap + " ms, emitted twice (thinking then answer) = about "
                + seconds + " s, " + (chunks * 2L * model.profile().chunkChars() / 1024) + " KiB");
        Throwable[] failure = new Throwable[1];
        Thread worker = new Thread(() -> {
            try {
                task.chat(task.model(), new NaruModelRequest(
                        List.of(NaruMessage.user("stream for me")), new HashMap<>()));
            } catch (Throwable t) {
                failure[0] = t;
            }
        }, "smoke-stream-worker");
        worker.setDaemon(true);
        worker.start();
        long tStart = System.currentTimeMillis();
        long until = tStart + (seconds + 120L) * 1000;
        long nextReport = 0;
        while (worker.isAlive() && System.currentTimeMillis() < until) {
            Thread.sleep(2000);
            long elapsed = (System.currentTimeMillis() - tStart) / 1000;
            if (elapsed < nextReport) {
                continue;
            }
            nextReport = elapsed + 30;
            int[] now = counts(session);
            if (now[1] > before[1]) {
                log.add("  stream so far: +" + (now[1] - before[1]) + " chars, blocks=" + now[0]
                        + " after " + elapsed + " s");
            }
        }
        if (worker.isAlive()) {
            worker.interrupt();
            return "the fake stream never finished within " + (seconds + 120) + " s";
        }
        // let the coalescer deliver everything it queued before the counters are read
        if (w.host() != null) {
            SwingUtilities.invokeAndWait(w.host()::drainAllOnEdt);
        }
        if (failure[0] != null) {
            return "the stream threw " + failure[0];
        }
        return null;
    }

    /**
     * Closing the window must not leave a worker parked — the plan's §4 parking guarantee.
     *
     * @return {@code null} when nothing stayed blocked, otherwise a description of what did
     */
    private static String awaitCloseReleases(NaruSessionModel session, MainWindow w) throws Exception {
        SwingUtilities.invokeAndWait(session::shutdown);
        for (int i = 0; i < 40; i++) {
            String any = null;
            for (NaruSessionModel.TaskRow t : session.tasks()) {
                if (t.status() == net.thevpc.naru.api.scheduler.NaruTaskStatus.BLOCKED_ON_INPUT) {
                    any = "task #" + t.id() + " " + t.name() + " still BLOCKED_ON_INPUT";
                }
            }
            if (any == null) {
                return null;
            }
            Thread.sleep(250);
            if (i == 39) {
                return any + " (after 10 s)";
            }
        }
        return "unreachable";
    }

    private static NaruSessionModel awaitSession(MainWindow w, int seconds) throws Exception {
        for (int i = 0; i < seconds * 4; i++) {
            NaruSessionModel s = w.session();
            if (s != null && s.isRunning()) {
                return s;
            }
            Thread.sleep(250);
        }
        return w.session();
    }

    /**
     * Wait for a request the caller has not seen yet, advancing the generation counter.
     * The old version compared against a remembered request object, which made a park that
     * followed an answer invisible — the second script line of the first gate run was dropped
     * exactly that way.
     */
    private static NaruInputRequest awaitNewRequest(NaruSessionModel s, long[] seen, long deadline)
            throws Exception {
        while (System.currentTimeMillis() < deadline) {
            final NaruInputRequest[] now = new NaruInputRequest[1];
            final long[] count = new long[1];
            SwingUtilities.invokeAndWait(() -> {
                now[0] = s.pendingRequest();
                count[0] = s.inputRequestsSeen();
            });
            if (now[0] != null && count[0] > seen[0]) {
                seen[0] = count[0];
                return now[0];
            }
            Thread.sleep(250);
        }
        return null;
    }

    /** Peek, without consuming: is there an unanswered request right now? */
    private static boolean requestWaiting(NaruSessionModel s, long[] seen) throws Exception {
        final boolean[] out = new boolean[1];
        SwingUtilities.invokeAndWait(() -> out[0] = s.pendingRequest() != null
                && s.inputRequestsSeen() > seen[0]);
        return out[0];
    }

    /**
     * Wait for the turn to stop: the agent either asks something or produces no new characters
     * for four seconds. Returns the wall-clock time it settled, so the log can report a duration.
     */
    private static long awaitQuiet(NaruSessionModel s, long[] seen, long deadline) throws Exception {
        long previous = -1;
        int stable = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            if (requestWaiting(s, seen)) {
                return System.currentTimeMillis();
            }
            long chars = counts(s)[1];
            if (chars == previous) {
                if (++stable >= 8) {
                    return System.currentTimeMillis();
                }
            } else {
                stable = 0;
                previous = chars;
            }
        }
        return System.currentTimeMillis();
    }

    /** The engine accepts the choice number, which is also what the button sends. */
    private static String firstChoice(String text) {
        return "1";
    }

    private static int trimmed(NaruSessionModel s) throws Exception {
        final int[] out = new int[1];
        SwingUtilities.invokeAndWait(() -> out[0] = s.transcript().trimmedBlocks());
        return out[0];
    }

    /** The transcript model is EDT-confined; a reader from another thread asks it on the EDT. */
    private static int[] counts(NaruSessionModel s) throws Exception {
        final int[] out = new int[2];
        SwingUtilities.invokeAndWait(() -> {
            out[0] = s.transcript().totalBlocks();
            out[1] = s.transcript().retainedChars();
        });
        return out;
    }

    /** Write every block, in order, with its mode — the transcript as the user would read it. */
    private static void dump(NaruSessionModel s, NPath file) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StringBuilder sb = new StringBuilder();
            for (TranscriptBlock b : s.transcript().blocks()) {
                sb.append("── ").append(b.mode()).append(b.isClosed() ? " [closed]" : " [open]")
                        .append(" ──\n").append(b.text()).append('\n');
            }
            try {
                file.writeString(sb.toString());
            } catch (Exception e) {
                sb.append("(dump failed: ").append(e).append(")");
            }
        });
        System.out.println("  transcript-dump=" + file);
    }

    private static File capture(MainWindow w) {
        try {
            BufferedImage img = new Robot().createScreenCapture(new Rectangle(1400, 900));
            File f = new File(System.getProperty("java.io.tmpdir"), "naru-phase1-smoke.png");
            ImageIO.write(img, "png", f);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private static void report(boolean ok, String detail, NPath project, String model, int pings,
                               long maxPing, int over16, int over250, int over1000, String stats,
                               int blocks, int chars, long shotBytes) {
        System.out.println("SMOKE phase1");
        System.out.println("  project=" + project);
        System.out.println("  model=" + model);
        System.out.println("  transcript-blocks=" + blocks + " retained-chars=" + chars);
        System.out.println("  edt-pings=" + pings + " max-ping-ms=" + maxPing);
        System.out.println("  stalls-over-16ms=" + over16 + " over-250ms=" + over250
                + " over-1s=" + over1000);
        System.out.println("  host-stats=" + stats);
        System.out.println("  screenshot-bytes=" + shotBytes);
        System.out.println("  detail:\n  " + detail);
        System.out.println("  VERDICT=" + (ok ? "PASS — Phase 1 ran end-to-end"
                : "FAIL — see the detail above"));
    }

    private static String abbrev(String s) {
        String x = s.replace('\n', ' ').trim();
        return x.length() > 60 ? x.substring(0, 60) + "…" : x;
    }
}
