package com.github.guoyyit.fixsessionenginejava.fix;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@link TaskRunner} backed by a single-thread scheduled executor:
 * all protocol work and timers are serialized on one thread.
 */
public final class ThreadedTaskRunner implements TaskRunner {

    private final ScheduledExecutorService executor;

    public ThreadedTaskRunner() {
        this(Executors.newSingleThreadScheduledExecutor());
    }

    public ThreadedTaskRunner(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public void execute(Runnable task) {
        executor.execute(task);
    }

    @Override
    public Object schedule(Runnable task, Duration delay) {
        return executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void cancel(Object handle) {
        if (handle instanceof ScheduledFuture<?> future) {
            future.cancel(false);
        }
    }

    @Override
    public void shutdown() {
        executor.shutdownNow();
    }
}
