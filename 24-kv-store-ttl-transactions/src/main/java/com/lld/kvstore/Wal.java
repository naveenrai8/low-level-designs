package com.lld.kvstore;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * A write-ahead log (WAL): before a commit changes memory, its changes are appended here, so a crashed process can rebuild its state by replaying the log.
 *
 * <p>One record is one whole commit (a batch), so a transaction is all-or-nothing in the log. Record layout: {@code [int length][int crc32][payload]}. A crash can leave the last record half-written (a "torn" record); replay detects it by a short length or a bad CRC and stops there.
 *
 * <p>Limits of this seam: it only flushes (no fsync), keys and values must each fit in 64 KB (a {@code writeUTF} limit), and there is no checkpointing. See the README for the production delta.
 */
public final class Wal {

    private final OutputStream out;
    private boolean failed;

    /**
     * The result of reading a log.
     *
     * @param batches every complete, valid commit in order
     * @param validBytes how many leading bytes were valid; truncate the file to this before appending again, or new records would hide behind a torn one
     */
    public record Replay(List<List<Mutation>> batches, int validBytes) {
    }

    /** Creates a log that appends to the given stream. */
    public Wal(OutputStream out) {
        this.out = out;
    }

    /** Appends one commit as a single framed record and flushes. Throws {@link UncheckedIOException} if the stream fails, and after that every append throws {@link IllegalStateException} (fail-stop): torn bytes may sit in the file, so acknowledging later commits would lose them on replay. */
    public synchronized void append(List<Mutation> batch) {
        if (failed) {
            throw new IllegalStateException("log is fail-stop after an earlier write error; recover from the log file");
        }
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(body);
            data.writeInt(batch.size());
            for (Mutation m : batch) {
                data.writeUTF(m.key());
                data.writeBoolean(m.value() != null);
                data.writeUTF(m.value() == null ? "" : m.value());
                data.writeLong(m.deadlineMs());
            }
            byte[] payload = body.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(payload);
            DataOutputStream frame = new DataOutputStream(out);
            frame.writeInt(payload.length);
            frame.writeInt((int) crc.getValue());
            frame.write(payload);
            frame.flush();
        } catch (IOException e) {
            failed = true;
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            failed = true;
            throw e;
        }
    }

    /** Reads a log from bytes. Stops quietly at the first torn or corrupt record and reports how many bytes were good. */
    public static Replay replay(byte[] log) {
        ByteBuffer buf = ByteBuffer.wrap(log);
        List<List<Mutation>> batches = new ArrayList<>();
        int good = 0;
        while (buf.remaining() >= 8) {
            int length = buf.getInt();
            int expectedCrc = buf.getInt();
            if (length < 0 || length > buf.remaining()) {
                break;
            }
            byte[] payload = new byte[length];
            buf.get(payload);
            CRC32 crc = new CRC32();
            crc.update(payload);
            if ((int) crc.getValue() != expectedCrc) {
                break;
            }
            List<Mutation> batch = parse(payload);
            if (batch == null) {
                break;
            }
            batches.add(batch);
            good = buf.position();
        }
        return new Replay(batches, good);
    }

    /** Decodes one payload into its mutations, or returns null if the payload is malformed. */
    private static List<Mutation> parse(byte[] payload) {
        try {
            DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(payload));
            int n = in.readInt();
            List<Mutation> batch = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String key = in.readUTF();
                boolean hasValue = in.readBoolean();
                String value = in.readUTF();
                long deadline = in.readLong();
                batch.add(new Mutation(key, hasValue ? value : null, deadline));
            }
            return batch;
        } catch (IOException e) {
            return null;
        }
    }
}
