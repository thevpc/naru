//package net.thevpc.naru.api.scheduler;
//
//import net.thevpc.naru.api.task.NaruTask;
//
//public class ChildrenTarget implements NaruEventTarget {
//    private final long sourceTid;
//
//    public ChildrenTarget(long sourceTid) {
//        this.sourceTid = sourceTid;
//    }
//
//    public boolean test(NaruTask candidate) {
//        return candidate.parentId() == sourceTid;
//    }
//}
