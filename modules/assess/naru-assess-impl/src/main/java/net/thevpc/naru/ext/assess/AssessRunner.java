package net.thevpc.naru.ext.assess;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionUsageListener;
import net.thevpc.naru.api.agent.NaruToolCallEvent;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruProviderRateLimitInfo;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.time.NDuration;

/**
 * Runs a model against assessment tasks. Each task gets a fresh copy of the asset in a temp
 * directory and an isolated naru session; the outcome is scored by the task's checks and
 * the cost is read from the session's own usage and tool-call events, not estimated.
 */
public final class AssessRunner {
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** Tools that wander off the task or duplicate the shell; keeping the toolset lean helps small models. */
    private static final String EXCLUDED_TOOLS = "cd set_working_dir web_search tag_add tag_remove "
            + "git_status git_diff git_log git_commit project_map code_symbols find_symbol project_summary "
            + "semantic_search semantic_index maven_compile maven_test maven_package";

    private AssessRunner() {
    }

    public static String newRunId(String model) {
        String slug = model.replaceAll("[^A-Za-z0-9.]+", "-").replaceAll("^-+|-+$", "");
        return ID_TIME.format(Instant.now()) + "-" + (slug.length() > 24 ? slug.substring(0, 24) : slug);
    }

    /** Runs the selected tasks one after the other and persists the run after each one. */
    public static AssessRun run(AssessAsset asset, List<AssessTask> tasks, String model, AssessOptions options,
                                AssessStore store, Consumer<String> progress) {
        AssessRun run = new AssessRun();
        run.id = newRunId(model);
        run.model = model;
        run.assetId = asset.id();
        run.startedAt = Instant.now();
        run.options = describe(options);
        for (AssessTask task : tasks) {
            progress.accept("▶ " + task.id() + " [" + task.tier().label() + ", cap "
                    + AssessReports.duration(options.timeoutMillis(task.tier())) + "] " + task.title());
            AssessTaskResult r = runTask(asset, task, model, options, progress);
            run.results.add(r);
            run.endedAt = Instant.now();
            store.save(run);
            AssessReports.taskLine(r).forEach(progress);
        }
        run.endedAt = Instant.now();
        store.save(run);
        return run;
    }

    static String describe(AssessOptions o) {
        return "timeout x" + o.timeoutScale + (o.thinking == null ? "" : ", thinking " + (o.thinking ? "on" : "off"))
                + ", maxSteps " + o.maxSteps;
    }

    public static AssessTaskResult runTask(AssessAsset asset, AssessTask task, String model, AssessOptions options,
                                           Consumer<String> progress) {
        AssessTaskResult result = new AssessTaskResult();
        result.taskId = task.id();
        result.tier = task.tier().label();
        result.title = task.title();
        Path dir = null;
        long t0 = System.currentTimeMillis();
        try {
            dir = Files.createTempDirectory("naru-assess-" + task.id() + "-");
            result.workDir = dir.toString();
            asset.prepare(task, dir);
            Map<String, String> baseline = AssessContext.fingerprint(dir);

            Probe probe = new Probe();
            execute(task, dir, model, options, probe, result);
            result.wallMillis = System.currentTimeMillis() - t0;
            probe.copyTo(result);

            progress.accept("  scoring " + task.id() + " ...");
            AssessContext ctx = new AssessContext(asset, task, dir, baseline);
            double hiddenShare = 1.0;
            for (AssessCheck check : task.checks()) {
                if (check.id().equals("hidden")) {
                    // evaluated first so gated checks can be scaled by it
                    hiddenShare = check.evaluate(ctx).fraction();
                }
            }
            for (AssessCheck check : task.checks()) {
                AssessCheck.Verdict v = check.evaluate(ctx);
                double earned = v.fraction();
                String detail = v.detail();
                if (check.gated() && hiddenShare < 1.0 && earned > 0) {
                    earned *= hiddenShare;
                    detail = "scaled by hidden-test progress " + Math.round(hiddenShare * 100) + "%" + ("ok".equals(detail) ? "" : "; " + detail);
                }
                result.checks.add(new AssessTaskResult.CheckResult(check.id(), check.description(), check.points(), earned, detail));
            }
        } catch (Exception e) {
            result.status = AssessTaskResult.Status.ERROR;
            result.error = e.toString();
            result.wallMillis = System.currentTimeMillis() - t0;
            if (result.checks.isEmpty()) {
                task.checks().forEach(c -> result.checks.add(
                        new AssessTaskResult.CheckResult(c.id(), c.description(), c.points(), 0, "not scored: " + e)));
            }
        } finally {
            if (!options.keepWorkDir) {
                deleteQuietly(dir);
                result.workDir = null;
            }
        }
        return result;
    }

    /** Builds the script, runs the naru session under a wall-clock cap and fills status/error. */
    private static void execute(AssessTask task, Path dir, String model, AssessOptions options, Probe probe,
                                AssessTaskResult result) throws InterruptedException {
        StringBuilder script = new StringBuilder();
        if (model.startsWith("ollama/")) {
            script.append("/ollama serve\n");
        }
        script.append("/model use ").append(model).append('\n');
        if (options.thinking != null) {
            script.append("/settings model.thinking=").append(options.thinking).append('\n');
        }
        script.append("/mode implement\n/tags enable fs\n/tags enable network\n");
        script.append("/tools exclude ").append(EXCLUDED_TOOLS).append('\n');
        script.append("/set maxSteps = ").append(options.maxSteps).append('\n');
        script.append("/buffer on\n").append(task.prompt()).append("\n/buffer off\n");

        NaruWorkspace workspace = NaruWorkspace.of();
        workspace.projectDirectory(NPath.of(dir.toString()));
        NaruSession session = workspace.newSession()
                .interaction(probe)
                .script(new ByteArrayInputStream(script.toString().getBytes(StandardCharsets.UTF_8)))
                .build();
        session.addUsageListener(probe);
        session.start();

        Thread waiter = new Thread(session::waitFor, "assess-wait-" + task.id());
        waiter.setDaemon(true);
        waiter.start();
        waiter.join(options.timeoutMillis(task.tier()));
        try {
            if (waiter.isAlive()) {
                result.status = AssessTaskResult.Status.TIMEOUT;
                session.terminate();
                waiter.join(30_000);
            } else {
                result.status = AssessTaskResult.Status.COMPLETED;
                NaruTask fg = session.findTask(session.foregroundTaskId()).orNull();
                if (fg != null) {
                    String st = String.valueOf(fg.status());
                    if (!st.equals("DONE")) {
                        result.error = "foreground task ended as " + st;
                    }
                }
            }
        } finally {
            try {
                session.stop();
            } catch (RuntimeException ignored) {
                // already stopped
            }
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Collects usage, tool calls and the final answer while a session runs. */
    static final class Probe implements NaruInteraction, NaruSessionUsageListener {
        final AtomicLong modelCalls = new AtomicLong();
        final AtomicLong promptTokens = new AtomicLong();
        final AtomicLong completionTokens = new AtomicLong();
        final AtomicLong peakPrompt = new AtomicLong();
        final AtomicLong modelMillis = new AtomicLong();
        final AtomicLong toolCalls = new AtomicLong();
        final AtomicLong toolErrors = new AtomicLong();
        final AtomicLong questions = new AtomicLong();
        final Map<String, Integer> tools = new ConcurrentHashMap<>();
        final StringBuilder answer = new StringBuilder();
        private final List<String> lastAnswers = new ArrayList<>();
        private final StringBuilder transcript = new StringBuilder();
        private static final int TRANSCRIPT_MAX = 60_000;

        /** The text of a styled message without its styling markup. */
        private static String plain(NMsg m) {
            try {
                return NText.of(m).filteredText();
            } catch (RuntimeException e) {
                return String.valueOf(m);
            }
        }

        /** Engine housekeeping that shares the answer channel but is not something the model said. */
        private static boolean isHousekeeping(String text) {
            return text.startsWith("Loading agent from");
        }

        private void log(String line) {
            synchronized (transcript) {
                if (transcript.length() < TRANSCRIPT_MAX) {
                    transcript.append(line).append('\n');
                }
            }
        }

        @Override
        public void open(NaruSession session) {
        }

        @Override
        public void requestInput(NaruInputRequest request) {
            // an assessment never has a human: record the question and refuse so the run cannot hang
            questions.incrementAndGet();
            request.cancel("non-interactive assessment: no one can answer");
        }

        @Override
        public void write(NaruLogMode mode, NMsg message) {
            if (message != null) {
                log("[" + mode + "] " + AssessReports.truncate(plain(message), 600));
            }
            if (mode == NaruLogMode.AGENT_RESPONSE && message != null) {
                String text = plain(message).strip();
                if (!isHousekeeping(text)) {
                    synchronized (answer) {
                        lastAnswers.add(text);
                    }
                }
            }
        }

        @Override
        public void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
            if (mode == NaruLogMode.AGENT_RESPONSE && fragment != null) {
                synchronized (answer) {
                    answer.append(plain(fragment));
                    if (end) {
                        String text = answer.toString().strip();
                        if (!isHousekeeping(text)) {
                            lastAnswers.add(text);
                        }
                        answer.setLength(0);
                    }
                }
            }
        }

        @Override
        public void reportToolCall(NaruToolCallEvent event) {
            if (event.kind() != NaruToolCallEvent.Kind.STARTED) {
                log("[TOOL " + event.kind() + "] " + event.toolName() + " " + AssessReports.truncate(String.valueOf(event.arguments()), 300)
                        + " -> " + AssessReports.truncate(AssessReports.oneLine(String.valueOf(event.result())), 300));
            }
            if (event.kind() == NaruToolCallEvent.Kind.RESULT || event.kind() == NaruToolCallEvent.Kind.FAILED) {
                toolCalls.incrementAndGet();
                tools.merge(String.valueOf(event.toolName()), 1, Integer::sum);
                if (event.kind() == NaruToolCallEvent.Kind.FAILED) {
                    toolErrors.incrementAndGet();
                }
            }
        }

        @Override
        public void close() {
        }

        @Override
        public void onModelCall(NaruTask task, NaruModelKey model, long prompt, long completion, long cacheWrite,
                                long cacheRead, Map<String, String> labels, NDuration duration) {
            modelCalls.incrementAndGet();
            promptTokens.addAndGet(Math.max(0, prompt));
            completionTokens.addAndGet(Math.max(0, completion));
            peakPrompt.accumulateAndGet(prompt, Math::max);
            if (duration != null) {
                modelMillis.addAndGet(duration.toMillis());
            }
        }

        @Override
        public void onProviderRateLimits(NaruProviderRateLimitInfo info) {
        }

        void copyTo(AssessTaskResult r) {
            r.modelCalls = modelCalls.get();
            r.promptTokens = promptTokens.get();
            r.completionTokens = completionTokens.get();
            r.peakPromptTokens = peakPrompt.get();
            r.modelMillis = modelMillis.get();
            r.toolCalls = toolCalls.get();
            r.toolErrors = toolErrors.get();
            r.userQuestions = questions.get();
            synchronized (transcript) {
                r.transcript = transcript.toString();
            }
            new java.util.TreeMap<>(tools).forEach(r.toolsByName::put);
            synchronized (answer) {
                r.finalAnswer = lastAnswers.isEmpty() ? answer.toString() : lastAnswers.get(lastAnswers.size() - 1);
            }
        }
    }
}
