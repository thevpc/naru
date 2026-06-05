package net.thevpc.naru.impl.scheduler;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.routine.NaruTaskFrame;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.scheduler.NaruTaskSchedulerView;
import net.thevpc.naru.api.scheduler.*;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.impl.agent.NaruSessionImpl;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NCancelException;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class NaruSchedulerImpl implements NaruScheduler {

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final NaruSession session;
    private final int threadCount;

    // ready queues
    private final BlockingQueue<NaruTask> readyQueue;
    // scheduler-level hold
    // full = not held, empty = held
    private final Semaphore holdGate;

    // counts workers currently inside tick()
    private final AtomicInteger activeWorkers;

    // latch to synchronize hold() caller with workers
    private volatile CountDownLatch holdLatch;

    // mode state
//    private volatile NaruSchedulerMode mode;
    private volatile long throttleDelayMs;

    // lifecycle
    private volatile boolean stopped;
    private volatile boolean shutdownRequested;
    private volatile NaruSchedulerStatus status;

    private final List<Thread> workerThreads;
    private volatile boolean runningRetention;
    private volatile boolean runningBlockedDrain;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public NaruSchedulerImpl(NaruSession session, int threadCount) {
        this.session = session;
        this.threadCount = threadCount;
        this.readyQueue = new LinkedBlockingQueue<>();
        this.holdGate = new Semaphore(Integer.MAX_VALUE);
        this.activeWorkers = new AtomicInteger(0);
        this.throttleDelayMs = 500;
        this.stopped = false;
        this.shutdownRequested = false;
        this.status = NaruSchedulerStatus.IDLE;
        this.workerThreads = new CopyOnWriteArrayList<>();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void start() {
        status = NaruSchedulerStatus.RUNNING;
        for (int i = 0; i < threadCount; i++) {
            Thread t = new Thread(this::workerLoop, "naru-session[" + System.identityHashCode(session) + "]-worker-" + i);
            t.setDaemon(true);
            workerThreads.add(t);
            t.start();
        }
    }


    @Override
    public void awaitTermination() {
        runRetention();
        runBlockedDrain();
        for (Thread t : workerThreads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public void awaitTermination(long timeout) {
        long now = System.currentTimeMillis();
        long remain = timeout;
        for (Thread t : workerThreads) {
            try {
                t.join(remain);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            remain -= System.currentTimeMillis() - now;
            if (remain <= 0) {
                break;
            }
        }
    }

    @Override
    public void shutdown() {
        shutdownRequested = true;
        status = NaruSchedulerStatus.STOPPING;
        // unblock idle workers so they see shutdownRequested and exit
        for (int i = 0; i < threadCount; i++) {
            readyQueue.offer(NaruPoisonTask.INSTANCE);
        }
    }

    @Override
    public void kill() {
        stopped = true;
        status = NaruSchedulerStatus.KILLED;
        workerThreads.forEach(Thread::interrupt);
    }

    // -------------------------------------------------------------------------
    // Scheduler-level hold
    // -------------------------------------------------------------------------

    @Override
    public void hold() {
        // drain permits — workers block after finishing current tick
        holdGate.drainPermits();
        status = NaruSchedulerStatus.HELD;
        // wait synchronously until all active workers finish current tick
        int active = activeWorkers.get();
        if (active > 0) {
            holdLatch = new CountDownLatch(active);
            try {
                holdLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                holdLatch = null;
            }
        }
    }

    @Override
    public void resume() {
        status = NaruSchedulerStatus.RUNNING;
        holdGate.release(Integer.MAX_VALUE);
    }

    // -------------------------------------------------------------------------
    // Task-level control
    // -------------------------------------------------------------------------

    public void onUnhold(long tid) {
        NaruTask task = session.findTask(tid).orNull();
        if (task == null) return;
        switch (task.status()) {
            case READY: {
                // transition to READY and enqueue
                ((NaruTaskSchedulerView) task).status(NaruTaskStatus.READY);
                enqueue(task);
                break;
            }
            case BLOCKED_ON_INPUT:
            case BLOCKED_ON_EVENT: {
                // can't resume a blocked task — it must be unblocked
                // by its blocking condition (input arrives, event fires, child completes)
                // just clear the held flag, which we already did above
                break;
            }
            case RUNNING: {
                // already running — nothing to do
                break;
            }
            case DONE:
            case FAILED:
            case KILLED: {
                // terminal — cannot resume
                break;
            }
        }
    }

    public void onTerminated(long tid) {
        NaruTask task = session.findTask(tid).orNull();
        if (task == null) return;
        readyQueue.remove(task);
    }

    @Override
    public void tick(long tid) {
        NaruTask task = session.findTask(tid).orNull();
        if (task == null) return;
        //manual tick only works in help mode
        if (!task.isHeld() && status != NaruSchedulerStatus.HELD) return;
        tick(task);
    }

    private void tick(NaruTask task) {
        // force one tick on calling thread — only when task is held
        if (task == null) return;
        if (stopped || shutdownRequested) {
            return;
        }
        drainInbox(task);
        NaruTaskStatus os = task.status();
        try {
            ((NaruTaskSchedulerView) task).status(NaruTaskStatus.RUNNING);
            try {
                task.tick();
            } catch (NCancelException exit) {
                task.kill();
            } catch (Exception error) {
                task.log(NaruLogMode.SCHEDULER, NMsg.ofC("[%s] Task failed: %s", task.id(), error.getMessage()).asError());
                task.kill();
            }
        } finally {
            NaruTaskStatus ns = task.status();
            if (ns == NaruTaskStatus.RUNNING) {
                ((NaruTaskSchedulerView) task).status(os);
            }
        }
        requeue(task);
    }

    // -------------------------------------------------------------------------
    // Step operations
    // -------------------------------------------------------------------------

    @Override
    public void stepAny() {
        // release one permit on first ready task found
        NaruTask task = peekReady();
        if (task != null) {
            task.releaseStepPermit();
        }
    }

    @Override
    public void step(long... tids) {
        for (long tid : tids) {
            NaruTask task = session.findTask(tid).orNull();
            if (task != null) {
                task.releaseStepPermit();
            }
        }
    }

    @Override
    public void stepAll() {
        allReadyTasks().forEach(NaruTask::releaseStepPermit);
    }

    @Override
    public void throttleDelay(long ms) {
        this.throttleDelayMs = ms;
    }

    // -------------------------------------------------------------------------
    // Event dispatch
    // -------------------------------------------------------------------------

    private List<NaruEventSubscription> findMatchingSubscriptions(
            NaruTask task, NaruEvent event) {
        List<NaruEventSubscription> matching = new ArrayList<>();
        for (Map.Entry<String, NaruEventSubscription> entry
                : task.eventSubscriptions().entrySet()) {
            NaruEventSubscription sub = entry.getValue();
            if (sub.filter().test(event)) {
                matching.add(sub);
            }
        }
        return matching;
    }

    // -------------------------------------------------------------------------
    // Worker loop
    // -------------------------------------------------------------------------


    @Override
    public void runRetention() {
        if (runningRetention) {
            return;
        }
        try {
            runningRetention = true;
            if (!stopped && !shutdownRequested) {
                switch (status) {
                    case KILLED:
                    case STOPPING:
                    case STOPPED:
                        return;
                    case HELD:
                        return;
                }
                long now = System.currentTimeMillis();
                for (NaruEvent e : session.eventLog().scan(0, null)) {
                    if (e.nextCheckMillis() <= now && e.shouldDrop()) {
                        session.eventLog().drop(e.seq());
                    }
                }
            }
        } finally {
            runningRetention = false;
        }
    }

    @Override
    public void runBlockedDrain() {
        if (runningBlockedDrain) {
            return;
        }
        try {
            runningBlockedDrain = true;
            if (!stopped && !shutdownRequested) {
                switch (status) {
                    case KILLED:
                    case STOPPING:
                    case STOPPED:
                        return;
                    case HELD:
                        return;
                }
                for (NaruTask task : session.tasks()) {
                    if (task.status() == NaruTaskStatus.BLOCKED_ON_EVENT) {
                        drainInbox(task);
                        if (task.status() == NaruTaskStatus.READY) {
                            enqueue(task);
                        }
                    }
                }
            }
        } finally {
            runningBlockedDrain = false;
        }
    }


    private void workerLoop() {
        while (!stopped && !shutdownRequested) {
            NaruTask task = nextReadyTask();

            // poison pill or null means exit
            if (task == null || task == NaruPoisonTask.INSTANCE) break;

            activeWorkers.incrementAndGet();
            try {
                // 1. scheduler-level hold gate
                holdGate.acquire();
                if (stopped || shutdownRequested) break;

                // 2. mode-dependent clearance (step/throttle)
                awaitClearance(task);
                if (stopped) break;

                // 3. drain inbox before tick
                this.tick(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                int remaining = activeWorkers.decrementAndGet();
                // signal hold() caller if waiting
                CountDownLatch latch = holdLatch;
                if (latch != null) {
                    latch.countDown();
                }
                // restore hold gate permit if not held
                if (status != NaruSchedulerStatus.HELD) {
                    holdGate.release();
                }
                // if last worker done and stopping
                if (remaining == 0 && shutdownRequested) {
                    status = NaruSchedulerStatus.STOPPED;
                }
            }
            notifyTickDone(task);
        }

        onWorkerExit();
    }

    // -------------------------------------------------------------------------
    // Inbox drain
    // -------------------------------------------------------------------------

    private void drainInbox(NaruTask task) {

        // PHASE 1: pull from session log into inbox
        session.eventLog()
                .scan(task.inbox().watermark(),
                        event -> {
                            boolean targetMatch = event.target().test(task);
                            boolean notConsumed = !event.isConsumedBy(task.id());
//                            session.log(NaruLogMode.SCHEDULER,
//                                    NMsg.ofC("[%s] scan event[%s/%s] for task[%s]: target=%s notConsumed=%s",
//                                            task.id(), event.seq(), event.name(), task.id(), targetMatch, notConsumed));
                            return targetMatch && notConsumed;
                        }
                )
                .forEach(event -> {
                    event.markVisited(task.id());
                    task.inbox().push(event.seq());
                });
        Set<Long> toConsume = new LinkedHashSet<>();

        // PHASE 2: peek for /on subscriptions
        NaruEvent onMatch = task.inbox().peek(null);
        if (onMatch != null) {
            List<NaruEventSubscription> subs = findMatchingSubscriptions(task, onMatch);
            if (!subs.isEmpty()) {
                for (NaruEventSubscription sub : subs) {
                    injectRoutine(task, onMatch, sub);
                    if (sub.once()) {
                        task.eventSubscriptions().remove(onMatch.name());
                    }
                }
                toConsume.add(onMatch.seq());
            }
        }

        // PHASE 3: peek for /wait condition
        if (task.status() == NaruTaskStatus.BLOCKED_ON_EVENT) {
            NaruEventFilter filter = task.awaitFilter();
            if (filter != null) {
                NaruEvent waitMatch = task.inbox().peek(filter);
                if (waitMatch != null) {
                    task.addAwaitReceived(waitMatch);
                    task.awaitFilter(null);
                    task.frame().setLocalVar("event", waitMatch);
                    ((NaruTaskSchedulerView) task).status(NaruTaskStatus.READY);
                    toConsume.add(waitMatch.seq());
                }
            }
        }

// PHASE 4: consume
        for (long seq : toConsume) {
            task.inbox().consume(seq);  // remove from inbox
            session.eventLog().markConsumed(seq, task.id());
        }
    }

    private void injectRoutine(NaruTask task, NaruEvent event,
                               NaruEventSubscription sub) {
        NaruTaskFrame f = task.pushFrame(0, null, null, false);
        f.setLocalVar("event", event);
        task.addStatements(
                session.routineManager()
                        .routine(sub.routineName(), task).get()
                        .getIndexedLines().stream()
                        .map(x -> task.parseStatement(x.command()).orNull())
                        .filter(x -> x != null)
                        .toArray(NaruStatement[]::new)
        );
    }


    // -------------------------------------------------------------------------
    // Requeue
    // -------------------------------------------------------------------------

    public void requeue(NaruTask task) {
        if (task.isHeld()) return;
        switch (task.status()) {
            case READY:
                enqueue(task);
                break;
            case DONE:
            case FAILED: {
                // notify parent
                onTaskStatusChanged(task); // clear foreground if needed
                break;
            }
            case KILLED:
                onTaskStatusChanged(task); // clear foreground if needed
                break;
            case BLOCKED_ON_INPUT: {
                ((NaruSessionImpl) session).onInputRequested(task); // ← wake readline thread
                onTaskStatusChanged(task);
                break;
            }
            case BLOCKED_ON_EVENT:
                onTaskStatusChanged(task); // clear foreground if needed
                break;
        }
    }

    public void onTaskStatusChanged(NaruTask task) {
    }

    public void enqueue(NaruTask task) {
        if (readyQueue.contains(task)) {
            return;
        }
        readyQueue.offer(task);
    }

    // -------------------------------------------------------------------------
    // Clearance and notification
    // -------------------------------------------------------------------------

    private void awaitClearance(NaruTask task) throws InterruptedException {
        switch (task.schedulerMode()) {
            case AUTO:
                break;
            case STEP:
                task.acquireStepPermit();
                break;
            case THROTTLED:
                Thread.sleep(throttleDelayMs);
                break;
        }
    }

    private void notifyTickDone(NaruTask task) {
        switch (task.schedulerMode()) {
            case STEP:
            case THROTTLED:
                //((NaruSessionImpl)session).onTickDone(task);
                break;
            case AUTO:
                break;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private NaruTask nextReadyTask() {
        try {
            return readyQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private NaruTask peekReady() {
        return readyQueue.peek();
    }

    private List<NaruTask> allReadyTasks() {
        List<NaruTask> all = new ArrayList<>();
        all.addAll(readyQueue);
        return all;
    }

    private void onWorkerExit() {
        workerThreads.remove(Thread.currentThread());
        if (workerThreads.isEmpty()) {
            status = NaruSchedulerStatus.STOPPED;
        }
    }

    public void ready(long id) {
        NaruTask task = session.findTask(id).orNull();
        if (task == null) return;
        ((NaruTaskSchedulerView) task).status(NaruTaskStatus.READY);
        enqueue(task);
    }


    // -------------------------------------------------------------------------
    // Introspection
    // -------------------------------------------------------------------------

    @Override
    public NaruSchedulerStatus status() {
        return status;
    }

    @Override
    public int workerCount() {
        return threadCount;
    }

    @Override
    public int activeCount() {
        return activeWorkers.get();
    }

    @Override
    public int readyCount() {
        return readyQueue.size();
    }
}
