package net.thevpc.naru.api.routine;

import java.util.List;

public interface SubroutineDef {
    int startLine();

    int endLine();

    List<String> params();
}
