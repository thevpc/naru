package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;

public class NaruNopStmt extends NaruStatement implements Cloneable {
    public NaruNopStmt() {
        super(Type.NOP);
    }

    public NaruNopStmt(NElement element) {
        super(Type.NOP, element);
    }

    @Override
    public void exec(NaruTask task) {
        task.defaultAdvance(this);
    }
}
