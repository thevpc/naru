package net.thevpc.naru.impl.engine.stmt;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NListContainerElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;

public class NaruSetRoutineLineStmt extends NaruStatement implements Cloneable{
    public int number;
    public String command;

    public NaruSetRoutineLineStmt(int number, String command) {
        super(Type.SET_ROUTINE_LINE);
        this.number = number;
        this.command = command;
    }

    public NaruSetRoutineLineStmt(NElement element) {
        super(Type.SET_ROUTINE_LINE,element);
        NListContainerElement lc = element.asListContainer().get();
        this.number = lc.getIntValue("number").get();
        this.command = lc.getStringValue("command").orNull();
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = (NObjectElementBuilder) super.toElement().builder();
        a.set("number", number);
        if(command!=null){
            a.set("command", command);
        }
        return a.build();
    }

    @Override
    public void exec(NaruTask task) {
        task.setRoutineLine(number, command);
        task.defaultAdvance(this);
    }
}
