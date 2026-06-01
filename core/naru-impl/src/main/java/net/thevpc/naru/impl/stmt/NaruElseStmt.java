package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

public class NaruElseStmt extends NaruStatement implements Cloneable {

    public NaruElseStmt() {
        super(Type.ELSE);
    }

    public NaruElseStmt(NElement element) {
        super(Type.ELSE,element);
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = (NObjectElementBuilder) super.toElement().builder();
        return a.build();
    }

    @Override
    public void exec(NaruTask task) {
        task.throwError(NMsg.ofC("invalid else statement"));
    }
}
