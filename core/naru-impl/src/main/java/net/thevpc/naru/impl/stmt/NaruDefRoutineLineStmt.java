package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NListContainerElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;

public class NaruDefRoutineLineStmt extends NaruStatement implements Cloneable{
    public int number;
    public String command;

    public NaruDefRoutineLineStmt(int number, String command) {
        super(Type.DEF_ROUTINE_LINE);
        this.number = number;
        this.command = command;
    }

    public NaruDefRoutineLineStmt(NElement element) {
        super(Type.DEF_ROUTINE_LINE,element);
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
        task.saveRoutineLine(number, command);
        task.defaultAdvance(this);
    }
}
