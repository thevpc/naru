package net.thevpc.naru.ext.models.ollama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The context window Ollama is asked for. Without {@code options.num_ctx} Ollama falls back to
 * its own default (4096 tokens) and silently drops the start of any longer prompt, which for an
 * agent is the system prompt and the task itself.
 */
public class NaruOllamaNumCtxWireTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NElement options(NaruModelConfig model) {
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(NPath.ofTempFolder("naru-numctx-wire"));
        NaruSession session = agent.newSession()
                .interaction(new NaruStreamInteraction(o -> {
                }, request -> {
                }))
                .interactive()
                .task(NaruTaskSpec.of().statements("/print 0").name("host"))
                .build();
        session.run();
        NaruModelRequest request = new NaruModelRequest(
                List.of(NaruMessage.user("hi")), Collections.emptyList(), new HashMap<>());
        NElement body = new NaruOllamaNativeRequestSerializer().serialize(request, model, session, true);
        return body.asObject().get().get("options").orNull();
    }

    @Test
    public void aModelWithoutAConfiguredContextStillGetsAUsableWindow() {
        NElement o = options(new NaruModelConfig("ollama", "qwen3"));
        assertTrue(o != null && o.asObject().get().get("num_ctx").isPresent(),
                "num_ctx must be sent, or Ollama truncates the prompt at its 4096 default");
        assertTrue(o.asObject().get().getLongValue("num_ctx").get() >= 16384,
                "an agent prompt (system prompt + tool schemas + history) does not fit in less");
    }
}
