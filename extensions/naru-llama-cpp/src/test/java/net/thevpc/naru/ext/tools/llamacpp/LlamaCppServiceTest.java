package net.thevpc.naru.ext.tools.llamacpp;

import net.thevpc.naru.api.model.NaruModelPsResult;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.platform.NEnv;
import net.thevpc.nuts.util.NIllegalArgumentException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

public class LlamaCppServiceTest {

    @BeforeAll
    public static void setUp() {
        Nuts.require();    }

    @Test
    public void testPlatformDetection() {
        NEnv env = NEnv.of();
        Assertions.assertNotNull(env.osFamily());
        Assertions.assertNotNull(env.archFamily());
        Assertions.assertFalse(env.osFamily().id().isEmpty());
        Assertions.assertNotNull(LlamaCppPlatform.osKey());
        Assertions.assertNotNull(LlamaCppPlatform.archKey());
        Assertions.assertTrue(LlamaCppPlatform.assetSuffix().startsWith("."));
    }

    @Test
    public void testInstallationInfo() {
        LlamaCppInstallationInfo info = LlamaCppService.of().getInstallationInfo(null);
        Assertions.assertNotNull(info);
        Assertions.assertNotNull(info.getOsFamily());
        Assertions.assertNotNull(info.getArchFamily());
        Assertions.assertNotNull(info.toMsg());

        LlamaCppInstallationInfo custom = new LlamaCppInstallationInfo(true,
                "/usr/local/bin/llama-server", "b11429", "linux", "x86_64", "ubuntu",
                "/home/u/.naru/llama.cpp", "/home/u/.naru/llama.cpp/bin", "/home/u/.naru/llama.cpp/models", "cpu");
        Assertions.assertTrue(custom.isInstalled());
        Assertions.assertEquals("/usr/local/bin/llama-server", custom.getExecutablePath());
        Assertions.assertEquals("b11429", custom.getVersion());
        Assertions.assertEquals("linux", custom.getOsFamily());
        Assertions.assertEquals("x86_64", custom.getArchFamily());
        Assertions.assertEquals("ubuntu", custom.getOsDist());
        Assertions.assertEquals("cpu", custom.getBackend());
        Assertions.assertTrue(custom.toString().contains("b11429"));

        LlamaCppInstallationInfo notInstalled = new LlamaCppInstallationInfo(false,
                null, null, "linux", "x86_64", "ubuntu",
                "/home/u/.naru/llama.cpp", "/home/u/.naru/llama.cpp/bin", "/home/u/.naru/llama.cpp/models", "cpu");
        Assertions.assertFalse(notInstalled.isInstalled());
        Assertions.assertTrue(notInstalled.toString().contains("Not installed"));
    }

    @Test
    public void testStatusSerialization() {
        LlamaCppInstallationInfo install = new LlamaCppInstallationInfo(true,
                "/usr/local/bin/llama-server", "b11429", "linux", "x86_64", "ubuntu",
                "/home/u/.naru/llama.cpp", "/home/u/.naru/llama.cpp/bin", "/home/u/.naru/llama.cpp/models", "cpu");
        LlamaCppStatus status = new LlamaCppStatus(
                true,
                "http://127.0.0.1:8080",
                "b11429",
                true,
                12345L,
                42L,
                Collections.singletonList("qwen2.5-coder-7b-instruct-q4_k_m"),
                Collections.singletonList(new NaruModelPsResult(
                        new net.thevpc.naru.api.model.NaruModelKey("llamacpp", "qwen2.5-coder-7b-instruct-q4_k_m"),
                        0L, null, 0L)),
                install
        );

        NElement element = status.toElement();
        Assertions.assertNotNull(element);
        Assertions.assertTrue(element.isAnyObject());
        Assertions.assertEquals("http://127.0.0.1:8080", element.asObject().get().getStringValue("url").orElse(null));
        Assertions.assertTrue(element.asObject().get().getBooleanValue("running").orElse(false));
        Assertions.assertTrue(element.asObject().get().getBooleanValue("startedByNaru").orElse(false));
        Assertions.assertEquals(12345L, element.asObject().get().getLongValue("pid").orElse(0L));
        Assertions.assertEquals(42L, element.asObject().get().getLongValue("responseTimeMs").orElse(0L));
        Assertions.assertEquals("b11429", element.asObject().get().getStringValue("serverVersion").orElse(null));
        Assertions.assertEquals("/usr/local/bin/llama-server", element.asObject().get().getStringValue("executablePath").orElse(null));
        Assertions.assertEquals("b11429", element.asObject().get().getStringValue("clientVersion").orElse(null));
        Assertions.assertEquals("linux", element.asObject().get().getStringValue("osFamily").orElse(null));
        Assertions.assertEquals("x86_64", element.asObject().get().getStringValue("archFamily").orElse(null));
        Assertions.assertEquals("ubuntu", element.asObject().get().getStringValue("osDist").orElse(null));
        Assertions.assertEquals("cpu", element.asObject().get().getStringValue("backend").orElse(null));
        Assertions.assertEquals(1, element.asObject().get().getArray("availableModels").get().size());
        Assertions.assertEquals(1, element.asObject().get().getArray("loadedModels").get().size());
    }

    @Test
    public void testDirectiveRegistration() {
        NaruLlamaCppDirective directive = new NaruLlamaCppDirective();
        Assertions.assertEquals("llamacpp", directive.name());
        Assertions.assertEquals("ai", directive.group());
        Assertions.assertTrue(Arrays.asList(directive.getAliases()).contains("lcpp"));
        Assertions.assertEquals("status", directive.noCommand());

        NaruLlamaCppDirectiveProvider provider = new NaruLlamaCppDirectiveProvider();
        Assertions.assertNotNull(provider.directives());
        Assertions.assertEquals(1, provider.directives().size());
        Assertions.assertEquals("llamacpp", provider.directives().get(0).name());
    }

    @Test
    public void testToolsetTools() {
        NaruLlamaCppToolset toolset = new NaruLlamaCppToolset("llamacpp");
        Assertions.assertEquals("llamacpp", toolset.id());
        Assertions.assertEquals(8, toolset.tools().size());

        Assertions.assertEquals("llamacpp_status", new LlamaCppStatusTool().getDefinition(null).getName());
        Assertions.assertEquals("llamacpp_install", new LlamaCppInstallTool().getDefinition(null).getName());
        Assertions.assertEquals("llamacpp_start", new LlamaCppStartTool().getDefinition(null).getName());
        Assertions.assertEquals("llamacpp_stop", new LlamaCppStopTool().getDefinition(null).getName());
        Assertions.assertEquals("llamacpp_list", new LlamaCppListTool().getDefinition(null).getName());
        Assertions.assertEquals("llamacpp_pull", new LlamaCppPullTool().getDefinition(null).getName());
        Assertions.assertEquals("llamacpp_rm", new LlamaCppRmTool().getDefinition(null).getName());
        Assertions.assertEquals("llamacpp_ps", new LlamaCppPsTool().getDefinition(null).getName());

        NaruToolDefinition def = new LlamaCppPullTool().getDefinition(null);
        Assertions.assertEquals("llamacpp_pull", def.getName());

        NaruLlamaCppToolsetProvider provider = new NaruLlamaCppToolsetProvider();
        Assertions.assertEquals("llamacpp", provider.name());
        Assertions.assertEquals(Collections.singletonList("llamacpp"), provider.supportedTypes());
        Assertions.assertNotNull(provider.createToolset("llamacpp", null));
    }

    @Test
    public void testProcessManagerInitialState() {
        Assertions.assertFalse(LlamaCppProcessManager.isStartedByNaru());
        Assertions.assertEquals(-1, LlamaCppProcessManager.getManagedPid());
        // Stopping when not started should return false cleanly
        Assertions.assertFalse(LlamaCppProcessManager.stopIfStartedByNaru());
    }

    @Test
    public void testModelResolverDirectUrl() {
        LlamaCppDownloadPlan single = LlamaCppModelResolver.resolve(
                "https://example.com/models/qwen2.5-coder-7b-instruct-q4_k_m.gguf");
        Assertions.assertEquals(1, single.getFiles().size());
        Assertions.assertEquals("qwen2.5-coder-7b-instruct-q4_k_m.gguf", single.getMainFileName());

        LlamaCppDownloadPlan sharded = LlamaCppModelResolver.resolve(
                "https://example.com/models/big-model-q4_k_m-00001-of-00003.gguf");
        Assertions.assertEquals(3, sharded.getFiles().size());
        Assertions.assertEquals("big-model-q4_k_m-00001-of-00003.gguf", sharded.getMainFileName());
        Assertions.assertEquals(
                "https://example.com/models/big-model-q4_k_m-00002-of-00003.gguf",
                sharded.getFiles().get(1).getUrl());
    }

    @Test
    public void testModelResolverRejectsInvalid() {
        Assertions.assertThrows(NIllegalArgumentException.class, () -> LlamaCppModelResolver.resolve(""));
        Assertions.assertThrows(NIllegalArgumentException.class, () -> LlamaCppModelResolver.resolve("not-a-repo"));
        Assertions.assertThrows(NIllegalArgumentException.class, () -> LlamaCppModelResolver.resolve("https://example.com/model.bin"));
    }

    @Test
    public void testShardExpansion() {
        java.util.List<String> expanded = LlamaCppModelResolver.expandShards(
                Collections.singletonList("model-q4_k_m-00001-of-00002.gguf"),
                "model-q4_k_m-00001-of-00002.gguf");
        Assertions.assertEquals(2, expanded.size());
        Assertions.assertEquals("model-q4_k_m-00002-of-00002.gguf", expanded.get(1));
    }

    @Test
    public void testAssetMatching() {
        String tag = "b11429";
        String os = LlamaCppPlatform.osKey();
        String arch = LlamaCppPlatform.archKey();
        String suffix = LlamaCppPlatform.assetSuffix();

        String cpuName;
        if ("win".equals(os)) {
            cpuName = "llama-" + tag + "-bin-win-cpu-" + arch + suffix;
        } else {
            cpuName = "llama-" + tag + "-bin-" + os + "-" + arch + suffix;
        }
        Assertions.assertTrue(LlamaCppPlatform.matchesMain(cpuName, tag, os, "cpu", arch));
        Assertions.assertFalse(LlamaCppPlatform.matchesMain(cpuName, tag, os, "cuda", arch));

        String cudaName = "llama-" + tag + "-bin-" + os + "-cuda-13.4-" + arch + suffix;
        Assertions.assertTrue(LlamaCppPlatform.matchesMain(cudaName, tag, os, "cuda", arch));
        Assertions.assertFalse(LlamaCppPlatform.matchesMain(cudaName, tag, os, "vulkan", arch));

        // a companion archive is never the main bundle
        Assertions.assertFalse(LlamaCppPlatform.matchesMain(
                "cudart-llama-" + tag + "-bin-" + os + "-cuda-13.4-" + arch + suffix, tag, os, "cuda", arch));

        java.util.List<String> names = Arrays.asList(
                cudaName,
                "cudart-llama-" + tag + "-bin-" + os + "-cuda-13.4-" + arch + suffix);
        java.util.List<String> urls = Arrays.asList("u0", "u1");
        Assertions.assertEquals(1, LlamaCppPlatform.findCudart(names, urls, cudaName, arch));
    }

    @Test
    public void testConfigKeys() {
        // null session => system env only; defaults must still resolve
        Assertions.assertEquals("127.0.0.1", LlamaCppPaths.host(null));
        Assertions.assertEquals(8080, LlamaCppPaths.port(null));
        Assertions.assertEquals("cpu", LlamaCppPaths.backend(null));
        Assertions.assertEquals("http://127.0.0.1:8080", LlamaCppPaths.url(null));
        Assertions.assertNotNull(LlamaCppPaths.home(null));
        Assertions.assertNotNull(LlamaCppPaths.modelsDir(null));
        Assertions.assertNotNull(LlamaCppPaths.serverExecutable(null));
    }
}
