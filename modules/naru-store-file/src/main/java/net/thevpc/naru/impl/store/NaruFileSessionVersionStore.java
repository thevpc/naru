package net.thevpc.naru.impl.store;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.store.*;
import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Version history for one session, on the filesystem.
 *
 * <pre>
 * .versions/
 *     objects/&lt;aa&gt;/&lt;rest&gt;.obj      content-addressed objects (messages, blobs, chunks)
 *     refs/&lt;seq&gt;.tson                one small record per commit
 *     HEAD                              the sequence number HEAD points at
 *     labels/&lt;name&gt;                   a label to a sequence number
 * </pre>
 *
 * <h2>Why a commit is a manifest and not a copy</h2>
 * Every commit writes every message again, but only the ones that changed get new objects:
 * the conversation from turn one is already stored, so turning it into an object costs a
 * hash and a lookup, and the bytes go to the version that first contained them. A session
 * with fifty commits of a hundred-turn conversation therefore costs about one
 * conversation plus fifty short turns, not fifty conversations. The cost of that choice is
 * that the version history and the live store must agree on what a message hashes to --
 * which is why both go through {@link NaruContentAddressedStore#hashOf}.
 *
 * <h2>Ordering</h2>
 * Objects are written, then the ref, then HEAD. A crash between the ref and HEAD leaves a
 * version that exists but is not head -- reachable, listable, restorable, and picked up by
 * the next commit. A crash between the objects and the ref leaves unreferenced objects,
 * which the sweep collects. Both are recoverable; the reverse order would not be.
 */
class NaruFileSessionVersionStore implements NaruSessionVersionStore {

    /**
     * How long an unreferenced object must be unreferenced before a sweep removes it.
     *
     * <p>Long enough to cover a commit that is in flight in another process: it computes
     * its objects, then writes its ref. A sweep in between would delete objects the ref is
     * about to name, and that commit would be permanently unreadable.
     */
    private static final Duration SWEEP_GRACE = Duration.ofMinutes(10);

    private final NPath sessionDir;
    private final NaruFileSessionStore store;
    private final NaruContentAddressedStore objects;

    NaruFileSessionVersionStore(NPath sessionDir, NaruFileSessionStore store) {
        this.sessionDir = sessionDir;
        this.store = store;
        this.objects = new NaruContentAddressedStore(sessionDir.resolve(".versions").resolve("objects"));
    }

    private NPath refsDir() {
        return sessionDir.resolve(".versions").resolve("refs");
    }

    private NPath headFile() {
        return sessionDir.resolve(".versions").resolve("HEAD");
    }

    private NPath labelsDir() {
        return sessionDir.resolve(".versions").resolve("labels");
    }

    // ---------------------------------------------------------------- read

    @Override
    public NOptional<NaruVersionRef> head() {
        long head = headSeq();
        return head < 0 ? NOptional.ofEmpty() : find(Long.toString(head));
    }

    private long headSeq() {
        NPath f = headFile();
        if (!NaruFileIo.isFile(f)) {
            return -1;
        }
        try {
            return Long.parseLong(NaruFileIo.readString(f).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public List<NaruVersionRef> list() {
        long head = headSeq();
        List<NaruVersionRef> out = new ArrayList<>();
        for (NPath f : NaruFileIo.children(refsDir())) {
            NaruVersionRef v = readRef(f);
            if (v != null) {
                out.add(v.asHead(v.seq() == head));
            }
        }
        out.sort(Comparator.comparingLong(NaruVersionRef::seq).reversed());
        return out;
    }

    private NaruVersionRef readRef(NPath f) {
        if (!NaruFileIo.isFile(f) || !f.name().endsWith(".tson")) {
            return null;
        }
        try {
            long seq = Long.parseLong(f.name().substring(0, f.name().length() - 5));
            NElement e = NaruContentAddressedStore.parse(NaruFileIo.readString(f));
            NObjectElement o = e.asObject().get();
            return new NaruVersionRef(seq, o.getStringValue("hash").orNull(),
                    o.getInstantValue("created").orNull(),
                    o.getStringValue("reason").orNull(),
                    o.getStringValue("label").orNull(), false);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public NOptional<NaruVersionRef> find(String seqOrLabel) {
        if (seqOrLabel == null || seqOrLabel.isBlank()) {
            return head();
        }
        String key = seqOrLabel.trim();
        for (NaruVersionRef v : list()) {
            if (key.equals(v.label())) {
                return NOptional.of(v);
            }
        }
        try {
            long seq = Long.parseLong(key);
            long head = headSeq();
            for (NaruVersionRef v : list()) {
                if (v.seq() == seq) {
                    return NOptional.of(v.asHead(seq == head));
                }
            }
        } catch (NumberFormatException ignored) {
            // not a sequence number
        }
        return NOptional.ofEmpty();
    }

    // ---------------------------------------------------------------- write

    @Override
    public NaruVersionRef commit(String reason, String label, NaruRestoreResult.Snapshot snapshot) {
        NaruSessionState state = snapshot.call();
        if (state == null) {
            throw new IllegalStateException("cannot commit: no state was produced");
        }
        Manifest manifest = writeState(state);
        // objects, then ref, then HEAD: see the class comment for why this order
        long seq = nextSeq();
        NObjectElementBuilder ref = NObjectElementBuilder.of();
        ref.set("seq", Long.valueOf(seq));
        ref.set("hash", manifest.hash);
        ref.set("created", NElement.ofInstant(Instant.now()));
        ref.set("reason", reason);
        ref.set("label", label);
        try {
            NaruFileIo.mkdirs(refsDir());
            NaruFileIo.writeAtomic(refsDir().resolve(seq + ".tson"), NaruFileSessionStore.pretty(ref.build()));
            NaruFileIo.writeAtomic(headFile(), Long.toString(seq));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        NaruVersionRef v = new NaruVersionRef(seq, manifest.hash, Instant.now(), reason, label, true);
        if (label != null && !label.isBlank()) {
            writeLabel(label, seq);
        }
        return v;
    }

    private void writeLabel(String label, long seq) {
        try {
            NaruFileIo.mkdirs(labelsDir());
            NaruFileIo.writeAtomic(labelsDir().resolve(label + ".tson"), Long.toString(seq));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public NaruVersionRef label(String seqOrLabel, String label) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        NaruVersionRef target = find(seqOrLabel).orElseThrow(
                () -> new IllegalArgumentException("no such version: " + seqOrLabel));
        for (NaruVersionRef v : list()) {
            if (label.equals(v.label()) && v.seq() != target.seq()) {
                // stealing a label would make an existing restore target change meaning
                // under the user's feet
                throw new IllegalStateException("label '" + label + "' is already on version " + v.seq());
            }
        }
        NPath f = refsDir().resolve(target.seq() + ".tson");
        NElement e = readElement(f);
        NObjectElementBuilder b = NObjectElementBuilder.of();
        for (NElement child : e.asObject().get().children()) {
            if (child.isNamedPair()
                    && !"label".equals(child.asPair().get().key().asStringValue().orNull())) {
                b.add(child.asPair().get());
            }
        }
        b.set("label", label);
        try {
            NaruFileIo.writeAtomic(f, NaruFileSessionStore.pretty(b.build()));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        writeLabel(label, target.seq());
        return target.withLabel(label);
    }

    private long nextSeq() {
        long max = 0;
        for (NPath f : NaruFileIo.children(refsDir())) {
            try {
                max = Math.max(max, Long.parseLong(f.name().substring(0, f.name().length() - 5)));
            } catch (Exception ignored) {
                // not a ref
            }
        }
        return max + 1;
    }

    // ---------------------------------------------------------------- read state

    @Override
    public boolean restore(String seqOrLabel, NaruRestoreResult.Sink sink) {
        NaruVersionRef v = find(seqOrLabel).orElse(null);
        if (v == null) {
            return false;
        }
        sink.accept(readState(v.hash()));
        return true;
    }

    /**
     * Rebuilds a version's state from its objects.
     *
     * <p>Objects are read by hash and never mutated, so this needs no lock of its own; the
     * caller has to apply the result inside its own critical section, which is why a
     * {@link NaruRestoreResult.Sink} is handed the state rather than the state being
     * returned.
     */
    NaruSessionState readState(String manifestHash) {
        NElement manifest = objects.get(manifestHash);
        if (manifest == null) {
            throw new IllegalStateException("version manifest is missing: " + manifestHash);
        }
        NObjectElement o = manifest.asObject().get();
        NaruSessionData data = NaruSessionData.of(o.get("session").orNull());
        NaruSessionState state = new NaruSessionState(data);
        NElement tasks = o.get("tasks").orNull();
        if (tasks != null && tasks.isAnyArray()) {
            for (NElement t : tasks.asArray().get()) {
                long id = t.asObject().get().getLongValue("id").orElse(0L);
                NElement skeletonRef = t.asObject().get().get("skeleton").orNull();
                NElement skeleton = objects.get(skeletonRef.asStringValue().orNull());
                List<NaruMessage> messages = new ArrayList<>();
                NElement mid = t.asObject().get().get("messages").orNull();
                if (mid != null && mid.isAnyArray()) {
                    for (NElement h : mid.asArray().get()) {
                        NElement m = objects.get(h.asStringValue().orNull());
                        if (m != null) {
                            messages.add(NaruMessage.of(m));
                        }
                    }
                }
                state.tasks().put(id, NaruTaskState.of(id, skeleton, new ArrayList<>(), messages));
            }
        }
        NElement routines = o.get("routines").orNull();
        if (routines != null && routines.isAnyArray()) {
            for (NElement r : routines.asArray().get()) {
                NObjectElement p = r.asObject().get();
                state.routines().put(p.getStringValue("name").orNull(),
                        objects.get(p.getStringValue("hash").orNull()));
            }
        }
        NElement exts = o.get("extensions").orNull();
        if (exts != null && exts.isAnyArray()) {
            for (NElement r : exts.asArray().get()) {
                NObjectElement p = r.asObject().get();
                state.extensions().put(p.getStringValue("name").orNull(),
                        objects.get(p.getStringValue("hash").orNull()));
            }
        }
        return state;
    }

    @Override
    public NaruRestoreResult.Diff diff(String from, String to) {
        NaruVersionRef a = find(from).orElseThrow(() -> new IllegalArgumentException("no such version: " + from));
        NaruVersionRef b = find(to).orElseThrow(() -> new IllegalArgumentException("no such version: " + to));
        NaruSessionState sa = readState(a.hash());
        NaruSessionState sb = readState(b.hash());
        NaruRestoreResult.Diff d = new NaruRestoreResult.Diff(String.valueOf(a.seq()), String.valueOf(b.seq()));
        for (Long id : sb.tasks().keySet()) {
            if (!sa.tasks().containsKey(id)) {
                d.added().add("task " + id);
            }
        }
        for (Long id : sa.tasks().keySet()) {
            if (!sb.tasks().containsKey(id)) {
                d.removed().add("task " + id);
            }
        }
        for (Map.Entry<Long, NaruTaskState> e : sb.tasks().entrySet()) {
            NaruTaskState old = sa.tasks().get(e.getKey());
            if (old == null) {
                continue;
            }
            List<String> notes = new ArrayList<>();
            int before = old.history().size();
            int after = e.getValue().history().size();
            if (before != after) {
                notes.add("history " + before + " -> " + after);
            } else {
                for (int i = 0; i < before; i++) {
                    if (!NaruContentAddressedStore.compact(old.history().get(i).toElement())
                            .equals(NaruContentAddressedStore.compact(e.getValue().history().get(i).toElement()))) {
                        notes.add("history differs at " + i);
                        break;
                    }
                }
            }
            String pcBefore = old.skeleton().asObject().get().getStringValue("pc").orNull();
            String pcAfter = e.getValue().skeleton().asObject().get().getStringValue("pc").orNull();
            if (!java.util.Objects.equals(pcBefore, pcAfter)) {
                notes.add("pc " + pcBefore + " -> " + pcAfter);
            }
            if (!notes.isEmpty()) {
                d.changed().put("task " + e.getKey(), String.join("; ", notes));
            }
        }
        return d;
    }

    // ---------------------------------------------------------------- write state

    private static final class Manifest {
        final String hash;

        Manifest(String hash) {
            this.hash = hash;
        }
    }

    /**
     * Turns a whole session state into one manifest and a pile of objects.
     *
     * <p>Every message goes through {@link NaruContentAddressedStore#putLeaf}, which is a
     * no-op if the identical message is already stored. That is the whole mechanism: fifty
     * commits of a long conversation each call this, and each call finds the earlier
     * messages already on disk.
     */
    private Manifest writeState(NaruSessionState state) {
        NObjectElementBuilder o = NObjectElementBuilder.of();
        o.set("session", state.session().toElement());
        NArrayElementBuilder tasks = NArrayElementBuilder.of();
        Set<String> refs = new LinkedHashSet<>();
        for (Map.Entry<Long, NaruTaskState> e : state.tasks().entrySet()) {
            NaruTaskState t = e.getValue();
            NObjectElementBuilder tb = NObjectElementBuilder.of();
            tb.set("id", t.id());
            // the skeleton is itself a referenced object, not a free-floating detail: a
            // manifest that omits it from refs would let a sweep delete the task metadata
            // and leave the version restorable-but-empty
            String skeletonHash = objects.putElement(t.skeleton(), java.util.Collections.emptyList());
            tb.set("skeleton", skeletonHash);
            refs.add(skeletonHash);
            NArrayElementBuilder msgs = NArrayElementBuilder.of();
            for (NaruMessage m : t.history()) {
                String h = objects.putLeaf(m.toElement());
                refs.add(h);
                msgs.add(NElement.ofString(h));
            }
            tb.set("messages", msgs.build());
            tasks.add(tb.build());
        }
        o.set("tasks", tasks.build());
        NArrayElementBuilder routines = NArrayElementBuilder.of();
        for (Map.Entry<String, NElement> e : state.routines().entrySet()) {
            String h = objects.putLeaf(e.getValue());
            refs.add(h);
            routines.add(NObjectElementBuilder.of().set("name", e.getKey()).set("hash", h).build());
        }
        o.set("routines", routines.build());
        NArrayElementBuilder exts = NArrayElementBuilder.of();
        for (Map.Entry<String, NElement> e : state.extensions().entrySet()) {
            String h = objects.putLeaf(e.getValue());
            refs.add(h);
            exts.add(NObjectElementBuilder.of().set("name", e.getKey()).set("hash", h).build());
        }
        o.set("extensions", exts.build());
        return new Manifest(objects.putElement(o.build(), refs));
    }

    // ---------------------------------------------------------------- gc

    @Override
    public NaruGcResult gc(NaruVersionPolicy policy) {
        Instant now = Instant.now();
        List<NaruVersionRef> versions = list();
        long head = headSeq();
        Set<String> keep = new LinkedHashSet<>();
        for (NaruVersionRef v : versions) {
            // HEAD first and unconditionally: a policy expression that can forget it
            // would leave a session with no restore point at all
            boolean protect = v.seq() == head || policy.protects(v, head, now) || v.label() != null;
            if (protect) {
                keep.add(v.hash());
            }
        }
        int removed = 0;
        for (NaruVersionRef v : versions) {
            if (keep.contains(v.hash())) {
                continue;
            }
            try {
                NaruFileIo.deleteIfExists(refsDir().resolve(v.seq() + ".tson"));
                removed++;
            } catch (IOException ignored) {
                // a ref we cannot delete leaves its version reachable, which is safe
            }
        }
        NaruContentAddressedStore.SweepResult swept = objects.sweep(keep, SWEEP_GRACE);
        return new NaruGcResult(removed, keep.size(), swept.bytes());
    }

    private NElement readElement(NPath f) {
        try {
            return NaruContentAddressedStore.parse(NaruFileIo.readString(f));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}