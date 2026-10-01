package com.github.guoyyit.fixsessionenginejava.session;

import com.github.guoyyit.fixsessionenginejava.fix.FixMessage;

/**
 * Callbacks from the session engine toward application-layer users.
 */
public interface FixApplication {

    void onLogon();

    void onLogout();

    /**
     * Called exactly once per accepted inbound sequence number.
     */
    void onMessage(FixMessage message);

    /**
     * Called when the peer sends a Session-level Reject(35=3). Default: ignore.
     */
    default void onSessionReject(FixMessage reject) {
    }
}
