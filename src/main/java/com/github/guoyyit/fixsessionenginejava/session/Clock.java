package com.github.guoyyit.fixsessionenginejava.session;

import java.time.Duration;

/**
 * Time source used by the session engine, so heartbeat and timeout behavior
 * stays deterministic in tests with a scripted clock.
 */
public interface Clock {

    long nowMillis();

    default long after(Duration duration) {
        return nowMillis() + duration.toMillis();
    }

    static Clock system() {
        return System::currentTimeMillis;
    }
}
