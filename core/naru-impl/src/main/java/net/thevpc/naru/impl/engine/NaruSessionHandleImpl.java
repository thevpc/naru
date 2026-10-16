package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.store.*;
import net.thevpc.naru.api.model.NaruChunkKind;
import net.thevpc.naru.api.model.NaruStreamChunkData;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.interaction.NaruTerminalInteraction;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;

/** Backend turn handle. Hydration and activation happen on the first submit, not build. */
final class NaruSessionHandleImpl implements NaruSessionHandle {
    private static final ConcurrentMap<String, Live> LIVE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static final Object LIVE_LOCK = new Object();
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "naru-turn"); t.setDaemon(true); return t;
    });

    private final NaruWorkspaceImpl agent;
    private final NPath directory;
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

    NaruSessionHandleImpl(NaruWorkspaceImpl agent, NPath directory, NaruInteraction interaction,
                          NaruTaskSpec task, String id, NaruSessionStore store, NaruSessionScope scope,
                          NaruInputBlockPolicy inputPolicy, Duration grace, boolean streaming,
                          NaruBusyPolicy busy, NaruTurnEventListener listener, int maxLive,
                          boolean checkpointEachMessage,
                          Duration capacityTimeout,
                          NaruSessionListener sessionListener, Predicate<NaruDirective> directiveFilter,
                          Predicate<NaruTool> toolFilter, Predicate<NaruToolTag> tagFilter) {
        this.agent = agent; this.directory = directory; this.customInteraction = interaction;
        this.task = task; this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        this.requestedStore = store; this.newScope = scope == null ? NaruSessionScope.PRIVATE : scope;
        this.inputPolicy = inputPolicy == null ? NaruInputBlockPolicy.HIBERNATE : inputPolicy;
        this.grace = grace == null ? Duration.ZERO : grace; this.streaming = streaming;
        this.busy = busy == null ? NaruBusyPolicy.REJECT : busy; this.listener = listener;
        this.maxLive = maxLive; this.sessionListener = sessionListener;
        this.checkpointEachMessage = checkpointEachMessage;
        this.capacityTimeout = capacityTimeout == null ? Duration.ofSeconds(30) : capacityTimeout;
        this.directiveFilter = directiveFilter; this.toolFilter = toolFilter; this.tagFilter = tagFilter;
    }

    @Override public String id() { return id; }

    @Override
    public NaruTurn submit(NaruInput input) {
        Objects.requireNonNull(input, "input");
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
                if (live.hibernating.get()) throw new NaruSessionBusyException(id);
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
                    } else if (live.started && !live.session.tasks().isEmpty()) {
                        if (busy == NaruBusyPolicy.REJECT) throw new NaruSessionBusyException(id);
                        if (busy == NaruBusyPolicy.INTERRUPT) {
                            for (NaruTask t : live.session.tasks()) if (!t.status().isCompleted()) t.cancel("interrupted by a new turn");
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
            if (selectedLive.driving.compareAndSet(false, true)) WORKERS.execute(() -> awaitTurn(selectedLive, selected));
            return new TurnImpl(pending, selectedLive);
        }
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
        NaruSessionImpl session = new NaruSessionImpl(agent, directory, interaction, true,
                sessionListener, directiveFilter, toolFilter, tagFilter, requestedStore);
        session.configureTurnPersistence(true, checkpointEachMessage);
        NaruSessionStore store = requestedStore == null ? session.rawSessionStore() : requestedStore;
        NaruSessionScope scope = store.scopeOf(id).orElse(newScope);
        if (store.exists(id, scope)) {
            session.loadStored(id, scope);
        } else {
            session.initializeHandleId(id, scope);
            if (task != null) {
                NaruTask initial = session.newTask(task);
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
        synchronized (live.monitor) { live.monitor.notifyAll(); }
    }

    private void awaitTurn(Live live, Pending pending) {
        try {
            long idleSince = 0;
            while (true) {
                boolean hasTasks = live.session.hasLiveTasks();
                if (live.started && !hasTasks) {
                    if (live.session.isRunning()) live.session.save();
                    live.finishAll(NaruTurnResult.completed(pending.events.lagged()));
                    LIVE.remove(id, live);
                    return;
                }
                if (inputPolicy == NaruInputBlockPolicy.HIBERNATE && live.session.isHibernateEligible()) {
                    if (idleSince == 0) idleSince = System.nanoTime();
                    if (System.nanoTime() - idleSince >= grace.toNanos()) {
                        hibernateLive(live);
                        return;
                    }
                } else {
                    idleSince = 0;
                }
                live.monitor.wait(20L);
            }
        } catch (Throwable e) {
            pending.events.emit(NaruTurnEvent.Kind.ERROR, e);
            live.finishAll(NaruTurnResult.failed(e, pending.events.lagged()));
            LIVE.remove(id, live);
        }
    }

    private void ensureCapacity() {
        if (LIVE.size() < maxLive) return;
        Live candidate = LIVE.values().stream().filter(x -> x.session.isHibernateEligible())
                .min(Comparator.comparingLong(x -> x.lastUse)).orElse(null);
        if (candidate == null) throw new NaruCapacityException(maxLive);
        try {
            hibernateLive(candidate);
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
                try { Thread.sleep(25); } catch (InterruptedException e) {
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

    private void hibernateLive(Live live) {
        if (!live.hibernating.compareAndSet(false, true)) return;
        try {
            TurnEvents events = live.events();
            events.emit(NaruTurnEvent.Kind.HIBERNATING, null);
            List<NaruPendingInput> pendingInputs = live.session.pendingInputs();
            live.session.hibernate();
            events.emit(NaruTurnEvent.Kind.HIBERNATED, null);
            live.finishAll(NaruTurnResult.hibernated(pendingInputs, events.lagged()));
            LIVE.remove(live.session.uuid(), live);
        } catch (RuntimeException e) {
            live.hibernating.set(false);
            throw e;
        }
    }

    @Override public void close() { }

    private final class TurnImpl implements NaruTurn {
        private final Pending pending; private final Live live;
        TurnImpl(Pending pending, Live live) { this.pending = pending; this.live = live; }
        public CompletableFuture<NaruTurnResult> result() { return pending.future; }
        public void disconnect() { /* continue and persist by default */ }
    }

    private static final class Pending {
        final NaruInput input; final CompletableFuture<NaruTurnResult> future = new CompletableFuture<>();
        volatile TurnEvents events;
        Pending(NaruInput input) { this.input = input; }
    }

    private final class Live {
        final NaruSessionImpl session; final NaruSessionScope scope; final TurnInteraction interaction;
        final Object monitor = new Object(); final Deque<Pending> queued = new ArrayDeque<>();
        final List<Pending> turns = new CopyOnWriteArrayList<>(); final AtomicBoolean driving = new AtomicBoolean();
        final AtomicLong sequence;
        final AtomicBoolean hibernating = new AtomicBoolean();
        volatile Pending current; volatile long lastUse = System.nanoTime(); volatile boolean started;
        Live(NaruSessionImpl session, NaruSessionScope scope, TurnInteraction interaction) {
            this.session = session; this.scope = scope; this.interaction = interaction;
            this.sequence = interaction.sequence;
        }
        NaruSessionListener listener() {
            return new NaruSessionListener() {
                public void onEventAppended(net.thevpc.naru.api.scheduler.NaruEvent e) { }
                public void sessionStarted(NaruSession s) { }
                public void sessionStopped(NaruSession s) { synchronized (monitor) { monitor.notifyAll(); } }
                public void onSessionReloaded(NaruSession s) { }
                public void onMessageComplete(long taskId, String cursor) {
                    Pending p = current;
                    if (p != null && p.events != null)
                        p.events.emit(NaruTurnEvent.Kind.MESSAGE_COMPLETE, Map.of("taskId", taskId, "cursor", cursor));
                }
                public void onTaskStatusChanged(NaruTask t, NaruTaskStatus oldStatus, NaruTaskStatus newStatus) {
                    synchronized (monitor) { monitor.notifyAll(); }
                    Pending p = current;
                    if (newStatus == NaruTaskStatus.BLOCKED_ON_INPUT && p != null && !queued.isEmpty()) {
                        Pending next = queued.poll();
                        NaruPendingInput request = session.pendingInputs().stream()
                                .filter(x -> x.taskId() == t.id()).findFirst().orElse(null);
                        if (request != null) session.deliverPendingInput(request.requestId(), next.input.text());
                    }
                }
            };
        }
        TurnEvents events() { return current == null ? interaction.events : current.events; }
        void finishAll(NaruTurnResult result) {
            for (Pending p : turns) {
                p.future.complete(new NaruTurnResult(result.status(), result.pendingInputs(),
                        p.events != null && p.events.lagged(), result.failure()));
                if (p.events != null) p.events.close();
            }
            turns.clear();
            synchronized (monitor) { monitor.notifyAll(); }
        }
    }

    private static final class TurnEvents {
        final NaruTurnEventListener listener; final boolean streaming;
        final ArrayBlockingQueue<NaruTurnEvent> queue = new ArrayBlockingQueue<>(128);
        final AtomicLong sequence; final AtomicBoolean lagged = new AtomicBoolean();
        final Map<NaruLogMode, StringBuilder> fragments = new ConcurrentHashMap<>();
        volatile Thread consumer;
        TurnEvents(NaruTurnEventListener listener, boolean streaming) { this(listener, streaming, new AtomicLong()); }
        TurnEvents(NaruTurnEventListener listener, boolean streaming, AtomicLong sequence) {
            this.listener = listener; this.streaming = streaming;
            this.sequence = sequence;
            if (listener != null) {
                consumer = new Thread(() -> {
                    try { while (true) listener.onEvent(queue.take()); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    catch (Throwable ignored) { lagged.set(true); queue.clear(); }
                }, "naru-turn-events"); consumer.setDaemon(true); consumer.start();
            }
        }
        void emit(NaruTurnEvent.Kind kind, Object payload) {
            if (listener == null || lagged.get()) return;
            NaruTurnEvent event = new NaruTurnEvent(sequence.incrementAndGet(), kind, payload);
            if (!queue.offer(event)) { lagged.set(true); queue.clear(); }
        }
        boolean lagged() { return lagged.get(); }
        void close() { if (consumer != null) consumer.interrupt(); }
    }

    private static final class TurnInteraction implements NaruInteraction {
        final NaruInteraction delegate; volatile TurnEvents events; final boolean streaming;
        final AtomicLong sequence = new AtomicLong();
        volatile boolean closed;
        final java.util.function.Consumer<NaruInputRequest> inputHook;
        TurnInteraction(NaruInteraction delegate, NaruTurnEventListener listener, boolean streaming,
                        java.util.function.Consumer<NaruInputRequest> inputHook) {
            this.delegate = delegate instanceof NaruTerminalInteraction ? null : delegate;
            this.events = new TurnEvents(listener, streaming, sequence); this.streaming = streaming;
            this.inputHook = inputHook;
        }
        public void open(NaruSession session) { closed = false; if (delegate != null) delegate.open(session); }
        public void requestInput(NaruInputRequest request) {
            events.emit(NaruTurnEvent.Kind.INPUT_REQUESTED,
                    new NaruPendingInput(request.task().id(), request.requestId(), request.prompt() == null ? "" : request.prompt().toString()));
            // Backend handles answer requests through submit(), so do not hand them to a
            // terminal or a legacy input listener that could block or cancel them.
            inputHook.accept(request);
        }
        public void write(NaruLogMode mode, NMsg message) {
            StringBuilder b = events.fragments.remove(mode);
            if (b != null && b.length() > 0) {
                if (streaming) events.emit(NaruTurnEvent.Kind.CHUNK,
                        NaruStreamChunkData.of(chunkKind(mode), "", chunkIndex.incrementAndGet()).asComplete(true));
                else message = NMsg.ofC("%s%s", b, message);
            }
            events.emit(NaruTurnEvent.Kind.LOG, message);
            if (delegate != null) delegate.write(mode, message);
        }
        public void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
            StringBuilder b = events.fragments.computeIfAbsent(mode, x -> new StringBuilder());
            b.append(fragment);
            if (streaming) events.emit(NaruTurnEvent.Kind.CHUNK,
                    new NaruStreamChunkData(chunkKind(mode), fragment.toString(), chunkIndex.incrementAndGet(), null, null, end));
            if (end) {
                events.fragments.remove(mode);
                if (!streaming) events.emit(NaruTurnEvent.Kind.LOG, NMsg.ofC("%s", b));
            }
            if (delegate != null) delegate.writeStream(mode, fragment, end);
        }
        public void close() { closed = true; if (delegate != null) delegate.close(); }
        public void onHibernate() { if (delegate != null) delegate.onHibernate(); }
        private final AtomicInteger chunkIndex = new AtomicInteger();
        private static NaruChunkKind chunkKind(NaruLogMode mode) {
            return mode == NaruLogMode.MODEL_THINKING ? NaruChunkKind.THINKING : NaruChunkKind.ANSWER;
        }
    }
}
