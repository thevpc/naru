package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.routine.NaruRoutineManager;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.io.NTerminal;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NCancelException;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

public class NaruExecRoutineLineStmt extends NaruStatement {

    public NaruExecRoutineLineStmt() {
        super(Type.EXEC_ROUTINE_LINE);
    }

    public NaruExecRoutineLineStmt(NElement element) {
        super(Type.EXEC_ROUTINE_LINE);
        String name;
        if (element.isName()) {
            name = element.asName().get().stringValue();
        } else if (element.isAnyObject()) {
            NObjectElement o = element.asObject().get();
            name = o.asNamed().get().name().get();
        } else {
            throw new NIllegalArgumentException(NMsg.ofC("invalid element %s", element));
        }
        switch (NNameFormat.CONST_NAME.format(name)) {
            case "EXEC_ROUTINE_LINE": {
                break;
            }
            default: {
                throw new NIllegalArgumentException(NMsg.ofC("invalid element %s", element));
            }
        }
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = NElement.ofObjectBuilder(type.name());
        return a.build();
    }

    @Override
    public void exec(NaruSession session) {
        NaruRoutineManager sm = session.routineManager();
        NaruRoutine currentScript = sm.getRoutine(sm.getCurrentRoutineName());
        String lineText = currentScript.getLines().get(session.pc());

        if (lineText == null) {
            session.log(NaruLogMode.PROGRESS, NMsg.ofC("Script execution finished."));
            session.pc(-1);
            if (session.isForever()) {
                session.pushStatement(NaruStatementHelper.ofReadLine());
            }
            return;
        }
//        String trimmed = lineText.trim();
//        if (trimmed.startsWith("/call ")) {
//            handleCallDirective(trimmed, session, currentScript);
//            return; // Don't push MODEL_CALL; subroutine handles next step
//        }
//        if (trimmed.equals("/return")) {
//            handleReturnDirective(session);
//            return;
//        }

        String prompt = "Execute line " + session.pc() + ": " + lineText;
        session.log(NaruLogMode.SCRIPT, NMsg.ofC(prompt));
        session.addHistory(NaruMessage.user(prompt));
        session.pushStatement(NaruStatementHelper.ofModelCall());
    }
}
