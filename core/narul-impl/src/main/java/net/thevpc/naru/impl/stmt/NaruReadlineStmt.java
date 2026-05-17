package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.agent.NaruSession;
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

public class NaruReadlineStmt extends NaruStatement {

    public NaruReadlineStmt() {
        super(Type.READLINE);
    }

    public NaruReadlineStmt(NElement element) {
        super(Type.READLINE);
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
            case "READLINE": {
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
        String line = null;
        try {
            line = NTerminal.of().readLine(NMsg.ofC("%s%s ", NMsg.ofStyledPrimary1("naru"), NMsg.ofStyledSeparator(">")));
        } catch (NCancelException e) {
            // CTRL-C ?
            session.pushStatement(NaruStatementHelper.ofReadLine());
        }
        if (line == null) {
            return;
        }
        line = line.trim();
        if (line.isEmpty()) {
            session.pushStatement(NaruStatementHelper.ofReadLine());
            return;
        }

        if (line.startsWith("/")) {
            if (session.isForever()) {
                session.pushStatement(NaruStatementHelper.ofReadLine());
            }
            session.runner().runDirective(line, session);
            return;
        }

        NaruRoutineManager sm = session.routineManager();
        if (sm.tryParseLine(line)) {
            // Line successfully added to script
            session.pushStatement(NaruStatementHelper.ofReadLine());
            return;
        }

        if (session.addHistory(line)) {
            session.pushStatement(NaruStatementHelper.ofModelCall());
        } else {
            session.pushStatement(NaruStatementHelper.ofReadLine());
        }
    }
}
