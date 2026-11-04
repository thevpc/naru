package net.thevpc.naru.impl.store;

import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NPairElement;
import net.thevpc.nuts.io.NPath;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A content-addressed store of immutable objects, shared by versioning and the audit log.
 *
 * <p>Both features have the same shape and the same problem. A version needs to keep fifty
 * states without keeping fifty copies of the messages that did not change; the audit log
 * needs to keep a thousand request bodies without keeping a thousand copies of the
 * conversation prefix that is in every one of them. The answer to both is the same: store
 * each distinct thing once, under a name derived from what it is, and let references do
 * the rest. So this is written once and used twice rather than as two stores that drift
 * apart.
 *
 * <h2>Identity</h2>
 * The name of an object is the first 16 bytes of the SHA-256 of its compact TSON, hex
 * encoded. Truncated because 128 bits is past the point where an accidental collision is
 * conceivable and a 64-hex-character path is a nuisance to read; two objects that collide
 * would be indistinguishable by design, not by accident. Compact TSON because the name
 * must not depend on how the element was printed -- an object whose name changed when a
 * line break moved would be unreachable.
 *
 * <h2>Types</h2 * <ul>
 * <li><b>leaf</b> -- a value with no references. Payloads, messages.</li>
 * <li><b>element</b> -- a value with references. Its children are stored first, and their
 *       names recorded in the element's {@code refs}. An element is only written once its
 *       children are on disk, so an element can never name something that is not there.</li>
 * <li><b>chunked leaf</b> -- a payload too large to hold in memory as one string, stored
 *       as chunks plus a small index object. Reads reassemble; the caller never sees the
 *       difference.</li>
 * </ul>
 *
 * <h2>Sweeping</h2>
 * {@link #sweep} starts from a set of roots the caller declares reachable and walks the
 * references. Everything unreachable is a candidate for deletion -- but only once it has
 * been unreachable for a grace period. That delay is the whole reason sweeping is safe to
 * run while a session is live: a commit computes its manifest and a sweep runs moments later
 * would otherwise delete objects the manifest has not been written down yet.
 */
public class NaruContentAddressedStore {

    /**
     * Bytes per chunk. Large enough that the chunk index is negligible next to the
     * payload, small enough that reading a 50MB body does not need 50MB of contiguous heap.
     */
    private static final int CHUNK_SIZE = 256 * 1024;

    /** Below this, a payload is stored whole rather than chunked. */
    private static final int CHUNK_THRESHOLD = 512 * 1024;

    private final NPath dir;

    public NaruContentAddressedStore(NPath dir) {
        this.dir = dir;
    }

    public NPath dir() {
        return dir;
    }

    /**
     * The name of an object: what it is, derived from what it contains.
     *
     * <p>Exposed because the version manifests and audit records must name objects the same
     * way the store does, and a second implementation of this hash that agrees today and
     * drifts tomorrow would silently orphan every object ever written.
     */
    public static String hashOf(NElement element) {
        return hashBytes(compact(element).getBytes(StandardCharsets.UTF_8));
    }

    public static String hashOfString(String content) {
        return hashBytes(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String hashBytes(byte[] bytes) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
        byte[] digest = md.digest(bytes);
        byte[] shortHash = new byte[16];
        System.arraycopy(digest, 0, shortHash, 0, shortHash.length);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : shortHash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Compact, single-line TSON.
     *
     * <p>Compact because the bytes are hashed; single-line because a name derived from a
     * document's layout would change whenever a formatter changed its mind.
     */
    public static String compact(NElement element) {
        return net.thevpc.nuts.elem.NElementWriter.ofTson().compact(true).formatPlain(element);
    }

    public static NElement parse(String content) {
        return net.thevpc.nuts.elem.NElementReader.ofTson().ntf(false).read(content);
    }

    public NPath objectPath(String hash) {
        return dir.resolve(hash.substring(0, 2)).resolve(hash.substring(2) + ".obj");
    }

    public boolean exists(String hash) {
        return hash != null && NaruFileIo.isFile(objectPath(hash));
    }

    /**
     * Stores a value with no references.
     *
     * @return the object's name
     */
    public String putLeaf(NElement value) {
        return write(hashOf(value), value);
    }

    /**
     * Stores a payload as raw text, chunking it if it is large.
     *
     * @return the object's name
     */
    public String putPayload(String payload) {
        if (payload == null) {
            return null;
        }
        if (payload.length() <= CHUNK_THRESHOLD) {
            return putLeaf(NElement.ofString(payload));
        }
        List<String> chunkHashes = new ArrayList<>();
        for (int i = 0; i < payload.length(); i += CHUNK_SIZE) {
            int end = Math.min(payload.length(), i + CHUNK_SIZE);
            chunkHashes.add(putLeaf(NElement.ofString(payload.substring(i, end))));
        }
        NObjectElementBuilder b = NObjectElementBuilder.of();
        b.set("chunked", true);
        NArrayElementBuilder chunks = NArrayElementBuilder.of();
        for (String h : chunkHashes) {
            chunks.add(NElement.ofString(h));
        }
        b.set("chunks", chunks.build());
        b.set("refs", refsElement(chunkHashes));
        return write(hashOf(b.build()), b.build());
    }

    /**
     * Stores a value that refers to other objects.
     *
     * <p>The children are written first and their names go into the element's
     * {@code refs}, which is what lets {@link #sweep} walk from a root to everything it
     * needs. Writing the children first is what makes a crash safe: an element that exists
     * always has its children, and the worst a crash can leave behind is an element nobody
     * references, which the next sweep removes.
     */
    public String putElement(NElement value, Collection<String> refs) {
        List<String> sorted = new ArrayList<>(new LinkedHashSet<>(refs));
        sorted.sort(String::compareTo);
        if (sorted.isEmpty()) {
            return write(hashOf(value), value);
        }
        NObjectElementBuilder b = NObjectElementBuilder.of();
        for (NElement child : value.asObject().get().children()) {
            if (child.isNamedPair()) {
                b.add(child.asPair().get());
            }
        }
        b.set("refs", refsElement(sorted));
        NElement withRefs = b.build();
        return write(hashOf(withRefs), withRefs);
    }

    private static NElement refsElement(List<String> refs) {
        NArrayElementBuilder a = NArrayElementBuilder.of();
        for (String r : refs) {
            a.add(NElement.ofString(r));
        }
        return a.build();
    }

    /**
     * Stores a value whose references are already named by {@code ...Ref} keys inside it.
     *
     * <p>Convenience for records that point at a payload: an audit record has a
     * {@code requestBodyRef} and a {@code responseBodyRef}, and the caller should not have
     * to collect them by hand and keep the collection and the record in step.
     */
    public String putElementWithRefKeys(NElement value) {
        return putElement(value, collectRefKeys(value));
    }

    /**
     * Reads an object back.
     *
     * <p>A chunked payload is reassembled here, so a caller cannot tell it was ever split.
     */
    public NElement get(String hash) {
        NPath p = objectPath(hash);
        if (!NaruFileIo.isFile(p)) {
            return null;
        }
        String content;
        try {
            content = NaruFileIo.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        NElement e = parse(content);
        if (isChunked(e)) {
            StringBuilder sb = new StringBuilder();
            for (String chunk : chunks(e)) {
                NElement c = get(chunk);
                if (c == null) {
                    // a missing chunk means a truncated store; report what is missing
                    // rather than returning a body that silently stops mid-JSON
                    throw new IllegalStateException("audit payload is incomplete: chunk "
                            + chunk + " is missing");
                }
                sb.append(c.asStringValue().orNull());
            }
            return NElement.ofString(sb.toString());
        }
        return e;
    }

    /**
     * The references an object holds. Collected from the {@code refs} key and from any
     * {@code ...Ref} string key, so a record that names its payload inline does not have to
     * duplicate the name into a second list that can drift.
     */
    public static List<String> referencesOf(NElement element) {
        List<String> out = new ArrayList<>();
        if (element == null || !element.isAnyObject()) {
            return out;
        }
        NObjectElement o = element.asObject().get();
        NElement refs = o.get("refs").orNull();
        if (refs != null && refs.isAnyArray()) {
            for (NElement r : refs.asArray().get()) {
                String s = r.asStringValue().orNull();
                if (s != null) {
                    out.add(s);
                }
            }
        }
        out.addAll(collectRefKeys(element));
        return out;
    }

    private static List<String> collectRefKeys(NElement element) {
        List<String> out = new ArrayList<>();
        if (element == null || !element.isAnyObject()) {
            return out;
        }
        for (NElement child : element.asObject().get().children()) {
            if (!child.isNamedPair()) {
                continue;
            }
            NPairElement pair = child.asPair().get();
            String key = pair.key().asStringValue().orNull();
            if (key != null && key.endsWith("Ref") && pair.value().isString()) {
                String v = pair.value().asStringValue().orNull();
                if (v != null && !v.isEmpty()) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private static boolean isChunked(NElement e) {
        return e.isAnyObject() && e.asObject().get().getBooleanValue("chunked").orElse(false);
    }

    private static List<String> chunks(NElement e) {
        List<String> out = new ArrayList<>();
        NElement c = e.asObject().get().get("chunks").orNull();
        if (c != null && c.isAnyArray()) {
            for (NElement x : c.asArray().get()) {
                String s = x.asStringValue().orNull();
                if (s != null) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private String write(String hash, NElement value) {
        NPath p = objectPath(hash);
        if (NaruFileIo.isFile(p)) {
            // content-addressed: the same content is the same object, so an existing one
            // is already correct and rewriting it would only risk a torn read
            return hash;
        }
        try {
            NaruFileIo.writeAtomic(p, compact(value));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return hash;
    }

    /**
     * Removes every object not reachable from {@code roots}.
     *
     * @param grace how long an object must have been unreachable before it is deleted.
     *              Zero deletes immediately. The purpose is to let a commit that is
     *              mid-flight -- manifest computed, ref not yet written -- finish before
     *              its objects become collectable.
     * @return objects deleted and bytes reclaimed
     */
    public SweepResult sweep(Set<String> roots, Duration grace) {
        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(roots);
        long now = System.currentTimeMillis();
        long graceMs = grace == null ? 0 : grace.toMillis();
        int deleted = 0;
        long bytes = 0;
        while (!queue.isEmpty()) {
            String h = queue.poll();
            if (h == null || !reachable.add(h)) {
                continue;
            }
            NPath p = objectPath(h);
            if (!NaruFileIo.isFile(p)) {
                continue;
            }
            try {
                queue.addAll(referencesOf(parse(NaruFileIo.readString(p))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        for (NPath prefix : NaruFileIo.children(dir)) {
            for (NPath file : NaruFileIo.children(prefix)) {
                String name = file.name();
                if (!name.endsWith(".obj")) {
                    continue;
                }
                String hash = prefix.name() + name.substring(0, name.length() - 4);
                if (reachable.contains(hash)) {
                    continue;
                }
                if (graceMs > 0 && now - NaruFileIo.lastModifiedMillis(file) < graceMs) {
                    // too young to be sure nothing is still about to point at it
                    continue;
                }
                long size = NaruFileIo.size(file);
                try {
                    NaruFileIo.deleteIfExists(file);
                    deleted++;
                    bytes += size;
                } catch (IOException e) {
                    // a file we cannot delete is not a reason to stop collecting the rest
                }
            }
            if (NaruFileIo.children(prefix).isEmpty()) {
                try {
                    NaruFileIo.deleteIfExists(prefix);
                } catch (IOException ignored) {
                    // empty prefix directories are cosmetic
                }
            }
        }
        return new SweepResult(deleted, bytes, reachable.size());
    }

    /** Every object name currently stored. Used by tests and by {@code sweep} diagnostics. */
    public Set<String> allObjects() {
        Set<String> out = new LinkedHashSet<>();
        for (NPath prefix : NaruFileIo.children(dir)) {
            for (NPath file : NaruFileIo.children(prefix)) {
                String name = file.name();
                if (name.endsWith(".obj")) {
                    out.add(prefix.name() + name.substring(0, name.length() - 4));
                }
            }
        }
        return out;
    }

    public long totalBytes() {
        long total = 0;
        for (String h : allObjects()) {
            total += NaruFileIo.size(objectPath(h));
        }
        return total;
    }

    public static class SweepResult {
        private final int deleted;
        private final long bytes;
        private final int reachable;

        public SweepResult(int deleted, long bytes, int reachable) {
            this.deleted = deleted;
            this.bytes = bytes;
            this.reachable = reachable;
        }

        public int deleted() {
            return deleted;
        }

        public long bytes() {
            return bytes;
        }

        public int reachable() {
            return reachable;
        }

        @Override
        public String toString() {
            return "SweepResult{deleted=" + deleted + ", bytes=" + bytes + ", reachable=" + reachable + '}';
        }
    }
}