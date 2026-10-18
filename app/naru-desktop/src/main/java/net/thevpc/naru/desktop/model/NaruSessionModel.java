package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.access.NaruPermissionStore;
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
import net.thevpc.naru.api.scheduler.NaruSchedulerMode;
import net.thevpc.naru.api.scheduler.NaruTaskMode;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskStackFrame;
import net.thevpc.naru.api.task.NaruTaskStackItem;
import net.thevpc.naru.desktop.host.InteractionHost;
import net.thevpc.naru.desktop.host.TranscriptEvent;
import net.thevpc.naru.desktop.util.EDT;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.time.NDuration;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.time.Instant;
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
    private final ToolCallModel tools = new ToolCallModel();
    /** The permissions editor's view-model, bound to the session policy once the session exists. */
    private final PermissionsModel permissions = new PermissionsModel();
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

    /**
     * A row of the task monitor: what the widget needs, read off a live task.
     *
     * <p>{@code depth} is not engine state — {@link TaskTree#arrange} stamps it when the flat poll
     * becomes a tree, so a row built straight from a task sits at 0 and no caller may read it as
     * meaningful until it has been arranged. {@code parentId} is engine state and is {@code -1} for
     * a root; a child whose parent has already deregistered keeps that real id, which is why the
     * tree treats an unknown parent as a root rather than as a missing row.
     */
    public record TaskRow(long id, String name, NaruTaskStatus status, boolean foreground,
                          boolean conversation,
                          long parentId, boolean held, int pc, java.time.Instant created,
                          String error, int depth) {

        public TaskRow(long id, String name, NaruTaskStatus status, boolean foreground,
                       long parentId, boolean held, int pc, java.time.Instant created,
                       String error, int depth) {
            this(id, name, status, foreground, false, parentId, held, pc, created, error, depth);
        }

        TaskRow withDepth(int next) {
            return new TaskRow(id, name, status, foreground, conversation, parentId, held, pc, created, error, next);
        }
    }

    /**
     * What the monitor can ask the scheduler to do to one task. Each of these is a documented
     * {@link NaruTask} call, so the enum exists to keep the pane off the engine surface and to make
     * the whole action set testable in one place.
     */
    public enum TaskAction {
        INTERRUPT("Interrupt"),
        KILL("Kill"),
        HOLD("Hold"),
        RESUME("Resume"),
        FOREGROUND("Bring to front"),
        BACKGROUND("Send to back");

        private final String label;

        TaskAction(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
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
                            tools.clear();
                            // a reload rewrote the rule set wholesale; the old ids and their counts
                            // describe rules that no longer exist, so both go
                            permissions.resetCounts();
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
            // the policy only exists on a built session, which is why the permissions editor binds
            // here rather than in a constructor; a session that refuses the read leaves the tab
            // attached to nothing, which the pane reports as "not attached".
            //
            // The committed file comes from the naru-permissions extension, looked up by interface
            // through the registry — the desktop must not compile against an extension jar, and a
            // profile without that extension leaves the store null so the pane says the file is
            // unavailable instead of parsing a second copy of the format.
            try {
                permissions.attach(session.accessPolicy(),
                        NaruPermissionStore.of(session));
            } catch (RuntimeException e) {
                permissions.attach(null);
            }
            taskPoll = new Timer(TASK_POLL_MILLIS, e -> {
                long t0 = System.nanoTime();
                refreshTasks();
                recordPollCost(System.nanoTime() - t0);
            });
            taskPoll.setRepeats(true);
            taskPoll.start();
            startModelLabelWorker();
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
        // the label worker may be sitting in a provider HTTP call for a task whose window is
        // closing; interrupting it is enough — the cache dies with the session either way
        if (modelLabelWorker != null) {
            modelLabelWorker.shutdownNow();
            modelLabelWorker = null;
        }
        taskModelLabels.clear();
        // the policy is still reachable from a stopped session; leaving the bridge attached would
        // keep this model alive and paint onto a window that is closing
        permissions.detach();
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
        // the same events drive two views: the transcript reads them in order as output, the tools
        // list keeps them as a table that does not get trimmed away with the chat
        tools.apply(batch);
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

    /**
     * What the last and the worst 2 Hz task poll cost, in milliseconds, measured on the EDT inside
     * the poll itself — the poll reads the engine, so a tick that has to wait for the scheduler or
     * renders a changed tree shows up here rather than only in a frozen window.
     */
    private volatile long lastPollMillis;
    private volatile long maxPollMillis;

    /**
     * Each task's model label, resolved off the EDT and remembered once per task id.
     * <p>
     * {@code NaruTask#model()} is a lazy catalogue lookup: a task whose model came from a hint asks
     * the registry to enumerate every provider, and a provider like OpenRouter answers that with an
     * HTTP round-trip. The §6 gate run caught the detail pane doing exactly that on the EDT, which
     * is how a monitor whose own poll costs 2 ms froze the window for 8.7 s at a time. The engine's
     * answer is untouched; only the thread it happens on has moved, and a task asks once.
     */
    private final java.util.Map<Long, String> taskModelLabels =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Marks an id whose label is on its way, so a repaint storm cannot launch one lookup per tick. */
    private static final String MODEL_LABEL_PENDING = "resolving…";

    /** Empty means the engine says this task has no model: the detail line then reads as before. */
    private static final String MODEL_LABEL_NONE = "";

    private volatile java.util.concurrent.ExecutorService modelLabelWorker;

    public long lastTaskPollMillis() {
        return lastPollMillis;
    }

    public long maxTaskPollMillis() {
        return maxPollMillis;
    }

    /**
     * The detail pane's model line for one task: the label if it is known, the {@link
     * #MODEL_LABEL_PENDING} marker while the worker is still asking the engine, and null when the
     * task has no model. First call for an id queues the lookup and returns the marker; the pane
     * re-reads it on the next tick.
     */
    private String taskModelLabel(long id) {
        String known = taskModelLabels.get(id);
        if (known == null) {
            if (taskModelLabels.putIfAbsent(id, MODEL_LABEL_PENDING) == null) {
                resolveModelLabel(id);
            }
            return MODEL_LABEL_PENDING;
        }
        return MODEL_LABEL_PENDING.equals(known) ? MODEL_LABEL_PENDING
                : (MODEL_LABEL_NONE.equals(known) ? null : known);
    }

    /** True while a task's model label is still being worked out; the pane asks again next tick. */
    public boolean isTaskModelLabelPending(long id) {
        return MODEL_LABEL_PENDING.equals(taskModelLabels.get(id));
    }

    private void resolveModelLabel(long id) {
        java.util.concurrent.ExecutorService worker = modelLabelWorker;
        NaruSession s = session;
        if (worker == null || worker.isShutdown() || s == null) {
            return;
        }
        worker.execute(() -> {
            String label = MODEL_LABEL_NONE;
            try {
                NaruTask t = s.findTask(id).orNull();
                // the call that must not happen on the EDT, and the only place it happens
                if (t != null && t.model() != null) {
                    label = String.valueOf(t.model().model());
                }
            } catch (RuntimeException | LinkageError e) {
                // a stopped session or a provider that will not answer leaves the task with no
                // label rather than a guess; the detail line then says none is selected
                label = MODEL_LABEL_NONE;
            }
            taskModelLabels.put(id, label);
        });
    }

    private void startModelLabelWorker() {
        if (modelLabelWorker == null || modelLabelWorker.isShutdown()) {
            modelLabelWorker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "naru-task-model-label");
                t.setDaemon(true);
                return t;
            });
        }
    }

    private void recordPollCost(long nanos) {
        long ms = nanos / 1_000_000;
        lastPollMillis = ms;
        maxPollMillis = Math.max(maxPollMillis, ms);
    }

    private void refreshTasks() {
        // decisions are counted on the worker that asked the policy, never painted from there; the
        // 2 Hz poll is what carries those numbers to the screen, so a busy tool run costs one
        // repaint per tick rather than one per check
        if (permissions.countsDirty()) {
            permissions.refresh();
        }
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
            // every one of these is a field read on an object the poll already holds; reading the
            // stack would not be, so frames stay in taskDetail() and are fetched on selection
            long parentId = -1;
            boolean held = false;
            int pc = -1;
            java.time.Instant created = null;
            try {
                parentId = t.parentId();
                held = t.isHeld();
                pc = t.pc();
                created = t.creationTime();
            } catch (RuntimeException ignored) {
                // same window: a task deregistered mid-read keeps the fields already taken
            }
            out.add(new TaskRow(t.id(), t.name(), st, t.id() == fg, safeConversation(t), parentId, held, pc, created,
                    err, 0));
        }
        // engine order is a registry's iteration order; the monitor shows a tree, so the
        // arrangement happens here rather than in a widget that would redo it on every repaint
        List<TaskRow> arranged = TaskTree.arrange(out);
        boolean changed = arranged.size() != tasks.size();
        if (!changed) {
            for (int i = 0; i < arranged.size(); i++) {
                if (!arranged.get(i).equals(tasks.get(i))) {
                    changed = true;
                    break;
                }
            }
        }
        if (changed) {
            tasks = List.copyOf(arranged);
            fireChanged();
        }
    }

    private static boolean safeConversation(NaruTask task) {
        try { return task.isConversation(); } catch (RuntimeException e) { return false; }
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

    /** The tool-call list behind the Tools tab, fed by the same batches as the transcript. */
    public ToolCallModel tools() {
        return tools;
    }

    /**
     * The permissions editor's model — a projection of {@code session.accessPolicy()} plus the
     * traffic through it, bound when the session boots and detached when the window closes.
     */
    public PermissionsModel permissions() {
        return permissions;
    }

    /** The project directory, for widgets that read or write project files. */
    public NPath projectDir() {
        return projectDir;
    }

    public List<TaskRow> tasks() {
        return tasks;
    }

    /**
     * One task, read for the monitor's detail pane. This is the GUI form of the terminal's
     * {@code /task frames} and {@code /task stacktrace}: those walk the engine's own objects and
     * print, so the desktop reads them once when the selection changes and hands the values to
     * {@code TaskText}. Nothing here is cached — a task that has deregistered between two clicks
     * has genuinely gone, and an old detail would be a lie — with one exception, the model label,
     * explained on {@link #taskModelLabels}.
     *
     * @return the detail, or null when the session has no such task (stopped, or finished and
     * deregistered since the last poll)
     */
    public TaskDetail taskDetail(long id) {
        NaruSession s = session;
        if (s == null) {
            return null;
        }
        NaruTask t;
        try {
            t = s.findTask(id).orNull();
        } catch (RuntimeException e) {
            // ensureNotStopped(): a session stopped under the selection is a normal race
            return null;
        }
        if (t == null) {
            return null;
        }
        List<NaruTaskStackFrame> frames = List.of();
        List<NaruTaskStackItem> trace = List.of();
        try {
            // frame text is built off the routine table, so a task that ended between the read and
            // the render must not take the pane down with it
            frames = t.stackFrames();
            trace = t.stacktrace();
        } catch (RuntimeException ignored) {
            // whatever came through stays; an empty frame list is an honest "nothing to show"
        }
        String modelName;
        try {
            modelName = taskModelLabel(id);
        } catch (RuntimeException e) {
            modelName = null;
        }
        NDuration ran;
        try {
            ran = t.duration().orNull();
        } catch (RuntimeException e) {
            ran = null;
        }
        String err;
        try {
            err = t.error().orNull();
        } catch (RuntimeException e) {
            err = null;
        }
        return new TaskDetail(
                safeLong(t::id, -1),
                safe(t::name),
                safeGet(t::status),
                safeBoolean(() -> t.isFg(), false),
                safeLong(t::parentId, -1),
                safeBoolean(t::isHeld, false),
                safeInt(t::pc, -1),
                safeBoolean(t::isKillRequested, false),
                safeBoolean(t::isInterruptRequested, false),
                safeInstant(t::creationTime),
                ran,
                safeGet(t::taskMode),
                safeGet(t::schedulerMode),
                modelName,
                err,
                frames,
                trace);
    }

    /**
     * Apply one monitor action to one task, then refresh so the result shows now rather than on
     * the next poll tick.
     * <p>
     * These are the terminal's own calls — {@code /task kill}, {@code /task hold}, {@code /task
     * unhold} — through the same interface, so a button cannot do something the REPL cannot. All
     * of them are cooperative flags on the engine side: none blocks, so this is safe to run inline
     * on the EDT, and doing it on a worker would only reorder the repaint against the poll.
     *
     * @return null on success, otherwise the reason to show the user
     */
    public String taskAction(long id, TaskAction action) {
        EDT.check("NaruSessionModel.taskAction");
        NaruSession s = session;
        if (s == null) {
            return "no session";
        }
        NaruTask t;
        try {
            t = s.findTask(id).orNull();
        } catch (RuntimeException e) {
            return "the session stopped: " + e.getMessage();
        }
        if (t == null) {
            return "no task #" + id + " — it has already finished and deregistered";
        }
        String failure;
        try {
            switch (action) {
                case INTERRUPT -> t.interrupt();
                // a reason, not a bare kill(): the task keeps it and reports it through error(), so
                // the row says the user stopped it instead of looking like it died on its own
                case KILL -> t.cancel("stopped from the desktop task monitor");
                case HOLD -> t.hold();
                case RESUME -> t.unhold();
                case FOREGROUND -> t.fg();
                case BACKGROUND -> t.bg();
                default -> {
                    return "unknown action " + action;
                }
            }
            failure = null;
        } catch (RuntimeException e) {
            failure = action.label() + " failed: " + e.getMessage();
        }
        refreshTasks();
        fireChanged();
        return failure;
    }

    // The detail reads one task the scheduler may be finishing at this very moment, and a view
    // model that threw on a race would be worse than one that shows a dash.
    private static String safe(java.util.function.Supplier<String> get) {
        try {
            return get.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long safeLong(java.util.function.Supplier<Long> get, long fallback) {
        try {
            return get.get();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int safeInt(java.util.function.Supplier<Integer> get, int fallback) {
        try {
            return get.get();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean safeBoolean(java.util.function.Supplier<Boolean> get, boolean fallback) {
        try {
            return get.get();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static Instant safeInstant(java.util.function.Supplier<Instant> get) {
        try {
            return get.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static <X> X safeGet(java.util.function.Supplier<X> get) {
        try {
            return get.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** What the monitor's detail pane shows for one task; {@code status} is null only if it vanished. */
    public record TaskDetail(long id, String name, NaruTaskStatus status, boolean foreground,
                             long parentId, boolean held, int pc, boolean killRequested,
                             boolean interruptRequested, Instant created, NDuration ran,
                             NaruTaskMode taskMode, NaruSchedulerMode schedulerMode, String model,
                             String error, List<NaruTaskStackFrame> frames,
                             List<NaruTaskStackItem> stacktrace) {
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
