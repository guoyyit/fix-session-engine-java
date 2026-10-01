package com.github.guoyyit.fixsessionenginejava.fix;

/**
 * How the session reacts to an invalid inbound message.
 */
public enum RejectPolicy {
    /** Send a Session-level Reject(35=3) and keep the session alive. */
    REJECT_AND_CONTINUE,
    /** Send a Reject followed by Logout and tear the session down. */
    REJECT_AND_DISCONNECT,
    /** Drop the frame silently without answering. */
    SILENT_DROP
}
