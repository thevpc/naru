//package net.thevpc.naru.desktop.spike;
//
//import net.thevpc.naru.api.agent.NaruSession;
//import net.thevpc.naru.api.task.NaruTask;
//import net.thevpc.naru.api.model.NaruMessage;
//import net.thevpc.naru.api.model.NaruModelConfig;
//import net.thevpc.naru.api.model.NaruModelRequest;
//import net.thevpc.naru.api.agent.NaruWorkspace;
//import net.thevpc.naru.api.task.NaruTaskSpec;
//import net.thevpc.naru.desktop.host.InteractionHost;
//import net.thevpc.nuts.Nuts;
//import net.thevpc.nuts.io.NPath;
//
//import javax.swing.JFrame;
//import javax.swing.SwingUtilities;
//import java.util.ArrayList;
//import java.util.HashMap;
//import java.util.List;
//
///**
// * Spike 1 -- the seam, measured.
// * <p>
// * Streams a scripted model turn through the real engine (workspace, session, task,
// * scheduler) into a Swing host, and answers the only questions that matter before Phase 1:
// * does text arrive incrementally, does publishing stay off the EDT without blocking the
// * worker, and how late is the EDT during a long turn.
// * <p>
// * Run: {@code java ... StreamingSpike <burst|slow|flood> [show]}
// */
//public class StreamingSpike {
//
//    public static void main(String[] args) throws Exception {
//        String scenario = args.length > 0 ? args[0] : "slow";
//        boolean show = args.length > 1 && "show".equals(args[1]);
//
//        Nuts.require();
//
//        FakeModel.Profile profile = switch (scenario) {
//            case "burst" -> FakeModel.Profile.burst(2000);
//            case "flood" -> FakeModel.Profile.flood();
//            default -> FakeModel.Profile.slowTurn();
//        };
//        FakeModel model = new FakeModel(profile);
//
//        TranscriptPanel panel = new TranscriptPanel(TranscriptPanel.Renderer.LABEL);
//        List<Object> seen = new ArrayList<>();
//        InteractionHost host = new InteractionHost("spike-" + scenario, 50_000,
//                (batch, h) -> {
//                    seen.addAll(batch);
//                    panel.applyBatch(batch);
//                });
//
//        JFrame frame = null;
//        if (show) {
//            frame = new JFrame("naru desktop spike — " + scenario);
//            JFrame f = frame;
//            JFrame[] holder = new JFrame[1];
//            SwingUtilities.invokeAndWait(() -> {
//                f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
//                f.setSize(1100, 700);
//                f.getContentPane().add(panel);
//                f.setVisible(true);
//                holder[0] = f;
//            });
//        }
//
//        EDTProbe probe = new EDTProbe(5);
//        SwingUtilities.invokeAndWait(host::startFlushTimer);
//        probe.start();
//
//        NaruWorkspace agent = NaruWorkspace.of();
//        agent.projectDirectory(NPath.ofTempFolder("naru-desktop-spike-" + System.nanoTime()));
//        NaruSession session = agent.newSession()
//                .directory(agent.projectDirectory())
//                .interaction(host)
//                .configureDefaults(false)
//                .build();
//        session.registry().registerModelProvider(model.provider());
//
//        NaruTask task = session.newTask(NaruTaskSpec.of().statements("noop"));
//        task.setModel(new NaruModelConfig("fake", "fake-model"));
//
//        long t0 = System.currentTimeMillis();
//        // a worker thread, exactly like the actor loop: the host must return immediately
//        long chatStart = System.currentTimeMillis();
//        task.chat(task.model(), new NaruModelRequest(List.of(NaruMessage.user("hi")), new HashMap<>()));
//        long chatMillis = System.currentTimeMillis() - chatStart;
//        long totalMillis = System.currentTimeMillis() - t0;
//
//        // let the coalescer drain everything it accepted
//        long drainStart = System.currentTimeMillis();
//        SwingUtilities.invokeAndWait(() -> host.drainAllOnEdt());
//        long drainMillis = System.currentTimeMillis() - drainStart;
//
//        probe.stop();
//
//        Runtime rt = Runtime.getRuntime();
//        long usedBefore = rt.totalMemory() - rt.freeMemory();
//
//        System.out.println("SPIKE1 scenario=" + scenario
//                + " chunks=" + profile.chunks()
//                + " gapMs=" + profile.gapMillis()
//                + " tailMs=" + profile.tailDelayMs());
//        System.out.println("  engine-stream-calls=" + model.streamCalls());
//        System.out.println("  chat-thread-time-ms=" + chatMillis + " (worker parked while streaming)");
//        System.out.println("  host " + host.statsLine());
//        System.out.println("  transcript blocks=" + panel.blockCount()
//                + " chars=" + panel.totalChars()
//                + " appliedEvents=" + panel.appliedEvents()
//                + " maxApplyMs=" + panel.maxApplyMillis());
//        System.out.println("  " + probe.line());
//        System.out.println("  drain-ms=" + drainMillis + " total-ms=" + totalMillis);
//        System.out.println("  heapUsedMb=" + (usedBefore / (1024 * 1024)));
//        System.out.println("  PASS-criteria: incremental=" + (panel.appliedEvents() > 1)
//                + " publishNeverBlocks(worker-time>0)=" + (chatMillis > 0)
//                + " noDrops=" + (host.droppedEvents() == 0));
//
//        session.stop();
//        host.close();
//        if (frame != null) {
//            frame.dispose();
//        }
//        System.exit(0);
//    }
//}
