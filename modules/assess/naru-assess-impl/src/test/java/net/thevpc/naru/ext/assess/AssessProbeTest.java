package net.thevpc.naru.ext.assess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.text.NMsg;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AssessProbeTest {
    @BeforeAll
    static void nuts() {
        Nuts.require();
    }

    @Test
    void finalAnswerIsPlainTextWithoutStylingMarkup() {
        AssessRunner.Probe p = new AssessRunner.Probe();
        p.write(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s The model repeated its call.", NMsg.ofStyledPrimary8("⏹")));
        AssessTaskResult r = new AssessTaskResult();
        p.copyTo(r);
        assertFalse(r.finalAnswer.contains("##"), r.finalAnswer);
        assertEquals("⏹ The model repeated its call.", r.finalAnswer);
    }

    @Test
    void engineHousekeepingIsNotTheAnswer() {
        AssessRunner.Probe p = new AssessRunner.Probe();
        p.write(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", "All done."));
        p.write(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", "Loading agent from jar:file:/x.jar!/qwen.md"));
        AssessTaskResult r = new AssessTaskResult();
        p.copyTo(r);
        assertEquals("All done.", r.finalAnswer);
    }
}
