package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElements;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

public class NaruElseIfStmt extends NaruStatement {
    public String condition;

    public NaruElseIfStmt(String condition) {
        super(Type.ELSEIF);
        this.condition = condition;
    }

    public NaruElseIfStmt(NElement element) {
        super(Type.ELSEIF);
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
            case "ELSEIF": {
                this.condition = element.asStringValue().orNull();
            }
            default: {
                throw new NIllegalArgumentException(NMsg.ofC("invalid element %s", element));
            }
        }
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = NElement.ofObjectBuilder(type.name());
        if (condition != null) {
            a.set("condition", NElements.of().toElement(condition));
        }
        return a.build();
    }

    @Override
    public void exec(NaruSession session) {

    }
}
