package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

public class NaruStatementHelper {

    public static NaruStatement of(NElement element) {
        String name;
        if (element.isName()) {
            name = element.asName().get().stringValue();
        } else if (element.isAnyObject()) {
            NObjectElement o = element.asObject().get();
            name = o.asNamed().get().name().get();
        } else {
            throw new NIllegalArgumentException(NMsg.ofC("invalid element %s", element));
        }
        switch (NNameFormat.CONST_NAME.format(name)) {
            case "READLINE":
            case "READ_LINE":
                return new NaruReadlineStmt(element);
            case "TOOL_CALL":
                return new NaruToolCallStmt(element);
            case "MODEL_CALL":
                return new NaruPromptStmt(element);
//            case "EXEC_ROUTINE_LINE":
//                return new NaruExecRoutineLineStmt(element);
            case "IF":
                return new NaruIfStmt(element);
            case "END":
                return new NaruEndStmt(element);
            case "ELSE":
                return new NaruElseStmt(element);
            case "ELSEIF":
                return new NaruElseIfStmt(element);
            case "WHILE":
                return new NaruWhileStmt(element);
            case "FOR":
                return new NaruForStmt(element);
            case "DEF_ROUTINE_LINE":
                return new NaruDefRoutineLineStmt(element);
            case "CALL":
                return new NaruDirectiveCallStmt(element);
            case "RETURN":
                return new NaruDirectiveReturn(element);
            case "SET":
                return new NaruSetStmt(element);
            case "NOP":
                return new NaruNopStmt(element);
        }
        throw new NIllegalArgumentException(NMsg.ofC("invalid element %s", element));
    }

    public static NaruStatement ofReadLine() {
        return new NaruReadlineStmt();
    }

//    public static NaruStatement ofExecRoutineLine() {
//        return new NaruExecRoutineLineStmt();
//    }

    public static NaruStatement ofToolCall(NaruToolCall call) {
        return new NaruToolCallStmt(call);
    }

    public static NaruStatement ofModelCall(String prompt) {
        return new NaruPromptStmt(prompt);
    }

    public static NaruStatement ofReturn() {
        return ofReturn(null);
    }

    public static NaruStatement ofReturn(String expression) {
        return new NaruDirectiveReturn(expression);
    }

}
