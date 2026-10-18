package net.thevpc.naru.ext.store.jdbc;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.store.*;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.util.NOptional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * JDBC-backed session store. Hot state is split into metadata, task skeletons and
 * individual message records; appending a turn does not rewrite prior messages.
 */
final class NaruJdbcSessionStore implements NaruSessionStore {
    private final NaruDataStore records;
    private final NaruStoreConfig config;

    NaruJdbcSessionStore(NaruDataStore records, NaruStoreConfig config) {
        this.records = records;
        this.config = config;
    }

    private static String enc(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String dec(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
    private static String scope(NaruSessionScope scope) { return scope == NaruSessionScope.PUBLIC ? "public" : "private"; }
    private static String root(NaruSessionScope scope) { return "session/" + scope(scope) + "/"; }
    private static String base(NaruSessionScope scope, String uuid) { return root(scope) + enc(uuid) + "/"; }
    private static String meta(NaruSessionScope scope, String uuid) { return base(scope, uuid) + "meta"; }
    private static String taskPrefix(NaruSessionScope scope, String uuid) { return base(scope, uuid) + "task/"; }
    private static String taskKey(NaruSessionScope scope, String uuid, long task) { return taskPrefix(scope, uuid) + task; }
    private static String msgPrefix(NaruSessionScope scope, String uuid, long task) { return taskKey(scope, uuid, task) + "/message/"; }
    private static NElement parse(String text) { return NElementReader.ofTson().read(text); }
    private static String format(NElement e) { return NElementWriter.ofTson().ntf(false).formatPlain(e); }
    private NOptional<NElement> get(String key) { return records.get(key); }
    private void put(String key, NElement value) { records.put(key, value); }
    private boolean delete(String key) { return records.delete(key); }
    private NaruSessionData readData(String uuid, NaruSessionScope scope) { return get(meta(scope, uuid)).map(NaruSessionData::of).orNull(); }
    private NaruSessionScope actualScope(String uuid) {
        if (get(meta(NaruSessionScope.PRIVATE, uuid)).isPresent()) return NaruSessionScope.PRIVATE;
        if (get(meta(NaruSessionScope.PUBLIC, uuid)).isPresent()) return NaruSessionScope.PUBLIC;
        return null;
    }
    private void require(String uuid, NaruSessionScope scope) {
        if (get(meta(scope, uuid)).isEmpty()) throw new IllegalArgumentException("no session " + uuid + " in scope " + scope);
    }

    @Override public List<NaruSessionRef> list(NaruSessionScope scope) {
        List<NaruSessionRef> out = new ArrayList<>();
        for (String key : records.keys(root(scope))) {
            if (!key.endsWith("/meta")) continue;
            String id = dec(key.substring(root(scope).length(), key.length() - 5));
            NaruSessionData data = readData(id, scope);
            if (data == null) continue;
            int tasks = taskIds(id, scope).size();
            int messages = 0;
            for (long taskId : taskIds(id, scope)) messages += records.keys(msgPrefix(scope, id, taskId)).size();
            out.add(new NaruSessionRef(id, data.name(), scope, data.creationInstant(), data.modificationInstant(), tasks, messages));
        }
        out.sort(Comparator.comparing(NaruSessionRef::modificationInstant, Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(NaruSessionRef::uuid));
        return out;
    }
    @Override public List<NaruSessionRef> list() {
        List<NaruSessionRef> all = new ArrayList<>(list(NaruSessionScope.PRIVATE)); all.addAll(list(NaruSessionScope.PUBLIC));
        all.sort(Comparator.comparing(NaruSessionRef::modificationInstant, Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(NaruSessionRef::uuid)); return all;
    }
    @Override public NOptional<String> findUuid(String key) {
        if (key == null || key.isBlank()) return NOptional.ofEmpty();
        String q = key.trim(); List<NaruSessionRef> all = list();
        for (var r : all) if (q.equals(r.uuid())) return NOptional.of(r.uuid());
        for (var r : all) if (r.name() != null && q.equalsIgnoreCase(r.name())) return NOptional.of(r.uuid());
        try { int n = Integer.parseInt(q); if (n > 0 && n <= all.size()) return NOptional.of(all.get(n - 1).uuid()); } catch (NumberFormatException ignored) { }
        return NOptional.ofEmpty();
    }
    @Override public NOptional<NaruSessionScope> scopeOf(String uuid) { return NOptional.ofNullable(actualScope(uuid)); }
    @Override public boolean exists(String uuid, NaruSessionScope scope) { return get(meta(scope, uuid)).isPresent(); }
    @Override public void create(NaruSessionData data, NaruSessionScope scope) {
        if (!createIfAbsent(data, scope)) throw new IllegalStateException("session already exists: " + data.uuid());
    }
    @Override public boolean createIfAbsent(NaruSessionData data, NaruSessionScope scope) {
        return records.transaction(s -> { if (get(meta(scope, data.uuid())).isPresent()) return false; put(meta(scope, data.uuid()), data.toElement()); return true; });
    }
    @Override public NOptional<NaruSessionData> loadData(String uuid, NaruSessionScope scope) { return get(meta(scope, uuid)).map(NaruSessionData::of); }
    @Override public void saveData(NaruSessionData data, NaruSessionScope scope) { require(data.uuid(), scope); records.transaction(s -> { put(meta(scope, data.uuid()), data.toElement()); return null; }); }
    @Override public long compareAndSetData(NaruSessionData data, NaruSessionScope scope, long expectedVersion) {
        return records.transaction(s -> { NaruSessionData old = readData(data.uuid(), scope); long actual = old == null ? -1 : old.version(); if (actual != expectedVersion) throw new NaruSessionConflictException(data.uuid(), expectedVersion, actual); data.version(expectedVersion + 1); put(meta(scope, data.uuid()), data.toElement()); return data.version(); });
    }

    @Override public void saveTask(String uuid, NaruSessionScope scope, NaruTaskState task) { saveTaskAndGetHistoryIds(uuid, scope, task); }
    @Override public void saveTaskSkeleton(String uuid, NaruSessionScope scope, NaruTaskState task) {
        require(uuid, scope); records.transaction(s -> { NaruTaskState old = loadTask(uuid, scope, task.id()).orNull(); List<String> ids = old == null ? task.historyIds() : old.historyIds(); put(taskKey(scope, uuid, task.id()), withIds(task.skeleton(), ids)); return null; });
    }
    @Override public List<String> saveTaskAndGetHistoryIds(String uuid, NaruSessionScope scope, NaruTaskState task) {
        require(uuid, scope); return records.transaction(s -> { NaruHistorySave h = saveHistory(uuid, scope, task.id(), task.history()); put(taskKey(scope, uuid, task.id()), withIds(task.skeleton(), h.ids())); return h.ids(); });
    }
    private static NElement withIds(NElement skeleton, List<String> ids) {
        NObjectElementBuilder b = NObjectElementBuilder.of(); for (NElement child : skeleton.asObject().get().children()) if (child.isNamedPair()) b.add(child.asPair().get());
        NArrayElementBuilder a = NArrayElementBuilder.of(); for (String id : ids) a.add(NElement.ofString(id)); b.set("history", a.build()); return b.build();
    }
    private List<String> ids(NElement task) {
        List<String> out = new ArrayList<>(); NElement h = task.asObject().get().get("history").orNull();
        if (h != null && h.isAnyArray()) for (NElement e : h.asArray().get()) { String id = e.asStringValue().orNull(); if (id != null) out.add(id); }
        return out;
    }
    private NMessagePair message(String key) {
        String encodedId = key.substring(key.lastIndexOf('/') + 1); return new NMessagePair(encodedId, get(key).map(NaruMessage::of).orNull());
    }
    @Override public NOptional<NaruTaskState> loadTask(String uuid, NaruSessionScope scope, long taskId) {
        NElement task = get(taskKey(scope, uuid, taskId)).orNull(); if (task == null) return NOptional.ofEmpty();
        List<String> ids = ids(task); List<NaruMessage> history = new ArrayList<>();
        for (String id : ids) { NElement e = get(msgPrefix(scope, uuid, taskId) + id).orNull(); if (e != null) history.add(NaruMessage.of(e)); }
        return NOptional.of(NaruTaskState.ofStored(taskId, task, history));
    }
    @Override public List<Long> taskIds(String uuid, NaruSessionScope scope) {
        TreeSet<Long> out = new TreeSet<>(); String p = taskPrefix(scope, uuid);
        for (String k : records.keys(p)) { String rest = k.substring(p.length()); int slash = rest.indexOf('/'); if (slash >= 0) rest = rest.substring(0, slash); try { out.add(Long.parseLong(rest)); } catch (NumberFormatException ignored) { } }
        return List.copyOf(out);
    }
    @Override public void deleteTask(String uuid, NaruSessionScope scope, long taskId) {
        records.transaction(s -> { String p = taskKey(scope, uuid, taskId); for (String k : records.keys(p)) delete(k); return null; });
    }
    @Override public NaruHistorySave saveHistory(String uuid, NaruSessionScope scope, long taskId, List<NaruMessage> messages) {
        require(uuid, scope); return records.transaction(s -> {
            String p = msgPrefix(scope, uuid, taskId); List<String> oldIds = get(taskKey(scope, uuid, taskId)).map(this::ids).orElse(List.of());
            Map<String, Deque<String>> reusable = new HashMap<>();
            for (String id : oldIds) { NElement e = get(p + id).orNull(); if (e != null) reusable.computeIfAbsent(format(e), x -> new ArrayDeque<>()).add(id); }
            long next = get(taskKey(scope, uuid, taskId) + "/next").map(e -> e.asLongValue().orElse(1L)).orElse(1L);
            List<String> fresh = new ArrayList<>(); int created = 0, reused = 0;
            for (NaruMessage m : messages) { NElement e = m.toElement(); Deque<String> q = reusable.get(format(e)); String id;
                if (q != null && !q.isEmpty()) { id = q.removeFirst(); reused++; }
                else { id = Long.toString(next++); put(p + id, e); created++; }
                fresh.add(id);
            }
            put(taskKey(scope, uuid, taskId) + "/next", NElement.ofNumber(next));
            NElement task = get(taskKey(scope, uuid, taskId)).orNull();
            if (task != null) put(taskKey(scope, uuid, taskId), withIds(NaruTaskState.withoutHistory(task), fresh));
            Set<String> keep = new HashSet<>(fresh); int removed = 0;
            for (String id : oldIds) if (!keep.contains(id)) { if (delete(p + id)) removed++; }
            return new NaruHistorySave(taskId, created, reused, removed, fresh);
        });
    }
    @Override public List<NaruMessage> loadHistory(String uuid, NaruSessionScope scope, long taskId) {
        NaruTaskState t = loadTask(uuid, scope, taskId).orNull(); return t == null ? List.of() : t.history();
    }
    @Override public List<String> routineNames(String uuid, NaruSessionScope scope) { return names(base(scope, uuid) + "routine/"); }
    private List<String> names(String p) { return records.keys(p).stream().map(k -> dec(k.substring(p.length()))).sorted().toList(); }
    @Override public NOptional<NElement> loadRoutine(String uuid, NaruSessionScope scope, String name) { return get(base(scope, uuid) + "routine/" + enc(name)); }
    @Override public void saveRoutine(String uuid, NaruSessionScope scope, String name, NElement element) { require(uuid, scope); put(base(scope, uuid) + "routine/" + enc(name), element); }
    @Override public void deleteRoutine(String uuid, NaruSessionScope scope, String name) { delete(base(scope, uuid) + "routine/" + enc(name)); }
    @Override public NOptional<NElement> loadExtensionState(String uuid, NaruSessionScope scope, String extension) { return get(base(scope, uuid) + "extension/" + enc(extension)); }
    @Override public void saveExtensionState(String uuid, NaruSessionScope scope, String extension, NElement element) { require(uuid, scope); put(base(scope, uuid) + "extension/" + enc(extension), element); }
    @Override public void deleteExtensionState(String uuid, NaruSessionScope scope, String extension) { delete(base(scope, uuid) + "extension/" + enc(extension)); }
    @Override public void move(String uuid, NaruSessionScope from, NaruSessionScope to) {
        records.transaction(s -> { if (exists(uuid, to)) throw new IllegalStateException("session " + uuid + " already exists in scope " + to); require(uuid, from); String src = base(from, uuid), dst = base(to, uuid); for (String k : records.keys(src)) { NElement v = get(k).orNull(); put(dst + k.substring(src.length()), v); delete(k); } return null; });
    }
    @Override public boolean delete(String uuid, NaruSessionScope scope) { return records.transaction(s -> { if (!exists(uuid, scope)) return false; for (String k : records.keys(base(scope, uuid))) delete(k); return true; }); }
    @Override public int purge(NaruSessionScope scope) { List<String> uuids = new ArrayList<>(); for (String k : records.keys(root(scope))) if (k.endsWith("/meta")) uuids.add(dec(k.substring(root(scope).length(), k.length() - 5))); int n = 0; for (String id : uuids) if (delete(id, scope)) n++; return n; }

    @Override public NaruSessionVersionStore versions(String uuid, NaruSessionScope scope) { return new JdbcVersions(uuid, scope); }
    @Override public NaruAuditStore audit(String uuid, NaruSessionScope scope) { return new JdbcAudit(uuid, scope); }
    @Override public void close() { records.close(); }

    private record NMessagePair(String id, NaruMessage message) { }

    private String versionPrefix(String uuid, NaruSessionScope scope) { return base(scope, uuid) + "version/"; }
    private NElement stateElement(NaruSessionState state) {
        NObjectElementBuilder root = NElement.ofObjectBuilder().set("session", state.session().toElement());
        NArrayElementBuilder tasks = NElement.ofArrayBuilder();
        for (var e : state.tasks().entrySet()) {
            NArrayElementBuilder messages = NElement.ofArrayBuilder();
            for (NaruMessage m : e.getValue().history()) messages.add(m.toElement());
            tasks.add(NElement.ofObjectBuilder().set("id", e.getKey()).set("task", e.getValue().toElement()).set("messages", messages.build()).build());
        }
        root.set("tasks", tasks.build()); root.set("routines", namedElements(state.routines())); root.set("extensions", namedElements(state.extensions()));
        return root.build();
    }
    private NElement namedElements(Map<String, NElement> values) { NObjectElementBuilder b = NElement.ofObjectBuilder(); values.forEach(b::set); return b.build(); }
    private NaruSessionState stateOf(NElement element) {
        NObjectElement o = element.asObject().get(); NaruSessionState state = new NaruSessionState(NaruSessionData.of(o.get("session").get()));
        NElement ts = o.get("tasks").orNull(); if (ts != null && ts.isAnyArray()) for (NElement t : ts.asArray().get()) {
            NObjectElement task = t.asObject().get(); long id = task.getLongValue("id").orElse(0L); NElement taskEl = task.get("task").get();
            List<NaruMessage> messages = new ArrayList<>(); NElement ms = task.get("messages").orNull(); if (ms != null && ms.isAnyArray()) for (NElement m : ms.asArray().get()) messages.add(NaruMessage.of(m));
            NObjectElementBuilder b = NObjectElementBuilder.of(); for (NElement c : taskEl.asObject().get().children()) if (c.isNamedPair() && !"history".equals(c.asPair().get().key().asStringValue().orNull())) b.add(c.asPair().get());
            NArrayElementBuilder a = NElement.ofArrayBuilder(); messages.forEach(m -> a.add(m.toElement())); b.set("history", a.build()); state.tasks().put(id, NaruTaskState.ofInline(id, b.build()));
        }
        readNamed(o.get("routines").orNull(), state.routines()); readNamed(o.get("extensions").orNull(), state.extensions()); return state;
    }
    private void readNamed(NElement element, Map<String, NElement> into) { if (element != null && element.isAnyObject()) for (NElement c : element.asObject().get().children()) if (c.isNamedPair()) into.put(c.asPair().get().key().asStringValue().orNull(), c.asPair().get().value()); }
    private String hash(NElement e) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(format(e).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private final class JdbcVersions implements NaruSessionVersionStore {
        private final String uuid; private final NaruSessionScope scope; private final String p;
        JdbcVersions(String uuid, NaruSessionScope scope) { this.uuid = uuid; this.scope = scope; this.p = versionPrefix(uuid, scope); }
        private long headSeq() { return get(p + "head").map(e -> e.asLongValue().orElse(0L)).orElse(0L); }
        private NaruVersionRef ref(String key, long head) { NObjectElement o = get(key).orNull().asObject().get(); long seq = o.getLongValue("seq").orElse(0L); return new NaruVersionRef(seq, o.getStringValue("hash").orNull(), o.getInstantValue("created").orNull(), o.getStringValue("reason").orNull(), o.getStringValue("label").orNull(), seq == head); }
        @Override public NOptional<NaruVersionRef> head() { long h = headSeq(); return h <= 0 ? NOptional.ofEmpty() : NOptional.of(ref(p + "ref/" + h, h)); }
        @Override public List<NaruVersionRef> list() { long h = headSeq(); List<NaruVersionRef> out = new ArrayList<>(); for (String k : records.keys(p + "ref/")) out.add(ref(k, h)); out.sort(Comparator.comparingLong(NaruVersionRef::seq).reversed()); return out; }
        @Override public NOptional<NaruVersionRef> find(String q) { if (q == null) return NOptional.ofEmpty(); for (NaruVersionRef v : list()) if (q.equals(v.label()) || q.equals(String.valueOf(v.seq()))) return NOptional.of(v); return NOptional.ofEmpty(); }
        @Override public NaruVersionRef commit(String reason, String label, NaruRestoreResult.Snapshot snapshot) {
            NaruSessionState state = snapshot.call(); if (state == null) throw new IllegalStateException("cannot commit: no state was produced"); NElement data = stateElement(state); String h = hash(data);
            for (NaruVersionRef v : list()) if (h.equals(v.hash())) return v;
            return records.transaction(s -> { if (label != null && !label.isBlank()) for (NaruVersionRef v : list()) if (label.equals(v.label())) throw new IllegalArgumentException("version label already exists: " + label);
                long seq = headSeq() + 1; Instant now = Instant.now(); put(p + "object/" + h, data); put(p + "ref/" + seq, NElement.ofObjectBuilder().set("seq", seq).set("hash", h).set("created", NElement.ofInstant(now)).set("reason", reason).set("label", label).build()); put(p + "head", NElement.ofNumber(seq)); return new NaruVersionRef(seq, h, now, reason, label, true); });
        }
        @Override public NaruVersionRef label(String q, String label) {
            NaruVersionRef target = find(q).orElseThrow(() -> new IllegalArgumentException("no such version: " + q)); if (label == null || label.isBlank()) throw new IllegalArgumentException("label is required");
            for (NaruVersionRef v : list()) if (v.seq() != target.seq() && label.equals(v.label())) throw new IllegalArgumentException("version label already exists: " + label);
            String k = p + "ref/" + target.seq(); NObjectElement old = get(k).get().asObject().get(); NObjectElementBuilder b = NObjectElementBuilder.of(); for (NElement c : old.children()) if (c.isNamedPair() && !"label".equals(c.asPair().get().key().asStringValue().orNull())) b.add(c.asPair().get()); b.set("label", label); put(k, b.build()); return target.withLabel(label);
        }
        @Override public boolean restore(String q, NaruRestoreResult.Sink sink) { NaruVersionRef r = find(q).orNull(); if (r == null) return false; sink.accept(stateOf(get(p + "object/" + r.hash()).get())); return true; }
        @Override public NaruRestoreResult.Diff diff(String from, String to) {
            NaruVersionRef a = find(from).orElseThrow(() -> new IllegalArgumentException("no such version: " + from)), b = find(to).orElseThrow(() -> new IllegalArgumentException("no such version: " + to));
            NaruSessionState x = stateOf(get(p + "object/" + a.hash()).get()), y = stateOf(get(p + "object/" + b.hash()).get()); NaruRestoreResult.Diff d = new NaruRestoreResult.Diff(from, to);
            for (Long id : y.tasks().keySet()) if (!x.tasks().containsKey(id)) d.added().add("task " + id); for (Long id : x.tasks().keySet()) if (!y.tasks().containsKey(id)) d.removed().add("task " + id);
            for (Long id : y.tasks().keySet()) if (x.tasks().containsKey(id) && !format(x.tasks().get(id).toElement()).equals(format(y.tasks().get(id).toElement()))) d.changed().put("task " + id, "task state changed"); return d;
        }
        @Override public NaruGcResult gc(NaruVersionPolicy policy) {
            List<NaruVersionRef> all = list(); long head = headSeq(); Instant now = Instant.now(); int removed = 0; for (NaruVersionRef v : all) if (!v.isHead() && !policy.protects(v, head, now)) { delete(p + "ref/" + v.seq()); removed++; }
            Set<String> used = new HashSet<>(); for (NaruVersionRef v : list()) used.add(v.hash()); long bytes = 0; for (String k : records.keys(p + "object/")) { if (!used.contains(k.substring((p + "object/").length()))) { bytes += format(get(k).get()).length(); delete(k); } }
            return new NaruSessionVersionStore.NaruGcResult(removed, list().size(), bytes);
        }
    }

    private final class JdbcAudit implements NaruAuditStore {
        private final String p;
        JdbcAudit(String uuid, NaruSessionScope scope) { p = base(scope, uuid) + "audit/"; }
        private String bodyKey(String ref) { return p + "body/" + ref; }
        private List<NaruAuditRecord> all() { List<NaruAuditRecord> out = new ArrayList<>(); for (String k : records.keys(p + "record/")) out.add(NaruAuditRecord.of(get(k).get(), false)); out.sort(Comparator.comparing(NaruAuditRecord::timestamp, Comparator.nullsLast(Comparator.reverseOrder()))); return out; }
        private boolean missing(NaruAuditRecord r) { return (r.requestBodyRef() != null && get(bodyKey(r.requestBodyRef())).isEmpty()) || (r.responseBodyRef() != null && get(bodyKey(r.responseBodyRef())).isEmpty()); }
        @Override public void append(NaruAuditRecord record, String requestBody, String responseBody) { records.transaction(s -> { String id = UUID.randomUUID().toString(); NaruAuditRecord.Builder b = record.toBuilder().id(id);
            if (requestBody != null) { String h = hash(NElement.ofString(requestBody)); put(bodyKey(h), NElement.ofString(requestBody)); b.requestBodyRef(h).requestBodyInline(null); }
            if (responseBody != null) { String h = hash(NElement.ofString(responseBody)); put(bodyKey(h), NElement.ofString(responseBody)); b.responseBodyRef(h).responseBodyInline(null); }
            put(p + "record/" + id, b.build().toElement()); return null; }); }
        @Override public List<NaruAuditRecord> listRefs(int limit, Instant since) { List<NaruAuditRecord> out = all().stream().filter(r -> since == null || (r.timestamp() != null && !r.timestamp().isBefore(since))).map(r -> r.withResolvedBodies(null, null, missing(r))).toList(); return limit < 0 ? out : out.subList(0, Math.min(limit, out.size())); }
        @Override public List<NaruAuditRecord> read(int limit, Instant since) { List<NaruAuditRecord> refs = listRefs(-1, since); List<NaruAuditRecord> out = new ArrayList<>(); for (NaruAuditRecord r : refs) out.add(r.withResolvedBodies(body(r.requestBodyRef()), body(r.responseBodyRef()), missing(r))); return limit < 0 ? out : out.subList(0, Math.min(limit, out.size())); }
        private NElement body(String ref) { return ref == null ? null : get(bodyKey(ref)).orNull(); }
        @Override public NOptional<NElement> resolveBody(String ref) { return ref == null ? NOptional.ofEmpty() : get(bodyKey(ref)); }
        @Override public long payloadBytes() { long n = 0; for (String k : records.keys(p + "body/")) n += get(k).get().asStringValue().orElse("").getBytes(StandardCharsets.UTF_8).length; return n; }
        @Override public NaruAuditPruneResult prune(NaruAuditRetention retention) {
            List<NaruAuditRecord> rows = all(); Map<Long,Integer> kept = new HashMap<>(), dropped = new LinkedHashMap<>(); Set<String> ids = new HashSet<>();
            for (NaruAuditRecord r : rows) { int max = retention.maxRecordsForTask().getOrDefault(r.taskId(), retention.maxRecordsPerTask()); if (!retention.keeps(r.timestamp()) || (max >= 0 && kept.getOrDefault(r.taskId(), 0) >= max)) dropped.merge(r.taskId(), 1, Integer::sum); else { kept.merge(r.taskId(), 1, Integer::sum); ids.add(r.id()); } }
            int removed = 0; for (NaruAuditRecord r : rows) if (!ids.contains(r.id())) { delete(p + "record/" + r.id()); removed++; }
            Set<String> refs = new HashSet<>(); for (NaruAuditRecord r : all()) { if (r.requestBodyRef() != null) refs.add(r.requestBodyRef()); if (r.responseBodyRef() != null) refs.add(r.responseBodyRef()); }
            int blobs = 0; long bytes = 0; for (String k : records.keys(p + "body/")) if (!refs.contains(k.substring((p + "body/").length()))) { bytes += get(k).get().asStringValue().orElse("").getBytes(StandardCharsets.UTF_8).length; delete(k); blobs++; }
            Map<String,Integer> byTask = new LinkedHashMap<>(); dropped.forEach((k,v) -> byTask.put(String.valueOf(k),v)); return new NaruAuditPruneResult(removed, blobs, bytes, refs.size(), byTask);
        }
    }
}
