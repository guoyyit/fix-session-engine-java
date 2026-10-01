package com.github.guoyyit.fixsessionenginejava.fix;

import java.time.Duration;

/**
 * Execution abstraction for the protocol thread and timer tasks,
 * so tests can run sessions deterministically without wall-clock waiting.
 */
public interface TaskRunner {

    /** Run a task on the runner's (single) protocol thread. */
    void execute(Runnable task);

    /** Schedule a one-shot task; returns a handle usable with {@link #cancel}. */
    Object schedule(Runnable task, Duration delay);

    /** Cancel a previously scheduled task. */
    void cancel(Object handle);

    /** Shut the runner down. */
    void shutdown();
}
