package net.thevpc.naru.impl.engine.stmt;

import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NListContainerElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;

public class NaruReturnStmt extends NaruStatement implements Cloneable {
    private String expression;
    public NaruReturnStmt(String expression) {
        super(Type.RETURN);
        this.expression = expression;
    }

    public NaruReturnStmt(NElement element) {
        super(Type.RETURN, element);
        NListContainerElement lc = element.asListContainer().get();
        this.expression = lc.get("expression").flatMap(NElement::asStringValue).orNull();
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = (NObjectElementBuilder) super.toElement().builder();
        a.set("expression", NElement.ofString(expression));
        return a.build();
    }


    @Override
    public void exec(NaruTask task) {
        Object ret = task.evalExpression(expression);
        NaruTask t = task.popFrame();
        t.setReturnResult(ret);
        t.frame().lastResult(NaruStmtResult.ofSuccess(ret));
        task.defaultAdvance(this);
    }
}
