package net.thevpc.naru.impl.engine.stmt;

import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NListContainerElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;

public class NaruGotoStmt extends NaruStatement implements Cloneable {
    private String name;
    public NaruGotoStmt(String name) {
        super(Type.GOTO);
        this.name=name;
    }

    public NaruGotoStmt(NElement element) {
        super(Type.GOTO, element);
        NListContainerElement lc = element.asListContainer().get();
        this.name = lc.get("name").flatMap(NElement::asStringValue).orNull();
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = (NObjectElementBuilder) super.toElement().builder();
        a.set("name", NElement.ofString(name));
        return a.build();
    }


    @Override
    public void exec(NaruTask task) {
        task.defaultAdvance(this);
    }
}
