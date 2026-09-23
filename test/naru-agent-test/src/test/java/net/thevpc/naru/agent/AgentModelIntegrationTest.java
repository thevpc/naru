package net.thevpc.naru.agent;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.ext.tools.ollama.OllamaProcessManager;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NOut;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test: start a session that bootstraps an Ollama server, selects a
 * local model and runs a plain prompt through it.
 *
 * <p>This test exercises the full agent pipeline (session, task, scheduler,
 * /ollama directive, /model directive, model chat) and asserts the important
 * system properties of that pipeline:
 * <ul>
 *   <li>the session <b>always terminates</b> (hard {@code assertTimeoutPreemptively}
 *       bound + a low {@code maxSteps}; it used to hang indefinitely when a code-only
 *       model without native tool support kept emitting emulated tool calls),</li>
 *   <li>the task reaches a terminal state,</li>
 *   <li>the model produced a final assistant message (printed for visibility).</li>
 * </ul>
 * It deliberately does not assert on the model's factual wording: the answer of a
 * small local model like {@code deepseek-coder-v2:16b} (which has no tool
 * capability) is stochastic, and asserting on it would make the test flaky.
 *
 * <p>The model can be overridden with {@code -Dnaru.test.model=ollama/qwen3:8b}.</p>
 *
 * <p>{@link #testAgentModelToolCall()} additionally forces the model to <b>use a
 * real tool</b>: it must call {@code file_write} to create a file containing a
 * unique marker that exists nowhere else, and the test asserts the file appears on
 * disk (in the project directory) with the exact marker. That is unambiguous proof
 * the tool call round-trip through the agent loop worked. It uses a model with
 * <b>native</b> tool support (default {@code ollama/qwen3:8b}, override with
 * {@code -Dnaru.test.tool.model}), and opts the file tools in via
 * {@code /mode implement} + {@code /tools add-tagged fs} (tagged tools are hidden
 * from the model unless the task opts into their tag).</p>
 *
 * <p>{@link #testAgentBuildsMavenCalculatorProject()} is the code-generation
 * showcase: one script hands the model a single goal (build a Java Maven
 * command-line calculator supporting {@code + - * /}) and Naru + the model decide
 * which tools to call. The model writes a Maven project, iterates on
 * compile/test failures through {@code run_shell} (offline — there is no network),
 * and repeats until the project compiles, its JUnit tests pass and the calculator
 * runs correctly. The test then verifies the outcome independently: a
 * {@code pom.xml} exists under the session folder, {@code mvn -o -q test} succeeds
 * offline in the project root, and {@code calc.Main} prints the expected results.
 * This doubles as a cheap benchmark of which local LLMs are capable enough for a
 * full write-build-test-fix loop.</p>
 */
public class AgentModelIntegrationTest {

    private static final Duration TEST_TIMEOUT = Duration.ofMinutes(10);

    private NaruAgent agent;
    private NPath folder;
    private NaruSession session;

    @BeforeEach
    void setUp() {
        NWorkspace ws = Nuts.require();
        // Initialize Naru system
        folder = NPath.ofTempFolder("naru");
        agent = new NaruAgentImpl();
        agent.setProjectDirectory(folder);
        NOut.println(NMsg.ofC("create agent at %s", agent));
    }

    @AfterEach
    void tearDown() {
        // Release the session so nothing keeps running after the test. stop() alone is
        // sufficient and idempotent: it drains the scheduler + readline threads, and
        // remaining workers are daemon so the JVM can always exit. (terminate() would
        // only matter for force-aborting live tasks, and that triggers stop() anyway.)
        if (session != null) {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
        // Safety net for paths where the session script didn't run to completion
        // (notably the preemptive-timeout path). On the normal path, the session's
        // own "/ollama stop" statement already stopped a naru-spawned server, and
        // stopIfStartedByNaru() only touches naru-spawned processes (never an
        // external/system ollama server).
        try {
            OllamaProcessManager.stopIfStartedByNaru();
        } catch (Exception ignored) {
            // nothing to clean up
        }
    }

    @Test
    public void testAgentModelInteraction() {
        assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
            session = agent.startSession(
                    "/ollama serve",
                    "/model use " + configuredModel(),
                    "what is the capital of Tunisia",
                    // stop only the ollama server naru spawned (never external ones);
                    // the @AfterEach guard covers the case where the script is cut
                    // short by the test timeout.
                    "/ollama stop"
            );
            // Bound the agent loop: if the model keeps emitting (emulated) tool calls
            // instead of answering, the session must still terminate promptly.
            session.setSessionEnv("maxSteps", 3);

            // Capture the task *before* waitFor(): once the session stops, tasks()
            // and findTask() are no longer usable, but the task object itself keeps
            // exposing its status/result.
            NaruTask task = captureForegroundTask(session);

            // This used to hang indefinitely whenever the (uncapped) tool-call loop
            // never ended, or the (non-daemon) readline thread kept the test JVM alive.
            session.waitFor();

            assertNotNull(task, "expected the session to create a task");
            assertTrue(task.status() == NaruTaskStatus.DONE,
                    () -> "expected the task to finish, but status was " + task.status());
            NaruMessage last = task.getLastResult();
            assertNotNull(last, "expected the model to produce a final assistant message");
            String answer = last.getContent();
            NOut.println(NMsg.ofC("model answer: %s", answer == null ? "<empty>" : answer.trim()));
        });
    }

    private static String configuredModel() {
        String m = System.getProperty("naru.test.model", "ollama/deepseek-coder-v2:16b");
        return m;
    }

    /**
     * A task the model can only complete by calling a real tool. The prompt asks it
     * to write a file containing a unique marker that exists nowhere else; the test
     * then asserts the file exists on disk with exactly that marker — something that
     * is impossible without the {@code file_write} tool round-tripping through the
     * agent loop (session → model → tool call → tool execution → model).
     */
    @Test
    public void testAgentModelToolCall() {
        assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
            String fileName = "naru-tool-test-" + System.nanoTime() + ".txt";
            String marker = "NARU-TOOL-MARKER-" + UUID.randomUUID();

            session = agent.startSession(
                    "/ollama serve",
                    "/model use " + configuredToolModel(),
                    // implement mode: its system prompt authorizes file writes
                    // (planning mode is READ-ONLY and tells the model not to edit).
                    "/mode implement",
                    // file tools are tagged "fs" and hidden from the model unless the
                    // task opts into that tag; this makes file_write/file_read visible.
                    "/tools add-tagged fs",
                    // models tend to "explore" with cd/pwd first; excluding cd keeps
                    // the agent focused on the actual write (this test's purpose).
                    "/tools exclude cd",
                    "Call the file_write tool ONCE with path=\"" + fileName
                            + "\" and content=\"" + marker
                            + "\". The bare file name is relative to the current (project root) directory. "
                            + "Do NOT call any other tool.",
                    "/ollama stop"
            );
            // Bound the agent loop: a couple of tool calls + a final answer is enough.
            session.setSessionEnv("maxSteps", 6);

            NaruTask task = captureForegroundTask(session);

            session.waitFor();

            assertNotNull(task, "expected the session to create a task");
            assertTrue(task.status() == NaruTaskStatus.DONE,
                    () -> "expected the task to finish, but status was " + task.status());

            // The decisive assertion: the file can only exist if the model invoked
            // file_write (or an equivalent file tool) and it really ran on disk.
            // The model may use a relative path (folder.resolve(fileName)) or an
            // absolute path that still lands under the project dir, so search the
            // whole project folder for any file containing the unique marker.
            NPath reported = folder.resolve(fileName);
            if (!reported.exists()) {
                reported = findFileContaining(folder, marker);
            }
            NPath file = reported;
            assertNotNull(file,
                    () -> "expected the model to really execute a tool that wrote the marker '"
                            + marker + "' into a file under " + folder
                            + " — no such file found");
            // Regression guard: a file whose NAME contains quote characters means a
            // TSON-formatted argument leaked into the tool (NElement toString()
            // yields "name.txt" instead of name.txt). That corrupts every tool call,
            // so names must be clean, not just the content.
            assertTrue(!file.name().contains("\"") && !file.name().contains("'"),
                    () -> "expected the file name to be clean, but it contains quote chars: "
                            + file.name());
            String actual = file.readString();
            assertTrue(actual != null && actual.contains(marker),
                    () -> "expected the file " + file + " to contain the marker '"
                            + marker + "' but got: " + (actual == null ? "<null>" : actual));

            NaruMessage last = task.getLastResult();
            if (last != null) {
                String answer = last.getContent();
                NOut.println(NMsg.ofC("model answer: %s", answer == null ? "<empty>" : answer.trim()));
            }
        });
    }

    private static String configuredToolModel() {
        // The tool test needs a model with NATIVE tool support: deepseek-coder-v2:16b
        // has no tools capability (tools are only emulated for it, which is noisy and
        // unreliable). qwen3:8b supports native tool calling and is installed with the
        // test setup. Override with -Dnaru.test.tool.model=ollama/my-model.
        return System.getProperty("naru.test.tool.model", "ollama/qwen3:8b");
    }

    /**
     * Code-generation showcase: a single scripted session with a single goal.
     * Naru + the model decide which tools to call. The model must create a complete
     * Java Maven project (a command-line calculator for + - * /) in the session
     * folder, then loop on compile/test failures (offline Maven — no network)
     * until it builds, all tests pass, and the calculator runs correctly.
     *
     * <p>Nothing here tells the model which tool to use for any given step beyond
     * describing the goal and the loop; the script only enables the required tool
     * capabilities (file tools tagged {@code fs}, the shell tool tagged
     * {@code network}) and hides the wandering-prone working-dir tools. The test
     * verification is independent of the model's narration, so this test doubles as
     * a benchmark: a model that is "good enough" will leave behind a project whose
     * {@code pom.xml} exists, whose {@code mvn -o -q test} passes offline, and whose
     * compiled {@code calc.Main} computes correct results.</p>
     */
    @Test
    public void testAgentBuildsMavenCalculatorProject() {
        // a write->build->fix->run loop is much longer than a chat round-trip,
        // so give the showcase its own generous (but still bounded) timeout.
        assertTimeoutPreemptively(Duration.ofMinutes(20), () -> {
            session = agent.startSession(
                    "/ollama serve",
                    "/model use " + configuredToolModel(),
                    // implement mode: authorizes file writes AND shell execution
                    // (planning mode is read-only).
                    "/mode implement",
                    // file tools are tagged "fs", run_shell is tagged "network";
                    // both are hidden from the model unless the task opts in.
                    "/tools add-tagged fs",
                    "/tools add-tagged network",
                    // hide everything irrelevant to the write->build->test->run loop:
                    // working-dir wanderers, web (offline), git/index/search/semantic
                    // and tag tools, plus the redundant maven_* shortcuts. A lean
                    // toolset keeps even small models focused on the goal.
                    "/tools exclude cd set_working_dir",
                    "/tools exclude search_web tag_add tag_remove",
                    "/tools exclude git_status git_diff git_log git_commit",
                    "/tools exclude project_map code_symbols find_symbol project_summary",
                    "/tools exclude semantic_search semantic_index maven_compile maven_test maven_package",
                    CALCULATOR_PROMPT,
                    "/ollama stop"
            );
            // Generous bound for the write->build->fix->run loop; never infinite.
            session.setSessionEnv("maxSteps", 80);

            NaruTask task = captureForegroundTask(session);

            session.waitFor();

            assertNotNull(task, "expected the session to create a task");
            assertTrue(task.status() == NaruTaskStatus.DONE,
                    () -> "expected the task to finish, but status was " + task.status());

            // Verification — the proof is the produced project, not the narration.
            // 1. a Maven project (pom.xml) appeared somewhere under the session folder;
            NPath pom = findByName(folder, "pom.xml");
            assertNotNull(pom,
                    () -> "expected the model to create a Maven project (pom.xml) under " + folder);
            NPath projectRoot = pom.parent();
            NOut.println(NMsg.ofC("maven project created at %s", projectRoot));

            // 2. it builds and passes its tests OFFLINE;
            CommandResult build = runCommand(projectRoot, "mvn -o -q test", 300);
            assertTrue(build.exitCode == 0,
                    () -> "expected 'mvn -o -q test' to succeed in " + projectRoot
                            + " but got EXIT_CODE=" + build.exitCode + ":\n"
                            + tail(build.output, 2000));

            // 3. the compiled calculator actually computes.
            CommandResult run = runCommand(projectRoot, "java -cp target/classes calc.Main \"7*6\"", 60);
            assertTrue(run.exitCode == 0 && "42".equals(run.output.trim()),
                    () -> "expected 'java -cp target/classes calc.Main \"7*6\"' to print 42 "
                            + "but got EXIT_CODE=" + run.exitCode + " output='" + run.output + "'");

            NaruMessage last = task.getLastResult();
            if (last != null) {
                String answer = last.getContent();
                NOut.println(NMsg.ofC("model answer: %s", answer == null ? "<empty>" : answer.trim()));
            }
        });
    }

    /**
     * The full task handed to the model — this IS the "command list". The only
     * decisive parts are the goal, the file layout (so the verification can find
     * the project), and the constraints the environment imposes (offline Maven,
     * versions present in the local repository). The model chooses which tools to
     * call and how to iterate.
     */
    private static final String CALCULATOR_PROMPT = String.join("\n",
            "Build a complete Java Maven command-line calculator project IN THE CURRENT DIRECTORY, ",
            "which IS the project root. pom.xml must be at the TOP level of the current directory. ",
            "Do NOT create a sub-folder for the project and do NOT create files outside the current directory.",
            "",
            "The calculator supports + - * / on INTEGER numbers that may have MORE than one digit, ",
            "e.g. 7*6 -> 42, 12/4 -> 3, 4+5 -> 9, 7-5 -> 2, 123+45 -> 168. ",
            "Expressions are written WITHOUT any whitespace: one operand, one operator, another operand.",
            "So eval receives exactly strings like \"7*6\" or \"12/4\" (no spaces, no parentheses).",
            "",
            "Create exactly these 4 files with the file tools:",
            "- pom.xml: groupId calc, artifactId calculator, version 1.0; properties maven.compiler.source=17, ",
            "  maven.compiler.target=17, project.build.sourceEncoding=UTF-8; ONE dependency ",
            "  org.junit.jupiter:junit-jupiter:5.8.2 with scope test; build plugins ",
            "  org.apache.maven.plugins:maven-compiler-plugin:3.8.1 and org.apache.maven.plugins:maven-surefire-plugin:2.22.2. ",
            "  Do NOT add any other dependency or plugin.",
            "- src/main/java/calc/Calculator.java: package calc; public class Calculator; ",
            "  public static int eval(String expression): parse a simple 'a op b' expression (single operator, ",
            "  integer operands that may have multiple digits, no whitespace) and return the result; ",
            "  support + - * /; throw IllegalArgumentException for unknown operators.",
            "- src/main/java/calc/Main.java: package calc; public class Main; public static void main(String[] args): ",
            "  read args[0] as the expression and print ONLY the integer result (no extra text); ",
            "  print an error to stderr and exit(1) if no argument is given.",
            "- src/test/java/calc/CalculatorTest.java: package calc; JUnit 5 (junit-jupiter) tests asserting ",
            "  eval(\"4+5\")==9, eval(\"7-5\")==2, eval(\"7*6\")==42 and eval(\"12/4\")==3.",
            "",
            "ENVIRONMENT: there is NO network access, Maven works ONLY offline. ALWAYS run Maven as 'mvn -o -q test' ",
            "(with -o for offline, -q for quiet). mvn and java are both on the PATH. run_shell returns ",
            "'EXIT_CODE=<code>' followed by the command output (stdout+stderr, possibly truncated at 8KB).",
            "",
            "WORK LOOP:",
            "1. Create the 4 files.",
            "2. Run 'mvn -o -q test' with run_shell. The project root is the current directory; do not cd anywhere.",
            "3. If the build or the tests fail, study the error output and diagnose. The defect is almost ",
            "   certainly in Calculator.eval's parsing of the expression, so fix ONLY Calculator.java (or ",
            "   Main.java if the printed output is wrong). NEVER modify or weaken the tests or the pom.xml ",
            "   to make them pass, and never change plugins to 'fix' the build; then run 'mvn -o -q test' ",
            "   again. Repeat until it succeeds (BUILD SUCCESS) and all tests pass.",
            "4. Then run 'java -cp target/classes calc.Main \"7*6\"' (must print 42) and ",
            "   'java -cp target/classes calc.Main \"12/4\"' (must print 3).",
            "5. DONE — you may ONLY finish when ALL of these are true, from commands you ran yourself: ",
            "   'mvn -o -q test' ended with BUILD SUCCESS and all tests pass, and the two java commands ",
            "   printed 42 and 3. Do NOT reply with advice, questions, or suggestions, and never declare ",
            "   completion based on tool results alone. When (and only when) the build is green, all tests ",
            "   pass, and both java runs printed their results, reply with a short factual summary of what ",
            "   you created, then STOP. That summary is your final answer; do not call any more tools after it.");

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    /**
     * Run a shell command in {@code workDir}, capturing combined output.
     */
    private static CommandResult runCommand(NPath workDir, String command, long timeoutSeconds) {
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
        pb.directory(new File(workDir.toString()));
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            ByteArrayOutputStream outs = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            try (InputStream in = p.getInputStream()) {
                while ((n = in.read(buf)) >= 0) {
                    outs.write(buf, 0, n);
                }
            }
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new CommandResult(-1, "<command timed out after " + timeoutSeconds + "s>");
            }
            return new CommandResult(p.exitValue(), outs.toString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            return new CommandResult(-1, "<failed to run: " + e + ">");
        }
    }

    private static String tail(String s, int max) {
        if (s == null) {
            return "<null>";
        }
        return s.length() <= max ? s : "...\n" + s.substring(s.length() - max);
    }

    /**
     * Recursively find the first file whose name equals {@code name} (tolerant of
     * the model writing relative vs absolute paths or nesting the project deeper).
     */
    private static NPath findByName(NPath root, String name) {
        if (root == null || !root.isDirectory()) {
            return null;
        }
        for (NPath child : root.list()) {
            if (child.isDirectory()) {
                NPath found = findByName(child, name);
                if (found != null) {
                    return found;
                }
            } else if (child.isRegularFile() && name.equals(child.name())) {
                return child;
            }
        }
        return null;
    }

    /**
     * Recursively search a directory tree for the first regular file whose text
     * content contains {@code marker} (returns {@code null} when not found).
     */
    private static NPath findFileContaining(NPath root, String marker) {
        if (root == null || !root.isDirectory()) {
            return null;
        }
        for (NPath child : root.list()) {
            if (child.isDirectory()) {
                NPath found = findFileContaining(child, marker);
                if (found != null) {
                    return found;
                }
            } else if (child.isRegularFile()) {
                try {
                    String text = child.readString();
                    if (text != null && text.contains(marker)) {
                        return child;
                    }
                } catch (Exception ignore) {
                    // binary or unreadable file — skip
                }
            }
        }
        return null;
    }

    private static NaruTask captureForegroundTask(NaruSession s) {
        long fgId = s.foregroundTaskId();
        NaruTask task = fgId >= 0 ? s.findTask(fgId).orNull() : null;
        if (task == null) {
            List<NaruTask> tasks = s.tasks();
            task = tasks.isEmpty() ? null : tasks.get(0);
        }
        return task;
    }
}