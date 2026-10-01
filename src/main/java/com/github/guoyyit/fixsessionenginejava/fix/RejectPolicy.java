package com.github.guoyyit.fixsessionenginejava.fix;

/**
 * How the engine reacts to malformed or unrecognized incoming messages.
 */
public enum RejectPolicy {
    /** Answer with a SessionReject(3) and keep the session alive. */
    REJECT,
    /** Discard the offending message without notifying the peer. */
    IGNORE,
    /** Send a Logout(5) and terminate the connection. */
    DISCONNECT
}
