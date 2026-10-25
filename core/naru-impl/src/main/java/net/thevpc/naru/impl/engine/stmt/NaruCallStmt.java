package net.thevpc.naru.impl.engine.stmt;

import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NListContainerElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;

public class NaruCallStmt extends NaruStatement implements Cloneable {
    private final String cmd;

    public NaruCallStmt(String cmd) {
        super(Type.CALL);
        this.cmd = cmd;
    }

    public NaruCallStmt(NElement element) {
        super(Type.GOTO, element);
        NListContainerElement lc = element.asListContainer().get();
        this.cmd = lc.get("cmd").flatMap(NElement::asStringValue).orNull();
    }

    @Override
    public NObjectElementBuilder toElementBuilder() {
        NObjectElementBuilder a = super.toElementBuilder();
        a.set("cmd", NElement.ofString(cmd));
        return a;
    }


    @Override
    public void exec(NaruTask task) {
        task.call(cmd);
    }
}
