package com.github.guoyyit.fixsessionenginejava.fix;

/**
 * Bidirectional ordered byte stream carrying complete FIX messages
 * (strings terminated by the SOH character).
 */
public interface Transport extends AutoCloseable {

    /** Send one complete message; non-blocking up to implementation backpressure. */
    void send(String message) throws Exception;

    /** Block until the next complete message arrives or the transport closes. */
    String receive() throws Exception;

    /** Whether the transport is still open. */
    boolean isOpen();

    @Override
    void close();
}
