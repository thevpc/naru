package net.thevpc.naru.impl.engine.stmt;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruToolCallEvent;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.registry.NaruInteractiveTool;
import net.thevpc.naru.api.registry.NaruTool;
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
    private boolean awaitingInput;

    public NaruToolCallStmt(NaruToolCall call) {
        super(Type.TOOL_CALL);
        this.call = call;
    }

    public NaruToolCallStmt(NElement element) {
        super(Type.TOOL_CALL);
        NListContainerElement lc = element.asListContainer().get();
        this.call = new NaruToolCall(lc.get("call").get());
        this.awaitingInput = lc.getBooleanValue("awaitingInput").orElse(false);
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = (NObjectElementBuilder) super.toElement().builder();
        if (call != null) {
            a.set("call", call.toElement());
        }
        if (awaitingInput) {
            a.set("awaitingInput", true);
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
            task.reportToolCall(NaruToolCallEvent.failed(task.id(), call, err, NaruToolCallEvent.UNKNOWN_DURATION));
            task.addHistory(NaruMessage.tool(call.getName(), call.getId(), err));
            task.defaultAdvance(this);
            task.frame().lastResult(NaruStmtResult.ofSuccess(err));
            return;
        }
        NaruTool registeredTool = task.session().registry().findTool(call.getName()).orNull();
        if (registeredTool instanceof NaruInteractiveTool) {
            NaruInteractiveTool interactiveTool = (NaruInteractiveTool) registeredTool;
            if (awaitingInput) {
                String answer = task.consumeInput();
                if (answer == null) {
                    task.prependStatement(copy());
                    task.requestInput(NMsg.ofC("Please answer the pending question to continue."));
                    return;
                }
                long t0 = System.nanoTime();
                String result = interactiveTool.complete(call, answer);
                finishToolCall(task, result, millisSince(t0));
                return;
            }
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
        task.reportToolCall(NaruToolCallEvent.started(task.id(), call));
        long t0 = System.nanoTime();
        String result = task.session().registry().dispatch(call, task);
        if (registeredTool instanceof NaruInteractiveTool
                && NaruInteractiveTool.WAITING_FOR_INPUT.equals(result)) {
            NaruToolCallStmt resumed = (NaruToolCallStmt) copy();
            resumed.awaitingInput = true;
            // the call has not settled: a host drawing a card must show it waiting, not done,
            // and the matching RESULT arrives when the answer comes back through complete()
            task.reportToolCall(NaruToolCallEvent.parked(task.id(), call));
            task.prependStatement(resumed);
            return;
        }
        finishToolCall(task, result, millisSince(t0));
    }

    private void finishToolCall(NaruTask task, String result, long durationMillis) {
        task.log(NaruLogMode.PROGRESS, NMsg.ofC("  %s Result: %s",
                NMsg.ofStyledPrimary6("📤"),
                ImplNaruUtils.abbreviate(result, 300)));
        // The engine's failure convention is a result string that starts with ERROR (unknown
        // tool, permission denial, a tool that threw). Tools have no other way to report it,
        // so this is the same classification the log line and the model's history already get.
        boolean failed = result != null && result.startsWith("ERROR");
        task.reportToolCall(failed
                ? NaruToolCallEvent.failed(task.id(), call, result, durationMillis)
                : NaruToolCallEvent.result(task.id(), call, result, durationMillis));
        task.addHistory(NaruMessage.tool(call.getName(), call.getId(), result));
        task.defaultAdvance(this);
        task.frame().lastResult(NaruStmtResult.ofSuccess(result));
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
