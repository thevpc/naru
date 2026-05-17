package net.thevpc.naru.api.agent;

import java.util.List;

public interface SubroutineDef {
    int startLine();

    int endLine();

    List<String> params();
}
