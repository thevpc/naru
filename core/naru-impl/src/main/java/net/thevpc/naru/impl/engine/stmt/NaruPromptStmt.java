package net.thevpc.naru.impl.engine.stmt;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.registry.NaruSessionExtension;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.impl.engine.stmt.shared.NaruStatementHelper;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NListContainerElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NLiteral;

import java.util.List;

public class NaruPromptStmt extends NaruStatement implements Cloneable {

    /**
     * Task env key holding how many consecutive tool-call rounds the CURRENT
     * model turn has run. It is accumulated while a turn's agent loop runs and
     * reset to 0 whenever the turn ends, so every scripted prompt invocation
     * (e.g. one re-triggered by a /while loop) starts with a full budget.
     */
    private static final String TOOL_CALL_ROUNDS_KEY = "naru.prompt.toolCallRounds";

    /**
     * Default maximum number of tool-call rounds before the agent loop is
     * forcibly ended, matching {@link net.thevpc.naru.api.agent.NaruAgentConfig#maxSteps}.
     * Overridable per session with the session env entry {@code maxSteps}.
     */
    private static final int DEFAULT_MAX_STEPS = 20;

    private final String prompt;

    public NaruPromptStmt(String prompt) {
        super(Type.PROMPT);
        this.prompt = prompt;
    }

    /**
     * The (raw) prompt text this statement hands to the model. When the
     * statement was produced by a {@code "/buffer on ... /buffer off"} block,
     * it is the joined raw lines of that block.
     *
     * @return prompt text, may be empty
     */
    public String prompt() {
        return prompt;
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
        try {
            fireBeforeModelRequest(task);
            response = task.chat(task.model(),
                    task.context(NaruSource.values())
            );
            task.frame().lastResult(NaruStmtResult.ofSuccess(response.getMessage()==null?"":response.getMessage().getContent()));
        } catch (Exception e) {
            String err = "ERROR calling model: " + e.getMessage();
            task.log(NaruLogMode.PROGRESS, NMsg.ofC("%s", err).asError());
            task.defaultAdvance(this);
            task.setTaskEnv(TOOL_CALL_ROUNDS_KEY, 0);
            NaruStmtResult.ofError(err);
            return;
        }

        NaruMessage assistantMsg = response.getMessage();
        if (assistantMsg == null) {
            task.log(NaruLogMode.DEBUG, NMsg.ofC("Model returned empty response."));
            task.defaultAdvance(this);
            task.setTaskEnv(TOOL_CALL_ROUNDS_KEY, 0);
            NaruStmtResult.ofSuccess("");
            return;
        }
        task.addHistory(assistantMsg);
        // ── Case 1: model wants to call tools ─────────────────────────────
        if (assistantMsg.hasToolCalls()) {
            // Guarantee termination: the agent loop must stop after maxSteps
            // tool-call rounds regardless of the model behaviour, otherwise a model
            // that keeps returning tool_calls would loop forever and the session
            // (and any waitFor() on it) would never end.
            // maxSteps bounds ONE model turn's tool-call rounds. A task-scoped
            // /set maxSteps = 80 overrides the session default (which itself
            // overrides NaruAgentConfig#maxSteps).
            int maxSteps = task.getTaskEnv("maxSteps", false)
                    .map(x -> NLiteral.of(x).asInt().orElse(DEFAULT_MAX_STEPS))
                    .orElse(task.session()
                            .getSessionEnv("maxSteps")
                            .map(x -> NLiteral.of(x).asInt().orElse(DEFAULT_MAX_STEPS))
                            .orElse(DEFAULT_MAX_STEPS));
            int rounds = task.getTaskEnv(TOOL_CALL_ROUNDS_KEY, false)
                    .map(x -> NLiteral.ofInt(x).orElse(0))
                    .orElse(0) + 1;
            task.setTaskEnv(TOOL_CALL_ROUNDS_KEY, rounds);
            if (rounds >= maxSteps) {
                task.log(NaruLogMode.PROGRESS, NMsg.ofC(
                        "Reached max tool-call rounds (%s); ending agent loop.", maxSteps));
                reportAnswer(task, assistantMsg);
                task.setLastResult(assistantMsg);
                task.setTaskEnv(TOOL_CALL_ROUNDS_KEY, 0);
                task.defaultAdvance(this);
                return;
            }
            List<NaruToolCall> toolCalls = assistantMsg.getToolCalls();
            task.pushFrame(null,false);
            for (NaruToolCall c : toolCalls) {
                task.addStatement(NaruStatementHelper.ofToolCall(c));
            }
            task.addStatement(NaruStatementHelper.ofModelCall(null));
            task.addStatement(new NaruReturnStmt((String)null));
            reportAnswer(task, assistantMsg);
            task.defaultAdvance(this);
            return;
        }
        reportAnswer(task, assistantMsg);
        task.setLastResult(assistantMsg);
        task.setTaskEnv(TOOL_CALL_ROUNDS_KEY, 0);
        task.defaultAdvance(this);
    }

    /**
     * Lets session extensions act on the request before it is built.
     *
     * <p>This is the only point where a feature can react to how big the request is about to
     * be, because {@code context()} has not run yet and the history it will read can still be
     * changed -- compacting it, for instance. A hook that ran after context assembly could
     * only append to a message list that was already fixed.
     *
     * <p>Placed here rather than inside {@code task.context()} on purpose: {@code context()}
     * is also called by {@code /context}, {@code /history list} and the budget estimator,
     * and a compaction triggered by running a display command would be a surprise.
     *
     * <p>Once per model request, including every round of a tool-calling turn. That falls out
     * of the shape of the loop rather than needing its own call site: each round re-enters
     * this statement via {@code ofModelCall}, so an extension that fires here sees the history
     * as it stands before every call, not just the first of a turn. That matters for
     * compaction -- a few dozen tool results can arrive between the first request of a turn
     * and the last, and only checking the first would let a turn overflow the window it was
     * supposed to stay inside.
     *
     * <p>A hook that throws is logged and ignored. The alternative -- letting it fail the
     * call -- would mean one badly-behaved extension could stop the agent from talking to a
     * model at all.
     */
    private static void fireBeforeModelRequest(NaruTask task) {
        for (NaruSessionExtension extension : task.session().registry().sessionExtensions()) {
            try {
                extension.beforeModelRequest(task);
            } catch (Exception e) {
                task.log(NaruLogMode.DEBUG, NMsg.ofC(
                        "session extension '%s' failed before the model request: %s",
                        extension.name(), e.getMessage()).asError());
            }
        }
    }

    /**
     * Shows the model's answer, unless {@link NaruTask#chat} already drew it live as it
     * arrived. Printing it again after a stream would show the user the same words
     * twice: once token by token, then once whole.
     */
    private static void reportAnswer(NaruTask task, NaruMessage assistantMsg) {
        if (task.isResponseStreamed()) {
            return;
        }
        if (!NBlankable.isBlank(assistantMsg.getContent())) {
            task.log(NaruLogMode.MODEL_RESPONSE, NMsg.ofC("%s", assistantMsg.getContent()));
        }
    }
}
