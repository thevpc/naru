//package net.thevpc.naru.api.scheduler;
//
//import net.thevpc.naru.api.task.NaruTask;
//
//public class SiblingsTarget implements NaruEventTarget {
//    private final long sourceTid;
//    private final long sourceParentTid;
//
//    public SiblingsTarget(long sourceTid, long sourceParentTid) {
//        this.sourceTid = sourceTid;
//        this.sourceParentTid = sourceParentTid;
//    }
//
//    public SiblingsTarget(NaruTask t) {
//        this.sourceTid = t.id();
//        this.sourceParentTid = t.parentId();
//    }
//
//
//    public boolean test(NaruTask candidate) {
//        return candidate.parentId() == sourceParentTid
//                && candidate.id() != sourceTid;
//    }
//}
