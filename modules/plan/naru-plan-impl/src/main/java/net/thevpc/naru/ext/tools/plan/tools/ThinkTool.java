package net.thevpc.naru.ext.tools.plan.tools;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruTranscriptReplay;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;

import java.util.List;

/**
 * No-op scratchpad tool for models without a native thinking/reasoning channel.
 * Gives such models a legitimate place to "think out loud" before acting,
 * mirroring what native reasoning models do implicitly.
 */
public class ThinkTool extends DefaultNaruTool {

    public ThinkTool() {
        // deliberately untagged: a no-op scratchpad is not a plan mutation, and sharing
        // the PLAN tag with the structural tools would hide thinking behind plan access.
        // The tag gate is fail-closed (untagged tools are hidden), so the one tool that
        // must stay unconditional says so explicitly.
        super("think", new String[0]);
    }

    @Override
    public boolean isEssential() {
        return true;
    }

    @Override
    public boolean isRelevant(NaruTask task) {
        // model.thinking=false takes the tool out of the schema entirely: an
        // offered tool is an instruction to call it, so leaving a thinking tool
        // in front of a model that was told not to think is how "thinking off"
        // turns into "thinking, but narrated through tool calls instead".
        if (!NaruThinkingConfig.isEnabled(task)) {
            return false;
        }
        // the observation beats the claim: a model that just reasoned on its own has
        // no use for a scratchpad, whatever its catalogue entry says
        if (lastAssistantTurnReasoned(task.history())) {
            return false;
        }
        // otherwise only expose to models that lack a native thinking/reasoning channel
        NaruModelConfig mc = task.model();
        if (mc == null) {
            return true;
        }
        return task.session().registry().provider(mc.provider())
                .flatMap(p -> p.getProtocol(mc, task.session()))
                .map(p -> !p.getCapabilities().isThinking())
                .orElse(true);
    }

    /**
     * Whether the turn this model produced immediately before this one carried reasoning of
     * its own.
     *
     * <p>A catalogue flag is a claim about an endpoint; the history is what the endpoint did.
     * nemotron on NVIDIA NIM streams its reasoning while that provider's capability heuristic
     * reports no reasoning channel, so the scratchpad stayed in the schema and the transcript
     * showed the same thought twice: once as the model's reasoning, once as a {@code think}
     * call whose text was the reasoning again. Only the newest assistant turn is consulted --
     * it is the answer the current model produced, so a mid-conversation model switch cannot
     * make one model's reasoning hide another's tool.
     */
    /** Package-private: the newest assistant turn decides, nothing earlier. */
    static boolean lastAssistantTurnReasoned(List<NaruMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            NaruMessage m = history.get(i);
            if (m.getRole() == NaruRole.assistant) {
                // getThinking covers both shapes: the legacy string, and the segments a
                // streamed reasoning channel is stored as
                return !NBlankable.isBlank(m.getThinking());
            }
        }
        return false;
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Use this tool to think through something privately before answering or acting. "
                + "It performs no action and returns no result; use it for planning, reviewing "
                + "tool output, verifying assumptions, or deciding the next step.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("thought", "Your private reasoning notes", true).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        Object thought = context.arg("thought").orNull();
        if (!NBlankable.isBlank(thought)) {
            // the intent is what puts this on the reasoning channel: a host that classifies a
            // stored engine line by intent alone would otherwise draw a private scratchpad as
            // another sentence the agent chose to say out loud
            context.task().addHistory(NaruMessage.engine(
                    NMsg.ofC("%s", thought).withIntent(NaruTranscriptReplay.THINKING)));
        }
        return "ok";
    }
}
