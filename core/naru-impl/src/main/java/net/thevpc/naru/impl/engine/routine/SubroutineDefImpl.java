package net.thevpc.naru.impl.engine.routine;

import java.util.List;

import net.thevpc.naru.api.routine.SubroutineDef;

public class SubroutineDefImpl implements SubroutineDef {
    private int startLine;
    int endLine;
    List<String> params;

    public SubroutineDefImpl(int startLine, int endLine, List<String> params) {
        this.startLine = startLine;
        this.endLine = endLine;
        this.params = params;
    }

    @Override
    public int startLine() {
        return startLine;
    }

    @Override
    public int endLine() {
        return endLine;
    }

    @Override
    public List<String> params() {
        return params;
    }
}
