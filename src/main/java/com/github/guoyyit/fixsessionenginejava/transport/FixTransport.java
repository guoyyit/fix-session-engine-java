package com.github.guoyyit.fixsessionenginejava.transport;

/**
 * Byte-oriented transport underneath the FIX session. Framing (8=FIX...10=xxx)
 * is handled by the session layer; the transport moves complete frames.
 */
public interface FixTransport {

    void send(byte[] frame) throws InterruptedException;

    byte[] receive() throws InterruptedException;

    boolean isOpen();

    void close();
}
