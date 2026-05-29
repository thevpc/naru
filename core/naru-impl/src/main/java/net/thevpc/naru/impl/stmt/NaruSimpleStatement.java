package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.routine.NaruRoutineManager;
import net.thevpc.naru.api.routine.RunContext;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.impl.agent.NaruSessionImpl;
import net.thevpc.nuts.text.NMsg;

public abstract class NaruSimpleStatement extends NaruStatement {
    public NaruSimpleStatement(Type type) {
        super(type);
    }

    @Override
    public final void execAndAdvance(NaruSession session) {
        RunContext peekContext = ((NaruSessionImpl) session).peekContext();
        exec(session);
        if (peekContext.getRunningRoutine() != null) {
            NaruRoutineManager sm = session.routineManager();
            NaruRoutine currentScript = sm.getRoutine(peekContext.getRunningRoutine());
            int ni = currentScript.nextLineIndex(session.pc());
            String li = currentScript.getLine(ni);
            if(li!=null){
                NaruStatement c = session.agent().parseStatement(li).get();
                if(c.type==Type.ELSE ||c.type==Type.ELSEIF || c.type==Type.END){
                    peekContext.pc(-1);
                    session.popContext();
                }else {
                    peekContext.pc(ni);
                    session.addStatement(c);
                }
            }else {
                session.log(NaruLogMode.PROGRESS, NMsg.ofC("Script execution finished."));
                session.pc(-1);
                session.popContext();
            }
        }
    }

    public abstract void exec(NaruSession session);
}
