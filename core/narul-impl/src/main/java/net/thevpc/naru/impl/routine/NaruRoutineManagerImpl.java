package net.thevpc.naru.impl.routine;

import net.thevpc.naru.api.agent.NAruVisibility;
import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.routine.NaruRoutineManager;
import net.thevpc.naru.impl.agent.NaruSessionImpl;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NAssert;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NStringUtils;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class NaruRoutineManagerImpl implements NaruRoutineManager {
    private String currentScriptName = "main";
    private static final Pattern LINE_PATTERN = Pattern.compile("^(\\d+)(?:\\s+(.*))?$");
    private static final Pattern METADATA = Pattern.compile("^([a-z]+)\\s*:(\\s*(.*))?$");
    private final NaruSessionImpl session;
    private final NaruRoutineImpl defaultMain = new NaruRoutineImpl("main");

    public NaruRoutineManagerImpl(NaruSessionImpl session) {
        this.session = session;
    }

    public String findByUuidOrName(String uuidOrName) {
        List<NaruResourceInfo> list = list();
        for (NaruResourceInfo s : list) {
            if (Objects.equals(s.getUuid(), uuidOrName)) {
                return s.getUuid();
            }
        }

        for (NaruResourceInfo s : list) {
            if (Objects.equals(NStringUtils.trim(s.getName()), NStringUtils.trim(uuidOrName))) {
                return s.getUuid();
            }
        }
        if (Objects.equals(session.name(), uuidOrName)) {
            return session.uuid();
        }
        Integer index = NLiteral.of(uuidOrName).asInt().orNull();
        if (index != null) {
            if (index - 1 >= 0 && index - 1 < list.size()) {
                return list.get(index - 1).getUuid();
            }
        }
        return null;
    }

    @Override
    public List<NaruResourceInfo> list() {
        List<NaruResourceInfo> a = new ArrayList<>();
        for (NPath p : routinesDir(NAruVisibility.PUBLIC).list().stream().filter(x -> x.name().endsWith(".tson")).collect(Collectors.toList())) {
            NaruResourceInfo s = NElementReader.ofTson().read(p, NaruResourceInfo.class);
            s.setMode(NAruVisibility.PUBLIC);
            s.setCreationDate(p.creationInstant());
            s.setModificationDate(p.lastModifiedInstant());
            a.add(s);
        }
        for (NPath p : routinesDir(NAruVisibility.PRIVATE).list().stream().filter(x -> x.name().endsWith(".tson")).collect(Collectors.toList())) {
            NaruResourceInfo s = NElementReader.ofTson().read(p, NaruResourceInfo.class);
            s.setMode(NAruVisibility.PRIVATE);
            s.setCreationDate(p.creationInstant());
            s.setModificationDate(p.lastModifiedInstant());
            a.add(s);
        }
        if (a.stream().noneMatch(x -> Objects.equals("main", x.getName()))) {
            Instant now = Instant.now();
            a.add(
                    new NaruResourceInfo()
                            .setName(defaultMain.getName())
                            .setCreationDate(now)
                            .setModificationDate(now)
            );
        }
        a.sort((o1, o2) -> o2.getModificationDate().compareTo(o1.getModificationDate()));
        return a;
    }


    private NPath routinesDir(NAruVisibility publicSession) {
        if (publicSession==NAruVisibility.PUBLIC) {
            return session.projectDir().resolve(".naru/routines/");
        }
        return session.projectDir().resolve(".naru/local/routines/");
    }

    @Override
    public NaruRoutine getRoutine(String name) {
        String a = findByUuidOrName(name);
        if (a != null) {
            return load(a);
        }
        if ("main".equals(name)) {
            return defaultMain;
        }
        return null;
    }

    @Override
    public NaruRoutine getCurrentRoutine() {
        return getRoutine(currentScriptName);
    }

    @Override
    public String getCurrentRoutineName() {
        return currentScriptName;
    }

    @Override
    public void switchRoutine(String name) {
        this.currentScriptName = name;
    }

    @Override
    public void putLine(int number, String text) {
        NaruRoutine r = getCurrentRoutine();
        r.putLine(number, text);
        save(r);
    }

    @Override
    public void removeLine(int number) {
        getCurrentRoutine().removeLine(number);
    }

    @Override
    public void clearCurrent() {
        getCurrentRoutine().clear();
    }

    @Override
    public String listCurrent() {
        return getCurrentRoutine().getFormattedText();
    }

    private synchronized NaruRoutine load(String uuid) {
        NPath path1 = routinesDir(NAruVisibility.PRIVATE).resolve(uuid + ".md");
        NPath path2 = routinesDir(NAruVisibility.PUBLIC).resolve(uuid);
        NPath path;
        NAruVisibility visibility = NAruVisibility.PUBLIC;
        if (path1.isRegularFile()) {
            visibility = NAruVisibility.PRIVATE;
            path = path1;
        } else if (path2.isRegularFile()) {
            visibility = NAruVisibility.PUBLIC;
            path = path2;
        } else {
            return null;
        }
        NaruRoutineImpl r = new NaruRoutineImpl(path.name());
        String text = path.readString();
        clearCurrent();
        boolean metadata = true;
        for (String line : text.split("\n")) {
            line = line.trim();
            if (!line.isEmpty()) {
                if (metadata) {
                    Matcher m0 = METADATA.matcher(line);
                    if (m0.matches()) {
                        String n = m0.group(1);
                        String content = m0.group(2);
                        switch (n) {
                            case "name": {
                                if (NBlankable.isBlank(content)) {
                                    r.setName(content);
                                }
                                break;
                            }
                        }
                    } else {
                        metadata = false;
                        Matcher m = LINE_PATTERN.matcher(line);
                        if (m.matches()) {
                            int num = Integer.parseInt(m.group(1));
                            String content = m.group(2) != null ? m.group(2) : "";
                            r.putLine(num, content);
                        }
                    }
                } else {
                    Matcher m = LINE_PATTERN.matcher(line);
                    if (m.matches()) {
                        int num = Integer.parseInt(m.group(1));
                        String content = m.group(2) != null ? m.group(2) : "";
                        r.putLine(num, content);
                    }
                }
            }
        }
        r.setVisibility(visibility);
        save(r);
        return r;
    }

    public synchronized void save(NaruRoutine r) {
        NAssert.requireNamedNonBlank(r.getName(), "name");
        if (!NStringUtils.isValidVar(r.getName())) {
            throw new IllegalArgumentException("invalid name " + r.getName());
        }
        if (NBlankable.isBlank(r.uuid())) {
            ((NaruRoutineImpl) r).setUuid(UUID.randomUUID().toString());
        }
        NPath pub = routinesDir(NAruVisibility.PUBLIC);
        NPath priv = routinesDir(NAruVisibility.PRIVATE);
        if (r.getVisibility()==NAruVisibility.PUBLIC) {
            if (priv.isRegularFile()) {
                priv.delete();
            }
            _write(pub, r);
        } else {
            if (pub.isRegularFile()) {
                pub.delete();
            }
            _write(priv, r);
        }
    }

    private void _write(NPath pub, NaruRoutine r) {
        StringBuilder sb = new StringBuilder();
        sb.append("name : ").append(NStringUtils.firstNonBlankTrimmed(r.getName(), "NO_NAME")).append("\n");
        sb.append("\n");
        for (Map.Entry<Integer, String> e : r.getLines().entrySet()) {
            sb.append(e.getKey()).append(" ").append(e.getValue()).append("\n");
        }
        pub.mkParentDirs().writeString(sb.toString());
    }

    @Override
    public boolean tryParseLine(String input) {
        Matcher m = LINE_PATTERN.matcher(input);
        if (m.matches()) {
            int num = Integer.parseInt(m.group(1));
            String content = m.group(2) != null ? m.group(2).trim() : "";
            if (content.isEmpty()) {
                removeLine(num);
            } else {
                putLine(num, content);
            }
            return true;
        }
        return false;
    }
}
