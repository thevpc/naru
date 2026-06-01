//package net.thevpc.naru.impl.stmt;
//
//import net.thevpc.naru.api.agent.NaruLogMode;
//import net.thevpc.naru.api.agent.NaruTask;
//import net.thevpc.naru.api.routine.NaruRoutine;
//import net.thevpc.naru.api.routine.NaruRoutineManager;
//import net.thevpc.naru.api.routine.NaruTaskFrame;
//import net.thevpc.naru.api.stmt.NaruStatement;
//import net.thevpc.nuts.elem.NElement;
//import net.thevpc.nuts.text.NMsg;
//
//public abstract class NaruSimpleStatement extends NaruStatement implements Cloneable{
//    public NaruSimpleStatement(Type type) {
//        super(type);
//    }
//
//    public NaruSimpleStatement(Type type, NElement element) {
//        super(type,element);
//    }
//
//    @Override
//    public final void execAndAdvance(NaruTask task) {
//        NaruTaskFrame peekContext = task.peekContext();
//        exec(task);
//        if (peekContext.getRunningRoutine() != null) {
//            NaruRoutineManager sm = task.session().routineManager();
//            NaruRoutine currentScript = sm.getRoutine(peekContext.getRunningRoutine());
//            int ni = currentScript.nextPc(task.pc());
//            String li = currentScript.lineCommandAt(ni);
//            if(li!=null){
//                NaruStatement c = task.parseStatement(li).get();
//                if(c.type==Type.ELSE ||c.type==Type.ELSEIF || c.type==Type.END){
//                    peekContext.pc(-1);
//                    task.popFrame();
//                }else {
//                    peekContext.pc(ni);
//                    task.addStatement(c);
//                }
//            }else {
//                task.log(NaruLogMode.PROGRESS, NMsg.ofC("Script execution finished."));
//                task.pc(-1);
//                task.popFrame();
//            }
//        }
//    }
//
//    public abstract void exec(NaruTask task);
//}
