package net.thevpc.naru.impl.store;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * A session folder's write-ahead log: what makes its files durable without a disk sync per
 * file.
 * <p>
 * Every write to the session's files is first appended here ({@code journal.wal}), then made
 * to the file itself, neither one synced. {@link #sync()} forces this one file, and from then
 * on every write logged before it survives a power loss: if a file did not reach the disk,
 * {@link #recover} rewrites it from its record. A sync used to cost two disk syncs per file
 * written (the file, then its folder), about eight per message.
 * <p>
 * Records are {@code [length][crc32][op][path][content]}, the path relative to the session
 * folder so the log stays valid when the folder moves. Replay stops at the first record that
 * is short or fails its checksum: that record, and anything after it, was not synced, and is
 * exactly the window a sync policy accepts to lose.
 * <p>
 * The log is emptied by {@link #checkpoint()}, which syncs the files it covers; it runs when
 * the log grows past a size, and before anything changes the folder without going through
 * it (a move, a delete), so a later replay can never put older content back. A checkpoint
 * rotates the log ({@code journal.wal} becomes {@code journal.old.wal}) and syncs the files
 * outside the log's lock, so writes go on into a fresh log meanwhile: syncing hundreds of
 * files used to stall the turn that happened to cross the size. Replay reads the old log,
 * then the current one.
 */
final class NaruFileJournal {
    static final String FILE_NAME = "journal.wal";
    /** The log being checkpointed: its files are still being synced. */
    static final String OLD_FILE_NAME = "journal.old.wal";
    private static final byte WRITE = 'W';
    private static final byte DELETE = 'D';
    /** Past this, a sync also checkpoints, so recovery never replays much. */
    private static final long CHECKPOINT_BYTES = 4L << 20;

    private final Path sessionDir;
    private final Path file;
    private final Path oldFile;
    /** Files the current log covers, which its checkpoint must sync. */
    private final Set<Path> touched = new LinkedHashSet<>();
    /** Files the rotated log covers, until a checkpoint has synced them. */
    private final Set<Path> rotated = new LinkedHashSet<>();
    /** One checkpoint at a time; writes only need {@code this}. */
    private final Object checkpointLock = new Object();
    private long size;
    private boolean dirty;

    private NaruFileJournal(Path sessionDir) {
        this.sessionDir = sessionDir;
        this.file = sessionDir.resolve(FILE_NAME);
        this.oldFile = sessionDir.resolve(OLD_FILE_NAME);
    }

    /**
     * Opens a session folder's log, first replaying what a previous process left in it.
     * <p>
     * A log that is not empty when its folder is first opened was not checkpointed: whether
     * that process stopped cleanly or not, its records are replayed (each rewrite synced),
     * which is idempotent when the files are already right.
     */
    static NaruFileJournal open(Path sessionDir) {
        NaruFileJournal journal = new NaruFileJournal(sessionDir);
        try {
            journal.recover();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return journal;
    }

    /** Logs and makes a write; nothing is synced. */
    synchronized void write(Path target, byte[] content) throws IOException {
        append(WRITE, target, content);
        NaruFileIo.writeAtomicBytes(NaruFileIo.npath(target), content, false);
    }

    /** Logs and makes a delete; nothing is synced. */
    synchronized void delete(Path target) throws IOException {
        append(DELETE, target, new byte[0]);
        Files.deleteIfExists(target);
    }

    /** Whether something was logged since the last sync. */
    synchronized boolean isDirty() {
        return dirty;
    }

    /** Makes every write logged so far durable, with one disk sync. */
    synchronized void sync() throws IOException {
        if (dirty) {
            forceLog();
        }
    }

    /** Whether the log has grown enough that a {@link #checkpoint()} should run soon. */
    synchronized boolean wantsCheckpoint() {
        return size > CHECKPOINT_BYTES;
    }

    private void forceLog() throws IOException {
        if (Files.exists(file)) {
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
                ch.force(false);
            }
        }
        dirty = false;
    }

    /**
     * Syncs the files the log covers, then drops it: from here they no longer need it.
     * <p>
     * The log is rotated under its lock, which only takes a rename; the files are synced
     * outside it, while writes go on into a fresh log, and the rotated log is deleted last. A
     * crash before that leaves both logs, which replay in order.
     */
    void checkpoint() throws IOException {
        synchronized (checkpointLock) {
            Set<Path> covered;
            synchronized (this) {
                if (size > 0 || !touched.isEmpty()) {
                    // the records about to be rotated must be durable before anything relies on them
                    forceLog();
                    if (Files.exists(oldFile)) {
                        // an earlier checkpoint stopped half way: the old log keeps its records,
                        // and the current ones follow them
                        Files.write(oldFile, Files.readAllBytes(file), StandardOpenOption.APPEND);
                        try (FileChannel ch = FileChannel.open(oldFile, StandardOpenOption.WRITE)) {
                            ch.force(false);
                        }
                        Files.delete(file);
                    } else if (Files.exists(file)) {
                        Files.move(file, oldFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    }
                    NaruFileIo.syncDir(sessionDir);
                    rotated.addAll(touched);
                    touched.clear();
                    size = 0;
                }
                if (rotated.isEmpty() && !Files.exists(oldFile)) {
                    return;
                }
                covered = new LinkedHashSet<>(rotated);
            }
            syncFiles(covered);
            synchronized (this) {
                Files.deleteIfExists(oldFile);
                NaruFileIo.syncDir(sessionDir);
                rotated.removeAll(covered);
            }
        }
    }

    private static void syncFiles(Set<Path> files) throws IOException {
        Set<Path> dirs = new LinkedHashSet<>();
        for (Path p : files) {
            if (Files.isRegularFile(p)) {
                try (FileChannel ch = FileChannel.open(p, StandardOpenOption.WRITE)) {
                    ch.force(true);
                }
            }
            if (p.getParent() != null) {
                dirs.add(p.getParent());
            }
        }
        for (Path d : dirs) {
            NaruFileIo.syncDir(d);
        }
    }

    private void append(byte op, Path target, byte[] content) throws IOException {
        String relative = sessionDir.relativize(target).toString().replace('\\', '/');
        ByteArrayOutputStream payload = new ByteArrayOutputStream(content.length + relative.length() + 16);
        DataOutputStream out = new DataOutputStream(payload);
        out.writeByte(op);
        out.writeUTF(relative);
        out.writeInt(content.length);
        out.write(content);
        out.flush();
        byte[] body = payload.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(body);
        ByteBuffer record = ByteBuffer.allocate(8 + body.length);
        record.putInt(body.length).putInt((int) crc.getValue()).put(body).flip();
        Files.createDirectories(sessionDir);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            while (record.hasRemaining()) {
                ch.write(record);
            }
        }
        size += 8 + body.length;
        touched.add(target);
        dirty = true;
    }

    private void recover() throws IOException {
        // the rotated log is older than the current one: its records go first
        boolean replayed = replay(oldFile) | replay(file);
        if (!replayed) {
            return;
        }
        // every record that could be replayed now is, synced: the logs have nothing left to give
        Files.deleteIfExists(oldFile);
        if (Files.exists(file)) {
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
                ch.truncate(0);
                ch.force(true);
            }
        }
        NaruFileIo.syncDir(sessionDir);
    }

    /** Replays one log file; {@code false} when there was none, or nothing in it. */
    private boolean replay(Path log) throws IOException {
        if (!Files.isRegularFile(log) || Files.size(log) == 0) {
            return Files.exists(log);
        }
        try (InputStream raw = Files.newInputStream(log);
             DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw))) {
            while (true) {
                byte[] body;
                int expected;
                try {
                    int length = in.readInt();
                    expected = in.readInt();
                    if (length < 0 || length > (64 << 20)) {
                        break;
                    }
                    body = in.readNBytes(length);
                    if (body.length != length) {
                        break;
                    }
                } catch (EOFException e) {
                    break;
                }
                CRC32 crc = new CRC32();
                crc.update(body);
                if ((int) crc.getValue() != expected) {
                    break;
                }
                DataInputStream record = new DataInputStream(new java.io.ByteArrayInputStream(body));
                byte op = record.readByte();
                Path target = sessionDir.resolve(record.readUTF()).normalize();
                if (!target.startsWith(sessionDir)) {
                    // a record can only ever name a file of its own folder
                    break;
                }
                byte[] content = record.readNBytes(record.readInt());
                if (op == WRITE) {
                    NaruFileIo.writeAtomicBytes(NaruFileIo.npath(target), content, true);
                } else if (op == DELETE) {
                    Files.deleteIfExists(target);
                    if (target.getParent() != null) {
                        NaruFileIo.syncDir(target.getParent());
                    }
                }
            }
        }
        return true;
    }
}
