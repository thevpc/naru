package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.nuts.elem.*;

public class NaruDirectiveCallStmt extends NaruStatement implements Cloneable{
    public String call;

    public NaruDirectiveCallStmt(String call) {
        super(Type.CALL);
        this.call = call;
    }

    public NaruDirectiveCallStmt(NElement element) {
        super(Type.CALL);
        NListContainerElement lc = element.asListContainer().get();
        this.call = lc.get("call").flatMap(NElement::asStringValue).orNull();
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = (NObjectElementBuilder) super.toElement().builder();
        if (call != null) {
            a.set("call", call);
        }
        return a.build();
    }

    @Override
    public void exec(NaruTask task) {
        task.invokeDirective(call);
        task.defaultAdvance(this);
    }
}
