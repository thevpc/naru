package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;

public interface NaruStructuralDirective extends NaruDirective{
    NaruStatement toStatement(String arguments, NaruTask task);
}
