package net.thevpc.naru.impl.agent;

import net.thevpc.naru.api.agent.NaruSessionInfo;
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
    public List<NaruSessionInfo> list() {
        List<NaruSessionInfo> a = new ArrayList<>();
        for (NPath p : sessionDir().list().stream().filter(x -> x.getName().endsWith(".tson")).collect(Collectors.toList())) {
            a.add(NElementReader.ofTson().read(p, NaruSessionInfo.class));
        }
        return a;
    }


    @Override
    public int clear() {
        int count = 0;
        for (NPath p : sessionDir().list().stream().filter(x -> x.getName().endsWith(".tson")).collect(Collectors.toList())) {
            p.delete();
            count++;
        }
        adapter.reset();
        return count;
    }

    private NPath sessionDir() {
        return adapter.projectDir().resolve(".naru/sessions/");
    }

    public NaruSessionManager load(String uuid) {
        NPath s = sessionFile(uuid);
        if (s.isRegularFile()) {
            adapter.load(NElementReader.ofTson().read(s));
        }
        return this;
    }

    public NaruSessionManager copyCurrent() {
        adapter.copy();
        return this;
    }

    public NaruSessionManager saveCurrent() {
        NPath path = sessionFile(adapter.uuid());
        NElementWriter.ofTson().setNtf(false).setFormatter(NElementFormatterStyle.PRETTY)
                .write(adapter.toElement(), path.mkParentDirs());
        return this;
    }

    public void dropCurrent() {
        delete(adapter.uuid());
    }

    public String findByUuidOrName(String uuidOrName) {
        List<NaruSessionInfo> list = list();
        for (NaruSessionInfo s : list) {
            if (Objects.equals(s.getUuid(), uuidOrName)) {
                return s.getUuid();
            }
        }
        if (Objects.equals(adapter.uuid(), uuidOrName)) {
            return adapter.uuid();
        }

        for (NaruSessionInfo s : list) {
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
        NPath a = sessionFile(uuid);
        boolean b = false;
        if (a.isRegularFile()) {
            a.delete();
            b = true;
        }
        if (adapter.uuid().equals(uuid)) {
            adapter.reset();
            b = true;
        }
        return b;
    }

    private NPath sessionFile(String uuid) {
        return sessionDir().resolve(uuid + ".tson");
    }

}
