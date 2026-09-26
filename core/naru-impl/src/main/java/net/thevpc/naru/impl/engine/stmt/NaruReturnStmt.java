package net.thevpc.naru.impl.engine.stmt;

import net.thevpc.naru.api.model.NaruMessage;
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
        Object ret = expression==null?null:task.evalExpression(expression);
        task.frame().lastResult(NaruStmtResult.ofSuccess(ret));
        task.popFrame();
        // A return that unwinds past the last frame is the task's own return value, and
        // nothing else would ever set it: popFrame only propagates between frames, so a
        // top-level return would otherwise leave getReturnResult() null forever. Recording
        // it here is what lets a caller ask a finished task what it produced.
        if (task.stackframes().isEmpty()) {
            task.setReturnResult(ret);
        }
        task.defaultAdvance(this);
    }
}
