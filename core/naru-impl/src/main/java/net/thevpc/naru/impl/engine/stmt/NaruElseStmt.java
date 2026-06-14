package net.thevpc.naru.impl.engine.stmt;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.text.NMsg;

public class NaruElseStmt extends NaruStatement implements Cloneable {

    public NaruElseStmt() {
        super(Type.ELSE);
    }

    public NaruElseStmt(NElement element) {
        super(Type.ELSE,element);
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = (NObjectElementBuilder) super.toElement().builder();
        return a.build();
    }

    @Override
    public void exec(NaruTask task) {
        task.prependStatement(copy().injected(true));
    }
}
