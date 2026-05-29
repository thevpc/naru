package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

public class NaruDirectiveReturn extends NaruSimpleStatement {
    public String expression;

    public NaruDirectiveReturn(String expression) {
        super(Type.DIRECTIVE_RETURN);
        this.expression = expression;
    }

    public NaruDirectiveReturn(NElement element) {
        super(Type.DIRECTIVE_RETURN);
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
            case "RETURN": {
                this.expression = element.asObject().get().get("expression").flatMap(x->x.asStringValue()).orNull();
            }
            default: {
                throw new NIllegalArgumentException(NMsg.ofC("invalid element %s", element));
            }
        }
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = NElement.ofObjectBuilder(type.name());
        if (expression != null) {
            a.set("expression", expression);
        }
        return a.build();
    }

    @Override
    public void exec(NaruSession session) {
        Object ret=null;
        if(expression!=null){
            ret = session.evalExpression(expression);
        }
        session.popContext().setReturnResult(ret);
    }
}
