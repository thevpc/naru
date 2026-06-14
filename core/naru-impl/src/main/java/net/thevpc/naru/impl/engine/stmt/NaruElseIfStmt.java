package net.thevpc.naru.impl.engine.stmt;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.*;

public class NaruElseIfStmt extends NaruFlowStatement implements Cloneable{
    public String condition;

    public NaruElseIfStmt(String condition) {
        super(Type.ELSEIF);
        this.condition = condition;
    }

    public NaruElseIfStmt(NElement element) {
        super(Type.ELSEIF);
        NListContainerElement lc = element.asListContainer().get();
        this.condition = lc.get("condition").flatMap(NElement::asStringValue).orNull();
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = (NObjectElementBuilder) super.toElement().builder();
        a.set("condition", NElement.ofString(condition));
        return a.build();
    }

    @Override
    public void exec(NaruTask task) {
        task.prependStatement(copy().injected(true));
    }
}
