package net.thevpc.naru.desktop.spike;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.desktop.host.InteractionHost;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spike 3 -- the transcript under abuse.
 * <p>
 * 50,000 lines across ~2,000 blocks, published as fast as four worker threads can emit, while
 * the frame is displayable so real layout and paint happen. The number that decides the
 * architecture is not throughput but <em>EDT ping latency</em>: how long a click's response has
 * to wait behind the output stream. That is measured continuously during the load.
 * <p>
 * Run: {@code java ... LoadSpike <lines> <blocks> <threads> <label|html>}
 */
public class LoadSpike {

    public static void main(String[] args) throws Exception {
        int lines = intArg(args, 0, 50_000);
        int blocks = intArg(args, 1, 2_000);
        int threads = intArg(args, 2, 4);
        TranscriptPanel.Renderer renderer = "html".equals(strArg(args, 3, "label"))
                ? TranscriptPanel.Renderer.HTML : TranscriptPanel.Renderer.LABEL;

        TranscriptPanel panel = new TranscriptPanel(renderer);
        InteractionHost host = new InteractionHost("spike-load", 200_000,
                (batch, h) -> panel.applyBatch(batch));

        JFrame frame = new JFrame("naru desktop spike — load (" + renderer + ")");
        SwingUtilities.invokeAndWait(() -> {
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setSize(1200, 800);
            frame.getContentPane().add(panel);
            frame.setVisible(true);
            frame.validate();
            host.startFlushTimer();
        });

        EDTProbe probe = new EDTProbe(5);
        probe.start();

        AtomicLong pingMax = new AtomicLong();
        AtomicLong pingSum = new AtomicLong();
        AtomicLong pingCount = new AtomicLong();
        volatileBox measuring = new volatileBox();

        Thread pinger = new Thread(() -> {
            while (measuring.on) {
                try {
                    long t0 = System.nanoTime();
                    SwingUtilities.invokeAndWait(() -> {
                    });
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    pingSum.addAndGet(ms);
                    pingCount.incrementAndGet();
                    pingMax.accumulateAndGet(ms, Math::max);
                } catch (Exception e) {
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "edt-pinger");
        pinger.start();

        CountDownLatch done = new CountDownLatch(threads);
        long t0 = System.currentTimeMillis();
        int perThread = lines / threads;
        int blocksPerThread = blocks / threads;
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            Thread w = new Thread(() -> {
                NaruLogMode[] modes = NaruLogMode.values();
                int linesPerBlock = Math.max(1, perThread / blocksPerThread);
                for (int b = 0; b < blocksPerThread; b++) {
                    NaruLogMode mode = modes[(tid + b) % modes.length];
                    for (int l = 0; l < linesPerBlock; l++) {
                        host.write(mode, net.thevpc.nuts.text.NMsg.ofC(
                                "line %d/%d thread %d :: the quick brown fox jumps over the lazy dog", b, l, tid));
                    }
                }
                done.countDown();
            }, "publisher-" + t);
            w.setDaemon(true);
            w.start();
        }
        done.await();
        long publishMillis = System.currentTimeMillis() - t0;

        // let the EDT catch up, then stop measuring
        long drainT0 = System.currentTimeMillis();
        while (host.queueDepth() > 0) {
            Thread.sleep(50);
        }
        SwingUtilities.invokeAndWait(() -> host.drainAllOnEdt());
        long drainMillis = System.currentTimeMillis() - drainT0;
        measuring.on = false;
        pinger.join(2000);
        probe.stop();

        long scrollMillis = onEdtReturning(() -> {
            long s = System.nanoTime();
            panel.stressScroll(500);
            return (System.nanoTime() - s) / 1_000_000L;
        });

        Runtime rt = Runtime.getRuntime();
        System.gc();
        Thread.sleep(300);
        long heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);

        System.out.println("SPIKE3 load renderer=" + renderer
                + " lines=" + lines + " blocks=" + blocks + " threads=" + threads);
        System.out.println("  publish-ms=" + publishMillis + " drain-ms=" + drainMillis);
        System.out.println("  host " + host.statsLine());
        System.out.println("  transcript blocks=" + panel.blockCount()
                + " chars=" + panel.totalChars()
                + " appliedEvents=" + panel.appliedEvents()
                + " maxApplyMs=" + panel.maxApplyMillis());
        System.out.println("  EDT ping during load: n=" + pingCount.get()
                + " avgMs=" + (pingCount.get() == 0 ? 0 : pingSum.get() / pingCount.get())
                + " maxMs=" + pingMax.get());
        System.out.println("  " + probe.line());
        System.out.println("  scroll-stress-500jumps-ms=" + scrollMillis);
        System.out.println("  heap-after-gc-mb=" + heapMb);
        System.out.println("  BUDGET flush<=16ms=" + (host.maxFlushMillis() <= 16)
                + " ping<=100ms=" + (pingMax.get() <= 100)
                + " noDrops=" + (host.droppedEvents() == 0));

        frame.dispose();
        System.exit(0);
    }

    /** {@code invokeAndWait} cannot return a value, so results travel through a holder. */
    private static long onEdtReturning(java.util.function.LongSupplier work) throws Exception {
        long[] out = new long[1];
        SwingUtilities.invokeAndWait(() -> out[0] = work.getAsLong());
        return out[0];
    }

    private static java.awt.Graphics getGraphics(JFrame f) {
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    f.getWidth(), f.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            f.paint(img.getGraphics());
            return img.getGraphics();
        } catch (Exception e) {
            return null;
        }
    }

    private static final class volatileBox {
        volatile boolean on = true;
    }

    private static int intArg(String[] a, int i, int def) {
        return a.length > i ? Integer.parseInt(a[i]) : def;
    }

    private static String strArg(String[] a, int i, String def) {
        return a.length > i ? a[i] : def;
    }
}
