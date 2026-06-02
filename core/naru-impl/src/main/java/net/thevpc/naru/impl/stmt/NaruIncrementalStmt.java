package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.nuts.elem.NElement;

public abstract class NaruIncrementalStmt extends NaruFlowStatement implements Cloneable{

    public NaruIncrementalStmt(Type type) {
        super(type);
    }
    public NaruIncrementalStmt(Type type, NElement element) {
        super(type,element);
    }

    public abstract boolean isPending() ;

    public abstract boolean acceptStatement(NaruStatement any, NaruTask task) ;


}
