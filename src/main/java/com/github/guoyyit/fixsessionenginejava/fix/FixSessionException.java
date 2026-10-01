package com.github.guoyyit.fixsessionenginejava.fix;

/**
 * Base class for session engine failures.
 */
public class FixSessionException extends Exception {

    public FixSessionException(String message) {
        super(message);
    }

    public FixSessionException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Raised for an illegal state transition. */
    public static class IllegalTransition extends FixSessionException {
        public IllegalTransition(String message) {
            super(message);
        }
    }

    /** Raised when persisted state cannot be recovered. */
    public static class Unrecoverable extends FixSessionException {
        public Unrecoverable(String message) {
            super(message);
        }

        public Unrecoverable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Raised when a bounded resource (outbound queue, retries) overflows. */
    public static class QueueOverflow extends FixSessionException {
        public QueueOverflow(String message) {
            super(message);
        }
    }
}
