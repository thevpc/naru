//package net.thevpc.naru.api.routine;
//
//import net.thevpc.naru.api.agent.NAruVisibility;
//import net.thevpc.naru.api.agent.NaruResourceInfo;
//import net.thevpc.naru.api.task.NaruTask;
//import net.thevpc.nuts.util.NOptional;
//
//import java.util.List;
//
//public interface NaruRoutineManager {
//    NOptional<NaruRoutine> routine(String nameOrUuidOrPath, NaruTask task);
//
//    List<NaruResourceInfo> routines();
//
//    NaruRoutine ensureRoutineExists(String currentScriptName, NAruVisibility visibilityOnCreate, NaruTask naruTask);
//
//    NaruRoutine newRoutine(String currentScriptName, NAruVisibility visibility, NaruTask naruTask);
//}
