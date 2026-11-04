package net.thevpc.naru.ext.tools.llamacpp;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruConfigKey;
import net.thevpc.naru.api.registry.NaruConfigKeyProvider;

import java.util.List;

/**
 * The {@code llamacpp.*} keys the extension reads through
 * {@link LlamaCppPaths#config} — the layered environment, same precedence as
 * the model registration path. Declared here, next to the reads, so the
 * settings surface documents the keys that actually reach the llama-server
 * process.
 */
public class NaruLlamaCppConfigKeysProvider implements NaruConfigKeyProvider {

    @Override
    public String name() {
        return "llamacpp";
    }

    @Override
    public List<NaruConfigKey> configKeys(NaruSession session) {
        return List.of(
                NaruConfigKey.of("llamacpp.url",
                        "server root (default http://127.0.0.1:8080)"),
                NaruConfigKey.of("llamacpp.host",
                        "bind host of the llama-server process"),
                NaruConfigKey.of("llamacpp.port",
                        "bind port of the llama-server process"),
                NaruConfigKey.of("llamacpp.home",
                        "install home of the llama.cpp binaries (default ~/.naru/llama.cpp)"),
                NaruConfigKey.of("llamacpp.modelsDir",
                        "GGUF models directory (default <home>/models)"),
                NaruConfigKey.of("llamacpp.backend",
                        "compute backend of the server build").withValues("cpu", "cuda", "vulkan", "rocm", "sycl", "openvino"),
                NaruConfigKey.of("llamacpp.releaseTag",
                        "pinned llama.cpp release tag, e.g. b11429"),
                NaruConfigKey.of("llamacpp.model",
                        "default GGUF to load when starting the server"),
                NaruConfigKey.of("llamacpp.ngl",
                        "GPU layers offloaded to the GPU (negative = all)"),
                NaruConfigKey.of("llamacpp.ctxSize",
                        "context size in tokens of the server"),
                NaruConfigKey.of("llamacpp.args",
                        "extra arguments passed to the llama-server process"),
                NaruConfigKey.of("llamacpp.tag",
                        "legacy alias of llamacpp.releaseTag"),
                NaruConfigKey.of("llamacpp.ctx",
                        "legacy alias of llamacpp.ctxSize")
        );
    }
}