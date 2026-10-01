package com.github.guoyyit.fixsessionenginejava.fix;

/**
 * FIX 4.4 session message types.
 */
public final class MsgType {

    private MsgType() {
    }

    public static final String HEARTBEAT = "0";
    public static final String TEST_REQUEST = "1";
    public static final String RESEND_REQUEST = "2";
    public static final String REJECT = "3";
    public static final String SEQUENCE_RESET = "4";
    public static final String LOGOUT = "5";
    public static final String LOGON = "A";
}
