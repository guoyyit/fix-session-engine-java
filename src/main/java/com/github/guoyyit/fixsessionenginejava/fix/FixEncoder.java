package com.github.guoyyit.fixsessionenginejava.fix;

/**
 * Encodes {@link FixMessage} instances into raw FIX wire bytes, filling
 * BeginString(8), BodyLength(9) and CheckSum(10) automatically.
 */
public final class FixEncoder {
    public static final byte SOH = 0x01;

    private final String beginString;

    public FixEncoder() {
        this("FIX.4.4");
    }

    public FixEncoder(String beginString) {
        this.beginString = beginString;
    }

    public byte[] encode(FixMessage message) {
        byte[] body = encodeBody(message);
        String header = "8=" + beginString + (char) SOH
                + "9=" + body.length + (char) SOH;
        byte[] headerBytes = header.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String checkSum = String.format("%03d", checksum(headerBytes, body));
        byte[] trailer = ("10=" + checkSum + (char) SOH).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] out = new byte[headerBytes.length + body.length + trailer.length];
        System.arraycopy(headerBytes, 0, out, 0, headerBytes.length);
        System.arraycopy(body, 0, out, headerBytes.length, body.length);
        System.arraycopy(trailer, 0, out, headerBytes.length + body.length, trailer.length);
        return out;
    }

    private byte[] encodeBody(FixMessage message) {
        StringBuilder builder = new StringBuilder();
        message.fields().forEach((tag, value) -> {
            if (tag == Tag.BEGIN_STRING || tag == Tag.BODY_LENGTH || tag == Tag.CHECK_SUM) {
                return;
            }
            builder.append(tag).append('=').append(value).append((char) SOH);
        });
        return builder.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * FIX checksum: sum of every byte from {@code 8=} up to (but not
     * including) the {@code 10=} field, mod 256.
     */
    static int checksum(byte[]... parts) {
        int sum = 0;
        for (byte[] part : parts) {
            for (byte b : part) {
                sum += b & 0xFF;
            }
        }
        return sum % 256;
    }
}
