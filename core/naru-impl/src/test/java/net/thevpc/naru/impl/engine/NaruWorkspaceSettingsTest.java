package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.api.agent.NaruTaskConfig;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.registry.NaruConfigKey;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The contracts the desktop settings panel builds on, kept out of the UI:
 * a key catalog that exists before any session, one value typing rule shared by
 * {@code /settings} and the panel, and a store that survives shadowing, removal,
 * comments, and a restart.
 */
public class NaruWorkspaceSettingsTest {

    @BeforeAll
    static void requireNuts() {
        Nuts.require();
    }

    private static NaruWorkspace workspaceAt(NPath projectDir) {
        NaruWorkspace workspace = NaruWorkspace.of();
        workspace.projectDirectory(projectDir);
        return workspace;
    }

    @Test
    public void configKeysAnswerWithoutASession() {
        List<NaruConfigKey> keys = workspaceAt(tempProject()).configKeys();
        List<String> names = keys.stream().map(NaruConfigKey::key).collect(Collectors.toList());
        Assertions.assertTrue(names.contains("maxSteps"), "core keys must be there with no session");
        Assertions.assertTrue(names.contains("trace"));
        Assertions.assertEquals(names.stream().sorted().collect(Collectors.toList()), names,
                "the catalog arrives sorted, the order an editor lists it in");
        Assertions.assertEquals(names.stream().distinct().collect(Collectors.toList()), names);
    }

    @Test
    public void parseValueTypesTheWaySettingsFilesHold() {
        Assertions.assertTrue(NaruTaskConfig.parseValue("true").isBoolean());
        Assertions.assertTrue(NaruTaskConfig.parseValue("40000").isNumber());
        Assertions.assertTrue(NaruTaskConfig.parseValue("3.5").isNumber());
        Assertions.assertTrue(NaruTaskConfig.parseValue("ollama/qwen2.5-coder:7b").isString());
        Assertions.assertEquals(Boolean.FALSE,
                NaruTaskConfig.parseValue("False").asBooleanValue().orNull(),
                "a boolean reads case-insensitively, matching /settings");
    }

    @Test
    public void typedWritesRoundTripThroughDiskAndShadowing() {
        NPath dir = tempProject();
        NaruWorkspace workspace = workspaceAt(dir);
        NaruEnv env = workspace.env();
        env.put("trace", NaruTaskConfig.parseValue("true"), NaruVisibility.PRIVATE);
        env.put("model", NaruTaskConfig.parseValue("ollama/qwen2.5-coder:7b"), NaruVisibility.PUBLIC);
        env.put("maxSteps", NaruTaskConfig.parseValue("40"), NaruVisibility.PUBLIC);

        // a second handle on the same project reads what the first wrote: a restart view
        NaruEnv reopened = workspaceAt(dir).env();
        NElement traceValue = reopened.get("trace", NaruVisibility.PRIVATE).orNull();
        Assertions.assertNotNull(traceValue);
        Assertions.assertEquals(Boolean.TRUE, traceValue.asBooleanValue().orNull(),
                "a boolean written by the panel is a boolean read from the file");
        // private shadows public, and per-visibility listings keep showing both files
        reopened.put("model", NaruTaskConfig.parseValue("other/model"), NaruVisibility.PRIVATE);
        Assertions.assertEquals("other/model", NaruTaskConfig.toText(reopened.get("model").orNull()));
        Assertions.assertEquals("ollama/qwen2.5-coder:7b",
                NaruTaskConfig.toText(reopened.get("model", NaruVisibility.PUBLIC).orNull()));
        // removing from one file restores the other's value, not nothing
        reopened.put("model", null, NaruVisibility.PRIVATE);
        Assertions.assertEquals("ollama/qwen2.5-coder:7b", NaruTaskConfig.toText(reopened.get("model").orNull()));
        Assertions.assertTrue(reopened.entries(NaruVisibility.PRIVATE).containsKey("trace"));
        Assertions.assertEquals(2, reopened.entries(NaruVisibility.PUBLIC).size());
    }

    @Test
    public void anEditThroughTheEnvKeepsTheFileComments() {
        NPath dir = tempProject();
        NPath publicEnv = dir.resolve(".naru/config/env.tson");
        publicEnv.parent().mkdirs();
        publicEnv.writeString(""
                + "# team defaults for this project\n"
                + "{\n"
                + "  # keep grep output bounded\n"
                + "  maxSteps : 8\n"
                + "}\n");
        NaruEnv env = workspaceAt(dir).env();
        env.put("maxSteps", NaruTaskConfig.parseValue("16"), NaruVisibility.PUBLIC);
        env.put("trace", NaruTaskConfig.parseValue("false"), NaruVisibility.PUBLIC);

        String text = publicEnv.readString();
        Assertions.assertTrue(text.contains("# team defaults for this project"),
                "the leading comment is the team's, not ours to drop:\n" + text);
        Assertions.assertTrue(text.contains("# keep grep output bounded"),
                "the comment above the edited key belongs to it:\n" + text);
        Assertions.assertTrue(text.contains("16"), text);
        Assertions.assertTrue(text.contains("trace"), text);
        // and the typed value reads back as the same type it was written as
        Assertions.assertTrue(workspaceAt(dir).env().get("maxSteps").get().isNumber());
    }

    private static NPath tempProject() {
        return NPath.ofTempFolder("naru-workspace-settings-" + System.nanoTime());
    }
}
