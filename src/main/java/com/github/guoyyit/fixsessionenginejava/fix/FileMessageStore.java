package com.github.guoyyit.fixsessionenginejava.fix;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * File-system backed {@link MessageStore}.
 *
 * <p>Layout inside the session directory:
 * <ul>
 *   <li>{@code state.nv} - nextSender/nextTarget sequence numbers, replaced
 *       atomically (temp file + rename);</li>
 *   <li>{@code outgoing.log} - append-only hex-encoded records
 *       {@code <seqNum>|<hex raw message>}, fsynced before the state pointer
 *       is advanced;</li>
 *   <li>{@code incoming.log} - same format for received messages.</li>
 * </ul>
 *
 * <p>Crash windows are resolved by the recovery rule: records at or above the
 * persisted pointers are stale leftovers and pruned; records below the
 * next-sender pointer must form the gap-free range 1..nextSender-1, otherwise
 * recovery fails with {@link FixSessionException.Unrecoverable} and the session
 * refuses to continue automatically.
 */
public final class FileMessageStore implements MessageStore {

    private static final String STATE_FILE = "state.nv";
    private static final String OUTGOING_LOG = "outgoing.log";
    private static final String INCOMING_LOG = "incoming.log";

    private final Path directory;
    private final Path stateFile;
    private final Path outgoingFile;
    private final Path incomingFile;

    private long nextSenderSeqNum;
    private long nextTargetSeqNum;
    private boolean resetPending;

    private final TreeMap<Long, String> outgoing = new TreeMap<>();
    private final TreeMap<Long, String> incoming = new TreeMap<>();

    private boolean closed;

    public FileMessageStore(Path directory, SessionId sessionId) {
        this.directory = directory.resolve(sessionId.getSenderCompId()
                + "-" + sessionId.getTargetCompId());
        this.stateFile = this.directory.resolve(STATE_FILE);
        this.outgoingFile = this.directory.resolve(OUTGOING_LOG);
        this.incomingFile = this.directory.resolve(INCOMING_LOG);
        try {
            Files.createDirectories(this.directory);
            load();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open message store in " + this.directory, e);
        } catch (FixSessionException.Unrecoverable e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public synchronized long getNextSenderSeqNum() {
        return nextSenderSeqNum;
    }

    @Override
    public synchronized long getNextTargetSeqNum() {
        return nextTargetSeqNum;
    }

    @Override
    public synchronized void saveOutgoing(long seqNum, String rawMessage) {
        ensureOpen();
        if (seqNum != nextSenderSeqNum) {
            throw new IllegalStateException("outgoing sequence " + seqNum
                    + " does not follow nextSenderSeqNum " + nextSenderSeqNum);
        }
        appendRecord(outgoingFile, seqNum, rawMessage);
        outgoing.put(seqNum, rawMessage);
        nextSenderSeqNum = seqNum + 1;
        persistState();
    }

    @Override
    public synchronized void saveIncoming(long seqNum, String rawMessage) {
        ensureOpen();
        String existing = incoming.get(seqNum);
        if (existing != null && !existing.equals(rawMessage)) {
            throw new IllegalStateException(new FixSessionException.Unrecoverable(
                    "two different messages persisted for incoming sequence " + seqNum));
        }
        if (existing == null) {
            appendRecord(incomingFile, seqNum, rawMessage);
            incoming.put(seqNum, rawMessage);
        }
    }

    @Override
    public synchronized void setNextTargetSeqNum(long seqNum) {
        ensureOpen();
        if (seqNum < nextTargetSeqNum) {
            throw new IllegalStateException("cannot move nextTargetSeqNum backwards from "
                    + nextTargetSeqNum + " to " + seqNum);
        }
        if (seqNum == nextTargetSeqNum) {
            return;
        }
        nextTargetSeqNum = seqNum;
        persistState();
    }

    @Override
    public synchronized void resetSequenceNumbers(long nextSenderSeqNum, long nextTargetSeqNum) {
        ensureOpen();
        if (nextSenderSeqNum < 1 || nextTargetSeqNum < 1) {
            throw new IllegalArgumentException("sequence numbers must start at 1");
        }
        this.nextSenderSeqNum = nextSenderSeqNum;
        this.nextTargetSeqNum = nextTargetSeqNum;
        this.outgoing.clear();
        this.incoming.clear();
        this.resetPending = false;
        persistState();
        rewriteLog(outgoingFile);
        rewriteLog(incomingFile);
    }

    @Override
    public synchronized boolean isResetPending() {
        return resetPending;
    }

    @Override
    public synchronized void setResetPending(boolean pending) {
        ensureOpen();
        if (pending == resetPending) {
            return;
        }
        resetPending = pending;
        persistState();
    }

    @Override
    public synchronized List<String> loadOutgoing(long beginSeq, long endSeq) {
        ensureOpen();
        if (beginSeq < 1) {
            throw new IllegalArgumentException("beginSeq must be >= 1");
        }
        long upper = endSeq == 0 ? Long.MAX_VALUE : endSeq - 1;
        return new ArrayList<>(outgoing.subMap(beginSeq, true, upper, true).values());
    }

    @Override
    public synchronized void verify() throws FixSessionException.Unrecoverable {
        for (long seq = 1; seq < nextSenderSeqNum; seq++) {
            if (!outgoing.containsKey(seq)) {
                throw new FixSessionException.Unrecoverable(
                        "outgoing log gap at sequence " + seq + " (nextSender="
                                + nextSenderSeqNum + ") in " + directory);
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("message store is closed");
        }
    }

    private void load() throws IOException, FixSessionException.Unrecoverable {
        boolean stateExists = Files.exists(stateFile);
        boolean logsExist = Files.exists(outgoingFile) || Files.exists(incomingFile);
        if (!stateExists) {
            if (logsExist && logSize(outgoingFile) + logSize(incomingFile) > 0) {
                throw new FixSessionException.Unrecoverable(
                        "message logs exist but state file is missing in " + directory);
            }
            nextSenderSeqNum = 1;
            nextTargetSeqNum = 1;
            persistState();
            return;
        }

        List<String> lines = Files.readAllLines(stateFile, StandardCharsets.US_ASCII);
        for (String line : lines) {
            String[] kv = line.split("=", 2);
            if (kv.length != 2) {
                throw new FixSessionException.Unrecoverable("corrupt state file: " + line);
            }
            long value;
            try {
                value = Long.parseLong(kv[1]);
            } catch (NumberFormatException e) {
                throw new FixSessionException.Unrecoverable("corrupt state file: " + line, e);
            }
            if ("nextSenderSeqNum".equals(kv[0])) {
                nextSenderSeqNum = value;
            } else if ("nextTargetSeqNum".equals(kv[0])) {
                nextTargetSeqNum = value;
            } else if ("resetPending".equals(kv[0])) {
                resetPending = value == 1;
            }
        }
        if (nextSenderSeqNum < 1 || nextTargetSeqNum < 1) {
            throw new FixSessionException.Unrecoverable(
                    "corrupt state file: sequence numbers must be >= 1");
        }

        readLog(outgoingFile, outgoing);
        readLog(incomingFile, incoming);

        int beforeOutgoing = outgoing.size();
        outgoing.tailMap(nextSenderSeqNum, true).clear();
        int beforeIncoming = incoming.size();
        incoming.tailMap(nextTargetSeqNum, true).clear();

        verify();
        if (beforeOutgoing != outgoing.size()) {
            rewriteLog(outgoingFile);
        }
        if (beforeIncoming != incoming.size()) {
            rewriteLog(incomingFile);
        }
    }

    private static long logSize(Path file) throws IOException {
        return Files.exists(file) ? Files.size(file) : 0;
    }

    private void readLog(Path file, Map<Long, String> target)
            throws IOException, FixSessionException.Unrecoverable {
        if (!Files.exists(file)) {
            return;
        }
        long expectedSeq = 1;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.US_ASCII)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                int sep = line.indexOf('|');
                if (sep <= 0) {
                    throw new FixSessionException.Unrecoverable(
                            "corrupt record in " + file + ": " + line);
                }
                long seq;
                try {
                    seq = Long.parseLong(line.substring(0, sep));
                } catch (NumberFormatException e) {
                    throw new FixSessionException.Unrecoverable(
                            "corrupt sequence number in " + file, e);
                }
                if (seq != expectedSeq) {
                    throw new FixSessionException.Unrecoverable(
                            "log " + file + " not gap-free: expected " + expectedSeq
                                    + " got " + seq);
                }
                expectedSeq++;
                byte[] raw;
                try {
                    raw = hexDecode(line.substring(sep + 1));
                } catch (IllegalArgumentException e) {
                    throw new FixSessionException.Unrecoverable(
                            "corrupt payload in " + file + " at sequence " + seq, e);
                }
                target.put(seq, new String(raw, StandardCharsets.US_ASCII));
            }
        }
    }

    private void appendRecord(Path file, long seqNum, String rawMessage) {
        String line = seqNum + "|" + toHex(rawMessage.getBytes(StandardCharsets.US_ASCII))
                + System.lineSeparator();
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.US_ASCII)));
            channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot append to " + file, e);
        }
    }

    private void rewriteLog(Path file) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                for (Map.Entry<Long, String> entry :
                        (file.equals(outgoingFile) ? outgoing : incoming).entrySet()) {
                    String line = entry.getKey() + "|"
                            + toHex(entry.getValue().getBytes(StandardCharsets.US_ASCII))
                            + System.lineSeparator();
                    channel.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.US_ASCII)));
                }
                channel.force(true);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot rewrite " + file, e);
        }
    }

    private void persistState() {
        try {
            Path tmp = stateFile.resolveSibling(STATE_FILE + ".tmp");
            String content = "nextSenderSeqNum=" + nextSenderSeqNum + System.lineSeparator()
                    + "nextTargetSeqNum=" + nextTargetSeqNum + System.lineSeparator()
                    + "resetPending=" + (resetPending ? 1 : 0) + System.lineSeparator();
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.US_ASCII)));
                channel.force(true);
            }
            try {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot persist state in " + directory, e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] hexDecode(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("odd-length hex data");
        }
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("non-hex character");
            }
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }
}
