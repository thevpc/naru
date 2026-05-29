package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

public class NaruDirectiveCallStmt extends NaruSimpleStatement {
    public String call;

    public NaruDirectiveCallStmt(String call) {
        super(Type.CALL);
        this.call = call;
    }

    public NaruDirectiveCallStmt(NElement element) {
        super(Type.CALL);
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
            case "CALL": {
                this.call = element.asObject().get().get("call").get().asStringValue().get();
            }
            default: {
                throw new NIllegalArgumentException(NMsg.ofC("invalid element %s", element));
            }
        }
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = NElement.ofObjectBuilder(type.name());
        if (call != null) {
            a.set("call", call);
        }
        return a.build();
    }

    @Override
    public void exec(NaruSession session) {
        session.agent().invokeDirective(call, session);
    }
}
