package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.access.NaruPermissionStore;
import net.thevpc.naru.api.agent.NaruInputMode;
import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.agent.NaruSessionUsageListener;
import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruProviderRateLimitInfo;
import net.thevpc.naru.api.registry.NaruLineCompletion;
import net.thevpc.naru.api.scheduler.NaruSchedulerMode;
import net.thevpc.naru.api.scheduler.NaruTaskMode;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.api.model.NaruMessage;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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

    public record MessageSearchHit(long taskId, net.thevpc.naru.api.store.NaruStoredMessage stored) { }
    public record MessageSearchResult(List<MessageSearchHit> hits, java.util.Map<Long, String> nextCursors,
                                      boolean hasMore) { }
    private record TranscriptRestore(List<TranscriptEvent> events, java.util.Map<Long, String> cursors) { }

    public interface Listener {
        /** Coarse "something visible changed"; widgets re-read the getters they care about. */
        void sessionModelChanged();
    }

    /** How long the transcript's model call has been running, for the status bar. */
    private static final int TASK_POLL_MILLIS = 500;

    private final java.util.function.Supplier<NaruWorkspace> workspaceFactory;
    private final NPath projectDir;
    private final String restoreId;
    private final net.thevpc.naru.api.store.NaruSessionScope restoreScope;
    private final InteractionHost host;
    private final TranscriptModel transcript = new TranscriptModel();
    private final ToolCallModel tools = new ToolCallModel();
    /** The permissions editor's view-model, bound to the session policy once the session exists. */
    private final PermissionsModel permissions = new PermissionsModel();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<String> inputHistory = new ArrayList<>();
    private int historyCursor = -1;
    /**
     * User lines that are already on screen but may still be echoed back by the durable store.
     * The live echo is what makes a submission visible immediately; the store callback is what
     * makes it survive a restart. Without this bridge the same line appears twice, once when it
     * is sent and once when the checkpoint completes.
     */
    private final Map<Long, Deque<UserEcho>> userEchoes = new HashMap<>();
    private static final int MAX_PENDING_ECHOES = 200;

    /** One user line shown locally, waiting to be matched by its stored copy (or delivered). */
    private static final class UserEcho {
        final String text;
        final String blockId;
        boolean awaitingDelivery;

        UserEcho(String text, String blockId, boolean awaitingDelivery) {
            this.text = text;
            this.blockId = blockId;
            this.awaitingDelivery = awaitingDelivery;
        }
    }

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
    private long latestPromptTokens = -1;
    private long latestContextWindow = -1;
    private net.thevpc.naru.api.model.NaruModelKey latestUsageModel;
    private final java.util.Map<String,net.thevpc.naru.api.model.NaruProviderRateLimitInfo> providerRateLimits = new java.util.LinkedHashMap<>();
    private String model = "—";
    private String saveState = "—";
    private NaruInputMode inputMode = NaruInputMode.LINE;
    private boolean running;
    private boolean closed;
    private final java.util.Map<Long, String> transcriptHistoryCursors = new java.util.LinkedHashMap<>();

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
        this(workspaceFactory, projectDir, host, null, net.thevpc.naru.api.store.NaruSessionScope.PRIVATE);
    }

    public NaruSessionModel(java.util.function.Supplier<NaruWorkspace> workspaceFactory,
                            NPath projectDir, InteractionHost host, String restoreId) {
        this(workspaceFactory, projectDir, host, restoreId, net.thevpc.naru.api.store.NaruSessionScope.PRIVATE);
    }

    public NaruSessionModel(java.util.function.Supplier<NaruWorkspace> workspaceFactory,
                            NPath projectDir, InteractionHost host, String restoreId,
                            net.thevpc.naru.api.store.NaruSessionScope restoreScope) {
        EDT.check("NaruSessionModel construct");
        this.workspaceFactory = workspaceFactory;
        this.projectDir = projectDir;
        this.host = host;
        this.restoreId = restoreId;
        this.restoreScope = restoreScope;
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
            var builder = workspace.newSession()
                .directory(projectDir)
                .id(restoreId == null ? java.util.UUID.randomUUID().toString() : restoreId)
                .scope(restoreScope)
                .interaction(host)
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
                            transcriptHistoryCursors.clear();
                            tools.clear();
                            userEchoes.clear();
                            // a reload rewrote the rule set wholesale; the old ids and their counts
                            // describe rules that no longer exist, so both go
                            permissions.resetCounts();
                            statusText = "reloaded";
                            refreshTasks();
                            fireChanged();
                            java.util.concurrent.CompletableFuture.supplyAsync(
                                    () -> loadTranscriptPage(s, java.util.Map.of(), 80))
                                    .whenComplete((page, failure) -> {
                                        if (failure == null && page != null) onEdt(() -> restoreTranscript(page));
                                    });
                        });
                    }

                    @Override
                    public void onTaskStatusChanged(NaruTask t, NaruTaskStatus a, NaruTaskStatus b) {
                        // any worker thread; the poll timer is the backstop
                        onEdt(NaruSessionModel.this::refreshTasks);
                    }

                    @Override
                    public void onMessageComplete(long taskId, String cursor) {
                        NaruSession s = session;
                        if (s == null) return;
                        NaruTask t = s.findTask(taskId).orNull();
                        if (t != null) {
                            try {
                                var storedList = t.messages().get(List.of(cursor));
                                if (storedList != null && !storedList.isEmpty()) {
                                    var item = storedList.get(0);
                                    if (item != null && item.message() != null) {
                                        NaruMessage stored = item.message();
                                        onEdt(() -> {
                                            // The store is allowed to echo a message that the
                                            // transcript already shows: user lines are echoed
                                            // immediately, assistant text arrives as a stream, and
                                            // tool results arrive as typed cards. Only add a card
                                            // when nothing on screen represents this message yet.
                                            if (stored.getRole() == NaruRole.user
                                                    && consumeUserEcho(taskId, stored.getContent())) {
                                                return;
                                            }
                                            if (stored.getRole() == NaruRole.system) return;
                                            if (stored.getRole() == NaruRole.tool) return;
                                            if (stored.getRole() == NaruRole.assistant
                                                    && isShownAsStreamedAnswer(taskId, stored)) {
                                                return;
                                            }
                                            if (stored.getRole() == NaruRole.assistant
                                                    && (stored.getContent() == null || stored.getContent().isBlank())
                                                    && stored.getParts().isEmpty()) {
                                                return;
                                            }
                                            String id = "stored-" + taskId + "-" + item.id();
                                            transcript.apply(List.of(new TranscriptEvent.Message(
                                                    id, taskId, item.creationSequence(), stored)));
                                            fireChanged();
                                        });
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                });
            // A user-created session gets the long-lived interactive task that its first line
            // will answer. A restored session already has its saved task tree: build() hydrates
            // it from the store because the id was given, and seeding another configured task
            // here would put a fresh interactive task beside the restored one, making every
            // reopen look like a newly-created session.
            if (restoreId == null) {
                builder.statements(new String[0]).interactive();
            }
            session = builder.build();
            if (restoreId == null) {
                // Create the durable session before the first turn. Task checkpoints are cheap
                // but they deliberately do nothing while the store has no session record, which
                // is why a desktop session used to reach the model and then vanish on restart.
                // Saving here makes every later message checkpoint land in the store.
                session.save();
            } else {
                if (session.uuid() == null || !restoreId.equals(session.uuid())) {
                    throw new IllegalStateException("Saved session " + restoreId
                            + " is no longer present in this project's session store."
                            + " The desktop will not replace it with a new session.");
                }
                NaruSession restored = session;
                TranscriptRestore page = loadTranscriptPage(restored, java.util.Map.of(), 80);
                SwingUtilities.invokeAndWait(() -> restoreTranscript(page));
            }
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
                    latestPromptTokens = p;
                    latestUsageModel = m;
                    try { Long w = task.model() == null ? null : task.model().contextLength(); latestContextWindow = w == null ? -1 : w; }
                    catch (RuntimeException ignored) { latestContextWindow = -1; }
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
                onEdt(() -> { providerRateLimits.put(info.providerName(), info); fireChanged(); });
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

    /** Load only the newest cursor page per task; older pages are requested by the transcript. */
    private TranscriptRestore loadTranscriptPage(NaruSession restored, java.util.Map<Long, String> cursors,
                                                  int limit) {
        List<TranscriptEvent> events = new ArrayList<>();
        java.util.Map<Long, String> nextCursors = new java.util.LinkedHashMap<>();
        java.util.Map<Long, NaruTask> taskMap = new java.util.TreeMap<>();
        for (NaruTask task : restored.tasks()) taskMap.put(task.id(), task);
        for (NaruTask task : restored.conversationTasks()) taskMap.put(task.id(), task);
        List<NaruTask> ordered = new ArrayList<>(taskMap.values());
        ordered.sort(java.util.Comparator.comparingLong(NaruTask::id));
        for (NaruTask task : ordered) {
            String cursor = cursors.get(task.id());
            if (!cursors.isEmpty() && cursor == null) continue;
            try {
                net.thevpc.naru.api.store.NaruMessagePage page = task.messages().query(
                        new net.thevpc.naru.api.store.NaruMessageQuery(cursor,
                                net.thevpc.naru.api.store.NaruMessageQueryDirection.BACKWARD,
                                limit, java.util.Set.of(), null, null));
                List<net.thevpc.naru.api.store.NaruStoredMessage> items = new ArrayList<>(page.items());
                // Some older stores retain task history in the task skeleton and do not expose
                // message pages. An empty page is therefore not proof that the conversation is
                // empty; forcing history also invokes the lazy durable-history loader installed
                // during restore.
                if (items.isEmpty() && cursor == null) {
                    appendRestoredHistory(events, task, task.history(), limit);
                    continue;
                }
                java.util.Collections.reverse(items);
                for (var item : items) {
                    NaruMessage message = item.message();
                    if (message == null || message.getRole() == null || message.getRole() == NaruRole.system) continue;
                    String id = "restored-" + task.id() + "-" + item.id();
                    events.add(new TranscriptEvent.Message(id, task.id(), item.creationSequence(), message));
                }
                if (page.hasMore()) nextCursors.put(task.id(), page.nextCursor());
            } catch (UnsupportedOperationException ignored) {
                appendRestoredHistory(events, task, task.history(), limit);
            }
        }
        return new TranscriptRestore(events, nextCursors);
    }

    private static void appendRestoredHistory(List<TranscriptEvent> events, NaruTask task,
                                              List<NaruMessage> history, int limit) {
        int start = Math.max(0, history.size() - limit);
        for (int i = start; i < history.size(); i++) {
            NaruMessage message = history.get(i);
            if (message == null || message.getRole() == null || message.getRole() == NaruRole.system) continue;
            events.add(new TranscriptEvent.Message("restored-" + task.id() + "-" + i,
                    task.id(), i, message));
        }
    }

    private void restoreTranscript(TranscriptRestore page) {
        EDT.check("NaruSessionModel.restoreTranscript");
        transcriptHistoryCursors.clear();
        transcriptHistoryCursors.putAll(page.cursors());
        if (!page.events().isEmpty()) transcript.apply(page.events());
    }

    /** Load another durable history page for every task that still has older messages. */
    public void loadOlderTranscript(java.util.function.Consumer<Boolean> callback) {
        NaruSession current = session;
        java.util.Map<Long, String> cursors = java.util.Map.copyOf(transcriptHistoryCursors);
        if (current == null || cursors.isEmpty()) {
            onEdt(() -> callback.accept(false));
            return;
        }
        java.util.concurrent.CompletableFuture.supplyAsync(() -> loadTranscriptPage(current, cursors, 80))
                .whenComplete((page, failure) -> onEdt(() -> {
                    if (failure != null || page == null) {
                        callback.accept(false);
                        return;
                    }
                    transcriptHistoryCursors.clear();
                    transcriptHistoryCursors.putAll(page.cursors());
                    if (!page.events().isEmpty()) transcript.apply(page.events());
                    callback.accept(!page.events().isEmpty());
                }));
    }

    public boolean hasOlderTranscript() { return !transcriptHistoryCursors.isEmpty(); }

    /** Append a host-originated line (not engine output) as its own closed block. */
    private void note(String text) {
        String id = "n" + System.nanoTime();
        transcript.apply(List.of(new TranscriptEvent.Opened(id, NaruLogMode.PROGRESS),
                new TranscriptEvent.Chunk(id, NaruLogMode.PROGRESS, text, true),
                new TranscriptEvent.Closed(id)));
    }

    /** Add a small host status entry to the session transcript. */
    public void notifyUser(String text) { note(text); }

    /** Persist a quiescent session as hibernated; stop only when it cannot safely hibernate. */
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
        userEchoes.clear();
        // the policy is still reachable from a stopped session; leaving the bridge attached would
        // keep this model alive and paint onto a window that is closing
        permissions.detach();
        closed = true;
        pending = null;
        if (session != null) {
            try {
                if (session.isHibernateEligible()) {
                    session.hibernate();
                } else {
                    session.stop();
                }
            } catch (RuntimeException ignored) {
                // already stopped; closing the window must not throw
            }
        } else {
            host.close();
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
            if (e instanceof TranscriptEvent.Chunk c && c.finalFragment() && isUserFacingError(c)) {
                // Actionable failures are conversation content even when their source mode is
                // hidden from the default activity view (for example, missing-model errors are
                // emitted as AGENT_RESPONSE, while request failures can be PROGRESS).
                transcript.apply(List.of(new TranscriptEvent.Message("user-error-" + c.blockId(),
                        c.taskId(), Long.MAX_VALUE, NaruMessage.system(c.text().trim()))));
            }
            if (e instanceof TranscriptEvent.InputRequested ir) {
                pending = ir.request();
                inputRequestsSeen++;
                statusText = "waiting for input";
                String question = ir.request().prompt() == null ? "Please provide input."
                        : ir.request().prompt().toString();
                // The ordinary readline prompt belongs to the terminal UI. Rendering it as an
                // assistant message leaks the console's `naru›` prefix into the desktop chat.
                // The console uses the localized brand as its readline prompt. It is not
                // conversation content and must never be rendered as an assistant message.
                if (!isConsolePrompt(question)) {
                    NaruMessage promptMessage = NaruMessage.assistant(question);
                    transcript.apply(List.of(new TranscriptEvent.Message(
                            "input-request-" + ir.request().requestId() + "-" + System.nanoTime(),
                            ir.request().task().id(), promptMessage)));
                }
                fireChanged();
                String queued = ir.request().task().pollQueuedMessage();
                if (queued != null) {
                    note("sending queued message for task #" + ir.request().task().id());
                    echoAndDeliver(queued, ir.request());
                } else if (inputListener != null) {
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

    private static boolean isConsolePrompt(String text) {
        return text != null && text.trim().matches("(?i)(?:naru|なる)\\s*[›>]");
    }

    private static boolean isUserFacingError(TranscriptEvent.Chunk c) {
        if (c.text() == null || c.text().isBlank()) return false;
        String text = c.text().stripLeading();
        String upper = text.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("ERROR:")
                || upper.startsWith("ERROR CALLING MODEL:")
                || upper.startsWith("NO MODEL IS SELECTED.")
                || upper.startsWith("SELECTED MODEL ") && upper.contains(" WAS NOT FOUND");
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
        return pending != null || canStartNewConversation();
    }

    /**
     * A restored session can legitimately have no live task: the previous process may have been
     * killed before its interactive task checkpointed, or the task may have finished. The store
     * still has the conversation, but the engine has nothing parked on input, so the composer
     * would stay disabled forever. An idle session starts a new interactive task on the next
     * submission; a session with a task that is actually working keeps the old rule.
     */
    private boolean canStartNewConversation() {
        if (closed || !running) {
            return false;
        }
        NaruSession current = session;
        if (current == null) {
            return false;
        }
        try {
            return current.tasks().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Creates the long-lived interactive task a restored, empty session needs. The first line is
     * queued before the task is released, so it cannot park on input and miss the message that
     * started the task in the first place.
     */
    private NaruTask startConversation(String firstLine) {
        NaruSession current = session;
        if (current == null || closed) {
            return null;
        }
        NaruTask task = current.newTask(NaruTaskSpec.of().statements(new String[0]));
        task.taskMode(NaruTaskMode.INTERACTIVE);
        current.foregroundTaskId(task.id());
        if (firstLine != null && !firstLine.isBlank()) {
            task.queueMessage(firstLine);
            // show it now, and remember it so the readline delivery does not draw a second copy
            noteQueued(firstLine, task.id());
        }
        task.unhold();
        return task;
    }

    /** Interrupt the foreground statement while leaving its interactive task alive. */
    public String interruptForegroundStatement() {
        EDT.check("NaruSessionModel.interruptForegroundStatement");
        NaruSession current = session;
        if (current == null || closed) return "no open session";
        try {
            long id = current.foregroundTaskId();
            NaruTask task = current.findTask(id).orNull();
            if (task == null || task.isCompleted()) return "there is no running statement to stop";
            task.interrupt();
            notifyUser("stopping the current statement");
            refreshTasks();
            return null;
        } catch (RuntimeException ex) {
            return "could not stop the current statement: " + ex.getMessage();
        }
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
        if (canStartNewConversation()) {
            return null;
        }
        return "the agent is working — it accepts input only when it asks";
    }

    /**
     * Answer the outstanding request, or start the conversation when a restored session has no
     * live task to answer. Never calls {@code session.deliverInput} directly: the request object
     * is what the engine handed us, is guarded so the first answer wins, and survives a task that
     * has already moved on.
     */
    public void submit(String line) {
        EDT.check("NaruSessionModel.submit");
        if (line == null) {
            return;
        }
        NaruInputRequest r = pending;
        if (r == null) {
            if (canStartNewConversation()) {
                startConversation(line);
                statusText = "starting…";
                fireChanged();
            }
            return;
        }
        pending = null;
        echoAndDeliver(line, r);
    }

    /** Deliver an inline choice/form submission to the currently parked input request. */
    public void submitMessageAction(String value) {
        EDT.check("NaruSessionModel.submitMessageAction");
        if (pending != null && value != null) submit(value);
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
            rememberHistory(line);
        }
        long taskId = request.task().id();
        // A queued line was already shown when the user pressed Enqueue. When it finally drains,
        // mark that echo as delivered instead of drawing a second copy of the same sentence.
        if (!markEchoDelivered(taskId, line)) {
            // the readline/ask_user path swallows the answer whole, so echoing it here is the only
            // way the user sees their own line in the transcript
            echoUser(line, taskId);
        }
        statusText = "running";
        fireChanged();
        request.deliver(line);
    }

    /**
     * Show a line the user queued for the next prompt. It is part of the conversation now — it
     * just has not reached the engine yet — so hiding it behind a "Queued · N" counter is wrong.
     * The echo is remembered until the queue drains, which is what keeps the delivered copy from
     * appearing twice.
     */
    public void noteQueued(String line, long taskId) {
        EDT.check("NaruSessionModel.noteQueued");
        if (line == null || line.isBlank()) {
            return;
        }
        if (!line.isBlank()) {
            rememberHistory(line);
        }
        String blockId = addUserEchoBlock(line, taskId);
        rememberEcho(taskId, new UserEcho(line, blockId, true));
        statusText = "queued for task #" + taskId;
        fireChanged();
    }

    /** Show the user's submission in the transcript before it disappears into the engine. */
    private void echoUser(String line, long taskId) {
        String blockId = addUserEchoBlock(line, taskId);
        rememberEcho(taskId, new UserEcho(line, blockId, false));
    }

    private String addUserEchoBlock(String line, long taskId) {
        String id = "u" + taskId + "-" + System.nanoTime();
        long seq = transcript.totalBlocks() + 1;
        transcript.apply(List.of(new TranscriptEvent.Message(id, taskId, seq, NaruMessage.user(line))));
        return id;
    }

    private void rememberHistory(String line) {
        if (inputHistory.isEmpty() || !inputHistory.get(inputHistory.size() - 1).equals(line)) {
            inputHistory.add(line);
        }
        historyCursor = inputHistory.size();
    }

    private void rememberEcho(long taskId, UserEcho echo) {
        Deque<UserEcho> queue = userEchoes.computeIfAbsent(taskId, k -> new ArrayDeque<>());
        queue.addLast(echo);
        while (queue.size() > MAX_PENDING_ECHOES) {
            queue.removeFirst();
        }
    }

    /** True when this line was queued rather than sent immediately; it is now being delivered. */
    private boolean markEchoDelivered(long taskId, String line) {
        Deque<UserEcho> queue = userEchoes.get(taskId);
        if (queue == null || line == null) return false;
        String wanted = line.strip();
        for (Iterator<UserEcho> it = queue.iterator(); it.hasNext(); ) {
            UserEcho echo = it.next();
            if (echo.awaitingDelivery && echo.text.strip().equals(wanted)) {
                echo.awaitingDelivery = false;
                return true;
            }
        }
        return false;
    }

    /**
     * Match a stored user message to the local echo that is already on screen. Consumes one echo,
     * because two identical prompts are two messages and only one of them is this callback.
     */
    private boolean consumeUserEcho(long taskId, String content) {
        Deque<UserEcho> queue = userEchoes.get(taskId);
        if (queue == null || content == null) return false;
        String wanted = content.strip();
        for (Iterator<UserEcho> it = queue.iterator(); it.hasNext(); ) {
            UserEcho echo = it.next();
            if (echo.text.strip().equals(wanted)) {
                it.remove();
                if (queue.isEmpty()) userEchoes.remove(taskId);
                return true;
            }
        }
        return false;
    }

    /**
     * True when the stored assistant message is the durable copy of text already streamed into
     * the transcript. The streamed block is the live presentation; the stored card would be the
     * same answer twice.
     */
    private boolean isShownAsStreamedAnswer(long taskId, NaruMessage stored) {
        String content = stored.getContent();
        if (content == null || content.isBlank()) return false;
        String wanted = content.strip();
        for (TranscriptBlock block : transcript.blocks()) {
            if (block.taskId() == taskId
                    && !block.isChatMessage()
                    && block.mode() == NaruLogMode.AGENT_RESPONSE
                    && block.text().strip().equals(wanted)) {
                return true;
            }
        }
        return false;
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

    /**
     * Completion for the input field, straight from E2. It stays on the EDT because it is bounded by
     * what it does: a directive scan and one directory listing, measured at 1.5 ms for 41 candidates
     * and 400 files. A token that made the engine walk a tree would not be safe here, and this is the
     * place to notice that.
     */
    public NaruLineCompletion.Result complete(String rawLine, int caret) {
        if (session == null) {
            return NaruLineCompletion.Result.none("", 0, 0);
        }
        try {
            return NaruLineCompletion.complete(session, rawLine, caret);
        } catch (RuntimeException e) {
            // An empty list is the honest answer for a line that is still being typed, but silence is
            // not: a completion that throws every time looks exactly like one that found nothing, and
            // the user is left with a widget that does nothing and no trace of why.
            if (e != lastCompletionFailure) {
                lastCompletionFailure = e;
                lastCompletionLine = rawLine;
                System.err.println("naru: completion failed for \"" + rawLine + "\" at " + caret);
                e.printStackTrace();
            }
            return NaruLineCompletion.Result.none("", 0, 0);
        }
    }

    /** The last completion failure, as one line, for a harness that has to prove the quiet path. */
    public String describeCompletionFailure() {
        return lastCompletionFailure == null ? "none"
                : lastCompletionFailure + " on \"" + lastCompletionLine + "\"";
    }

    private Throwable lastCompletionFailure;
    private String lastCompletionLine;

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

    /** Search every conversation task through its durable message store, outside the EDT. */
    public void searchMessages(String text, int limit, java.util.function.Consumer<MessageSearchResult> callback) {
        searchMessages(text, limit, java.util.Map.of(), callback);
    }

    /** Continue a cross-task durable search from each task's opaque store cursor. */
    public void searchMessages(String text, int limit, java.util.Map<Long, String> cursors,
                               java.util.function.Consumer<MessageSearchResult> callback) {
        final java.util.Map<Long, String> cursorSnapshot = cursors == null
                ? java.util.Map.of() : java.util.Map.copyOf(cursors);
        NaruSession current = session;
        if (current == null) {
            onEdt(() -> callback.accept(new MessageSearchResult(List.of(), java.util.Map.of(), false)));
            return;
        }
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            List<MessageSearchHit> hits = new ArrayList<>();
            java.util.Map<Long, String> nextCursors = new java.util.LinkedHashMap<>();
            boolean more = false;
            java.util.Map<Long, NaruTask> tasksToSearch = new java.util.TreeMap<>();
            for (NaruTask task : current.tasks()) tasksToSearch.put(task.id(), task);
            for (NaruTask task : current.conversationTasks()) tasksToSearch.put(task.id(), task);
            for (NaruTask task : tasksToSearch.values()) {
                try {
                    String cursor = cursorSnapshot.get(task.id());
                    if (!cursorSnapshot.isEmpty() && cursor == null) continue;
                    net.thevpc.naru.api.store.NaruMessagePage page = task.messages().search(text,
                            new net.thevpc.naru.api.store.NaruMessageQuery(cursor,
                                    net.thevpc.naru.api.store.NaruMessageQueryDirection.BACKWARD,
                                    Math.max(1, limit), java.util.Set.of(), null, null));
                    for (var item : page.items()) hits.add(new MessageSearchHit(task.id(), item));
                    if (page.hasMore()) {
                        more = true;
                        nextCursors.put(task.id(), page.nextCursor());
                    }
                } catch (UnsupportedOperationException ignored) {
                    // Non-persistent host implementations can still display their live transcript.
                } catch (RuntimeException ignored) {
                    // A task may be deleted or its session closed while the search is in flight.
                }
            }
            hits.sort(java.util.Comparator.comparingLong(MessageSearchHit::taskId)
                    .thenComparingLong(h -> h.stored().creationSequence()));
            return new MessageSearchResult(List.copyOf(hits), java.util.Map.copyOf(nextCursors), more);
        }).whenComplete((result, failure) -> onEdt(() -> callback.accept(failure == null
                ? result : new MessageSearchResult(List.of(), java.util.Map.of(), false))));
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

    public long latestPromptTokens() { return latestPromptTokens; }
    public long latestContextWindow() { return latestContextWindow; }
    public net.thevpc.naru.api.model.NaruModelKey latestUsageModel() { return latestUsageModel; }
    public java.util.List<net.thevpc.naru.api.model.NaruProviderRateLimitInfo> providerRateLimits() { return java.util.List.copyOf(providerRateLimits.values()); }

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

    /**
     * The live engine session, or null once {@link #shutdown()} has stopped it.
     * <p>
     * The null is the point: shutdown's last act is one more {@code fireChanged()}, and a pane that
     * answers that paint by reaching into the engine — the inspector shows the project directory, the
     * workbench lists routines — would otherwise throw {@code IllegalStateException: session is
     * stopped} on the EDT. Found by the §6 gate run, which shuts the model down without disposing the
     * views first; every pane already has a "no session" branch, so honouring it is the whole fix.
     */
    public NaruSession session() {
        return closed ? null : session;
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
