package net.thevpc.naru.api.stmt;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

public abstract class NaruStatement implements NToElement {
    public Type type;

    public enum Type {
        READLINE,
        MODEL_CALL,
        TOOL_CALL,
        EXEC_ROUTINE_LINE,
        IF,
        ELSEIF,
        ELSE,
        END,
        WHILE,
        FOR,
        DIRECTIVE_CALL,
        DEF_ROUTINE_LINE,
    }

    public NaruStatement() {
    }



    @Override
    public NElement toElement() {
        NObjectElementBuilder a = NElement.ofObjectBuilder(type.name());
        return a.build();
    }


    public NaruStatement(Type type) {
        this.type = type;
    }

    public abstract void exec(NaruSession session);
}
