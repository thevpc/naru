package net.thevpc.naru.agent;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.ext.tools.ollama.OllamaProcessManager;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.impl.engine.stmt.NaruNopStmt;
import net.thevpc.naru.impl.engine.stmt.NaruPromptStmt;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NOut;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * {@code /mode implement} + {@code /tags enable fs} (tagged tools are hidden
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
    public void testScriptControlFlowLoopWithSystem() {
        // No LLM involved: this is a pure engine mechanics check for the building
        // blocks of the codegen showcase — /while + /end control flow, /set --task
        // variables and update operators, the /system directive (raw shell argument,
        // full env, exit code published as lastExitCode), /assert --set green to
        // fold each check into one flag, and early exit of a bounded loop once an
        // iteration turns green. The loop keys on the script's own `green` var and
        // NEVER writes lastExitCode (it is naru-owned): green is reset at the top
        // of each iteration, the /assert chain AND-accumulates, green==1 stops the
        // loop. No model prompt appears in the script, so no model is needed at all.
        assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
            session = agent.startSession(
                    "/set --task attempts = 0",
                    "/set --task green = 0",
                    "/while (attempts < 3) && (green != 1)",
                    "/set --task green = 1",
                    "/set --task attempts = attempts + 1",
                    "/system if [ -f \".loop-flag\" ]; then exit 0; else touch .loop-flag; exit 5; fi",
                    "/assert --set green lastExitCode == 0",
                    "/end"
            );
            NaruTask task = captureForegroundTask(session);
            session.waitFor();

            assertNotNull(task, "expected the session to create a task");
            assertTrue(task.status() == NaruTaskStatus.DONE,
                    () -> "expected the task to finish, but status was " + task.status());
            // Attempt 1: flag absent -> /system exits 5 -> assert fails -> green=0 -> loop again.
            // Attempt 2: flag present -> /system exits 0 -> assert passes -> green=1 -> EXITS EARLY.
            assertEquals("2", task.getTaskEnv("attempts", true).map(x -> x == null ? "<null>" : x.toString()).orNull(),
                    "expected the bounded loop to run twice and then stop early");
            assertEquals("1", task.getTaskEnv("green", true).map(Object::toString).orNull(),
                    "expected the final iteration to be fully green");
            assertEquals("0", task.getTaskEnv("lastExitCode", true).map(x -> x == null ? "<null>" : x.toString()).orNull(),
                    "expected the last /assert (green outcome) to publish lastExitCode 0");
            assertTrue(folder.resolve(".loop-flag").isRegularFile(),
                    "expected /system to really run in the project dir (it must create .loop-flag)");
        });
    }

    @Test
    public void testScriptStreamBufferAndComments() {
        // Engine-only (no LLM) checks for the script facilities the codegen
        // showcase now relies on:
        //  1. startSession(InputStream) loads and runs a script from a stream;
        //  2. "//" (and "#") lines are comments — ignored;
        //  3. "/set --session" writes the session env that NaruPromptStmt reads
        //     (maxSteps), so the showcase can bound itself from its own script;
        //  4. "/buffer on ... /buffer off" folds raw multi-line text (blank lines
        //     and indentation included) into ONE prompt statement.
        assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
            String script = "// script born as a stream: comments and blank lines are ignored\n"
                    + "\n"
                    + "/set --session maxSteps = 12\n"
                    + "/system touch stream-loaded-marker\n";
            session = agent.startSession(new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)));
            NaruTask task = captureForegroundTask(session);
            session.waitFor();

            assertNotNull(task, "expected the session to create a task");
            assertTrue(task.status() == NaruTaskStatus.DONE,
                    () -> "expected the task to finish, but status was " + task.status());
            assertTrue(folder.resolve("stream-loaded-marker").isRegularFile(),
                    "expected the streamed script to really run (it must create the marker file)");
            // session env written by the script itself (what NaruPromptStmt reads as maxSteps)
            assertEquals("12", task.session().getSessionEnv("maxSteps")
                            .map(x -> x == null ? "<null>" : x.toString()).orNull(),
                    "expected /set --session maxSteps to be visible as a session env entry");

            // parser-level checks on the same finished task: comments, blanks, buffer folding
            assertTrue(task.parseStatement("// just a comment").get() instanceof NaruNopStmt,
                    "expected '//' lines to parse as comments");
            assertTrue(task.parseStatement("# legacy comment too").get() instanceof NaruNopStmt,
                    "expected '#' lines to parse as comments");
            assertTrue(task.parseStatement("   ").get() instanceof NaruNopStmt,
                    "expected blank lines to parse as no-ops (and not crash like an empty optional)");
            // /buffer on ... /buffer off folds raw lines into ONE prompt statement
            task.parseStatement("/buffer on");
            task.parseStatement("first line");
            task.parseStatement("");
            task.parseStatement("  second indented line");
            NOptional<NaruStatement> folded = task.parseStatement("/buffer off");
            assertTrue(folded.isPresent() && folded.get() instanceof NaruPromptStmt,
                    "expected /buffer off to emit ONE prompt statement, got " + folded);
            assertEquals("first line\n\n  second indented line",
                    ((NaruPromptStmt) folded.get()).prompt(),
                    "expected the buffer to join raw lines verbatim, preserving blanks and indentation");
        });
    }

    @Test
    public void testScriptTagsScriptableFileAndSystemSave() {
        // Engine-only (no LLM): the scriptable primitives the naru-native green
        // check relies on — /tags (enable/disable + list), scriptable /file
        // (read/grep/find publish lastExitCode; --save stores values; find also
        // stores the parent dir with --dir), /system --save (captures trimmed
        // output into a var) and {{var}} moustache interpolation of task vars
        // into later directive arguments.
        assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
            String script = ""
                    + "// /tags: opt into the fs tag, exclude two tools, list for good measure\n"
                    + "/tags enable fs network\n"
                    + "/tags disable cd set_working_dir\n"
                    + "/tags list\n"
                    + "// prepare a nested project layout with a file to grep\n"
                    + "/system mkdir -p proj\n"
                    + "/system touch proj/pom.xml\n"
                    + "/system echo hi > data.txt\n"
                    + "// scriptable /file find: publishes exit code, saves first match + parent dir\n"
                    + "/file find . --include=pom.xml --save=pom --dir=pomDir\n"
                    + "/file find . --include=data.txt --save=data --dir=dataDir\n"
                    + "// scriptable /file grep: 0 on match, 1 on miss\n"
                    + "/file grep data.txt --pattern=hi --save=g\n"
                    + "/if lastExitCode == 0\n"
                    + "/system touch grep-hit-ok\n"
                    + "/end\n"
                    + "/file grep data.txt --pattern=zzz-missing --save=g2\n"
                    + "/if lastExitCode == 1\n"
                    + "/system touch grep-miss-ok\n"
                    + "/end\n"
                    + "// /system --save captures trimmed output; string equality works in /if\n"
                    + "/system --save answer echo 42\n"
                    + "/if answer == \"42\"\n"
                    + "/system touch answer-ok\n"
                    + "/else\n"
                    + "/system touch answer-bad\n"
                    + "/end\n"
                    + "// {{var}} interpolation + && / || in /if conditions\n"
                    + "/if pom == \"proj/pom.xml\" && pomDir == \"proj\" || data == \"wrong\"\n"
                    + "/system touch interp-ok\n"
                    + "/else\n"
                    + "/system touch interp-bad\n"
                    + "/end\n"
                    + "/system --save out cat data.txt\n"
                    + "/if out == \"hi\"\n"
                    + "/system touch save-ok\n"
                    + "/end\n";
            session = agent.startSession(new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)));
            NaruTask task = captureForegroundTask(session);
            session.waitFor();

            assertNotNull(task, "expected the session to create a task");
            assertTrue(task.status() == NaruTaskStatus.DONE,
                    () -> "expected the task to finish, but status was " + task.status());

            // /tags took effect on the task
            assertTrue(task.findToolTags().stream().anyMatch(t -> "fs".equals(t.name())),
                    "expected the 'fs' tag to be enabled via /tags");
            assertTrue(task.findToolExclusions().contains("cd")
                            && task.findToolExclusions().contains("set_working_dir"),
                    "expected cd and set_working_dir to be excluded via /tags");

            // /file find --save / --dir published usable values
            assertEquals("proj/pom.xml",
                    task.getTaskEnv("pom", true).map(Object::toString).orNull(),
                    "expected /file find to save the first matching path relative to the search root");
            assertEquals("proj",
                    task.getTaskEnv("pomDir", true).map(Object::toString).orNull(),
                    "expected /file find to save the parent dir of the first match");
            assertEquals("data.txt",
                    task.getTaskEnv("data", true).map(Object::toString).orNull(),
                    "expected /file find to save the top-level file path");
            assertEquals(".",
                    task.getTaskEnv("dataDir", true).map(Object::toString).orNull(),
                    "expected /file find to save '.' as parent dir of a top-level file");

            // /system --save captured the trimmed output
            assertEquals("42",
                    task.getTaskEnv("answer", true).map(Object::toString).orNull(),
                    "expected /system --save to store the trimmed command output");
            assertEquals("hi",
                    task.getTaskEnv("out", true).map(Object::toString).orNull(),
                    "expected /system --save to store the trimmed 'cat' output");

            // the /if conditions all evaluated true (markers prove grep-like exit
            // codes, string equality, && / || and {{var}} interpolation worked)
            assertTrue(folder.resolve("grep-hit-ok").isRegularFile(),
                    "grep match -> lastExitCode 0");
            assertTrue(folder.resolve("grep-miss-ok").isRegularFile(),
                    "grep miss -> lastExitCode 1");
            assertTrue(folder.resolve("answer-ok").isRegularFile(),
                    "answer == \"42\"");
            assertTrue(!folder.resolve("answer-bad").exists(),
                    "expected /else NOT to run when the condition is true");
            assertTrue(folder.resolve("interp-ok").isRegularFile(),
                    "pom/pomDir interpolation + && / || evaluation");
            assertTrue(!folder.resolve("interp-bad").exists(),
                    "expected /else NOT to run when the condition is true");
            assertTrue(folder.resolve("save-ok").isRegularFile(),
                    "/system --save out == \"hi\"");
        });
    }

    @Test
    public void testScriptAssertSetOperatorsAndLastResult() {
        // Engine-only (no LLM): the script conveniences added for the showcase —
        // /set defaults to the task env with arithmetic updates (++/--/+=/-=/*=/=)
        // and publishes its (value, exitCode) centrally: lastResult (alias "_")
        // carries the assigned value and, per Rule C, a Boolean value mirrors its
        // truthiness into lastExitCode (true->0, false->1) while any non-Boolean
        // value publishes 0 (/set z = 0 -> lastResult 0 AND lastExitCode 0).
        // /assert evaluates a condition in one line (publishes lastExitCode, --set
        // AND-accumulates into a task var), and every directive publishes BOTH
        // lastResult and lastExitCode — errors surface the value in lastError.
        assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
            String script = ""
                    + "/system echo hi > data.txt\n"
                    + "// /set targets the task env by default; update operators\n"
                    + "/set n = 0\n"
                    + "/set n++\n"
                    + "/set n += 4\n"
                    + "/set n--\n"
                    + "/if n == 4\n"
                    + "/system touch n-ok\n"
                    + "/end\n"
                    + "/set m = 10\n"
                    + "/set m -= 2\n"
                    + "/set m *= 3\n"
                    + "/if m == 24\n"
                    + "/system touch m-ok\n"
                    + "/end\n"
                    + "/set r = 9\n"
                    + "/set r /= 3\n"
                    + "/if r == 3\n"
                    + "/system touch r-ok\n"
                    + "/end\n"
                    + "// /assert: plain form publishes lastExitCode\n"
                    + "/assert n == 4\n"
                    + "/if lastExitCode == 0\n"
                    + "/system touch a-ok\n"
                    + "/end\n"
                    + "/assert n == 99\n"
                    + "/if lastExitCode == 1\n"
                    + "/system touch a-fail-ok\n"
                    + "/end\n"
                    + "// /assert --set: AND-accumulates across a chain\n"
                    + "/assert --set green n == 4\n"
                    + "/assert --set green m == 24\n"
                    + "/if green == 1\n"
                    + "/system touch green-ok\n"
                    + "/end\n"
                    + "/assert --set ff n == 99\n"
                    + "/assert --set ff n == 4\n"
                    + "/if ff == 1\n"
                    + "/system touch ff-bad\n"
                    + "/end\n"
                    + "// lastResult and its _ alias carry the last directive result\n"
                    + "/system --save out cat data.txt\n"
                    + "/if lastResult == \"hi\" && _ == \"hi\"\n"
                    + "/system touch lr-ok\n"
                    + "/end\n"
                    + "/set check = 5\n"
                    + "/if _ == 5 && lastResult == 5\n"
                    + "/system touch us-ok\n"
                    + "/end\n"
                    + "/set x = 7*6\n"
                    + "/if lastResult == 42\n"
                    + "/system touch lr2-ok\n"
                    + "/end\n"
                    + "// Rule C: non-Boolean /set values always publish lastExitCode 0\n"
                    + "/set t = 1\n"
                    + "/if lastExitCode == 0\n"
                    + "/system touch lce1-ok\n"
                    + "/end\n"
                    + "/set f = 0\n"
                    + "/if lastExitCode == 0\n"
                    + "/system touch lce0-ok\n"
                    + "/end\n"
                    + "/set s = \"\"\n"
                    + "/if lastExitCode == 0\n"
                    + "/system touch lce-empty-ok\n"
                    + "/end\n"
                    + "// a boolean /set mirrors the bool in lastExitCode (Rule C)\n"
                    + "/set g = (n == 4)\n"
                    + "/if _ && lastExitCode == 0\n"
                    + "/system touch gset-ok\n"
                    + "/end\n"
                    + "/set g2 = (n == 99)\n"
                    + "/if lastExitCode == 1 && lastError == false\n"
                    + "/system touch gset2-ok\n"
                    + "/end\n";
            session = agent.startSession(new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)));
            NaruTask task = captureForegroundTask(session);
            session.waitFor();

            assertNotNull(task, "expected the session to create a task");
            assertTrue(task.status() == NaruTaskStatus.DONE,
                    () -> "expected the task to finish, but status was " + task.status());

            // /set update operators worked (default task scope)
            assertEquals("4", task.getTaskEnv("n", true).map(x -> x == null ? "<null>" : x.toString()).orNull(),
                    "expected n = 0; n++; n += 4; n-- to yield 4");
            assertEquals("24", task.getTaskEnv("m", true).map(Object::toString).orNull(),
                    "expected m = 10; m -= 2; m *= 3 to yield 24");
            assertEquals("3", task.getTaskEnv("r", true).map(Object::toString).orNull(),
                    "expected r = 9; r /= 3 to yield 3");

            // /assert publishes lastExitCode (0 success / 1 failure)
            assertTrue(folder.resolve("a-ok").isRegularFile(),
                    "assert true -> lastExitCode 0");
            assertTrue(folder.resolve("a-fail-ok").isRegularFile(),
                    "assert false -> lastExitCode 1");

            // /assert --set AND-accumulates: green stays 1 through two successes,
            // ff stays 0 after one failure even though a later assert passed
            assertEquals("1", task.getTaskEnv("green", true).map(Object::toString).orNull(),
                    "expected --set green to AND two passing asserts to 1");
            assertTrue(folder.resolve("green-ok").isRegularFile(), "green == 1");
            assertEquals("0", task.getTaskEnv("ff", true).map(Object::toString).orNull(),
                    "expected --set ff to stay 0 after a single failure");
            assertTrue(!folder.resolve("ff-bad").exists(),
                    "expected ff to stay 0 after a failed then a passing assert");

            // lastResult / _ reflect the last result-producing directive
            assertEquals("hi", task.getTaskEnv("out", true).map(Object::toString).orNull(),
                    "expected /system --save to store the trimmed 'cat' output");
            assertTrue(folder.resolve("lr-ok").isRegularFile(),
                    "lastResult == \"hi\" && _ == \"hi\" after /system --save out cat data.txt");
            assertTrue(folder.resolve("us-ok").isRegularFile(),
                    "_ == 5 && lastResult == 5 after /set check = 5 (underscore alias)");
            assertTrue(folder.resolve("lr2-ok").isRegularFile(),
                    "lastResult == 42 after /set x = 7*6");

            // Rule C: non-Boolean /set values publish lastExitCode 0 always
            assertTrue(folder.resolve("lce1-ok").isRegularFile(),
                    "/set t = 1 -> non-Boolean -> lastExitCode 0");
            assertTrue(folder.resolve("lce0-ok").isRegularFile(),
                    "/set f = 0 -> non-Boolean -> lastExitCode 0 (not 1)");
            assertTrue(folder.resolve("lce-empty-ok").isRegularFile(),
                    "/set s = \"\" -> non-Boolean -> lastExitCode 0 (not 1)");

            // a Boolean /set mirrors the boolean in lastExitCode: value+0 on true;
            // on false the value is carried by lastError (Rule C + type split)
            assertEquals("true", task.getTaskEnv("g", true).map(Object::toString).orNull(),
                    "/set g = (n == 4) stores true");
            assertTrue(folder.resolve("gset-ok").isRegularFile(),
                    "/set g=(n==4): _ truthy && lastExitCode 0");
            assertEquals("false", task.getTaskEnv("g2", true).map(Object::toString).orNull(),
                    "/set g2 = (n == 99) stores false");
            assertTrue(folder.resolve("gset2-ok").isRegularFile(),
                    "/set g2=(n==99): lastExitCode 1 && lastError carries false");
        });
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
                    "/tags enable fs",
                    // models tend to "explore" with cd/pwd first; excluding cd keeps
                    // the agent focused on the actual write (this test's purpose).
                    "/tags disable cd",
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
     * {@code network}) and hides the wandering-prone working-dir tools. Unlike a
     * bare prompt, the script does NOT trust the model to decide when it is done:
     * a bounded {@code /while} loop re-invokes the model until a naru-owned check
     * (offline {@code mvn -o -q test} plus both runtime calculator runs) exits 0
     * or 8 attempts run out. The test verification is independent of the model's
     * narration, so this test doubles as a benchmark: a model that is "good
     * enough" will leave behind a project whose {@code pom.xml} exists, whose
     * {@code mvn -o -q test} passes offline, and whose compiled {@code calc.Main}
     * computes correct results.</p>
     */
    @Test
    public void testAgentBuildsMavenCalculatorProject() {
        // a write->build->fix->run loop is much longer than a chat round-trip,
        // so give the showcase its own generous (but still bounded) timeout.
        // Large local models are slow: bump with -Dnaru.test.codegen.timeoutMinutes=NN.
        long codegenTimeout = Long.getLong("naru.test.codegen.timeoutMinutes", 20);
        assertTimeoutPreemptively(Duration.ofMinutes(codegenTimeout), () -> {
            // The whole script — the /ollama & /model directives, the tool filter,
            // /set maxSteps (default task scope), the bounded /while loop, the
            // multi-line goal prompt wrapped in /buffer on ... /buffer off, and the
            // naru-owned green check — lives in a classpath RESOURCE
            // (src/test/resources/scripts/calc-build-loop.naru). @MODEL@ is
            // substituted with the model under test before the stream is handed to
            // startSession(InputStream).
            String script = loadScriptResource("/scripts/calc-build-loop.naru")
                    .replace("@MODEL@", configuredToolModel());
            session = agent.startSession(
                    new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)));

            // maxSteps bounds a single model turn's tool-call rounds (the budget
            // is reset when a turn ends, so each while-loop re-invocation gets a
            // full one). It is set INSIDE the script now (/set maxSteps = 80 with
            // the default task scope) and it is never infinite: the /while loop
            // itself is capped at 12 attempts and the preemptive timeout below
            // bounds wall-clock time.

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

            // The model may be tempted to 'make the build pass' by deleting or
            // weakening the tests — mvn -o -q test exits 0 even when no test was
            // compiled. Require a real JUnit test that asserts the required
            // expressions (multi-digit operands included).
            NPath testFile = projectRoot.resolve("src/test/java/calc/CalculatorTest.java");
            assertTrue(testFile.exists(),
                    () -> "expected the model to keep a real JUnit test at " + testFile
                            + " (deleting tests to make 'mvn test' pass is not acceptable)");
            String testSrc = testFile.readString();
            assertTrue(testSrc != null && testSrc.contains("@Test")
                            && testSrc.contains("7*6") && testSrc.contains("12/4"),
                    () -> "expected the JUnit test to actually assert the required expressions "
                            + "(including multi-digit 7*6 and 12/4):\n" + testSrc);

            // 2. it builds and passes its tests OFFLINE (now non-trivially: the
            // tests above exist and must pass);
            CommandResult build = runCommand(projectRoot, "mvn -o -q test", 300);
            assertTrue(build.exitCode == 0,
                    () -> "expected 'mvn -o -q test' to succeed in " + projectRoot
                            + " but got EXIT_CODE=" + build.exitCode + ":\n"
                            + tail(build.output, 2000));

            // 3. the compiled calculator actually computes multi-digit expressions.
            CommandResult run = runCommand(projectRoot, "java -cp target/classes calc.Main \"7*6\"", 60);
            assertTrue(run.exitCode == 0 && "42".equals(run.output.trim()),
                    () -> "expected 'java -cp target/classes calc.Main \"7*6\"' to print 42 "
                            + "but got EXIT_CODE=" + run.exitCode + " output='" + run.output + "'");
            CommandResult run2 = runCommand(projectRoot, "java -cp target/classes calc.Main \"12/4\"", 60);
            assertTrue(run2.exitCode == 0 && "3".equals(run2.output.trim()),
                    () -> "expected 'java -cp target/classes calc.Main \"12/4\"' to print 3 "
                            + "but got EXIT_CODE=" + run2.exitCode + " output='" + run2.output + "'");

            NaruMessage last = task.getLastResult();
            if (last != null) {
                String answer = last.getContent();
                NOut.println(NMsg.ofC("model answer: %s", answer == null ? "<empty>" : answer.trim()));
            }
        });
    }

    /**
     * Load a classpath script resource (UTF-8). The whole codegen showcase —
     * the /ollama & /model directives, the tool filter, /set maxSteps,
     * the bounded /while loop, the multi-line goal prompt wrapped in
     * /buffer on ... /buffer off, and the naru-owned green check — lives in
     * src/test/resources/scripts/calc-build-loop.naru.
     */
    private static String loadScriptResource(String name) {
        try (InputStream in = AgentModelIntegrationTest.class.getResourceAsStream(name)) {
            assertNotNull(in, "missing script resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

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