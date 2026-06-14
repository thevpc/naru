package net.thevpc.naru.impl.engine.stmt;

import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NListContainerElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;

public class NaruAppendRoutineLineStmt extends NaruStatement implements Cloneable{
    public int increment;
    public String command;

    public NaruAppendRoutineLineStmt(int increment, String command) {
        super(Type.APPEND_ROUTINE_LINE);
        this.increment = increment;
        this.command = command;
    }

    public NaruAppendRoutineLineStmt(NElement element) {
        super(Type.APPEND_ROUTINE_LINE,element);
        NListContainerElement lc = element.asListContainer().get();
        this.increment = lc.getIntValue("number").get();
        this.command = lc.getStringValue("command").orNull();
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = (NObjectElementBuilder) super.toElement().builder();
        a.set("number", increment);
        if(command!=null){
            a.set("command", command);
        }
        return a.build();
    }

    @Override
    public void exec(NaruTask task) {
        task.appendRoutineLine(increment, command);
        task.defaultAdvance(this);
    }
}
