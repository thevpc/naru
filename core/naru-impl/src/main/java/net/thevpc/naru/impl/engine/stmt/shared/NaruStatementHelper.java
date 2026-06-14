package net.thevpc.naru.impl.engine.stmt.shared;

import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.impl.engine.stmt.*;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;
import net.thevpc.nuts.util.NOptional;

import java.util.Set;

public class NaruStatementHelper {
    public static final Set<String> STATEMENT_KEYWORDS = Set.of("for", "while", "if", "else", "elseif", "end", "goto");
    public static NOptional<NaruStatement> of(String cmd,String args) {
        if(cmd.startsWith(":") || cmd.endsWith(":")) {
            return NOptional.of(new NaruLabelStmt(cmd.replace(":"," ").trim()));
        }
        switch (cmd.toLowerCase()){
            case "for":{
                return NOptional.of(new NaruForStmt(args));
            }
            case "while":{
                return NOptional.of(new NaruWhileStmt(args));
            }
            case "goto":{
                return NOptional.of(new NaruGotoStmt(args));
            }
            case "if":{
                return NOptional.of(new NaruIfStmt(args));
            }
            case "else":{
                return NOptional.of(new NaruElseStmt());
            }
            case "elseif":{
                return NOptional.of(new NaruElseIfStmt(args));
            }
            case "end":{
                return NOptional.of(new NaruEndStmt());
            }
            case "call":{
                return NOptional.of(new NaruCallStmt(args));
            }
        }
        return NOptional.ofNamedEmpty("unknown statement");
    }

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
            case "SET_ROUTINE_LINE":
                return new NaruSetRoutineLineStmt(element);
            case "APPEND_ROUTINE_LINE":
                return new NaruAppendRoutineLineStmt(element);
            case "DIRECTIVE":
                return new NaruDirectiveAsStmt(element);
            case "RETURN":
                return new NaruReturnStmt(element);
            case "SET":
                return new NaruSetStmt(element);
            case "NOP":
                return new NaruNopStmt(element);
            case "LABEL":
                return new NaruLabelStmt(element);
            case "GOTO":
                return new NaruGotoStmt(element);
            case "CALL":
                return new NaruCallStmt(element);
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

//    public static NaruStatement ofReturn() {
//        return ofReturn(null);
//    }
//
//    public static NaruStatement ofReturn(String expression) {
//        return new NaruDirectiveReturn(expression);
//    }

}
