package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.routine.NaruTaskFrame;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NListContainerElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;

public class NaruSetStmt extends NaruStatement implements Cloneable {
    private final String varName;
    private final String expression;

    public NaruSetStmt(String varName,String expression) {
        super(Type.PROMPT);
        this.varName = varName;
        this.expression = expression;
    }

    public NaruSetStmt(NElement element) {
        super(Type.PROMPT, element);
        NListContainerElement lc = element.asListContainer().get();
        this.varName = lc.get("varName").flatMap(NElement::asStringValue).orNull();
        this.expression = lc.get("expression").flatMap(NElement::asStringValue).orNull();
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = (NObjectElementBuilder) super.toElement().builder();
        a.set("varName", NElement.ofString(varName));
        a.set("expression", NElement.ofString(expression));
        return a.build();
    }

    @Override
    public void exec(NaruTask task) {
        NaruTaskFrame f = task.peekFrame();
        Object last = task.evalExpression(expression);
        f.bindParam(varName, last);
        task.defaultAdvance(this);
    }
}
