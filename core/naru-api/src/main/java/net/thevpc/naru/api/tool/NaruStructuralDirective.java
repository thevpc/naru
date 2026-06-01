package net.thevpc.naru.api.tool;

import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;

public interface NaruStructuralDirective extends NaruDirective{
    NaruStatement toStatement(String arguments, NaruTask task);
}
