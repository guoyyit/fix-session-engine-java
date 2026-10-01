package com.github.guoyyit.fixsessionenginejava.fix;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Deterministic {@link TaskRunner}: submitted tasks run inline when the
 * test calls {@link #tick(Duration)} or {@link #pumpReady()}; scheduled tasks
 * run when virtual time reaches their deadline.
 */
public final class ManualTaskRunner implements TaskRunner {

    private static long handleSeq;

    private final List<Runnable> ready = new ArrayList<>();
    private final PriorityQueue<ScheduledTask> scheduled = new PriorityQueue<>();
    private long nowMillis;

    @Override
    public void execute(Runnable task) {
        ready.add(task);
    }

    @Override
    public Object schedule(Runnable task, Duration delay) {
        ScheduledTask scheduledTask =
                new ScheduledTask(++handleSeq, nowMillis + Math.max(0, delay.toMillis()), task);
        scheduled.add(scheduledTask);
        return scheduledTask;
    }

    @Override
    public void cancel(Object handle) {
        if (handle instanceof ScheduledTask task) {
            task.cancelled = true;
        }
    }

    @Override
    public void shutdown() {
        ready.clear();
        scheduled.clear();
    }

    public long getNowMillis() {
        return nowMillis;
    }

    /** Run every queued task, including tasks queued while running. */
    public void runReady() {
        while (runReadyOnce()) {
            // drain
        }
    }

    /**
     * Run queued tasks plus scheduled tasks due at/before the current virtual
     * instant, including work they enqueue. Returns true when anything ran.
     */
    public boolean pumpReady() {
        boolean ran = false;
        boolean changed = true;
        while (changed) {
            changed = runReadyOnce();
            ran |= changed;
            while (!scheduled.isEmpty()) {
                ScheduledTask next = scheduled.peek();
                if (next.cancelled) {
                    scheduled.poll();
                    changed = true;
                    continue;
                }
                if (next.deadlineMillis > nowMillis) {
                    break;
                }
                scheduled.poll().runnable.run();
                changed = true;
                ran = true;
            }
        }
        return ran;
    }

    /** Advance virtual time by {@code window}, running everything due in it. */
    public void tick(Duration window) {
        nowMillis += window.toMillis();
        pumpReady();
    }

    /** Number of tasks waiting to run (queued or already due). */
    public int snapshotReadyCount() {
        int count = ready.size();
        for (ScheduledTask task : scheduled) {
            if (!task.cancelled && task.deadlineMillis <= nowMillis) {
                count++;
            }
        }
        return count;
    }

    private boolean runReadyOnce() {
        if (ready.isEmpty()) {
            return false;
        }
        List<Runnable> batch = new ArrayList<>(ready);
        ready.clear();
        batch.forEach(Runnable::run);
        return true;
    }

    private static final class ScheduledTask implements Comparable<ScheduledTask> {
        private final long id;
        private final long deadlineMillis;
        private final Runnable runnable;
        private boolean cancelled;

        private ScheduledTask(long id, long deadlineMillis, Runnable runnable) {
            this.id = id;
            this.deadlineMillis = deadlineMillis;
            this.runnable = runnable;
        }

        @Override
        public int compareTo(ScheduledTask other) {
            int byDeadline = Long.compare(deadlineMillis, other.deadlineMillis);
            return byDeadline != 0 ? byDeadline : Long.compare(id, other.id);
        }
    }
}
