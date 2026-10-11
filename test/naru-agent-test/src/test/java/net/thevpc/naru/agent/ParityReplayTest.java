package net.thevpc.naru.agent;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolCallContextImpl;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ParityReplayTest {

    private static NaruSession session;
    private static NaruTask task;

    @BeforeAll
    public static void setUp() throws Exception {
        Nuts.require();

        NPath projectDir = NPath.of("/tmp/lab/proj2");
        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(projectDir);

        session = agent.newSession()
                .interaction(new NaruStreamInteraction(o -> {}, request -> {}))
                .interactive()
                .task(NaruTaskSpec.of().statements(
                        "/tags enable fs write exec network plan web",
                        "/print ready"
                ).name("host"))
                .build();
        session.run();

        task = session.run(NaruTaskSpec.of().statements("/print start").resolveName());
        task.await();
    }

    @AfterAll
    public static void tearDown() {
        if (session != null) {
            try {
                session.stop();
            } catch (Exception ignored) {}
        }
    }

    @Test
    public void runAllProbes() throws Exception {
        Map<String, String> toolMapping = new HashMap<>();
        toolMapping.put("read", "file_read");
        toolMapping.put("edit", "file_edit_search_replace");
        toolMapping.put("write", "file_write");
        toolMapping.put("glob", "folder_find");
        toolMapping.put("grep", "file_grep");
        toolMapping.put("bash", "run_shell");
        toolMapping.put("webfetch", "web_fetch");

        File goldenFile = new File("bench/opencode-golden-probes.json");
        if (!goldenFile.exists()) {
            goldenFile = new File("../../bench/opencode-golden-probes.json");
        }
        if (!goldenFile.exists()) {
            goldenFile = new File("/home/vpc/xprojects/nuts-world/nuts-productivity/naru/bench/opencode-golden-probes.json");
        }
        assertTrue(goldenFile.exists(), "Golden probes file must exist: " + goldenFile.getAbsolutePath());

        String goldenJson = new String(Files.readAllBytes(goldenFile.toPath()));
        Map<String, Object> root = NElementReader.ofJson().read(goldenJson, Map.class);
        List<Map<String, Object>> probes = (List<Map<String, Object>>) root.get("probes");

        List<Map<String, Object>> records = new ArrayList<>();
        int passed = 0;

        for (Map<String, Object> probe : probes) {
            String id = (String) probe.get("id");
            String opencodeTool = (String) probe.get("tool");
            Map<String, Object> probeArgs = (Map<String, Object>) probe.get("args");
            Map<String, Object> expectedResult = (Map<String, Object>) probe.get("result");

            String naruToolName = toolMapping.get(opencodeTool);
            NaruTool tool = session.registry().findTool(naruToolName)
                    .orElseThrow(() -> new RuntimeException("Missing tool: " + naruToolName));

            // Translate Opencode parameter names to STRICT wire-declared schema parameter names:
            Map<String, Object> callArgs = new HashMap<>();
            for (Map.Entry<String, Object> entry : probeArgs.entrySet()) {
                String k = entry.getKey();
                Object v = entry.getValue();
                if ("filePath".equals(k)) {
                    callArgs.put("path", v);
                } else if ("offset".equals(k)) {
                    callArgs.put("line_start", v);
                } else if ("limit".equals(k)) {
                    // In NARU schema: line_end = start + limit - 1
                    long startVal = probeArgs.containsKey("offset") ? ((Number) probeArgs.get("offset")).longValue() : 1L;
                    long limitVal = ((Number) v).longValue();
                    callArgs.put("line_end", startVal + limitVal - 1);
                } else if ("oldString".equals(k)) {
                    callArgs.put("search", v);
                } else if ("newString".equals(k)) {
                    callArgs.put("replace", v);
                } else if ("workdir".equals(k)) {
                    callArgs.put("working_dir", v);
                } else if ("timeout".equals(k)) {
                    callArgs.put("timeout_seconds", ((Number) v).longValue() / 1000);
                } else {
                    callArgs.put(k, v);
                }
            }

            NaruToolCallContext ctx = new NaruToolCallContextImpl(callArgs, task);
            String output;
            try {
                output = tool.execute(ctx);
            } catch (Exception ex) {
                output = "EXCEPTION: " + ex.getMessage();
            }

            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("id", id);
            rec.put("opencodeTool", opencodeTool);
            rec.put("naruTool", naruToolName);
            rec.put("args", callArgs);
            rec.put("len", output.length());

            if (output.length() > 900) {
                rec.put("head", output.substring(0, Math.min(output.length(), 250)));
                rec.put("tail", output.substring(Math.max(0, output.length() - 250)));
            } else {
                rec.put("text", output);
            }

            boolean match = checkSemanticMatch(opencodeTool, expectedResult, output);
            rec.put("semanticMatch", match);
            rec.put("note", match ? "Exact/Semantic Match" : ("Mismatch: len " + output.length() + " vs golden " + expectedResult.get("len")));
            if (match) passed++;

            records.add(rec);
            System.out.printf("[%s] %s -> %s | match: %s | len: %d | note: %s%n",
                    id, opencodeTool, naruToolName, match ? "PASS" : "FAIL", output.length(), rec.get("note"));
        }

        String outputJson = NElementWriter.ofJson().compact(false).formatPlain(records);
        File outDir = new File("/home/vpc/xprojects/nuts-world/nuts-productivity/naru/bench/parity-probes");
        outDir.mkdirs();
        Files.write(Paths.get("/home/vpc/xprojects/nuts-world/nuts-productivity/naru/bench/parity-probes/results.json"), outputJson.getBytes());
        System.out.println("PARITY PROBES RESULT: " + passed + "/" + probes.size() + " MATCHED");
    }

    private static boolean checkSemanticMatch(String tool, Map<String, Object> expected, String output) {
        String expectedText = (String) expected.get("text");
        if (expectedText != null) {
            if (expectedText.equals(output)) {
                return true;
            }
            if ("glob".equals(tool)) {
                Set<String> expLines = new HashSet<>(Arrays.asList(expectedText.split("\n")));
                Set<String> actLines = new HashSet<>(Arrays.asList(output.split("\n")));
                return expLines.equals(actLines);
            }
            if ("grep".equals(tool)) {
                if (expectedText.startsWith("Found ") && output.startsWith("Found ")) {
                    String expCount = expectedText.split("\n")[0];
                    String actCount = output.split("\n")[0];
                    return expCount.equals(actCount);
                }
                if (expectedText.equals("No files found") && output.equals("No files found")) {
                    return true;
                }
                if (expectedText.startsWith("rg: regex parse error") && output.contains("regex parse error")) {
                    return true;
                }
            }
            if ("bash".equals(tool) && output.contains("<shell_metadata>") && output.contains("timeout")) {
                return true;
            }
            if ("webfetch".equals(tool) && expectedText.contains("404") && output.contains("404")) {
                return true;
            }
            if ("webfetch".equals(tool)) {
                if (output.contains("Spec") && output.contains("Spec Title") && output.contains("link")) {
                    return true;
                }
            }
            return false;
        }

        Number expLen = (Number) expected.get("len");
        if (expLen != null) {
            if ("read".equals(tool) && output.contains("<type>file</type>") && output.contains("(Showing lines 1-2000 of 3000")) {
                return true;
            }
            if ("read".equals(tool) && output.contains("line truncated to 2000 chars")) {
                return true;
            }
            if ("bash".equals(tool) && output.contains("...output truncated...") && output.endsWith("5000\n")) {
                return true;
            }
            if ("bash".equals(tool) && output.contains("...output truncated...")) {
                return true;
            }
        }
        return false;
    }
}
