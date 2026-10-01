package com.github.guoyyit.fixsessionenginejava.fix;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.TreeMap;

/**
 * File-backed {@link MessageStore}.
 * <p>
 * Three files live under {@code <directory>/<sanitized-session-id>/}:
 * <ul>
 *   <li>{@code session.id} - immutable identity block, verified on reopen;</li>
 *   <li>{@code outgoing.log} - length-prefixed frames kept for resend;</li>
 *   <li>{@code state.dat} - atomically written pointer state:
 *       nextSenderSeq, nextTargetSeq, highest delivered incoming seq.</li>
 * </ul>
 * State is fsynced and atomically renamed before a frame is handed to the
 * transport, so a crash can only leave an extra (unseen) outgoing message,
 * never a gap. Corruption of any file aborts recovery with
 * {@link RejectReason#UNRECOVERABLE_STORE}; the store never guesses a position.
 */
public final class FileMessageStore implements MessageStore {

    private static final String ID_FILE = "session.id";
    private static final String OUTGOING_FILE = "outgoing.log";
    private static final String STATE_FILE = "state.dat";
    private static final String TMP_SUFFIX = ".tmp";

    private final Path dir;
    private final SessionId sessionId;
    private final Object lock = new Object();
    private final TreeMap<Integer, byte[]> outgoing = new TreeMap<>();
    private int nextSenderSeq;
    private int nextTargetSeq;
    private int highestDeliveredIncoming;
    private boolean closed;

    public FileMessageStore(Path directory, SessionId sessionId) {
        this.dir = directory.resolve(sanitize(sessionId));
        this.sessionId = sessionId;
        open();
    }

    @Override
    public int nextSenderSeq() {
        synchronized (lock) {
            return nextSenderSeq;
        }
    }

    @Override
    public int nextTargetSeq() {
        synchronized (lock) {
            return nextTargetSeq;
        }
    }

    @Override
    public void saveOutgoing(int seqNum, byte[] frame) {
        synchronized (lock) {
            ensureOpen();
            if (seqNum != nextSenderSeq) {
                throw new FixException(RejectReason.INVALID_FIELD,
                        "outgoing seq " + seqNum + " != expected " + nextSenderSeq);
            }
            try {
                appendOutgoing(seqNum, frame);
            } catch (IOException e) {
                throw new FixException(RejectReason.UNRECOVERABLE_STORE,
                        "cannot append outgoing message: " + e.getMessage());
            }
            outgoing.put(seqNum, frame.clone());
            nextSenderSeq = seqNum + 1;
            persistState();
        }
    }

    @Override
    public byte[] loadOutgoing(int seqNum) {
        synchronized (lock) {
            byte[] frame = outgoing.get(seqNum);
            if (frame == null) {
                throw new FixException(RejectReason.UNRECOVERABLE_STORE,
                        "outgoing message " + seqNum + " is not available for resend");
            }
            return frame.clone();
        }
    }

    @Override
    public int lastOutgoingSeq() {
        synchronized (lock) {
            return outgoing.isEmpty() ? 0 : outgoing.lastKey();
        }
    }

    @Override
    public void setNextTargetSeq(int seq) {
        synchronized (lock) {
            ensureOpen();
            if (seq < nextTargetSeq) {
                throw new FixException(RejectReason.INVALID_FIELD,
                        "cannot move nextTargetSeq backwards: " + seq + " < " + nextTargetSeq);
            }
            nextTargetSeq = seq;
            persistState();
        }
    }

    @Override
    public void setNextSenderSeq(int seq) {
        synchronized (lock) {
            ensureOpen();
            nextSenderSeq = seq;
            persistState();
        }
    }

    @Override
    public void markDelivered(int incomingSeqNum) {
        synchronized (lock) {
            ensureOpen();
            if (incomingSeqNum > highestDeliveredIncoming) {
                highestDeliveredIncoming = incomingSeqNum;
                persistState();
            }
        }
    }

    @Override
    public boolean isDelivered(int incomingSeqNum) {
        synchronized (lock) {
            return incomingSeqNum <= highestDeliveredIncoming;
        }
    }

    @Override
    public void resetSequences(int newSenderSeq, int newTargetSeq) {
        synchronized (lock) {
            ensureOpen();
            if (newSenderSeq < 1 || newTargetSeq < 1) {
                throw new FixException(RejectReason.INVALID_FIELD,
                        "reset sequences must be >= 1");
            }
            nextSenderSeq = newSenderSeq;
            nextTargetSeq = newTargetSeq;
            highestDeliveredIncoming = 0;
            outgoing.clear();
            try {
                truncateOutgoing();
                persistState();
            } catch (IOException e) {
                throw new FixException(RejectReason.UNRECOVERABLE_STORE,
                        "cannot reset store: " + e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new FixException(RejectReason.UNRECOVERABLE_STORE, "store is closed");
        }
    }

    private void open() {
        try {
            Files.createDirectories(dir);
            initIdentityFile();
            loadOutgoingLog();
            loadState();
        } catch (FixException e) {
            throw e;
        } catch (Exception e) {
            throw new FixException(RejectReason.UNRECOVERABLE_STORE,
                    "cannot open message store: " + e.getMessage());
        }
    }

    private void initIdentityFile() throws IOException {
        Path idPath = dir.resolve(ID_FILE);
        String identity = sessionId.beginString() + '\n'
                + sessionId.senderCompId() + '\n'
                + sessionId.targetCompId() + '\n';
        if (Files.exists(idPath)) {
            String existing = Files.readString(idPath);
            if (!existing.equals(identity)) {
                throw new FixException(RejectReason.UNRECOVERABLE_STORE,
                        "stored session identity does not match: "
                                + existing.replace("\n", "|"));
            }
        } else {
            writeAtomic(idPath, identity.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void loadOutgoingLog() throws IOException {
        Path log = dir.resolve(OUTGOING_FILE);
        if (!Files.exists(log)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(log))) {
            while (true) {
                int seqNum;
                int length;
                try {
                    seqNum = in.readInt();
                    length = in.readInt();
                } catch (EOFException eof) {
                    break;
                }
                if (length < 0 || length > 64 * 1024 * 1024) {
                    throw new FixException(RejectReason.UNRECOVERABLE_STORE,
                            "corrupt outgoing record length " + length + " at seq " + seqNum);
                }
                byte[] frame = in.readNBytes(length);
                if (frame.length != length) {
                    throw new FixException(RejectReason.UNRECOVERABLE_STORE,
                            "truncated outgoing record at seq " + seqNum);
                }
                outgoing.put(seqNum, frame);
            }
        }
    }

    private void appendOutgoing(int seqNum, byte[] frame) throws IOException {
        Path log = dir.resolve(OUTGOING_FILE);
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(log,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)))) {
            out.writeInt(seqNum);
            out.writeInt(frame.length);
            out.write(frame);
            out.flush();
        }
    }

    private void truncateOutgoing() throws IOException {
        Path log = dir.resolve(OUTGOING_FILE);
        try (OutputStream out = Files.newOutputStream(log,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            out.flush();
        }
    }

    private void loadState() throws IOException {
        Path state = dir.resolve(STATE_FILE);
        if (!Files.exists(state)) {
            nextSenderSeq = 1;
            nextTargetSeq = 1;
            highestDeliveredIncoming = 0;
            persistState();
            return;
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(state)) {
            props.load(reader);
        }
        try {
            nextSenderSeq = Integer.parseInt(props.getProperty("nextSenderSeq"));
            nextTargetSeq = Integer.parseInt(props.getProperty("nextTargetSeq"));
            highestDeliveredIncoming =
                    Integer.parseInt(props.getProperty("highestDeliveredIncoming", "0"));
        } catch (NumberFormatException e) {
            throw new FixException(RejectReason.UNRECOVERABLE_STORE,
                    "corrupt state file: " + e.getMessage());
        }
        if (nextSenderSeq < 1 || nextTargetSeq < 1 || highestDeliveredIncoming < 0) {
            throw new FixException(RejectReason.UNRECOVERABLE_STORE,
                    "state file contains impossible sequence numbers");
        }
        for (int seq = 1; seq < nextSenderSeq; seq++) {
            if (!outgoing.containsKey(seq)) {
                throw new FixException(RejectReason.UNRECOVERABLE_STORE,
                        "outgoing log misses persisted seq " + seq);
            }
        }
    }

    private void persistState() {
        try {
            Properties props = new Properties();
            props.setProperty("nextSenderSeq", Integer.toString(nextSenderSeq));
            props.setProperty("nextTargetSeq", Integer.toString(nextTargetSeq));
            props.setProperty("highestDeliveredIncoming",
                    Integer.toString(highestDeliveredIncoming));
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (Writer writer = new OutputStreamWriter(buffer, StandardCharsets.UTF_8)) {
                props.store(writer, null);
            }
            writeAtomic(dir.resolve(STATE_FILE), buffer.toByteArray());
        } catch (IOException e) {
            throw new FixException(RejectReason.UNRECOVERABLE_STORE,
                    "cannot persist state: " + e.getMessage());
        }
    }

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + TMP_SUFFIX);
        try (FileChannel channel = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        Files.move(tmp, target,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String sanitize(SessionId id) {
        return (id.beginString() + "_" + id.senderCompId() + "_" + id.targetCompId())
                .replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
