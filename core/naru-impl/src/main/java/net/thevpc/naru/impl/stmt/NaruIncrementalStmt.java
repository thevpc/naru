package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.stmt.NaruStatement;

public abstract class NaruIncrementalStmt extends NaruStatement {

    public NaruIncrementalStmt(Type type) {
        super(type);
    }

    public abstract boolean isPending() ;

    public abstract boolean acceptStatement(NaruStatement any, NaruSession session) ;


}
