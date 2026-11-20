package net.thevpc.naru.impl.store;

import net.thevpc.naru.api.store.*;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.elem.NElementFormatterStyle;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Durable namespaced TSON records, with a write-ahead journal for multi-key transactions.
 */
final class NaruFileDataStore implements NaruDataStore {
    private final NPath root;
    private final NPath records;
    private final NPath journal;
    private final NPath lockPath;
    private final ReentrantLock localLock = new ReentrantLock();
    private final ThreadLocal<Tx> current = new ThreadLocal<>();

    NaruFileDataStore(NPath root) {
        this.root = root;
        this.records = root.resolve("records");
        this.journal = root.resolve("transaction.tson");
        this.lockPath = root.resolve(".lock");
        NaruFileIo.mkdirs(records);
        transaction(s -> null); // recover a committed journal before serving requests
    }

    @Override
    public NOptional<NElement> get(String key) {
        validate(key);
        Tx tx = current.get();
        if (tx != null && tx.pending.containsKey(key)) {
            Op op = tx.pending.get(key);
            return op.deleted ? NOptional.ofEmpty() : NOptional.of(op.value);
        }
        if (tx != null) return read(key);
        return withLock(() -> read(key));
    }

    @Override
    public void put(String key, NElement value) {
        validate(key);
        Objects.requireNonNull(value, "value");
        Tx tx = current.get();
        if (tx != null) { tx.pending.put(key, new Op(key, value, false)); return; }
        transaction(s -> { s.put(key, value); return null; });
    }

    @Override
    public boolean delete(String key) {
        validate(key);
        Tx tx = current.get();
        if (tx != null) {
            Op previous = tx.pending.get(key);
            boolean existed = previous == null ? read(key).isPresent() : !previous.deleted;
            tx.pending.put(key, new Op(key, null, true));
            return existed;
        }
        boolean existed = get(key).isPresent();
        transaction(s -> { s.delete(key); return null; });
        return existed;
    }

    @Override
    public List<String> keys(String prefix) {
        String p = prefix == null ? "" : prefix;
        Tx tx = current.get();
        if (tx != null) return listKeys(p, tx.pending);
        return withLock(() -> listKeys(p, Map.of()));
    }

    @Override
    public <T> T transaction(NaruDataStoreWork<T> work) {
        Objects.requireNonNull(work, "work");
        Tx nested = current.get();
        if (nested != null) return work.run(this);
        return withLock(() -> {
            Tx tx = new Tx();
            current.set(tx);
            try {
                T result = work.run(this);
                commit(tx);
                return result;
            } finally {
                current.remove();
            }
        });
    }

    private <T> T withLock(java.util.concurrent.Callable<T> action) {
        localLock.lock();
        try {
            NaruFileIo.mkdirs(root);
            try (FileChannel channel = FileChannel.open(NaruFileIo.path(lockPath),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                recover();
                return action.call();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (Exception e) {
                if (e instanceof RuntimeException re) throw re;
                throw new IllegalStateException(e);
            }
        } finally {
            localLock.unlock();
        }
    }

    private void commit(Tx tx) {
        if (tx.pending.isEmpty()) return;
        writeAtomic(journal, encode(tx.pending.values()));
        apply(tx.pending.values());
        NaruFileIo.deleteQuietly(journal);
    }

    private void recover() {
        if (!NaruFileIo.isFile(journal)) return;
        NElement element = NaruContentAddressedStore.parse(NaruFileIo.readStringOrThrow(journal));
        List<Op> ops = decode(element);
        apply(ops);
        NaruFileIo.deleteQuietly(journal);
    }

    private void apply(Iterable<Op> ops) {
        for (Op op : ops) {
            NPath file = record(op.key);
            if (op.deleted) NaruFileIo.deleteQuietly(file);
            else writeAtomic(file, pretty(op.value));
        }
    }

    private NOptional<NElement> read(String key) {
        NPath file = record(key);
        if (!NaruFileIo.isFile(file)) return NOptional.ofEmpty();
        return NOptional.of(NaruContentAddressedStore.parse(NaruFileIo.readStringOrThrow(file)));
    }

    private List<String> listKeys(String prefix, Map<String, Op> pending) {
        Set<String> result = new TreeSet<>();
        for (NPath file : NaruFileIo.children(records)) {
            if (!NaruFileIo.isFile(file) || !file.name().endsWith(".tson")) continue;
            try {
                String key = new String(Base64.getUrlDecoder().decode(file.name().substring(0, file.name().length() - 5)), StandardCharsets.UTF_8);
                if (key.startsWith(prefix)) result.add(key);
            } catch (IllegalArgumentException ignored) { }
        }
        for (Op op : pending.values()) {
            if (op.deleted) result.remove(op.key);
            else if (op.key.startsWith(prefix)) result.add(op.key);
        }
        return List.copyOf(result);
    }

    private NPath record(String key) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
        return records.resolve(encoded + ".tson");
    }

    private static void validate(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.contains("\\") || key.contains(".."))
            throw new IllegalArgumentException("invalid data-store key: " + key);
    }

    private static String pretty(NElement element) {
        return NElementWriter.ofTson().ntf(false).formatter(NElementFormatterStyle.PRETTY).formatPlain(element);
    }

    private static void writeAtomic(NPath path, String content) {
        try {
            NaruFileIo.writeAtomic(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String encode(Iterable<Op> ops) {
        var array = NElement.ofArrayBuilder();
        for (Op op : ops) {
            var object = NElement.ofObjectBuilder().set("key", op.key).set("deleted", op.deleted);
            if (!op.deleted) object.set("value", op.value);
            array.add(object.build());
        }
        return pretty(array.build());
    }

    private static List<Op> decode(NElement element) {
        List<Op> out = new ArrayList<>();
        for (NElement item : element.asArray().get()) {
            var obj = item.asObject().get();
            String key = obj.getStringValue("key").get();
            boolean deleted = obj.getBooleanValue("deleted").orElse(false);
            out.add(new Op(key, deleted ? null : obj.get("value").get(), deleted));
        }
        return out;
    }

    private static final class Tx { final Map<String, Op> pending = new LinkedHashMap<>(); }
    private static final class Op {
        final String key; final NElement value; final boolean deleted;
        Op(String key, NElement value, boolean deleted) { this.key = key; this.value = value; this.deleted = deleted; }
    }
}
