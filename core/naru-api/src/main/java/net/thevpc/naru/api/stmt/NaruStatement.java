package net.thevpc.naru.api.stmt;

import net.thevpc.naru.api.access.NaruActor;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NCopiable;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

public abstract class NaruStatement implements NToElement, NCopiable, Cloneable {
    public Type type;
    public boolean injected;
    /**
     * Who authored this statement. Null means "no claim", and the task's
     * {@link NaruTask#currentActor()} is used instead — which is the case for
     * every statement that existed before actors and for one the runtime injects
     * while executing another.
     */
    public NaruActor creatorActor;

    public enum Type {
        READLINE,
        PROMPT,
        TOOL_CALL,
        //        EXEC_ROUTINE_LINE,
        IF,
        ELSEIF,
        ELSE,
        END,
        WHILE,
        FOR,
        DIRECTIVE,
        SET_ROUTINE_LINE,
        APPEND_ROUTINE_LINE,
        SOURCE,
        START,
        SET,
        NOP,
        LABEL,
        GOTO,
        CALL,
        RETURN,
    }

    public NaruStatement() {
    }


    @Override
    public NElement toElement() {
        NObjectElementBuilder a = NElement.ofObjectBuilder(type.name());
        if (injected) {
            a.set("injected", injected);
        }
        if (creatorActor != null) {
            a.set("actor", creatorActor.toElement());
        }
        return a.build();
    }


    public NaruStatement(Type type) {
        this.type = type;
    }

    public NaruStatement(Type type, NElement element) {
        this.type = type;
        String name;
        if (element.isName()) {
            name = element.asName().get().stringValue();
        } else if (element.isListContainer()) {
            NListContainerElement o = element.asListContainer().get();
            if (o.isNamed()) {
                name = o.asNamed().get().name().get();
            } else {
                throw new NIllegalArgumentException(NMsg.ofC("invalid element %s", element));
            }
        } else {
            throw new NIllegalArgumentException(NMsg.ofC("invalid element %s", element));
        }
        if (!NNameFormat.CONST_NAME.format(name).equals(type.name())) {
            throw new NIllegalArgumentException(NMsg.ofC("invalid element %s", element));
        }
    }

    public boolean injected() {
        return injected;
    }

    public NaruStatement injected(boolean injected) {
        this.injected = injected;
        return this;
    }

    /** The actor that authored this statement, or null when it carries no claim. */
    public NaruActor creatorActor() {
        return creatorActor;
    }

    public NaruStatement creatorActor(NaruActor actor) {
        this.creatorActor = actor;
        return this;
    }

    @Override
    public NaruStatement copy() {
        return clone();
    }

    @Override
    protected NaruStatement clone() {
        try {
            return (NaruStatement) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    public abstract void exec(NaruTask task);
}
