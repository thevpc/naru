package net.thevpc.naru.impl.engine.stmt;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.impl.util.ImplNaruUtils;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;

import java.util.Map;
import java.util.stream.Collectors;

public class NaruToolCallStmt extends NaruStatement implements Cloneable {
    public NaruToolCall call;

    public NaruToolCallStmt(NaruToolCall call) {
        super(Type.TOOL_CALL);
        this.call = call;
    }

    public NaruToolCallStmt(NElement element) {
        super(Type.TOOL_CALL);
        NListContainerElement lc = element.asListContainer().get();
        this.call = new NaruToolCall(lc.get("call").get());
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = (NObjectElementBuilder) super.toElement().builder();
        if (call != null) {
            a.set("call", call.toElement());
        }
        return a.build();
    }

    @Override
    protected NaruToolCallStmt clone() {
        NaruToolCallStmt o = (NaruToolCallStmt) super.clone();
        if (o.call != null) {
            o.call = o.call.copy();
        }
        return o;
    }

    @Override
    public void exec(NaruTask task) {
        // Defense against malformed model output: a tool call with a blank name
        // must degrade to a tool error string the model can react to, instead of
        // throwing here (which would mark the whole task KILLED).
        if (NBlankable.isBlank(call.getName())) {
            String err = "ERROR: Empty tool name. Available tools: "
                    + task.session().registry().tools().keySet().stream().sorted().collect(Collectors.joining(", "));
            task.log(NaruLogMode.PROGRESS, NMsg.ofC("  %s Result: %s",
                    NMsg.ofStyledPrimary6("📤"),
                    ImplNaruUtils.abbreviate(err, 300)));
            task.addHistory(NaruMessage.tool(call.getName(), call.getId(), err));
            task.defaultAdvance(this);
            task.frame().lastResult(NaruStmtResult.ofSuccess(err));
            return;
        }
        NObjectElementBuilder t = NElement.ofObjectBuilder(call.getName());
        for (Map.Entry<String, Object> e : call.getArguments().entrySet()) {
            t.set(e.getKey(), NElement.of(e.getValue()));
        }

        task.log(NaruLogMode.PROGRESS, NMsg.ofC("%s Tool: %s",
                        NMsg.ofStyledPrimary9("🔧"),
                        NText.ofCode("tson", t.build().toString())
                )
        );
        String result = task.session().registry().dispatch(call, task);
        task.log(NaruLogMode.PROGRESS, NMsg.ofC("  %s Result: %s",
                NMsg.ofStyledPrimary6("📤"),
                ImplNaruUtils.abbreviate(result, 300)));
        task.addHistory(NaruMessage.tool(call.getName(), call.getId(), result));
        task.defaultAdvance(this);
        task.frame().lastResult(NaruStmtResult.ofSuccess(result));
    }
}
