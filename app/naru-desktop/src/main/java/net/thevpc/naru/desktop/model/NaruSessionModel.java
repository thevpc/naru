package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.agent.NaruInputMode;
import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.agent.NaruSessionUsageListener;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruProviderRateLimitInfo;
import net.thevpc.naru.api.registry.NaruLineCompletion;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.desktop.host.InteractionHost;
import net.thevpc.naru.desktop.host.TranscriptEvent;
import net.thevpc.naru.desktop.util.EDT;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.time.NDuration;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The per-session view-model (plan §4.1). Widgets talk to this and to nothing else: no widget
 * imports {@code naru-impl}, no widget polls a backend, and — per the §3.1 decision — no widget
 * resolves anything through the process-global shared workspace, because every action here
 * carries the explicit {@link NaruSession} it belongs to.
 * <p>
 * Everything below the line is worker-thread noise that this class converts into EDT-confined
 * state: host batches arrive on the Swing flush timer, usage numbers arrive on the thread that
 * made the model call, task transitions arrive on the scheduler. Those are marshalled in with
 * {@code invokeLater} and applied here, so a widget can read any getter without synchronising
 * and know it is consistent.
 * <p>
 * Task state is polled rather than event-driven at 2 Hz. {@code NaruSessionListener
 * .onTaskStatusChanged} exists and is used to trigger an immediate refresh, but it fires on
 * every READY/RUNNING edge the scheduler performs, and the monitor needs the whole set — so the
 * listener is the nudge and the timer is the guarantee.
 */
public final class NaruSessionModel implements InteractionHost.Listener {

    public interface Listener {
        /** Coarse "something visible changed"; widgets re-read the getters they care about. */
        void sessionModelChanged();
    }

    /** How long the transcript's model call has been running, for the status bar. */
    private static final int TASK_POLL_MILLIS = 500;

    private final java.util.function.Supplier<NaruWorkspace> workspaceFactory;
    private final NPath projectDir;
    private final InteractionHost host;
    private final TranscriptModel transcript = new TranscriptModel();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<String> inputHistory = new ArrayList<>();
    private int historyCursor = -1;

    private volatile NaruSession session;
    private Timer taskPoll;
    private volatile NaruInputRequest pending;
    private volatile boolean booting;
    /**
     * How many input requests the host has delivered, ever. A reader needs this because
     * {@code pending} is cleared the moment it answers: without a generation counter there is no
     * way to tell "the prompt parked again" from "the prompt I already answered is still there",
     * which is what broke the first Phase 1 gate run.
     */
    private long inputRequestsSeen;

    private List<TaskRow> tasks = List.of();
    private String statusText = "not started";
    private long promptTokens;
    private long completionTokens;
    private long cacheReadTokens;
    private long modelCalls;
    private String model = "—";
    private String saveState = "—";
    private NaruInputMode inputMode = NaruInputMode.LINE;
    private boolean running;
    private boolean closed;

    /** A row of the task monitor: what the widget needs, read off a live task. */
    public record TaskRow(long id, String name, NaruTaskStatus status, boolean foreground,
                          long parentId, String error) {
    }

    public NaruSessionModel(java.util.function.Supplier<NaruWorkspace> workspaceFactory,
                            NPath projectDir, InteractionHost host) {
        EDT.check("NaruSessionModel construct");
        this.workspaceFactory = workspaceFactory;
        this.projectDir = projectDir;
        this.host = host;
        host.setListener(this);
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Build and start the session, mirroring the console exactly except for two things: the
     * interaction is this host, and {@code richTerm} stays off — it mutates the process-global
     * terminal singleton, which plan §4.4 forbids in a desktop host.
     * <p>
     * Called on the EDT, but it does not boot on the EDT. Resolving the project's extensions is
     * a seconds-long disk-and-network job ({@code naru-profile-dev} alone took 1.9 s in the first
     * gate run), and doing it inline is exactly the stall this phase is supposed to eliminate.
     * So the work goes to a worker thread and only the Swing-visible results come back:
     * the flush timer starts immediately on the EDT, so anything the engine writes while the
     * workspace is still resolving is displayed as soon as it lands.
     */
    public void start() {
        EDT.check("NaruSessionModel.start");
        if (session != null || booting) {
            return;
        }
        booting = true;
        statusText = "starting…";
        fireChanged();
        Thread t = new Thread(this::boot, "naru-session-boot");
        t.setDaemon(true);
        t.start();
    }

    /** Worker thread: no Swing, no widget, no EDT assertion. */
    private void boot() {
        try {
            NaruWorkspace workspace = workspaceFactory.get();
            session = workspace.newSession()
                .directory(projectDir)
                .interaction(host)
                .statements(new String[0])
                .interactive()
                .banner(false)
                .richTerm(false)
                .sessionListener(new NaruSessionListener() {
                    @Override
                    public void onEventAppended(net.thevpc.naru.api.scheduler.NaruEvent e) {
                    }

                    @Override
                    public void sessionStarted(NaruSession s) {
                        onEdt(() -> {
                            running = true;
                            statusText = "running";
                            fireChanged();
                        });
                    }

                    @Override
                    public void sessionStopped(NaruSession s) {
                        onEdt(() -> {
                            running = false;
                            statusText = "stopped";
                            fireChanged();
                        });
                    }

                    @Override
                    public void onSessionReloaded(NaruSession s) {
                        onEdt(() -> {
                            transcript.clear();
                            statusText = "reloaded";
                            refreshTasks();
                            fireChanged();
                        });
                    }

                    @Override
                    public void onTaskStatusChanged(NaruTask t, NaruTaskStatus a, NaruTaskStatus b) {
                        // any worker thread; the poll timer is the backstop
                        onEdt(NaruSessionModel.this::refreshTasks);
                    }
                })
                .build();
        // usage has no builder hook: it is a per-session listener, and the core announces it on
        // the thread that made the call, so everything below marshals before touching state
        session.addUsageListener(new NaruSessionUsageListener() {
            @Override
            public void onModelCall(NaruTask task, NaruModelKey m, long p, long c,
                                    long cw, long cr, NDuration duration) {
                String label = m == null ? "—" : m.provider() + "/" + m.model();
                String took = duration == null ? "" : " " + duration;
                onEdt(() -> {
                    modelCalls++;
                    promptTokens += Math.max(0, p);
                    completionTokens += Math.max(0, c);
                    cacheReadTokens += Math.max(0, cr);
                    if (m != null) {
                        model = label;
                    }
                    statusText = "model call" + took;
                    fireChanged();
                });
            }

            @Override
            public void onProviderRateLimits(NaruProviderRateLimitInfo info) {
                onEdt(NaruSessionModel.this::fireChanged);
            }
        });
        session.start();
        onEdt(() -> {
            booting = false;
            running = true;
            statusText = "running";
            taskPoll = new Timer(TASK_POLL_MILLIS, e -> refreshTasks());
            taskPoll.setRepeats(true);
            taskPoll.start();
            refreshTasks();
            fireChanged();
        });
        } catch (Throwable t) {
            // a project that cannot boot must leave a usable window and a readable reason
            String message = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
            onEdt(() -> {
                booting = false;
                running = false;
                statusText = "failed to start";
                note("session failed to start: " + message);
                fireChanged();
            });
        }
    }

    /** Append a host-originated line (not engine output) as its own closed block. */
    private void note(String text) {
        String id = "n" + System.nanoTime();
        transcript.apply(List.of(new TranscriptEvent.Opened(id, NaruLogMode.PROGRESS)));
        transcript.apply(List.of(new TranscriptEvent.Chunk(id, NaruLogMode.PROGRESS, text, true)));
    }

    /** Close the host and stop the session. A parked task is released, not left blocked. */
    public void shutdown() {
        EDT.check("NaruSessionModel.shutdown");
        if (taskPoll != null) {
            taskPoll.stop();
        }
        host.close();
        closed = true;
        pending = null;
        if (session != null) {
            try {
                session.stop();
            } catch (RuntimeException ignored) {
                // already stopped; closing the window must not throw
            }
        }
        statusText = "closed";
        // the poll timer has stopped, so this is the last chance to show real state: without it
        // the monitor keeps displaying the task that was parked *before* the close and the
        // parking guarantee looks violated when it was actually honoured
        refreshTasks();
        fireChanged();
    }

    public boolean isClosed() {
        return closed;
    }

    // ── host input (EDT, from the flush timer) ────────────────────────────────

    @Override
    public void onBatch(List<TranscriptEvent> batch, InteractionHost from) {
        transcript.apply(batch);
        for (TranscriptEvent e : batch) {
            if (e instanceof TranscriptEvent.InputRequested ir) {
                pending = ir.request();
                inputRequestsSeen++;
                statusText = "waiting for input";
                fireChanged();
                if (inputListener != null) {
                    inputListener.onInputRequested(ir.request());
                }
            } else if (e instanceof TranscriptEvent.HostClosed) {
                pending = null;
                running = false;
                statusText = "closed";
                fireChanged();
            } else if (e instanceof TranscriptEvent.Lagged l) {
                statusText = "output lagged (" + l.droppedEvents() + " chunks dropped)";
                fireChanged();
            }
        }
    }

    /** Separate hook because the dialog, not the transcript, owns the answer. */
    public interface InputListener {
        void onInputRequested(NaruInputRequest request);
    }

    private InputListener inputListener;

    public void setInputListener(InputListener l) {
        this.inputListener = l;
    }

    // ── submitting ────────────────────────────────────────────────────────────

    /**
     * True when a submitted line would actually reach the agent. This is the E8 rule from
     * §3.4: the engine drops a line on the floor when nothing is blocked on input
     * ({@code NaruSessionImpl.deliverInput} returns without effect unless the foreground task is
     * {@code BLOCKED_ON_INPUT}), so the field is disabled with a reason instead of lying.
     */
    public boolean canAcceptInput() {
        return pending != null;
    }

    public String inputDisabledReason() {
        if (closed) {
            return "session closed";
        }
        if (!running) {
            return "session is not running";
        }
        if (pending != null) {
            return null;
        }
        return "the agent is working — it accepts input only when it asks";
    }

    /**
     * Answer the outstanding request. Never calls {@code session.deliverInput} directly: the
     * request object is what the engine handed us, is guarded so the first answer wins, and
     * survives a task that has already moved on.
     */
    public void submit(String line) {
        EDT.check("NaruSessionModel.submit");
        NaruInputRequest r = pending;
        if (r == null || line == null) {
            return;
        }
        pending = null;
        echoAndDeliver(line, r);
    }

    /**
     * Show the answer in the transcript and hand it to the request. Public because the prompt
     * buttons answer a request the field did not come from, and the pending slot may already have
     * moved on by then.
     */
    public void echoAndDeliver(String line, NaruInputRequest request) {
        EDT.check("NaruSessionModel.echoAndDeliver");
        if (line == null || request == null) {
            return;
        }
        if (request == pending) {
            pending = null;
        }
        if (!line.isBlank()) {
            inputHistory.add(line);
            historyCursor = inputHistory.size();
        }
        // the readline/ask_user path swallows the answer whole, so echoing it here is the only
        // way the user sees their own line in the transcript
        echoUser(line);
        statusText = "running";
        fireChanged();
        request.deliver(line);
    }

    /** Show the user's submission in the transcript before it disappears into the engine. */
    private void echoUser(String line) {
        String id = "u" + System.nanoTime();
        transcript.apply(List.of(new TranscriptEvent.Opened(id, NaruLogMode.SCRIPT)));
        transcript.apply(List.of(new TranscriptEvent.Chunk(id, NaruLogMode.SCRIPT,
                "you> " + line + "\n", true)));
    }

    public void cancelPendingInput(String reason) {
        NaruInputRequest r = pending;
        if (r != null) {
            pending = null;
            r.cancel(reason == null ? "cancelled from the desktop" : reason);
            fireChanged();
        }
    }

    public NaruInputRequest pendingRequest() {
        return pending;
    }

    /** Monotonic count of requests seen; compare with a previously read value to spot a new park. */
    public long inputRequestsSeen() {
        return inputRequestsSeen;
    }

    /** True while the workspace/session is still booting on its worker thread. */
    public boolean isBooting() {
        return booting;
    }

    /** Completion for the input field, straight from E2. Safe on the EDT: it never blocks. */
    public NaruLineCompletion.Result complete(String rawLine, int caret) {
        if (session == null) {
            return NaruLineCompletion.Result.none("", 0, 0);
        }
        try {
            return NaruLineCompletion.complete(session, rawLine, caret);
        } catch (RuntimeException e) {
            return NaruLineCompletion.Result.none("", 0, 0);
        }
    }

    // ── state ─────────────────────────────────────────────────────────────────

    private void refreshTasks() {
        if (session == null) {
            return;
        }
        List<NaruTask> live;
        try {
            live = session.tasks();
        } catch (RuntimeException e) {
            // the session stopped between the poll and the read: a normal state, not an error
            running = false;
            tasks = List.of();
            fireChanged();
            return;
        }
        List<TaskRow> out = new ArrayList<>();
        long fg = -1;
        try {
            fg = session.foregroundTaskId();
        } catch (RuntimeException ignored) {
            // no foreground task
        }
        for (NaruTask t : live) {
            NaruTaskStatus st;
            try {
                st = t.status();
            } catch (RuntimeException e) {
                continue;
            }
            String err = null;
            try {
                err = t.error().orNull();
            } catch (RuntimeException ignored) {
                // task deregistered while reading
            }
            out.add(new TaskRow(t.id(), t.name(), st, t.id() == fg, -1, err));
        }
        boolean changed = out.size() != tasks.size();
        if (!changed) {
            for (int i = 0; i < out.size(); i++) {
                if (!out.get(i).equals(tasks.get(i))) {
                    changed = true;
                    break;
                }
            }
        }
        if (changed) {
            tasks = List.copyOf(out);
            fireChanged();
        }
    }

    private void onEdt(Runnable body) {
        if (SwingUtilities.isEventDispatchThread()) {
            body.run();
        } else {
            SwingUtilities.invokeLater(body);
        }
    }

    public TranscriptModel transcript() {
        return transcript;
    }

    public List<TaskRow> tasks() {
        return tasks;
    }

    public String statusText() {
        return statusText;
    }

    public String model() {
        return model;
    }

    public String projectPath() {
        return projectDir == null ? "—" : projectDir.toString();
    }

    public String sessionName() {
        try {
            return session == null ? "—" : session.name();
        } catch (RuntimeException e) {
            return "—";
        }
    }

    public String saveState() {
        if (session == null) {
            return "—";
        }
        try {
            return session.modificationInstant() == null ? "—"
                    : String.valueOf(session.modificationInstant()).substring(0, 19);
        } catch (RuntimeException e) {
            return "—";
        }
    }

    public String tokensLabel() {
        if (modelCalls == 0) {
            return "no model calls yet";
        }
        return modelCalls + " call" + (modelCalls == 1 ? "" : "s")
                + " · " + promptTokens + " in · " + completionTokens + " out"
                + (cacheReadTokens > 0 ? " · " + cacheReadTokens + " cache read" : "");
    }

    public NaruInputMode inputMode() {
        return inputMode;
    }

    public void setInputMode(NaruInputMode mode) {
        if (inputMode != mode) {
            inputMode = mode;
            fireChanged();
        }
    }

    public List<String> inputHistory() {
        return List.copyOf(inputHistory);
    }

    /** Previous/next history entry for the input area; -1 means "the live line". */
    public String historyPrev() {
        if (inputHistory.isEmpty()) {
            return null;
        }
        historyCursor = Math.max(0, historyCursor - 1);
        return inputHistory.get(historyCursor);
    }

    public String historyNext() {
        if (inputHistory.isEmpty()) {
            return null;
        }
        historyCursor = Math.min(inputHistory.size(), historyCursor + 1);
        return historyCursor >= inputHistory.size() ? "" : inputHistory.get(historyCursor);
    }

    public NaruSession session() {
        return session;
    }

    public boolean isRunning() {
        return running;
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void fireChanged() {
        EDT.check("NaruSessionModel.fireChanged");
        for (Listener l : listeners) {
            l.sessionModelChanged();
        }
    }

    /** Force a task/state refresh, e.g. when a tab becomes visible. */
    public void refreshNow() {
        refreshTasks();
        fireChanged();
    }
}
