package net.thevpc.naru.ext.tools.plan.tools;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruThinkingExtraction;
import net.thevpc.naru.api.model.NaruThinkingSegment;
import net.thevpc.nuts.Nuts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scratchpad tool is meant for a model that has nowhere else to put a thought. The catalogue
 * flag that decides this is a provider's claim about an endpoint, and the reported session showed
 * that claim wrong: nemotron on NVIDIA NIM has no reasoning channel in its entry while its stream
 * carries one, so the model was offered {@code think} on top of its own reasoning and the same
 * paragraph appeared twice above the answer. These cases pin the observation that beats the claim.
 */
class ThinkToolReasoningGateTest {

    @BeforeAll
    static void nuts() {
        Nuts.require();
    }

    private static NaruMessage reasoned(String answer) {
        return NaruMessage.assistant(answer)
                .addThinkingSegment(new NaruThinkingSegment(0, "weighing the wording",
                        NaruThinkingExtraction.NATIVE_FIELD, "nvidia"));
    }

    @Test
    void aModelThatReasonedLastTurnDoesNotGetTheScratchpad() {
        List<NaruMessage> history = List.of(
                NaruMessage.user("corrige cet email"),
                reasoned("Voici la version corrigée."));
        assertTrue(ThinkTool.lastAssistantTurnReasoned(history));
    }

    @Test
    void aModelThatAnsweredPlainlyStillGetsIt() {
        List<NaruMessage> history = List.of(
                NaruMessage.user("what is in the folder"),
                NaruMessage.assistant("three files"));
        assertFalse(ThinkTool.lastAssistantTurnReasoned(history));
    }

    @Test
    void theNewestTurnDecidesSoAModelSwitchIsNotPunished() {
        // a switch from a reasoning model to one without a reasoning channel must not leave the
        // new model with no way to think: its own turns are the evidence, not its predecessor's
        List<NaruMessage> history = List.of(
                reasoned("an answer from the reasoning model"),
                NaruMessage.user("and now?"),
                NaruMessage.assistant("an answer from the plain model"));
        assertFalse(ThinkTool.lastAssistantTurnReasoned(history));
    }

    @Test
    void aLegacyThinkingStringCountsAsReasoningToo() {
        List<NaruMessage> history = List.of(
                NaruMessage.assistant("the answer").setThinking("a thought, stored the old way"));
        assertTrue(ThinkTool.lastAssistantTurnReasoned(history));
    }

    @Test
    void anEmptyHistoryOffersTheTool() {
        assertFalse(ThinkTool.lastAssistantTurnReasoned(List.of()));
    }
}
