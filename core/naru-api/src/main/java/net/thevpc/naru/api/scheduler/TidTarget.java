//package net.thevpc.naru.api.scheduler;
//
//import net.thevpc.naru.api.task.NaruTask;
//
//public class TidTarget implements NaruEventTarget{
//    private final long tid;
//
//    public TidTarget(long tid) {
//        this.tid = tid;
//    }
//
//    public boolean test(NaruTask candidate) {
//        return candidate.id() == tid;
//    }
//}
