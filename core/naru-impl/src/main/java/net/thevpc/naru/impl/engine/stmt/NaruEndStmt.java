package net.thevpc.naru.impl.engine.stmt;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;

public class NaruEndStmt extends NaruFlowStatement implements Cloneable{

    public NaruEndStmt() {
        super(Type.END);
    }

    public NaruEndStmt(NElement element) {
        super(Type.END,element);
    }

    @Override
    public void exec(NaruTask task) {
        task.prependStatement(copy().injected(true));
    }
}
