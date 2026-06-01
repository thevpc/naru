package net.thevpc.naru.impl.scheduler;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.agent.NaruTaskSchedulerView;
import net.thevpc.naru.api.scheduler.*;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.impl.agent.NaruSessionImpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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


    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public NaruSchedulerImpl(NaruSession session, int threadCount) {
        this.session = session;
        this.threadCount = threadCount;
        this.readyQueue = new LinkedBlockingQueue<>();
        this.holdGate = new Semaphore(Integer.MAX_VALUE);
        this.activeWorkers = new AtomicInteger(0);
//        this.mode = NaruSchedulerMode.AUTO;
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
            Thread t = new Thread(this::workerLoop, "naru-worker-" + i);
            t.setDaemon(true);
            workerThreads.add(t);
            t.start();
        }
    }

    @Override
    public void awaitTermination() {
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
            case BLOCKED_ON_EVENT:
            case BLOCKED_ON_TASK: {
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

    public void onKill(long tid) {
        NaruTask task = session.findTask(tid).orNull();
        if (task == null) return;
        task.kill();
        ((NaruTaskSchedulerView) task).status(NaruTaskStatus.KILLED);
        readyQueue.remove(task);
        onTaskStatusChanged(task);
    }

    @Override
    public void tick(long tid) {
        // force one tick on calling thread — only when task is held
        NaruTask task = session.findTask(tid).orNull();
        if (task == null) return;
        if (!task.isHeld() && status != NaruSchedulerStatus.HELD) return;
        drainInbox(task);
        task.tick();
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

    // -------------------------------------------------------------------------
    // Mode
    // -------------------------------------------------------------------------

//    @Override
//    public NaruSchedulerMode mode() {
//        return mode;
//    }
//
//    @Override
//    public void mode(NaruSchedulerMode mode) {
//        this.mode = mode;
//        if (mode == NaruSchedulerMode.AUTO) {
//            // release all step permits so no task stays stuck
//            session.tasks().forEach(t -> t.releaseStepPermit());
//        }
//    }

    @Override
    public void throttleDelay(long ms) {
        this.throttleDelayMs = ms;
    }

    // -------------------------------------------------------------------------
    // Event dispatch
    // -------------------------------------------------------------------------

    @Override
    public void dispatch(NaruEvent event) {
        for (NaruTask task : session.tasks()) {
            dispatchToTask(task, event);
        }
    }

    private void dispatchToTask(NaruTask task, NaruEvent event) {
        switch (task.status()) {
            case BLOCKED_ON_EVENT: {
                NaruEventFilter filter = task.awaitFilter();
                if (filter != null
                        && filter.matches(event, task.awaitReceived())) {
                    task.awaitReceived().put(event.type(), event);
                    task.addInbox(event);
                    if (filter.satisfied(task.awaitReceived())) {
                        // unblock
                        task.awaitFilter(null);
                        task.awaitReceived().clear();
                        ((NaruTaskImpl) task).status(NaruTaskStatus.READY);
                        if (!task.isHeld()) {
                            enqueue(task);
                        }
                    }
                } else {
                    // not matching await — queue for later
                    task.addInbox(event);
                }
                break;
            }

            case READY:
            case RUNNING: {
                // check subscriptions
                NaruEventSubscription sub = findMatchingSubscription(task, event);
                if (sub != null) {
                    task.addInbox(event);
                    if (sub.once()) {
                        task.eventSubscriptions().remove(event.type());
                    }
                }
                // no subscription — silently discard
                break;
            }

            case BLOCKED_ON_INPUT:
            case BLOCKED_ON_TASK: {
                // queue — deliver on resume
                NaruEventSubscription sub = findMatchingSubscription(task, event);
                if (sub != null || task.awaitFilter() != null) {
                    task.addInbox(event);
                }
                break;
            }

            case DONE:
            case FAILED:
            case KILLED: {
                // terminal — discard
                break;
            }
        }
    }

    private NaruEventSubscription findMatchingSubscription(
            NaruTask task, NaruEvent event) {
        for (Map.Entry<String, NaruEventSubscription> entry
                : task.eventSubscriptions().entrySet()) {
            NaruEventSubscription sub = entry.getValue();
            if (sub.filter().matches(event, Collections.emptyMap())) {
                return sub;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Worker loop
    // -------------------------------------------------------------------------

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
                drainInbox(task);

                // 4. execute tick
                task.tick();

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

            // 5. requeue based on new status
            requeue(task);

            // 6. notify for STEP/THROTTLED observability
            notifyTickDone(task);
        }

        onWorkerExit();
    }

    // -------------------------------------------------------------------------
    // Inbox drain
    // -------------------------------------------------------------------------

    private void drainInbox(NaruTask task) {
        NaruEvent event;
        while ((event = task.pollInbox()) != null) {
            NaruEventSubscription sub = findMatchingSubscription(task, event);
            if (sub != null) {
                task.setTaskProperty("$event", event.type());
                task.setTaskProperty("$eventData", event.data());
                task.pushFrame();
                task.addStatements(
                        task.session().routineManager().routine(sub.routineName()).get().getIndexedLines()
                                .stream().map(x -> task.parseStatement(x.command()).orNull())
                                .filter(x -> x != null)
                                .toArray(NaruStatement[]::new)
                );
                if (sub.once()) {
                    task.eventSubscriptions().remove(event.type());
                }
            }
            // no subscription — discard (already served its purpose
            // for BLOCKED_ON_EVENT unblocking via dispatch)
        }
    }

    // -------------------------------------------------------------------------
    // Requeue
    // -------------------------------------------------------------------------

    private void requeue(NaruTask task) {
        if (task.isHeld()) return;
        switch (task.status()) {
            case READY:
                enqueue(task);
                break;
            case DONE:
            case FAILED: {
                // notify parent
                NaruTask parent = task.parent().orNull();
                if (parent != null) {
                    dispatch(new NaruEvent(
                            task.status() == NaruTaskStatus.DONE
                                    ? "task.done" : "task.failed",
                            task.getReturnResult(),
                            task.id()
                    ));
                }
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
            case BLOCKED_ON_TASK:
                onTaskStatusChanged(task); // clear foreground if needed
                break;
        }
    }

    public void onTaskStatusChanged(NaruTask task) {
        // only session-specific concerns
        switch (task.status()) {
            case DONE:
            case FAILED:
            case KILLED:
                if (session.foregroundTaskId() == task.id()) {
                    session.foregroundTaskId(-1);
                }
                break;
        }
    }

    public void enqueue(NaruTask task) {
        if(readyQueue.contains(task)){
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