package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.nuts.elem.NElement;

import java.util.List;

public class NaruForStmt extends NaruIncrementalStmt {
    private String condition;
    private List<NaruStatement> branch;
    private boolean closed;

    public NaruForStmt() {
        super(NaruStatement.Type.FOR);
    }

    public NaruForStmt(NElement element) {
        super(NaruStatement.Type.FOR);
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
