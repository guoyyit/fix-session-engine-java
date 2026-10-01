package com.github.guoyyit.fixsessionenginejava.fix;

/**
 * Top-level session state machine states.
 */
public enum SessionState {
    /** Constructed, not connected. */
    DISCONNECTED,
    /** Initiator only: Logon sent, waiting for the peer Logon. */
    LOGON_SENT,
    /** Logon exchanged; normal message exchange. */
    ACTIVE,
    /** Logout sent, waiting for peer Logout. */
    LOGOUT_SENT,
    /** Session permanently closed. */
    CLOSED
}
