package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NListContainerElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;

public class NaruPromptStmt extends NaruStatement implements Cloneable {
    private final String prompt;

    public NaruPromptStmt(String prompt) {
        super(Type.PROMPT);
        this.prompt = prompt;
    }

    public NaruPromptStmt(NElement element) {
        super(Type.PROMPT, element);
        NListContainerElement lc = element.asListContainer().get();
        this.prompt = lc.get("prompt").flatMap(NElement::asStringValue).orNull();
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = (NObjectElementBuilder) super.toElement().builder();
        if (!NBlankable.isBlank(prompt)) {
            a.set("prompt", NElement.ofString(prompt));
        }
        return a.build();
    }

    @Override
    public void exec(NaruTask task) {
        if (task.model() == null) {
            throw new NIllegalArgumentException(NMsg.ofC("no model selected. use '%s' to select one."
                    , NMsg.ofCode("bash", "/model")
            ));
        }
        task.log(NaruLogMode.PROGRESS, NMsg.ofC("%s Model: %s…",
                NMsg.ofStyledPrimary8("\uD83E\uDDE0"),
                task.model().toText()
        ));
        if (!NBlankable.isBlank(prompt)) {
            task.addHistory(NaruMessage.user(prompt));
        }
        NaruResponse response;
        List<NaruToolDefinition> defs = new ArrayList<>();
        for (NaruTool t : task.session().registry().tools().values()) {
            defs.add(t.getDefinition(task.session()));
        }
        try {
            response = task.chat(task.model(),
                    task.context(NaruSource.values())
            );
        } catch (Exception e) {
            String err = "ERROR calling model: " + e.getMessage();
            task.log(NaruLogMode.PROGRESS, NMsg.ofC("%s", err).asError());
            task.defaultAdvance(this);
            return;
        }

        NaruMessage assistantMsg = response.getMessage();
        if (assistantMsg == null) {
            task.log(NaruLogMode.DEBUG, NMsg.ofC("Model returned empty response."));
            task.defaultAdvance(this);
            return;
        }
        task.addHistory(assistantMsg);
        // ── Case 1: model wants to call tools ─────────────────────────────
        if (assistantMsg.hasToolCalls()) {
            List<NaruToolCall> toolCalls = assistantMsg.getToolCalls();
            task.pushFrame();
            for (NaruToolCall c : toolCalls) {
                task.addStatement(NaruStatementHelper.ofToolCall(c));
            }
            task.addStatement(NaruStatementHelper.ofModelCall(null));
            task.addStatement(NaruStatementHelper.ofReturn());
            if (!NBlankable.isBlank(assistantMsg.getContent())) {
                task.log(NaruLogMode.MODEL_RESPONSE, NMsg.ofC("%s", assistantMsg.getContent()));
            }
            task.defaultAdvance(this);
            return;
        }
        task.log(NaruLogMode.MODEL_RESPONSE, NMsg.ofC("%s", assistantMsg.getContent()));
        task.setLastResult(assistantMsg);
        task.defaultAdvance(this);
    }
}
