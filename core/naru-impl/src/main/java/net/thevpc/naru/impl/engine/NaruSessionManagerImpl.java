package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NAruVisibility;
import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.agent.NaruSessionManager;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NStringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class NaruSessionManagerImpl implements NaruSessionManager {
    private final NaruSessionImpl adapter;

    public NaruSessionManagerImpl(NaruSessionImpl adapter) {
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
        a.sort((o1, o2) -> o2.getModificationInstant().compareTo(o1.getModificationInstant()));
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

    private static class NonSnapshotSessionFolder implements Predicate<NPath> {
        @Override
        public boolean test(NPath x) {
            return !x.name().equalsIgnoreCase("snapshot")
                    && x.resolve("session.tson").exists();
        }
    }
}
