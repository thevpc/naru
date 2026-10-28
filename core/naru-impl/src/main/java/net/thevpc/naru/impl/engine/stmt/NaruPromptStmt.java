package net.thevpc.naru.impl.engine.stmt;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruRole;
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
    public NObjectElementBuilder toElementBuilder() {
        NObjectElementBuilder a = super.toElementBuilder();
        if (!NBlankable.isBlank(prompt)) {
            a.set("prompt", NElement.ofString(prompt));
        }
        return a;
    }

    @Override
    public void exec(NaruTask task) {
        if (task.model() == null) {
            NMsg message = NMsg.ofC("No model is selected. Choose one yourself with /model list and /model use <key>, then submit your request again.");
            task.addHistory(NaruMessage.engine(message));
            task.frame().lastResult(NaruStmtResult.ofError(message));
            task.defaultAdvance(this);
            return;
        }
        task.addHistory(NaruMessage.engine(NMsg.ofC("%s Model: %s…",
                NMsg.ofStyledPrimary8("\uD83E\uDDE0"),
                task.model().toText()
        )));
        if (!NBlankable.isBlank(prompt)) {
            task.addHistory(NaruMessage.user(prompt));
        }
        NaruResponse response;
        try {
            fireBeforeModelRequest(task);
            response = task.chat(task.model(),
                    task.context(NaruSource.values())
            );
            task.frame().lastResult(NaruStmtResult.ofSuccess(
                    NElement.of(response.getMessage()==null?"":response.getMessage().getFilteredContent())

            ));
        } catch (Exception e) {
            String err = "ERROR calling model: " + e.getMessage();
            task.defaultAdvance(this);
            task.setTaskEnv(TOOL_CALL_ROUNDS_KEY, 0);
            NaruStmtResult.ofError(NMsg.ofP(err).asError());
            return;
        }

        NaruMessage assistantMsg = response.getMessage();
        if (assistantMsg == null) {
            task.addHistory(NaruMessage.engine(NMsg.ofC("Model returned empty response.")));
            task.defaultAdvance(this);
            task.setTaskEnv(TOOL_CALL_ROUNDS_KEY, 0);
            return;
        }
        // Interrupted (Ctrl+C) while this call was running: the stream stopped at
        // its next event, so this is the answer cut where the user cut it. The
        // turn ends here rather than starting a tool round from a call the user
        // stopped -- and ending the turn normally, not by killing the task, is
        // what brings the prompt back with the task still alive.
        if (task.isInterruptRequested()) {
            if (assistantMsg.hasToolCalls()) {
                // A tool_calls entry with no results after it cannot be sent to
                // the provider on the next request: the history would be rejected
                // for every later turn of this task. The half-started round is
                // dropped for the same reason a partial answer is kept -- neither
                // is a thing the model ever finished saying.
                task.addHistory(NaruMessage.engine(NMsg.ofC(
                        "%s Interrupted before the tool call completed; discarding it.",
                        NMsg.ofStyledPrimary8("⏹"))));
            } else {
                task.addHistory(assistantMsg);
                task.addHistory(NaruMessage.engine(NMsg.ofC(
                        "%s Interrupted.", NMsg.ofStyledPrimary8("⏹"))));
                reportAnswer(task, assistantMsg);
                task.setLastResult(assistantMsg);
            }
            task.setTaskEnv(TOOL_CALL_ROUNDS_KEY, 0);
            task.defaultAdvance(this);
            return;
        }
        task.addHistory(assistantMsg);
        // ── Case 1: model wants to call tools ─────────────────────────────
        if (assistantMsg.hasToolCalls()) {
            // A stalled loop costs a provider request and a transcript card per round. Bounding it
            // by round count alone lets a model spend the whole budget re-asking for the same call.
            if (repeatsLastToolRound(task)) {
                task.addHistory(NaruMessage.engine(NMsg.ofC(
                        "%s The model repeated its previous tool call unchanged; ending this turn.",
                        NMsg.ofStyledPrimary8("⏹"))));
                reportAnswer(task, assistantMsg);
                reportSilentTurn(task, assistantMsg);
                task.setLastResult(assistantMsg);
                task.setTaskEnv(TOOL_CALL_ROUNDS_KEY, 0);
                task.defaultAdvance(this);
                return;
            }
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
                task.addHistory(NaruMessage.engine(NMsg.ofC(
                        "Reached max tool-call rounds (%s); ending agent loop.", maxSteps)));
                reportAnswer(task, assistantMsg);
                reportSilentTurn(task, assistantMsg);
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
                task.addHistory(NaruMessage.engine(NMsg.ofC(
                        "session extension '%s' failed before the model request: %s",
                        extension.name(), e.getMessage()).asError()));
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
            task.addHistory(NaruMessage.engine(NMsg.ofC("%s", assistantMsg.getContent())));
        }
    }

    /**
     * Say so when a turn ends with the model having asked for tools instead of answering.
     *
     * <p>Without this the user's last visible line is whatever the transcript held before they
     * typed, which reads as though the message was never delivered — the usual complaint against
     * a small local model that only emits {@code tool_calls} and no text at all.
     */
    private static void reportSilentTurn(NaruTask task, NaruMessage assistantMsg) {
        if (!NBlankable.isBlank(assistantMsg.getContent()) || task.isResponseStreamed()) {
            return;
        }
        task.addHistory(NaruMessage.engine(NMsg.ofC(
                "%s This turn ended in tool calls without an answer. Ask again, or turn off the"
                        + " tools the model keeps calling (/tools, /tag).",
                NMsg.ofStyledPrimary8("⚠"))));
    }

    /**
     * True when the round about to run repeats the previous assistant tool round exactly.
     *
     * <p>A small local model can call {@code think}, read the {@code ok} that comes back and ask
     * for the same call again, unchanged, until the round budget runs out — every round costing a
     * provider request. Nothing in a tool result persuades it to stop, so the loop is bounded by
     * noticing the repetition instead. Text is part of the comparison: a model that repeats a call
     * while saying something new is still working, not stalled.
     */
    private static boolean repeatsLastToolRound(NaruTask task) {
        List<NaruMessage> h = task.history();
        String previous = null;
        for (int i = h.size() - 1; i >= 0 && i >= h.size() - 24; i--) {
            NaruMessage m = h.get(i);
            if (m.getRole() == NaruRole.user) {
                // the prompt that started this turn: earlier rounds belong to another turn, where
                // the same call with the same arguments is not a stall but a repeat request.
                return false;
            }
            if (m.getRole() != NaruRole.assistant || !m.hasToolCalls()) {
                continue;
            }
            String s = roundSignature(m);
            if (previous == null) {
                previous = s;
                continue;
            }
            return previous.equals(s);
        }
        return false;
    }

    /** Call ids are provider-generated and not stable between rounds, so they are left out. */
    private static String roundSignature(NaruMessage m) {
        StringBuilder sb = new StringBuilder(String.valueOf(m.getContent()));
        for (NaruToolCall c : m.getToolCalls()) {
            sb.append('|').append(c.getName()).append('=').append(c.getArguments());
        }
        return sb.toString();
    }
}
