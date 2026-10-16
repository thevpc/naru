package net.thevpc.naru.agent;

import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolCallContextImpl;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class NaruToolParityRegressionTest {

    private static NPath tempProjectDir;
    private static NaruWorkspace agent;
    private static NaruSession session;
    private static NaruTask task;

    @BeforeAll
    public static void setUpAll() throws Exception {
        Nuts.require();

        Path p = Files.createTempDirectory("naru-parity-test");
        tempProjectDir = NPath.of(p.toString());

        agent = NaruWorkspace.of();
        agent.projectDirectory(tempProjectDir);

        session = agent.newSession()
                .interaction(new NaruStreamInteraction(o -> {}, request -> {}))
                .interactive()
                .task(NaruTaskSpec.of().statements(
                        "/tags enable fs write exec network plan",
                        "/print ready"
                ).name("host"))
                .build();
        session.run();

        task = session.run(NaruTaskSpec.of().statements("/print start").resolveName());
        task.await();
    }

    @AfterAll
    public static void tearDownAll() {
        if (session != null) {
            try {
                session.stop();
            } catch (Exception ignored) {}
        }
    }

    @Test
    public void testSchemaVsImplementationNoCrashOnRequiredArgs() {
        Map<String, NaruTool> tools = session.registry().tools();
        assertFalse(tools.isEmpty(), "Tool registry should not be empty");

        for (NaruTool tool : tools.values()) {
            if ("ollama_install".equals(tool.name())) {
                continue; // Do not attempt to run OS-level package installer in unit test
            }
            NaruToolDefinition def = tool.getDefinition(task);
            if (!(def instanceof NaruToolDefinitionFunction)) {
                continue;
            }
            NaruToolDefinitionFunction fn = (NaruToolDefinitionFunction) def;
            Map<String, Object> dummyArgs = new HashMap<>();

            for (NaruToolParameter param : fn.getParams()) {
                if (param.isRequired()) {
                    String name = param.getName();
                    NaruToolParameter.Type type = param.getType();
                    if (type == NaruToolParameter.Type.STRING) {
                        if ("path".equalsIgnoreCase(name) || "filePath".equalsIgnoreCase(name)) {
                            dummyArgs.put(name, tempProjectDir.resolve("dummy.txt").toString());
                        } else if ("command".equalsIgnoreCase(name)) {
                            dummyArgs.put(name, "echo test");
                        } else if ("url".equalsIgnoreCase(name)) {
                            dummyArgs.put(name, "http://127.0.0.1:8099/spec.html");
                        } else {
                            dummyArgs.put(name, "dummy_value");
                        }
                    } else if (type == NaruToolParameter.Type.INTEGER || type == NaruToolParameter.Type.NUMBER) {
                        dummyArgs.put(name, 1);
                    } else if (type == NaruToolParameter.Type.BOOLEAN) {
                        dummyArgs.put(name, false);
                    } else if (type == NaruToolParameter.Type.ARRAY) {
                        Map<String, Object> sampleItem = new HashMap<>();
                        sampleItem.put("description", "item 1");
                        sampleItem.put("title", "item 1");
                        dummyArgs.put(name, Collections.singletonList(sampleItem));
                    } else {
                        dummyArgs.put(name, "dummy");
                    }
                }
            }

            System.out.println("Testing tool: " + tool.name());
            NaruToolCallContext ctx = new NaruToolCallContextImpl(dummyArgs, task);
            try {
                String result = tool.execute(ctx);
                assertNotNull(result, "Tool " + tool.name() + " returned null output");
                assertFalse(result.toLowerCase().contains("is required"),
                        "Tool " + tool.name() + " reported missing required parameter despite all schema-required args being provided. Output: " + result);
            } catch (Exception e) {
                fail("Tool " + tool.name() + " crashed with exception on required args: " + e.getMessage());
            }
        }
    }

    @Test
    public void testFileEditParityRules() {
        NPath testFile = tempProjectDir.resolve("parity-dup.txt");
        testFile.writeString("foo\nbar\nfoo\n");

        NaruTool editTool = session.registry().findTool("file_edit_search_replace").get();
        assertNotNull(editTool);

        // Ambiguous match without replaceAll
        Map<String, Object> args1 = new HashMap<>();
        args1.put("path", testFile.toString());
        args1.put("search", "foo");
        args1.put("replace", "baz");
        String res1 = editTool.execute(new NaruToolCallContextImpl(args1, task));
        assertEquals("Found multiple matches for oldString. Provide more surrounding context to make the match unique.", res1);

        // Identical search and replace
        Map<String, Object> args2 = new HashMap<>();
        args2.put("path", testFile.toString());
        args2.put("search", "foo");
        args2.put("replace", "foo");
        String res2 = editTool.execute(new NaruToolCallContextImpl(args2, task));
        assertEquals("No changes to apply: oldString and newString are identical.", res2);

        // ReplaceAll = true
        Map<String, Object> args3 = new HashMap<>();
        args3.put("path", testFile.toString());
        args3.put("search", "foo");
        args3.put("replace", "baz");
        args3.put("replaceAll", true);
        String res3 = editTool.execute(new NaruToolCallContextImpl(args3, task));
        assertEquals("Edit applied successfully.", res3);
        assertEquals("baz\nbar\nbaz\n", testFile.readString());
    }

    @Test
    public void testFileWriteParityRules() {
        NPath testFile = tempProjectDir.resolve("parity-write.txt");
        NaruTool writeTool = session.registry().findTool("file_write").get();
        assertNotNull(writeTool);

        Map<String, Object> args = new HashMap<>();
        args.put("path", testFile.toString());
        args.put("content", "hello parity\n");
        String res = writeTool.execute(new NaruToolCallContextImpl(args, task));
        assertEquals("Wrote file successfully.", res);
        assertEquals("hello parity\n", testFile.readString());
    }
}
