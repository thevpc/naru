package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NListContainerElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;

public class NaruDirectiveReturn extends NaruStatement implements Cloneable {
    public String expression;

    public NaruDirectiveReturn(String expression) {
        super(Type.DIRECTIVE_RETURN);
        this.expression = expression;
    }

    public NaruDirectiveReturn(NElement element) {
        super(Type.DIRECTIVE_RETURN);
        NListContainerElement lc = element.asListContainer().get();
        this.expression = lc.get("expression").flatMap(NElement::asStringValue).orNull();
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = (NObjectElementBuilder) super.toElement().builder();
        if (expression != null) {
            a.set("expression", expression);
        }
        return a.build();
    }

    @Override
    public void exec(NaruTask task) {
        Object ret = null;
        if (expression != null) {
            ret = task.evalExpression(expression);
        }
        NaruTask f = task.popFrame();
        f.setReturnResult(ret);
        f.frame().lastResult(NaruStmtResult.ofSuccess(ret));
    }
}
