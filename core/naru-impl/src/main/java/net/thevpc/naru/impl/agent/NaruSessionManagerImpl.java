package net.thevpc.naru.impl.agent;

import net.thevpc.naru.api.agent.NAruVisibility;
import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.agent.NaruSessionManager;
import net.thevpc.nuts.elem.NElementFormatterStyle;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NStringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class NaruSessionManagerImpl implements NaruSessionManager {
    private final NaruSessionImpl adapter;

    public NaruSessionManagerImpl(NaruSessionImpl adapter) {
        this.adapter = adapter;
    }

    @Override
    public List<NaruResourceInfo> list() {
        List<NaruResourceInfo> a = new ArrayList<>();
        for (NPath p : sessionDir(false).list().stream().filter(x -> x.name().endsWith(".tson")).collect(Collectors.toList())) {
            if (p.name().endsWith("snapshot.tson")) {
                //just skip snapshot!
                continue;
            }
            NaruResourceInfo s = NElementReader.ofTson().read(p, NaruResourceInfo.class);
            s.setVisibility(NAruVisibility.PRIVATE);
            a.add(s);
        }
        for (NPath p : sessionDir(true).list().stream().filter(x -> x.name().endsWith(".tson")).collect(Collectors.toList())) {
            if (p.name().endsWith("snapshot.tson")) {
                //just skip snapshot!
                continue;
            }
            NaruResourceInfo s = NElementReader.ofTson().read(p, NaruResourceInfo.class);
            s.setVisibility(NAruVisibility.PUBLIC);
            a.add(s);
        }
        a.sort((o1, o2) -> o2.getModificationDate().compareTo(o1.getModificationDate()));
        return a;
    }


    @Override
    public int clear() {
        int count = 0;
        for (NPath p : sessionDir(false).list().stream().filter(x -> x.name().endsWith(".tson")).collect(Collectors.toList())) {
            p.delete();
            count++;
        }
        for (NPath p : sessionDir(true).list().stream().filter(x -> x.name().endsWith(".tson")).collect(Collectors.toList())) {
            p.delete();
            count++;
        }
        adapter.reset(false);
        return count;
    }

    public synchronized NaruSessionManager saveSnapshot() {
        NPath snapshotFile = adapter.projectDir().resolve(".naru/local/sessions/snapshot.tson");
        NElementWriter.ofTson().ntf(false).formatter(NElementFormatterStyle.PRETTY)
                .write(adapter.toElement(), snapshotFile.mkParentDirs());
        return this;
    }

    @Override
    public NaruSessionManager restoreSnapshot() {
        NPath snapshotFile = adapter.projectDir().resolve(".naru/local/sessions/snapshot.tson");
        if (snapshotFile.isRegularFile()) {
            adapter.load(NElementReader.ofTson().read(snapshotFile));
        }
        return this;
    }

    private NPath sessionDir(boolean publicSession) {
        if (publicSession) {
            return adapter.projectDir().resolve(".naru/sessions/");
        }
        return adapter.projectDir().resolve(".naru/local/sessions/");
    }

    @Override
    public NaruSessionManager reload() {
        load(adapter.uuid());
        return this;
    }

    public NaruSessionManager load(String uuid) {
        NPath s = sessionFile(uuid, true);
        if (s.isRegularFile()) {
            adapter.load(NElementReader.ofTson().read(s));
            adapter.setVisibility(NAruVisibility.PUBLIC);
            return this;
        }
        s = sessionFile(uuid, false);
        if (s.isRegularFile()) {
            adapter.load(NElementReader.ofTson().read(s));
            adapter.setVisibility(NAruVisibility.PRIVATE);
        }
        return this;
    }

    public NaruSessionManager copyCurrent() {
        adapter.copy();
        return this;
    }

    public NaruSessionManager saveCurrent() {
        NPath pathOk = sessionFile(adapter.uuid(), adapter.getVisibility()==NAruVisibility.PUBLIC);
        NPath pathKo = sessionFile(adapter.uuid(), adapter.getVisibility()==NAruVisibility.PRIVATE);
        NElementWriter.ofTson().ntf(false).formatter(NElementFormatterStyle.PRETTY)
                .write(adapter.toElement(), pathOk.mkParentDirs());
        if (pathKo.isRegularFile()) {
            pathKo.delete();
        }
        return this;
    }

    public void dropCurrent() {
        delete(adapter.uuid());
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
            if (Objects.equals(NStringUtils.trim(s.getName()), NStringUtils.trim(uuidOrName))) {
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
        if (a.isRegularFile()) {
            a.delete();
            b = true;
        }
        a = sessionFile(uuid, false);
        if (a.isRegularFile()) {
            a.delete();
            b = true;
        }
        if (adapter.uuid().equals(uuid)) {
            adapter.reset(false);
            b = true;
        }
        return b;
    }

    private NPath sessionFile(String uuid, boolean publicSession) {
        return sessionDir(publicSession).resolve(uuid + ".tson");
    }

}
