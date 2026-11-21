package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.text.NMsgSnapshot;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.scheduler.NaruTaskMode;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.store.*;
import net.thevpc.naru.api.model.NaruChunkKind;
import net.thevpc.naru.api.model.NaruStreamChunkData;
import net.thevpc.naru.api.model.NaruModelFilter;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.interaction.NaruTerminalInteraction;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NException;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;

/**
 * Backend turn handle. Hydration and activation happen on the first submit, not build.
 */
final class NaruSessionHandleImpl implements NaruSessionHandle {
    private static final ConcurrentMap<String, Live> LIVE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static final Object LIVE_LOCK = new Object();
    /**
     * Runs between a session's idle check and its hibernation; tests use it to make a submit
     * land in that window, which is otherwise a few instructions wide.
     */
    static volatile Runnable beforeHibernate;
    /** Runs once a session is marked hibernating, before it is saved; see {@link #beforeHibernate}. */
    static volatile Runnable whileHibernating;
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "naru-turn");
        t.setDaemon(true);
        return t;
    });

    private final NaruWorkspaceImpl agent;
    /**
     * The caller's stated session directory, or null: when null and a project is given,
     * the project's folder becomes the session directory at activation.
     */
    private final NPath sessionDir;
    /**
     * Project reference resolved at activation; supplies initial values only.
     */
    private final String projectRef;
    /**
     * The opaque user string seeded on a newly created session.
     */
    private final String userSeed;
    private final NaruInteraction customInteraction;
    private final NaruTaskSpec task;
    private final String id;
    private final NaruSessionStore requestedStore;
    private final NaruSessionScope newScope;
    private final NaruInputBlockPolicy inputPolicy;
    private final Duration grace;
    private final boolean streaming;
    private final NaruBusyPolicy busy;
    private final NaruTurnEventListener listener;
    private final int maxLive;
    private final boolean checkpointEachMessage;
    private final Duration capacityTimeout;
    private final NaruSessionListener sessionListener;
    private final Predicate<NaruDirective> directiveFilter;
    private final Predicate<NaruTool> toolFilter;
    private final Predicate<NaruToolTag> tagFilter;
    private final NaruModelFilter modelFilter;
    /**
     * A name the host chose at creation, or null for a placeholder.
     */
    private final String sessionName;
    /**
     * Whether the first task is a conversation: it parks on input after each turn, which is
     * what lets the handle hibernate the session between turns instead of completing it.
     */
    private final boolean interactive;

    NaruSessionHandleImpl(NaruWorkspaceImpl agent, NPath sessionDir, NaruInteraction interaction,
                          NaruTaskSpec task, String id, NaruSessionStore store, NaruSessionScope scope,
                          NaruInputBlockPolicy inputPolicy, Duration grace, boolean streaming,
                          NaruBusyPolicy busy, NaruTurnEventListener listener, int maxLive,
                          boolean checkpointEachMessage,
                          Duration capacityTimeout,
                          NaruSessionListener sessionListener, Predicate<NaruDirective> directiveFilter,
                          Predicate<NaruTool> toolFilter, Predicate<NaruToolTag> tagFilter,
                          NaruModelFilter modelFilter, String projectRef, String userSeed,
                          String sessionName, boolean interactive) {
        this.agent = agent;
        this.sessionDir = sessionDir;
        this.customInteraction = interaction;
        this.projectRef = projectRef;
        this.userSeed = userSeed;
        this.sessionName = sessionName;
        this.interactive = interactive;
        this.task = task;
        this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        this.requestedStore = store;
        this.newScope = scope == null ? NaruSessionScope.PRIVATE : scope;
        this.inputPolicy = inputPolicy == null ? NaruInputBlockPolicy.HIBERNATE : inputPolicy;
        this.grace = grace == null ? Duration.ZERO : grace;
        this.streaming = streaming;
        this.busy = busy == null ? NaruBusyPolicy.REJECT : busy;
        this.listener = listener;
        this.maxLive = maxLive;
        this.sessionListener = sessionListener;
        this.checkpointEachMessage = checkpointEachMessage;
        this.capacityTimeout = capacityTimeout == null ? Duration.ofSeconds(30) : capacityTimeout;
        this.directiveFilter = directiveFilter;
        this.toolFilter = toolFilter;
        this.tagFilter = tagFilter;
        this.modelFilter = modelFilter;
    }

    @Override
    public String id() {
        return id;
    }

    /**
     * Submits a turn. A session that is being saved for hibernation is not busy: its last turn
     * is over, and the save is housekeeping the caller cannot see. So whatever the busy policy,
     * the submit waits for the save (at most the capacity timeout) and serves the turn from
     * the session the store now holds. Only a timeout reports it busy.
     */
    @Override
    public NaruTurn submit(NaruInput input) {
        Objects.requireNonNull(input, "input");
        long deadline = System.nanoTime() + capacityTimeout.toNanos();
        while (true) {
            CompletableFuture<Void> saving;
            try {
                return submitOnce(input);
            } catch (HibernationInProgress h) {
                saving = h.saving;
            }
            // outside every lock: the save does not need them, but the next submit does
            try {
                saving.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                throw new NaruSessionBusyException(id);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NaruSessionBusyException(id);
            } catch (ExecutionException e) {
                // the save failed and the session stays live; the next attempt finds it so
            }
        }
    }

    /** Thrown inside {@link #submitOnce} to leave its locks before waiting on {@link #saving}. */
    private static final class HibernationInProgress extends RuntimeException {
        final CompletableFuture<Void> saving;

        HibernationInProgress(CompletableFuture<Void> saving) {
            super(null, null, false, false);
            this.saving = saving;
        }
    }

    private NaruTurn submitOnce(NaruInput input) {
        Pending pending = new Pending(input);
        Object lock = LOCKS.computeIfAbsent(id, x -> new Object());
        synchronized (lock) {
            Live live;
            synchronized (LIVE_LOCK) {
                live = LIVE.get(id);
                if (live != null && live.started && !live.session.isRunning() && !live.session.hasLiveTasks()) {
                    LIVE.remove(id, live);
                    live = null;
                }
                if (live == null) {
                    try {
                        ensureCapacity();
                        live = activate();
                        LIVE.put(id, live);
                    } catch (NaruCapacityException capacity) {
                        WORKERS.execute(() -> awaitCapacity(pending, System.nanoTime() + capacityTimeout.toNanos()));
                        return new TurnImpl(pending, null);
                    }
                }
            }
            synchronized (live.monitor) {
                if (live.hibernating.get()) throw new HibernationInProgress(live.saving);
                live.submits++;
                live.lastUse = System.nanoTime();
                TurnEvents events = new TurnEvents(listener, streaming, live.sequence);
                pending.events = events;
                live.current = pending;
                live.interaction.events = events;
                if (input.idempotencyKey() != null && live.session.hasDeliveredInputKey(input.idempotencyKey())) {
                    events.close();
                    pending.future.complete(NaruTurnResult.hibernated(live.session.pendingInputs(), false));
                    return new TurnImpl(pending, live);
                }
                try {
                    List<NaruPendingInput> inputs = live.session.pendingInputs();
                    if (!inputs.isEmpty()) {
                        NaruPendingInput target = chooseInput(inputs, input.requestId());
                        if (!live.session.deliverPendingInput(target.requestId(), input.text(), input.idempotencyKey())) {
                            throw new IllegalStateException("pending input request expired: " + target.requestId());
                        }
                        // a session just rehydrated from the store holds the parked request but
                        // is not running yet: without this the answer is delivered to a task
                        // nothing schedules, and the turn never ends
                        if (!live.started) {
                            live.session.start();
                            live.started = true;
                        }
                    } else if (live.started && awaitingInput(live.session)) {
                        // every task already waits for input, but its request is registered just
                        // after the status changes: the session is idle, not busy, and the request
                        // takes this from the queue when it is registered (onInputRequested)
                        live.queued.add(pending);
                    } else if (live.started && !live.session.tasks().isEmpty()) {
                        if (busy == NaruBusyPolicy.REJECT) throw new NaruSessionBusyException(id);
                        if (busy == NaruBusyPolicy.INTERRUPT) {
                            for (NaruTask t : live.session.tasks())
                                if (!t.status().isCompleted()) t.cancel("interrupted by a new turn");
                            throw new NaruSessionBusyException(id);
                        }
                        live.queued.add(pending);
                    } else if (!live.started) {
                        live.queued.add(pending);
                        live.session.start();
                        live.started = true;
                    }
                    live.turns.add(pending);
                } catch (RuntimeException e) {
                    events.close();
                    throw e;
                }
            }
            Pending selected = pending;
            Live selectedLive = live;
            if (selectedLive.driving.compareAndSet(false, true))
                WORKERS.execute(() -> awaitTurn(selectedLive, selected));
            return new TurnImpl(pending, selectedLive);
        }
    }

    /** Some task is live, and every live task is parked waiting for input. */
    private static boolean awaitingInput(NaruSession session) {
        boolean any = false;
        for (NaruTask t : session.tasks()) {
            if (t.status().isCompleted()) continue;
            if (t.status() != NaruTaskStatus.BLOCKED_ON_INPUT) return false;
            any = true;
        }
        return any;
    }

    private NaruPendingInput chooseInput(List<NaruPendingInput> requests, String requestId) {
        if (requestId != null) {
            return requests.stream().filter(r -> requestId.equals(r.requestId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no pending input request '" + requestId + "'"));
        }
        if (requests.size() != 1) throw new NaruAmbiguousInputException(requests);
        return requests.get(0);
    }

    private Live activate() {
        TurnInteraction interaction = new TurnInteraction(customInteraction, listener, streaming, this::onInputRequested);
        // the effective-value rule at activation, mirroring NaruSessionBuilder.build():
        // explicit directory, else the project's folder, else the workspace default; a
        // project reference naming nothing is an error before any state is written
        NaruSessionGroup project = null;
        if (projectRef != null && !projectRef.isBlank()) {
            project = agent.sessionGroup(projectRef).orNull();
            if (project == null) {
                throw new net.thevpc.nuts.util.NIllegalArgumentException(
                        NMsg.ofC("project not found: %s", projectRef));
            }
        }
        NPath dir = sessionDir != null
                ? sessionDir
                : (project != null && project.folder() != null ? project.folder() : agent.defaultSessionDir());
        NaruSessionImpl session = new NaruSessionImpl(agent, dir, interaction, true,
                sessionListener, directiveFilter, toolFilter, tagFilter, modelFilter, requestedStore);
        session.configureTurnPersistence(true, checkpointEachMessage);
        NaruSessionStore store = requestedStore == null ? session.rawSessionStore() : requestedStore;
        NaruSessionScope scope = store.scopeOf(id).orElse(newScope);
        if (store.exists(id, scope)) {
            session.loadStored(id, scope);
        } else {
            session.initializeHandleId(id, scope);
            // creation-time seeding only: a resumed session keeps its stored project and user
            session._setProjectId(project == null ? null : project.id());
            session._setUserName(userSeed != null ? userSeed : (project == null ? null : project.user()));
            if (sessionName != null) {
                session.rename(sessionName, NaruSessionNameSource.USER);
            }
            if (task != null || interactive) {
                NaruTask initial = session.newTask(task != null ? task : NaruTaskSpec.of().resolveNameOr("naru"));
                if (interactive) {
                    initial.taskMode(NaruTaskMode.INTERACTIVE);
                    initial.fg();
                }
                initial.unhold();
            }
        }
        Live live = new Live(session, scope, interaction);
        session.addSessionListener(live.listener());
        return live;
    }

    private void onInputRequested(NaruInputRequest request) {
        Live live = LIVE.get(id);
        if (live == null) return;
        Pending queued = null;
        synchronized (live.monitor) {
            if (!live.queued.isEmpty()) {
                if (live.queued.peek().input.requestId() == null
                        || live.queued.peek().input.requestId().equals(request.requestId())) {
                    queued = live.queued.poll();
                }
            }
        }
        if (queued != null) request.deliver(queued.input.text(), queued.input.idempotencyKey());
        synchronized (live.monitor) {
            live.monitor.notifyAll();
        }
    }

    private void awaitTurn(Live live, Pending pending) {
        try {
            long idleSince = 0;
            while (true) {
                // read before the idle checks below, which run outside the monitor
                long seenSubmits = live.submits;
                boolean hasTasks = live.session.hasLiveTasks();
                if (live.started && !hasTasks) {
                    if (live.session.isRunning()) live.session.save();
                    // released before reported, as in hibernateLive
                    LIVE.remove(id, live);
                    live.finishAll(NaruTurnResult.completed(pending.events.lagged()));
                    return;
                }
                boolean undelivered;
                synchronized (live.monitor) {
                    undelivered = !live.queued.isEmpty();
                }
                // a task parked on input is only idle once the input this turn carries has
                // reached it: hibernating earlier dropped the first turn's text on the floor
                if (inputPolicy == NaruInputBlockPolicy.HIBERNATE && !undelivered && live.session.isHibernateEligible()) {
                    if (idleSince == 0) idleSince = System.nanoTime();
                    if (System.nanoTime() - idleSince >= grace.toNanos()) {
                        Runnable hook = beforeHibernate;
                        if (hook != null) hook.run();
                        if (hibernateLive(live, seenSubmits)) {
                            return;
                        }
                        idleSince = 0;
                    }
                } else {
                    idleSince = 0;
                }
                synchronized (live.monitor) {
                    live.monitor.wait(20L);
                }
            }
        } catch (Throwable e) {

            pending.events.emit(NaruTurnEvent.Kind.ERROR, new NMsgSnapshot(NException.getErrorFormattedMessage(e)).toElement());
            // released before reported, as in hibernateLive: a retry must not find it busy
            LIVE.remove(id, live);
            live.finishAll(NaruTurnResult.failed(e, pending.events.lagged()));
        }
    }

    private void ensureCapacity() {
        if (LIVE.size() < maxLive) return;
        Live candidate = null;
        long candidateSubmits = 0;
        for (Live x : LIVE.values()) {
            long seen = x.submits;
            if (x.session.isHibernateEligible() && (candidate == null || x.lastUse < candidate.lastUse)) {
                candidate = x;
                candidateSubmits = seen;
            }
        }
        if (candidate == null) throw new NaruCapacityException(maxLive);
        try {
            if (!hibernateLive(candidate, candidateSubmits)) throw new NaruCapacityException(maxLive);
            LIVE.remove(candidate.session.uuid(), candidate);
        } catch (RuntimeException e) {
            throw new NaruCapacityException(maxLive);
        }
    }

    private void awaitCapacity(Pending pending, long deadline) {
        while (System.nanoTime() < deadline && !pending.future.isDone()) {
            try {
                NaruTurn turn = submit(pending.input);
                turn.result().whenComplete((result, error) -> {
                    if (error != null) pending.future.complete(NaruTurnResult.failed(error, false));
                    else pending.future.complete(result);
                });
                return;
            } catch (NaruCapacityException ignored) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pending.future.complete(NaruTurnResult.failed(e, false));
                    return;
                }
            } catch (Throwable e) {
                pending.future.complete(NaruTurnResult.failed(e, false));
                return;
            }
        }
        pending.future.complete(NaruTurnResult.failed(new NaruCapacityException(maxLive), false));
    }

    /**
     * Hibernates {@code live} unless a turn was submitted since its idle check.
     * <p>
     * That check reads the session outside the monitor (taking it there would order it against
     * the task locks the session's listeners hold), so a submit can land between the check and
     * this call. Hibernating then dropped the input it carried. Under the monitor {@code submit}
     * holds, the submit count says whether one did.
     *
     * @return whether it hibernated; {@code false} leaves the session live
     */
    private boolean hibernateLive(Live live, long seenSubmits) {
        CompletableFuture<Void> saving = new CompletableFuture<>();
        synchronized (live.monitor) {
            if (live.submits != seenSubmits || !live.queued.isEmpty()) return false;
            if (!live.hibernating.compareAndSet(false, true)) return false;
            live.saving = saving;
        }
        try {
            Runnable hook = whileHibernating;
            if (hook != null) hook.run();
            TurnEvents events = live.events();
            events.emit(NaruTurnEvent.Kind.HIBERNATING, null);
            List<NaruPendingInput> pendingInputs = live.session.pendingInputs();
            live.session.hibernate();
            events.emit(NaruTurnEvent.Kind.HIBERNATED, null);
            // out of LIVE before anyone hears "hibernated": a caller that submits its next turn
            // on that news must rehydrate from the store, not find this entry still hibernating
            // and be told the session is busy
            LIVE.remove(live.session.uuid(), live);
            live.finishAll(NaruTurnResult.hibernated(pendingInputs, events.lagged()));
            // only now, out of LIVE: a waiting submit retries and activates from the store
            saving.complete(null);
            return true;
        } catch (RuntimeException e) {
            live.hibernating.set(false);
            saving.completeExceptionally(e);
            throw e;
        }
    }

    @Override
    public void close() {
    }

    private final class TurnImpl implements NaruTurn {
        private final Pending pending;
        private final Live live;

        TurnImpl(Pending pending, Live live) {
            this.pending = pending;
            this.live = live;
        }

        public CompletableFuture<NaruTurnResult> result() {
            return pending.future;
        }

        public void disconnect() { /* continue and persist by default */ }
    }

    private static final class Pending {
        final NaruInput input;
        final CompletableFuture<NaruTurnResult> future = new CompletableFuture<>();
        volatile TurnEvents events;

        Pending(NaruInput input) {
            this.input = input;
        }
    }

    private final class Live {
        final NaruSessionImpl session;
        final NaruSessionScope scope;
        final TurnInteraction interaction;
        final Object monitor = new Object();
        final Deque<Pending> queued = new ArrayDeque<>();
        final List<Pending> turns = new CopyOnWriteArrayList<>();
        final AtomicBoolean driving = new AtomicBoolean();
        final AtomicLong sequence;
        final AtomicBoolean hibernating = new AtomicBoolean();
        /** Completes when the hibernation under way ends; set with {@link #hibernating}. */
        volatile CompletableFuture<Void> saving = CompletableFuture.completedFuture(null);
        /** Turns submitted so far; written under {@link #monitor}, see {@link #hibernateLive}. */
        volatile long submits;
        volatile Pending current;
        volatile long lastUse = System.nanoTime();
        volatile boolean started;

        Live(NaruSessionImpl session, NaruSessionScope scope, TurnInteraction interaction) {
            this.session = session;
            this.scope = scope;
            this.interaction = interaction;
            this.sequence = interaction.sequence;
        }

        NaruSessionListener listener() {
            return new NaruSessionListener() {
                public void onEventAppended(net.thevpc.naru.api.scheduler.NaruEvent e) {
                }

                public void sessionStarted(NaruSession s) {
                }

                public void sessionStopped(NaruSession s) {
                    synchronized (monitor) {
                        monitor.notifyAll();
                    }
                }

                public void onSessionReloaded(NaruSession s) {
                }

                public void onMessageComplete(long taskId, String cursor) {
                    Pending p = current;
                    if (p != null && p.events != null)
                        p.events.emit(NaruTurnEvent.Kind.MESSAGE_COMPLETE,
                                NObjectElementBuilder.of().add("taskId", NElement.of(taskId)).add("cursor", NElement.of(cursor)).build()
                        );
                }

                public void onTaskStatusChanged(NaruTask t, NaruTaskStatus oldStatus, NaruTaskStatus newStatus) {
                    synchronized (monitor) {
                        monitor.notifyAll();
                    }
                    Pending p = current;
                    if (newStatus == NaruTaskStatus.BLOCKED_ON_INPUT && p != null) {
                        // the status can change before the input request is registered; taking
                        // the queued input with nothing to deliver it to used to lose it, so it
                        // stays queued for onInputRequested in that case
                        NaruPendingInput request = session.pendingInputs().stream()
                                .filter(x -> x.taskId() == t.id()).findFirst().orElse(null);
                        Pending next = null;
                        if (request != null) {
                            synchronized (monitor) {
                                next = queued.poll();
                            }
                        }
                        if (next != null) session.deliverPendingInput(request.requestId(), next.input.text());
                    }
                }
            };
        }

        TurnEvents events() {
            return current == null ? interaction.events : current.events;
        }

        void finishAll(NaruTurnResult result) {
            for (Pending p : turns) {
                p.future.complete(new NaruTurnResult(result.status(), result.pendingInputs(),
                        p.events != null && p.events.lagged(), result.failure()));
                if (p.events != null) p.events.close();
            }
            turns.clear();
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }
    }

    private static final class TurnEvents {
        final NaruTurnEventListener listener;
        final boolean streaming;
        final ArrayBlockingQueue<NaruTurnEvent> queue = new ArrayBlockingQueue<>(128);
        final AtomicLong sequence;
        final AtomicBoolean lagged = new AtomicBoolean();
        final Map<NaruLogMode, StringBuilder> fragments = new ConcurrentHashMap<>();
        volatile Thread consumer;

        TurnEvents(NaruTurnEventListener listener, boolean streaming) {
            this(listener, streaming, new AtomicLong());
        }

        TurnEvents(NaruTurnEventListener listener, boolean streaming, AtomicLong sequence) {
            this.listener = listener;
            this.streaming = streaming;
            this.sequence = sequence;
            if (listener != null) {
                consumer = new Thread(() -> {
                    try {
                        while (true) listener.onEvent(queue.take());
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable ignored) {
                        lagged.set(true);
                        queue.clear();
                    }
                }, "naru-turn-events");
                consumer.setDaemon(true);
                consumer.start();
            }
        }

        void emit(NaruTurnEvent.Kind kind, NElement payload) {
            if (listener == null || lagged.get()) return;
            NaruTurnEvent event = new NaruTurnEvent(sequence.incrementAndGet(), kind, payload);
            if (!queue.offer(event)) {
                lagged.set(true);
                queue.clear();
            }
        }

        boolean lagged() {
            return lagged.get();
        }

        void close() {
            if (consumer != null) consumer.interrupt();
        }
    }

    private static final class TurnInteraction implements NaruInteraction {
        final NaruInteraction delegate;
        volatile TurnEvents events;
        final boolean streaming;
        final AtomicLong sequence = new AtomicLong();
        volatile boolean closed;
        final java.util.function.Consumer<NaruInputRequest> inputHook;

        TurnInteraction(NaruInteraction delegate, NaruTurnEventListener listener, boolean streaming,
                        java.util.function.Consumer<NaruInputRequest> inputHook) {
            this.delegate = delegate instanceof NaruTerminalInteraction ? null : delegate;
            this.events = new TurnEvents(listener, streaming, sequence);
            this.streaming = streaming;
            this.inputHook = inputHook;
        }

        public void open(NaruSession session) {
            closed = false;
            if (delegate != null) delegate.open(session);
        }

        public void requestInput(NaruInputRequest request) {
            events.emit(NaruTurnEvent.Kind.INPUT_REQUESTED,
                    new NaruPendingInput(request.task().id(), request.requestId(), request.prompt() == null ? "" : request.prompt().toString())
                            .toElement()
            );
            // Backend handles answer requests through submit(), so do not hand them to a
            // terminal or a legacy input listener that could block or cancel them.
            inputHook.accept(request);
        }

        public void write(NaruLogMode mode, NMsg message) {
            StringBuilder b = events.fragments.remove(mode);
            if (b != null && b.length() > 0) {
                if (streaming) events.emit(NaruTurnEvent.Kind.CHUNK,
                        NaruStreamChunkData.of(chunkKind(mode), "", chunkIndex.incrementAndGet()).asComplete(true).toElement());
                else message = NMsg.ofC("%s%s", b, message);
            }
            events.emit(NaruTurnEvent.Kind.LOG, new NMsgSnapshot(message).toElement());
            if (delegate != null) delegate.write(mode, message);
        }

        public void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
            StringBuilder b = events.fragments.computeIfAbsent(mode, x -> new StringBuilder());
            b.append(fragment);
            if (streaming) events.emit(NaruTurnEvent.Kind.CHUNK,
                    new NaruStreamChunkData(chunkKind(mode), fragment.toString(), chunkIndex.incrementAndGet(), null, null, end).toElement());
            if (end) {
                events.fragments.remove(mode);
                if (!streaming) events.emit(NaruTurnEvent.Kind.LOG, NMsgSnapshot.of(NMsg.ofC("%s", b)).toElement());
            }
            if (delegate != null) delegate.writeStream(mode, fragment, end);
        }

        public void close() {
            closed = true;
            if (delegate != null) delegate.close();
        }

        public void onHibernate() {
            if (delegate != null) delegate.onHibernate();
        }

        /**
         * Tool-call reports pass straight through to whatever is underneath, and are also
         * published as turn events so a backend host gets the same lifecycle the desktop host
         * reads off its interaction.
         */
        public void reportToolCall(NaruToolCallEvent event) {
            events.emit(NaruTurnEvent.Kind.TOOL_CALL, event.toElement());
            if (delegate != null) delegate.reportToolCall(event);
        }

        @Override
        public void write(NaruLogMode mode, NMsg message, long taskId) {
            if (delegate != null) delegate.write(mode, message, taskId);
            else write(mode, message);
        }

        @Override
        public void writeStream(NaruLogMode mode, NMsg fragment, boolean end, long taskId) {
            if (delegate != null) delegate.writeStream(mode, fragment, end, taskId);
            else writeStream(mode, fragment, end);
        }

        private final AtomicInteger chunkIndex = new AtomicInteger();

        private static NaruChunkKind chunkKind(NaruLogMode mode) {
            return mode == NaruLogMode.MODEL_THINKING ? NaruChunkKind.THINKING : NaruChunkKind.ANSWER;
        }
    }
}
