package com.github.guoyyit.fixsessionenginejava.fix;

/**
 * Lifecycle states of a FIX session.
 */
public enum SessionState {
    /** Not connected; a session may be (re)started from this state. */
    DISCONNECTED,
    /** Initiator: Logon sent, waiting for Logon reply. */
    LOGON_SENT,
    /** Acceptor: Logon received, reply to be sent / resync in progress. */
    LOGON_RECEIVED,
    /** Logon handshake complete; catching up sequence gaps. */
    RESYNC,
    /** Fully established; application messages flow. */
    ACTIVE,
    /** Local side requested a confirmed sequence reset. */
    RESET_REQUEST_SENT,
    /** Remote side requested a confirmed sequence reset. */
    RESET_REQUEST_RECEIVED,
    /** Reset applied locally; waiting for the peer to apply it too. */
    RESET_PENDING_PEER,
    /** Logout(5) sent, waiting for acknowledgement. */
    LOGOUT_SENT,
    /** Logout(5) received, reply to be sent. */
    LOGOUT_RECEIVED
}
