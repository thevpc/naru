package net.thevpc.naru.impl.engine.stmt;

import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.nuts.elem.NElement;

public abstract class NaruFlowStatement extends NaruStatement implements Cloneable {
    public NaruFlowStatement(Type type) {
        super(type);
    }

    public NaruFlowStatement(Type type, NElement element) {
        super(type, element);
    }
}
