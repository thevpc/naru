package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

public class NaruDefRoutineLineStmt extends NaruSimpleStatement {
    public int number;
    public String command;

    public NaruDefRoutineLineStmt(int number, String command) {
        super(Type.DEF_ROUTINE_LINE);
        this.number = number;
        this.command = command;
    }

    public NaruDefRoutineLineStmt(NElement element) {
        super(Type.DEF_ROUTINE_LINE);
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
            case "DEF_ROUTINE_LINE": {
                this.number = element.asListContainer().get().get("number").get().asIntValue().get();
                this.command = element.asListContainer().get().get("command").get().asStringValue().orNull();
            }
            default: {
                throw new NIllegalArgumentException(NMsg.ofC("invalid element %s", element));
            }
        }
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = NElement.ofObjectBuilder(type.name());
        a.set("number", number);
        a.set("command", command);
        return a.build();
    }

    @Override
    public void exec(NaruSession session) {
        session.routineManager().putLine(number, command);
    }
}
