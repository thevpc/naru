package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.routine.NaruRoutineManager;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

import java.util.ArrayList;
import java.util.List;

public class NaruModelCallStmt extends NaruStatement {
    private String prompt;
    public NaruModelCallStmt(String prompt) {
        super(Type.MODEL_CALL);
        this.prompt = prompt;
    }

    public NaruModelCallStmt(NElement element) {
        super(Type.MODEL_CALL);
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
            case "MODEL_CALL": {
                this.prompt = element.asObject().get().get("prompt").flatMap(NElement::asStringValue).orNull();
                break;
            }
            default: {
                throw new NIllegalArgumentException(NMsg.ofC("invalid element %s", element));
            }
        }
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = NElement.ofObjectBuilder(type.name());
        if(!NBlankable.isBlank(prompt)){
            a.set("prompt", NElement.ofString(prompt));
        }
        return a.build();
    }

    @Override
    public void exec(NaruSession session) {
        session.log(NaruLogMode.PROGRESS, NMsg.ofC("%s Model: %s…",
                NMsg.ofStyledPrimary8("\uD83E\uDDE0"),
                session.model().toText()
        ));
        if(!NBlankable.isBlank(prompt)){
            session.addHistory(NaruMessage.user(prompt));
        }
        NaruResponse response;
        List<NaruToolDefinition> defs = new ArrayList<>();
        for (NaruTool t : session.registry().tools().values()) {
            defs.add(t.getDefinition(session));
        }
        try {
            response = session.chat(session.model(), session.history(true),
                    defs);
        } catch (Exception e) {
            String err = "ERROR calling model: " + e.getMessage();
            session.log(NaruLogMode.PROGRESS, NMsg.ofC("%s", err).asError());
//            if (session.isForever()) {
//                session.pushStatement(NaruStatementHelper.ofReadLine());
//            }
            return;
        }

        NaruMessage assistantMsg = response.getMessage();
        if (assistantMsg == null) {
            session.log(NaruLogMode.DEBUG, NMsg.ofC("Model returned empty response."));
//            if (session.isForever()) {
//                session.pushStatement(NaruStatementHelper.ofReadLine());
//            }
            return;
        }
        session.addHistory(assistantMsg);
        // ── Case 1: model wants to call tools ─────────────────────────────
        if (assistantMsg.hasToolCalls()) {
            List<NaruToolCall> toolCalls = assistantMsg.getToolCalls();
//            if (session.isForever()) {
//                session.pushStatement(NaruStatementHelper.ofReadLine());
//            }
            session.pushStatement(NaruStatementHelper.ofModelCall(null));
            for (int i = toolCalls.size() - 1; i >= 0; i--) {
                session.pushStatement(NaruStatementHelper.ofToolCall(toolCalls.get(i)));
            }
            if (!NBlankable.isBlank(assistantMsg.getContent())) {
                session.log(NaruLogMode.MODEL_RESPONSE, NMsg.ofC("%s", assistantMsg.getContent()));
            }
            return;
        }
        session.log(NaruLogMode.MODEL_RESPONSE, NMsg.ofC("%s", assistantMsg.getContent()));
        session.setLastResult(assistantMsg);

        if (session.pc() != -1) {
            NaruRoutineManager sm = session.routineManager();
            NaruRoutine currentScript = sm.getRoutine(sm.getCurrentRoutineName());
            Integer nextPc = currentScript.getLines().higherKey(session.pc());
            if (nextPc != null) {
                session.pc(nextPc);
                session.pushStatement(NaruStatementHelper.ofExecRoutineLine());
            } else {
                session.log(NaruLogMode.PROGRESS, NMsg.ofC("%s Script execution finished.",
                        NMsg.ofStyledSuccess("✅")
                ));
                session.pc(-1);
//                if (session.isForever()) {
//                    session.pushStatement(NaruStatementHelper.ofReadLine());
//                }
            }
        }
//        else if (session.isForever()) {
//            session.pushStatement(NaruStatementHelper.ofReadLine());
//        }
    }
}
