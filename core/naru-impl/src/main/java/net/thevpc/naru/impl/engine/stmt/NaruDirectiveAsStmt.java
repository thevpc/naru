package net.thevpc.naru.impl.engine.stmt;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.nuts.elem.*;

public class NaruDirectiveAsStmt extends NaruStatement implements Cloneable {
    public String call;

    public NaruDirectiveAsStmt(String call) {
        super(Type.DIRECTIVE);
        this.call = call;
    }

    public NaruDirectiveAsStmt(NElement element) {
        super(Type.DIRECTIVE);
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
