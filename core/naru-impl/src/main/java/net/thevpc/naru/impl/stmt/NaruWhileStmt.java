package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.nuts.elem.NElement;

import java.util.List;

public class NaruWhileStmt extends NaruIncrementalStmt {
    String condition;
    public List<NaruStatement> branch;
    public boolean closed;

    public NaruWhileStmt(String condition) {
        super(Type.WHILE);
        this.condition = condition;
    }

    public NaruWhileStmt(NElement element) {
        super(Type.WHILE);
    }

    @Override
    public boolean isPending() {
        return !closed;
    }

    @Override
    public boolean acceptStatement(NaruStatement any, NaruSession session) {
        return false;
    }

    @Override
    public void exec(NaruSession session) {

    }
}
