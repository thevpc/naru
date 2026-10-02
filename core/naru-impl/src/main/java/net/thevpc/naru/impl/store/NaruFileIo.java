package net.thevpc.naru.impl.store;

import net.thevpc.nuts.io.NPath;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * File primitives the store depends on, in one place.
 *
 * <p>Every write here is atomic: content goes to a sibling temporary file, is flushed to
 * the disk, and only then replaces the target. That is not fastidiousness about tidiness.
 * A session is written after every statement of every turn, which in practice means a
 * process is killed mid-write eventually -- a closed laptop, an OOM, a {@code kill -9} --
 * and without this the file left behind is a truncated TSON document that will not parse.
 * Since that file is the only record of what happened, a torn write is a lost turn.
 *
 * <p>Named to draw attention to what it does <em>not</em> do: it does not write to a
 * temporary file and hope. It fsyncs, because a rename that reaches the disk before its
 * contents do is atomic against a crash of the <em>process</em> and meaningless against a
 * crash of the <em>machine</em>.
 */
final class NaruFileIo {

    private NaruFileIo() {
    }

    static NPath npath(Path path) {
        return NPath.of(path);
    }

    static Path path(NPath npath) {
        net.thevpc.nuts.util.NOptional<Path> p = npath.toPath();
        if (!p.isPresent()) {
            throw new IllegalStateException("not a local file path: " + npath);
        }
        return p.get();
    }

    /**
     * Replaces {@code target} with {@code content}, or leaves it exactly as it was.
     *
     * @throws IOException if the content could not be durably written; the caller is
     *                     expected to propagate this rather than continue, because a store
     *                     that swallows a failed write is worse than one that stops
     */
    static void writeAtomic(NPath target, String content) throws IOException {
        Path dst = path(target);
        Path dir = dst.getParent();
        if (dir == null) {
            throw new IOException("cannot write to a filesystem root: " + target);
        }
        Files.createDirectories(dir);
        // the temporary is a sibling, so the rename below stays on one filesystem and can
        // therefore be atomic
        Path tmp = dir.resolve("." + dst.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            writeAndSync(tmp, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // some filesystems and some containers refuse ATOMIC_MOVE; REPLACE_EXISTING
                // is still better than writing in place, just not crash-atomic
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            syncDir(dir);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Writes bytes and does not return until they are on the disk.
     *
     * <p>Through a {@link java.nio.channels.FileChannel} rather than an
     * {@code OutputStream}, because an {@code OutputStream} cannot be fsynced -- the only
     * implementation the JDK offers for that is {@code FileOutputStream}, and asking
     * whether the stream "is" one is a check that quietly succeeds as false under a
     * different implementation and silently skips the sync that this whole class exists to
     * perform.
     */
    private static void writeAndSync(Path target, byte[] bytes) throws IOException {
        try (java.nio.channels.FileChannel ch =
                     java.nio.channels.FileChannel.open(target,
                             java.nio.file.StandardOpenOption.WRITE,
                             java.nio.file.StandardOpenOption.CREATE,
                             java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                ch.write(buffer);
            }
            ch.force(true);
        }
    }

    static String readString(NPath source) throws IOException {
        return new String(Files.readAllBytes(path(source)), StandardCharsets.UTF_8);
    }

    /**
     * {@link #readString} with the checked exception turned into an unchecked one, for
     * callers that already sit inside a method that cannot throw it.
     */
    static String readStringOrThrow(NPath source) {
        try {
            return readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] readBytes(NPath source) throws IOException {
        return Files.readAllBytes(path(source));
    }

    /**
     * The byte-for-byte counterpart of {@link #writeAtomic}.
     *
     * <p>Separate from it, and not routed through it, because a round trip through
     * {@code String} is not a round trip: bytes that are not valid UTF-8 come back as
     * replacement characters, which for a binary blob is silent corruption.
     */
    static void writeAtomicBytes(NPath target, byte[] content) throws IOException {
        Path dst = path(target);
        Path dir = dst.getParent();
        if (dir == null) {
            throw new IOException("cannot write to a filesystem root: " + target);
        }
        Files.createDirectories(dir);
        Path tmp = dir.resolve("." + dst.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            writeAndSync(tmp, content);
            try {
                Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            syncDir(dir);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Flushes a directory entry so a rename survives power loss. Not all platforms allow
     * opening a directory for this, and failing to fsync is not a reason to fail a write.
     */
    static void syncDir(Path dir) {
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(dir,
                java.nio.file.StandardOpenOption.READ)) {
            ch.force(true);
        } catch (Exception ignored) {
            // Windows will not open a directory at all; macOS and Linux will
        }
    }

    static void deleteIfExists(NPath target) throws IOException {
        Files.deleteIfExists(path(target));
    }

    /**
     * Deletes a file, treating a failure to do so as a warning rather than an error.
     *
     * <p>For cleanup after the real work is already committed. An unreadable leftover is
     * untidy but harmless -- it is either unreferenced, and collected later, or it is
     * referenced, in which case the next save overwrites it -- while a thrown exception
     * would report a save as failed after it had in fact succeeded.
     */
    static void deleteQuietly(NPath target) {
        try {
            Files.deleteIfExists(path(target));
        } catch (IOException ignored) {
            // deliberately ignored: see above
        }
    }

    static void deleteTreeIfExists(NPath target) throws IOException {
        deleteTree(path(target));
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(p);
            return;
        }
        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Moves a directory, atomically if the filesystem allows it.
     *
     * <p>Tries {@code ATOMIC_MOVE} first, which is a single directory-entry change: the
     * session appears at the destination complete, or not at all. A cross-filesystem move
     * cannot be atomic by definition, so it falls back to copy-then-delete and the caller
     * has to treat that case as non-atomic -- which is exactly why the copy goes to a
     * hidden sibling and is renamed into place only once every byte is there.
     */
    static boolean moveTree(NPath from, NPath to) throws IOException {
        Path src = path(from);
        Path dst = path(to);
        Files.createDirectories(dst.getParent());
        if (Files.exists(dst, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.FileAlreadyExistsException(dst.toString());
        }
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
            syncDir(dst.getParent());
            return true;
        } catch (java.io.IOException atomicFailed) {
            if (Files.exists(dst, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new java.nio.file.FileAlreadyExistsException(dst.toString());
            }
            return copyTreeThenDelete(src, dst);
        }
    }

    /**
     * The non-atomic move: copy to a hidden staging directory, then rename into place.
     *
     * <p>Copying straight to the destination would be wrong in a way that only shows up
     * when it is too late. A crash halfway through would leave a real, plausible-looking
     * session directory that is missing part of its history, and the next start would
     * happily load it. Copying to {@code .<name>.moving} means an interrupted move leaves
     * something the catalog skips, and the real destination only ever appears complete.
     *
     * <p>Returns false, meaning "the move happened but was not atomic". The source is
     * deleted only after the destination rename succeeds, so a failure anywhere before
     * that leaves the session exactly where it was.
     */
    private static boolean copyTreeThenDelete(Path src, Path dst) throws IOException {
        Path parent = dst.getParent();
        Path staging = parent.resolve("." + dst.getFileName() + ".moving");
        deleteTree(staging);
        try {
            copyTree(npath(src), npath(staging));
            try {
                Files.move(staging, dst);
            } catch (java.io.IOException renameFailed) {
                // a plain move within one directory is not expected to fail here, and if it
                // does, a concurrent writer is the likely cause: theirs is a legitimate
                // destination and overwriting it would destroy their work
                if (Files.exists(dst, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw new java.nio.file.FileAlreadyExistsException(dst.toString());
                }
                throw renameFailed;
            }
            syncDir(parent);
        } catch (IOException e) {
            deleteTree(staging);
            throw e;
        }
        deleteTree(src);
        syncDir(src.getParent());
        return false;
    }

    static void copyTree(NPath from, NPath to) throws IOException {
        Path src = path(from);
        Path dst = path(to);
        if (!Files.isDirectory(src, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file).toString()),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static List<NPath> children(NPath dir) {
        Path p = path(dir);
        if (!Files.isDirectory(p)) {
            return new ArrayList<>();
        }
        try (java.util.stream.Stream<Path> s = Files.list(p)) {
            List<NPath> out = new ArrayList<>();
            s.forEach(x -> out.add(NPath.of(x)));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void mkdirs(NPath dir) {
        try {
            Files.createDirectories(path(dir));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean exists(NPath p) {
        return Files.exists(path(p), java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    static boolean isFile(NPath p) {
        return Files.isRegularFile(path(p), java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    static boolean isDirectory(NPath p) {
        return Files.isDirectory(path(p), java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    static long size(NPath p) {
        try {
            return Files.size(path(p));
        } catch (IOException e) {
            return 0L;
        }
    }

    static long lastModifiedMillis(NPath p) {
        try {
            return Files.getLastModifiedTime(path(p)).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    static void touchModified(NPath p, long millis) {
        try {
            Files.setLastModifiedTime(path(p), java.nio.file.attribute.FileTime.fromMillis(millis));
        } catch (IOException e) {
            // best effort: only the sweep's grace period depends on this
        }
    }

    static String toStringUnchecked(NPath p) {
        return p == null ? null : p.toString();
    }

    static void renameQuietly(NPath from, NPath to) {
        try {
            Files.move(path(from), path(to), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // a leftover temporary is harmless; the caller sweeps them
        }
    }
}