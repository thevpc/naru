package net.thevpc.naru.impl.routine;

import net.thevpc.naru.api.agent.NAruVisibility;
import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.routine.NaruRoutineManager;
import net.thevpc.naru.impl.agent.NaruSessionImpl;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.io.NDigest;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class NaruRoutineManagerImpl implements NaruRoutineManager {
    private final NaruSessionImpl session;

    public NaruRoutineManagerImpl(NaruSessionImpl session) {
        this.session = session;
    }

    public String resolveRoutineUuid(String uuidOrName) {
        List<NaruResourceInfo> list = routines();
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
    public NaruRoutine ensureRoutineExists(String routineName, NAruVisibility visibilityOnCreate, NaruTask naruTask) {
        routineName=NStringUtils.firstNonBlankTrimmed(routineName,"main");
        NaruRoutine rt = routine(routineName, naruTask).orNull();
        if(rt!=null){
            return rt;
        }
        if (NaruUtils.isPath(routineName) || NBlankable.isBlank(routineName)) {
            throw new NIllegalArgumentException(NMsg.ofC("Invalid routine name: %s", routineName));
        } else {
            NaruRoutineImpl r=new NaruRoutineImpl(UUID.randomUUID().toString(), routineName, routinesDir(NAruVisibility.PUBLIC), routinesDir(NAruVisibility.PRIVATE), null,
                    visibilityOnCreate ==null?NAruVisibility.PRIVATE: visibilityOnCreate
                    , true);
            r.flush();
            return r;
        }
    }

    @Override
    public NaruRoutine newRoutine(String routineName, NAruVisibility visibility, NaruTask naruTask) {
        if (NaruUtils.isPath(routineName) || NBlankable.isBlank(routineName)) {
            throw new NIllegalArgumentException(NMsg.ofC("Invalid routine name: %s", routineName));
        } else {
            NaruRoutine rt = routine(routineName, naruTask).orNull();
            if(rt!=null){
                throw new NIllegalArgumentException(NMsg.ofC("Routine already exist name: %s", routineName));
            }
            NaruRoutineImpl r=new NaruRoutineImpl(UUID.randomUUID().toString(), routineName, routinesDir(NAruVisibility.PUBLIC), routinesDir(NAruVisibility.PRIVATE), null,
                    visibility ==null?NAruVisibility.PRIVATE: visibility
                    , true);
            r.flush();
            return r;
        }
    }

    @Override
    public List<NaruResourceInfo> routines() {
        NPath publicDir = routinesDir(NAruVisibility.PUBLIC);
        NPath privateDir = routinesDir(NAruVisibility.PRIVATE);
        Map<String, NaruResourceInfo> list = new HashMap<>();
        for (NPath p : privateDir.list().stream().filter(x -> x.name().endsWith(".naru")).collect(Collectors.toList())) {
            String uuid = p.name().substring(0, p.name().length() - 5);
            try {
                NaruRoutineImpl u = new NaruRoutineImpl(uuid, null, publicDir, privateDir, null, NAruVisibility.PRIVATE, false);
                list.put(u.uuid(),
                        new NaruResourceInfo()
                                .setCreationDate(u.creationInstant())
                                .setModificationDate(u.modificationInstant())
                                .setVisibility(NAruVisibility.PRIVATE)
                                .setName(u.name())
                                .setUuid(u.uuid())
                );
            } catch (Exception ex) {
                //
            }
        }
        for (NPath p : publicDir.list().stream().filter(x -> x.name().endsWith(".naru")).collect(Collectors.toList())) {
            String uuid = p.name().substring(0, p.name().length() - 5);
            try {
                NaruRoutineImpl u = new NaruRoutineImpl(uuid, null, publicDir, privateDir, null, NAruVisibility.PRIVATE, false);
                if (!list.containsKey(u.uuid())) {
                    list.put(u.uuid(),
                            new NaruResourceInfo()
                                    .setCreationDate(u.creationInstant())
                                    .setModificationDate(u.modificationInstant())
                                    .setVisibility(NAruVisibility.PUBLIC)
                                    .setName(u.name())
                                    .setUuid(u.uuid())
                    );
                }
            } catch (Exception ex) {
                //
            }
        }
        return list.values().stream().sorted((o1, o2) -> o2.getModificationDate().compareTo(o1.getModificationDate())).collect(Collectors.toList());
    }


    private NPath routinesDir(NAruVisibility publicSession) {
        if (publicSession == NAruVisibility.PUBLIC) {
            return session.projectDir().resolve(".naru/routines/");
        }
        return session.projectDir().resolve(".naru/local/routines/");
    }

    @Override
    public NOptional<NaruRoutine> routine(String nameOrUuidOrPath, NaruTask task) {
        NPath publicDir = routinesDir(NAruVisibility.PUBLIC);
        NPath privateDir = routinesDir(NAruVisibility.PRIVATE);
        if (NaruUtils.isPath(nameOrUuidOrPath)) {
            NPath path = NPath.of(nameOrUuidOrPath).toAbsolute(task.workingDir());
            if (path.exists()) {
                String uuid = NDigest.of().sha256().addSource(path.toString().getBytes(StandardCharsets.UTF_8)).computeString();
                return NOptional.of(new NaruRoutineImpl(uuid, path.toString(), publicDir, privateDir, path, NAruVisibility.PUBLIC, true));
            }
            if (!path.name().endsWith(".naru") && !path.name().endsWith(".")) {
                path = NPath.of(nameOrUuidOrPath + ".naru").toAbsolute(task.workingDir());
                if (path.exists()) {
                    String uuid = NDigest.of().sha256().addSource(path.toString().getBytes(StandardCharsets.UTF_8)).computeString();
                    return NOptional.of(new NaruRoutineImpl(uuid, path.toString(), publicDir, privateDir, path, NAruVisibility.PUBLIC, true));
                }
            }
        } else {
            NPath t = privateDir.resolve(nameOrUuidOrPath + ".naru");
            if (t.exists()) {
                try {
                    return NOptional.of(new NaruRoutineImpl(nameOrUuidOrPath, null, publicDir, privateDir, null, NAruVisibility.PRIVATE, true));
                } catch (Exception ex) {
                    //
                }
            }
            t = publicDir.resolve(nameOrUuidOrPath + ".naru");
            if (t.exists()) {
                try {
                    return NOptional.of(new NaruRoutineImpl(nameOrUuidOrPath, null, publicDir, privateDir, null, NAruVisibility.PUBLIC, true));
                } catch (Exception ex) {
                    //
                }
            }
            for (NPath p : privateDir.list().stream().filter(x -> x.name().endsWith(".naru")).collect(Collectors.toList())) {
                String uuid = p.name().substring(0, p.name().length() - 5);
                try {
                    NaruRoutineImpl u = new NaruRoutineImpl(uuid, null, publicDir, privateDir, null, NAruVisibility.PRIVATE, true);
                    if (Objects.equals(u.name(), nameOrUuidOrPath)) {
                        return NOptional.of(u);
                    }
                } catch (Exception ex) {
                    //
                }
            }
            for (NPath p : publicDir.list().stream().filter(x -> x.name().endsWith(".naru")).collect(Collectors.toList())) {
                String uuid = p.name().substring(0, p.name().length() - 5);
                try {
                    NaruRoutineImpl u = new NaruRoutineImpl(uuid, null, publicDir, privateDir, null, NAruVisibility.PUBLIC, true);
                    if (Objects.equals(u.name(), nameOrUuidOrPath)) {
                        return NOptional.of(u);
                    }
                } catch (Exception ex) {
                    //
                }
            }

            NPath path = NPath.of(nameOrUuidOrPath).toAbsolute(task.workingDir());
            if (path.exists()) {
                String uuid = NDigest.of().sha256().addSource(path.toString().getBytes(StandardCharsets.UTF_8)).computeString();
                return NOptional.of(new NaruRoutineImpl(uuid, path.toString(), publicDir, privateDir, path, NAruVisibility.PUBLIC, true));
            }
            if (!path.name().endsWith(".naru") && !path.name().endsWith(".")) {
                path = NPath.of(nameOrUuidOrPath + ".naru").toAbsolute(task.workingDir());
                if (path.exists()) {
                    String uuid = NDigest.of().sha256().addSource(path.toString().getBytes(StandardCharsets.UTF_8)).computeString();
                    return NOptional.of(new NaruRoutineImpl(uuid, path.toString(), publicDir, privateDir, path, NAruVisibility.PUBLIC, true));
                }
            }
        }
        return NOptional.ofEmpty(NMsg.ofC("Error statement: routine not found %s", nameOrUuidOrPath));
    }

}
