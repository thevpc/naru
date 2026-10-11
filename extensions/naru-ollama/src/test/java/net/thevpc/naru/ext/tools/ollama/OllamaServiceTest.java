package net.thevpc.naru.ext.tools.ollama;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.platform.NEnv;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;

public class OllamaServiceTest {

    @BeforeAll
    public static void setUp() {
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            try {
                NWorkspace ws = Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    public void testPlatformDetection() {
        NEnv env = NEnv.of();
        Assertions.assertNotNull(env.osFamily());
        Assertions.assertNotNull(env.archFamily());
        Assertions.assertFalse(env.osFamily().id().isEmpty());
    }

    @Test
    public void testInstallationInfo() {
        OllamaInstallationInfo info = OllamaService.of().getInstallationInfo();
        Assertions.assertNotNull(info);
        Assertions.assertNotNull(info.getOsFamily());
        Assertions.assertNotNull(info.getArchFamily());
        Assertions.assertNotNull(info.toMsg());

        // Test custom constructor
        OllamaInstallationInfo custom = new OllamaInstallationInfo(true, "/usr/bin/ollama", "0.13.5", "linux", "amd64", "ubuntu");
        Assertions.assertTrue(custom.isInstalled());
        Assertions.assertEquals("/usr/bin/ollama", custom.getExecutablePath());
        Assertions.assertEquals("0.13.5", custom.getVersion());
        Assertions.assertEquals("linux", custom.getOsFamily());
        Assertions.assertEquals("amd64", custom.getArchFamily());
        Assertions.assertEquals("ubuntu", custom.getOsDist());
        Assertions.assertTrue(custom.toString().contains("0.13.5"));

        OllamaInstallationInfo notInstalled = new OllamaInstallationInfo(false, null, null, "linux", "amd64", "ubuntu");
        Assertions.assertFalse(notInstalled.isInstalled());
        Assertions.assertTrue(notInstalled.toString().contains("Not installed"));
    }

    @Test
    public void testStatusSerialization() {
        OllamaInstallationInfo install = new OllamaInstallationInfo(true, "/usr/local/bin/ollama", "0.13.5", "linux", "amd64", "ubuntu");
        OllamaStatus status = new OllamaStatus(
                true,
                "http://localhost:11434",
                "0.13.5",
                true,
                12345L,
                42L,
                Collections.singletonList("qwen2.5-coder:7b"),
                Collections.emptyList(),
                install
        );

        NElement element = status.toElement();
        Assertions.assertNotNull(element);
        Assertions.assertTrue(element.isAnyObject());
        Assertions.assertEquals("http://localhost:11434", element.asObject().get().getStringValue("url").orElse(null));
        Assertions.assertTrue(element.asObject().get().getBooleanValue("running").orElse(false));
        Assertions.assertTrue(element.asObject().get().getBooleanValue("startedByNaru").orElse(false));
        Assertions.assertEquals(12345L, element.asObject().get().getLongValue("pid").orElse(0L));
        Assertions.assertEquals(42L, element.asObject().get().getLongValue("responseTimeMs").orElse(0L));
        Assertions.assertEquals("0.13.5", element.asObject().get().getStringValue("serverVersion").orElse(null));
        Assertions.assertEquals("/usr/local/bin/ollama", element.asObject().get().getStringValue("executablePath").orElse(null));
        Assertions.assertEquals("0.13.5", element.asObject().get().getStringValue("clientVersion").orElse(null));
        Assertions.assertEquals("linux", element.asObject().get().getStringValue("osFamily").orElse(null));
        Assertions.assertEquals("amd64", element.asObject().get().getStringValue("archFamily").orElse(null));
        Assertions.assertEquals("ubuntu", element.asObject().get().getStringValue("osDist").orElse(null));
        Assertions.assertEquals(1, element.asObject().get().getArray("availableModels").get().size());
    }

    @Test
    public void testDirectiveRegistration() {
        NaruOllamaDirective directive = new NaruOllamaDirective();
        Assertions.assertEquals("ollama", directive.name());
        Assertions.assertEquals("ai", directive.group());

        NaruOllamaDirectiveProvider provider = new NaruOllamaDirectiveProvider();
        Assertions.assertNotNull(provider.directives());
        Assertions.assertEquals(1, provider.directives().size());
        Assertions.assertEquals("ollama", provider.directives().get(0).name());
    }

    @Test
    public void testToolsetTools() {
        NaruOllamaToolset toolset = new NaruOllamaToolset("ollama");
        Assertions.assertEquals("ollama", toolset.id());
        Assertions.assertEquals(5, toolset.tools().size());

        OllamaStatusTool statusTool = new OllamaStatusTool();
        NaruToolDefinition def = statusTool.getDefinition(null);
        Assertions.assertEquals("ollama_status", def.getName());

        OllamaStartTool startTool = new OllamaStartTool();
        Assertions.assertEquals("ollama_start", startTool.getDefinition(null).getName());

        OllamaStopTool stopTool = new OllamaStopTool();
        Assertions.assertEquals("ollama_stop", stopTool.getDefinition(null).getName());

        OllamaInstallTool installTool = new OllamaInstallTool();
        Assertions.assertEquals("ollama_install", installTool.getDefinition(null).getName());

        OllamaPsTool psTool = new OllamaPsTool();
        Assertions.assertEquals("ollama_ps", psTool.getDefinition(null).getName());

        NaruOllamaToolsetProvider provider = new NaruOllamaToolsetProvider();
        Assertions.assertEquals("ollama", provider.name());
        Assertions.assertEquals(Collections.singletonList("ollama"), provider.supportedTypes());
        Assertions.assertNotNull(provider.createToolset("ollama", null));
    }

    @Test
    public void testProcessManagerInitialState() {
        Assertions.assertFalse(OllamaProcessManager.isStartedByNaru());
        Assertions.assertEquals(-1, OllamaProcessManager.getManagedPid());
        // Stopping when not started should return false cleanly
        Assertions.assertFalse(OllamaProcessManager.stopIfStartedByNaru());
    }
}
