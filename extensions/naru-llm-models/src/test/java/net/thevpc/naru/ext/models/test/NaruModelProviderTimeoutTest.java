package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruModelTimeouts;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.ext.models.NaruModelCapabilitiesImpl;
import net.thevpc.naru.ext.models.custom.NaruCustomProvider;
import net.thevpc.naru.ext.models.ollama.NaruOllamaProvider;
import net.thevpc.naru.ext.models.openai.NaruModelProtocolOpenAICompat;
import net.thevpc.naru.ext.models.openai.NaruOpenAiProvider;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.time.NDuration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Locality and default-timeout resolution at the provider factory.
 *
 * <p>The point of the feature is that the provider — the one place that knows both
 * the endpoint (its configured {@code --url}) and the machine — decides the
 * timeout, and the protocol just carries the answer. So these tests ask the
 * provider ({@link NaruModelProvider#isLocal}, {@code defaultReadTimeout}) and
 * then the protocol, never a host lookup inside the protocol.
 *
 * <p>GPU presence is simulated by overriding {@code hasDedicatedGpu()} so the
 * 300s/600s branch is deterministic; one test also pins the wiring to the real
 * {@code NEnv} probe without asserting what this particular machine has.
 */
public class NaruModelProviderTimeoutTest {

    @BeforeAll
    public static void setUp() {
        Nuts.require();
    }

    // ── locality ─────────────────────────────────────────────────────────────

    @Test
    public void aRemoteUrlIsRemote() {
        TestProvider p = new TestProvider(true).wire("https://api.openai.com/v1");
        Assertions.assertFalse(p.isLocal(null));
        Assertions.assertEquals(120, p.defaultReadTimeout(null).toSeconds());
    }

    @Test
    public void aLocalUrlIsLocal() {
        for (String url : new String[]{"http://localhost:11434", "https://127.0.0.1", "http://[::1]:11434"}) {
            TestProvider p = new TestProvider(true).wire(url);
            Assertions.assertTrue(p.isLocal(null), url);
        }
    }

    @Test
    public void aLocalEndpointWithAGpuGets300s() {
        TestProvider p = new TestProvider(true).wire("http://localhost:11434");
        Assertions.assertTrue(p.isLocal(null));
        Assertions.assertEquals(300, p.defaultReadTimeout(null).toSeconds());
    }

    @Test
    public void aLocalEndpointWithoutAGpuGets600s() {
        TestProvider p = new TestProvider(false).wire("http://127.0.0.1:8080");
        Assertions.assertTrue(p.isLocal(null));
        Assertions.assertEquals(600, p.defaultReadTimeout(null).toSeconds());
    }

    @Test
    public void explicitLocalTrueForcesALocalEndpoint() {
        TestProvider p = new TestProvider(true).wire("https://api.openai.com/v1");
        p.setParam("local", "true");
        Assertions.assertTrue(p.isLocal(null), "--local=true must win over the url");
        Assertions.assertEquals(300, p.defaultReadTimeout(null).toSeconds());
    }

    @Test
    public void explicitLocalFalseForcesARemoteEndpoint() {
        TestProvider p = new TestProvider(false).wire("http://127.0.0.1:8080");
        p.setParam("local", "false");
        Assertions.assertFalse(p.isLocal(null), "--local=false must win over the url");
        Assertions.assertEquals(120, p.defaultReadTimeout(null).toSeconds());
    }

    @Test
    public void ollamaIsLocalByDefault() {
        NaruOllamaProvider p = new NaruOllamaProvider();
        Assertions.assertTrue(p.isLocal(null), "ollama's built-in default url is localhost");
    }

    @Test
    public void aCloudProviderIsRemoteByDefault() {
        NaruOpenAiProvider p = new NaruOpenAiProvider();
        Assertions.assertFalse(p.isLocal(null));
        Assertions.assertEquals(120, p.defaultReadTimeout(null).toSeconds());
    }

    @Test
    public void theLocalDefaultTracksTheRealGpuProbe() {
        boolean gpu = NaruModelTimeouts.hasDedicatedGpu();
        NaruCustomProvider p = new NaruCustomProvider();
        p.setParam("url", "http://localhost:11434");
        p.setParam("models", "qwen3:32b");
        p.setParam("probe", "false");
        Assertions.assertEquals(gpu ? 300 : 600, p.defaultReadTimeout(null).toSeconds(),
                "the provider must ask NEnv, not hard-code a branch");
    }

    // ── precedence at the protocol ───────────────────────────────────────────

    @Test
    public void anExplicitReadTimeoutParamWins() {
        TestProvider p = new TestProvider(false).wire("http://127.0.0.1:8080");
        p.setParam("readTimeout", "45s");
        Assertions.assertEquals(45, protocol(p).resolvedReadTimeout(null).toSeconds());
    }

    @Test
    public void anExplicitTimeoutParamWinsWhenNoReadTimeout() {
        TestProvider p = new TestProvider(false).wire("http://127.0.0.1:8080");
        p.setParam("timeout", "70s");
        Assertions.assertEquals(70, protocol(p).resolvedReadTimeout(null).toSeconds());
    }

    @Test
    public void withoutAnExplicitValueTheProtocolCarriesTheProviderDefault() {
        TestProvider p = new TestProvider(false).wire("http://127.0.0.1:8080");
        Assertions.assertEquals(600, protocol(p).resolvedReadTimeout(null).toSeconds());
    }

    @Test
    public void aProtocolBuiltOutsideTheFactoryKeepsTheHistorical120s() {
        TestProvider p = new TestProvider(true).wire("http://localhost:11434");
        TestProtocol proto = new TestProtocol(p, new NaruModelConfig(p.name(), "qwen3:32b"),
                capabilities(), p.isLocal(null), null);
        Assertions.assertEquals(120, proto.resolvedReadTimeout(null).toSeconds());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static TestProtocol protocol(TestProvider p) {
        return new TestProtocol(p, new NaruModelConfig(p.name(), "qwen3:32b"),
                capabilities(), p.isLocal(null), p.defaultReadTimeout(null));
    }

    private static NaruModelCapabilities capabilities() {
        return new NaruModelCapabilitiesImpl(false, true, false, false, 8192, NaruCachingMode.NONE);
    }

    /**
     * A custom-endpoint provider with a fixed GPU answer, so 300 vs 600 is a
     * choice of the test rather than of the CI machine.
     */
    static class TestProvider extends NaruCustomProvider {
        private final boolean gpu;

        TestProvider(boolean gpu) {
            this.gpu = gpu;
        }

        @Override
        public boolean hasDedicatedGpu() {
            return gpu;
        }

        TestProvider wire(String url) {
            setParam("url", url);
            setParam("models", "qwen3:32b");
            setParam("probe", "false");
            return this;
        }
    }

    /** Exposes the protected timeout resolution so precedence can be asserted. */
    static class TestProtocol extends NaruModelProtocolOpenAICompat {
        TestProtocol(NaruModelProvider provider, NaruModelConfig model, NaruModelCapabilities capabilities,
                     boolean local, NDuration defaultReadTimeout) {
            super(provider, model, provider.name(), "chat/completions", capabilities,
                    "http://localhost:1", local, defaultReadTimeout);
        }

        NDuration resolvedReadTimeout(NaruTask task) {
            return readTimeout(task, (Map<String, NElement>) null);
        }
    }
}
