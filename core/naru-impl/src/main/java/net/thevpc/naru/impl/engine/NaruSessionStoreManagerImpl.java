package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NAruVisibility;
import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.agent.NaruSessionStoreManager;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NStringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class NaruSessionStoreManagerImpl implements NaruSessionStoreManager {
    /**
     * The session this catalog is reached through.
     * <p>
     * A back-reference on purpose, and it is what keeps the store honest: a lookup falls
     * back to the current session's own uuid and name, and purging or deleting the current
     * session resets it rather than leaving stale state behind. Everything else here is
     * pure disk access.
     */
    private final NaruSessionImpl adapter;

    public NaruSessionStoreManagerImpl(NaruSessionImpl adapter) {
        this.adapter = adapter;
    }

    @Override
    public List<NaruResourceInfo> list() {
        List<NaruResourceInfo> a = new ArrayList<>();
        for (NPath p : sessionDir(false).list().stream().filter(new NonSnapshotSessionFolder()).collect(Collectors.toList())) {
            NaruResourceInfo s = NElementReader.ofTson().read(p.resolve("session.tson"), NaruResourceInfo.class);
            s.setVisibility(NAruVisibility.PRIVATE);
            a.add(s);
        }
        for (NPath p : sessionDir(true).list().stream().filter(new NonSnapshotSessionFolder()).collect(Collectors.toList())) {
            NaruResourceInfo s = NElementReader.ofTson().read(p.resolve("session.tson"), NaruResourceInfo.class);
            s.setVisibility(NAruVisibility.PUBLIC);
            a.add(s);
        }
        // newest first. A session file with no recorded modification instant is treated as
        // the oldest rather than allowed to blow up the whole listing: one odd file on disk
        // should not make /sessions unusable.
        a.sort((o1, o2) -> {
            Instant t1 = o1.getModificationInstant();
            Instant t2 = o2.getModificationInstant();
            if (t1 == null && t2 == null) {
                return 0;
            }
            if (t1 == null) {
                return 1;
            }
            if (t2 == null) {
                return -1;
            }
            return t2.compareTo(t1);
        });
        return a;
    }


    @Override
    public int purge() {
        int count = 0;
        for (NPath p : sessionDir(false).list().stream().filter(new NonSnapshotSessionFolder()).collect(Collectors.toList())) {
            p.deleteTree();
            count++;
        }
        for (NPath p : sessionDir(true).list().stream().filter(new NonSnapshotSessionFolder()).collect(Collectors.toList())) {
            p.deleteTree();
            count++;
        }
        adapter.reset(false);
        return count;
    }

    private NPath sessionDir(boolean publicSession) {
        if (publicSession) {
            return adapter.projectDir().resolve(".naru/sessions/");
        }
        return adapter.projectDir().resolve(".naru/local/sessions/");
    }

    public String findByUuidOrName(String uuidOrName) {
        List<NaruResourceInfo> list = list();
        for (NaruResourceInfo s : list) {
            if (Objects.equals(s.getUuid(), uuidOrName)) {
                return s.getUuid();
            }
        }
        if (Objects.equals(adapter.uuid(), uuidOrName)) {
            return adapter.uuid();
        }

        for (NaruResourceInfo s : list) {
            if (Objects.equals(NStringUtils.strip(s.getName()), NStringUtils.strip(uuidOrName))) {
                return s.getUuid();
            }
        }
        if (Objects.equals(adapter.name(), uuidOrName)) {
            return adapter.uuid();
        }
        Integer index = NLiteral.of(uuidOrName).asInt().orNull();
        if (index != null) {
            if (index - 1 >= 0 && index - 1 < list.size()) {
                return list.get(index - 1).getUuid();
            }
        }
        return null;
    }

    public boolean delete(String uuid) {
        NPath a = sessionFile(uuid, true);
        boolean b = false;
        if (a.parent().exists()) {
            a.parent().deleteTree();
            b = true;
        }
        a = sessionFile(uuid, false);
        if (a.parent().exists()) {
            a.parent().deleteTree();
            b = true;
        }
        if (adapter.uuid().equals(uuid)) {
            adapter.reset(false);
            b = true;
        }
        return b;
    }

    private NPath sessionFile(String uuid, boolean publicSession) {
        return sessionDir(publicSession).resolve(uuid).resolve("session.tson");
    }

    /**
     * A directory is a saved session only if it holds a {@code session.tson}.
     * <p>
     * The snapshot exclusion is for directories written by older layouts, which kept the
     * working snapshot at {@code .naru/local/sessions/snapshot} as a sibling of the real
     * session folders. It is harmless to keep now that scratch state lives outside
     * this folder.
     */
    private static class NonSnapshotSessionFolder implements Predicate<NPath> {
        @Override
        public boolean test(NPath x) {
            return !x.name().equalsIgnoreCase("snapshot")
                    && x.resolve("session.tson").exists();
        }
    }
}
