package com.github.guoyyit.fixsessionenginejava.fix;

/**
 * Callbacks from the session engine to the trading application.
 *
 * <p>All callbacks are invoked from the session's protocol thread. Implementations
 * must not block: a slow {@code fromApp} consumer is decoupled from the sending
 * path but it delays processing of subsequent incoming messages.
 */
public interface Application {

    /** Called once the session reaches {@link SessionState#ACTIVE}. */
    default void onLogon(SessionId sessionId) {
    }

    /** Called when the session is terminated by logout or transport loss. */
    default void onLogout(SessionId sessionId) {
    }

    /** Delivers an inbound application message exactly once per sequence number. */
    default void fromApp(SessionId sessionId, FixMessage message) {
    }

    /** Called when an administrative message cannot be delivered (e.g. rejected). */
    default void onError(SessionId sessionId, String message) {
    }
}
