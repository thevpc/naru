package net.thevpc.naru.desktop.spike;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures how late the EDT actually is.
 * <p>
 * A Swing timer fires no sooner than its delay, and later by however long the event queue is
 * busy. So the gap between scheduled fire time and real fire time <em>is</em> the stall the
 * user feels: if this number exceeds a frame budget, the UI is dropping frames no matter how
 * clean the code looks. This is the instrument behind the Phase 0 numbers.
 */
public class EDTProbe {

    private final List<Long> lagsMicros = new ArrayList<>();
    private volatile boolean running;
    private Timer timer;
    private long nextFireAtNanos;
    private final long periodNanos;
    private volatile long busyWorkNanos;

    public EDTProbe(int periodMillis) {
        this.periodNanos = periodMillis * 1_000_000L;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        lagsMicros.clear();
        nextFireAtNanos = System.nanoTime() + periodNanos;
        timer = new Timer((int) Math.max(1, periodNanos / 1_000_000L), e -> {
            long now = System.nanoTime();
            long lag = now - nextFireAtNanos;
            if (lag > 0) {
                synchronized (EDTProbe.this) {
                    lagsMicros.add(lag / 1_000);
                }
            }
            nextFireAtNanos += periodNanos;
            if (nextFireAtNanos < now) {
                // we fell a whole period behind; resynchronise rather than report a backlog
                nextFireAtNanos = now + periodNanos;
            }
        });
        timer.setCoalesce(false);
        timer.start();
    }

    public synchronized void stop() {
        running = false;
        if (timer != null) {
            timer.stop();
        }
    }

    public synchronized int samples() {
        return lagsMicros.size();
    }

    public synchronized long maxMicros() {
        return lagsMicros.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    public synchronized long p99Micros() {
        return percentile(99);
    }

    public synchronized long medianMicros() {
        return percentile(50);
    }

    private long percentile(int p) {
        if (lagsMicros.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(lagsMicros);
        sorted.sort(Long::compare);
        int idx = (int) Math.floor((p / 100.0) * (sorted.size() - 1));
        return sorted.get(idx);
    }

    /** Run something on the EDT and report how long it took, for per-flush budget checks. */
    public static long timedEdtMillis(Runnable work) throws Exception {
        long[] result = new long[1];
        Runnable wrapped = () -> {
            long t0 = System.nanoTime();
            work.run();
            result[0] = (System.nanoTime() - t0) / 1_000_000L;
        };
        if (SwingUtilities.isEventDispatchThread()) {
            long t0 = System.nanoTime();
            work.run();
            return (System.nanoTime() - t0) / 1_000_000L;
        }
        java.awt.EventQueue.invokeAndWait(wrapped);
        return result[0];
    }

    public String line() {
        return "edt-lag samples=" + samples()
                + " medianUs=" + medianMicros()
                + " p99Us=" + p99Micros()
                + " maxUs=" + maxMicros();
    }
}
