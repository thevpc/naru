package net.thevpc.naru.ext.tools.llamacpp;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelPsResult;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.elem.NArrayElement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.mon.NChronometer;
import net.thevpc.nuts.net.NHttpClient;
import net.thevpc.nuts.net.NHttpRequest;
import net.thevpc.nuts.net.NHttpResponse;
import net.thevpc.nuts.platform.NArchFamily;
import net.thevpc.nuts.platform.NEnv;
import net.thevpc.nuts.platform.NOsFamily;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NLiteral;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestrates the llama.cpp lifecycle: installation, model provisioning and
 * enumeration, and starting/stopping the local {@code llama-server}.
 *
 * <p>Unlike Ollama, {@code llama-server} serves a single model chosen at launch,
 * so model provisioning (downloading GGUF files) and server launch (pointing at
 * one of them) are two distinct operations.
 */
public class LlamaCppService {

    private static final LlamaCppService INSTANCE = new LlamaCppService();
    private static final Pattern SHARD = Pattern.compile("^(.*)-(\\d{5})-of-(\\d{5})\\.gguf$", Pattern.CASE_INSENSITIVE);

    public static LlamaCppService of() {
        return INSTANCE;
    }

    private NElementReader elementReader() {
        return NElementReader.ofJson();
    }

    public String getUrl(NaruSession session) {
        return LlamaCppPaths.url(session);
    }

    // ── Installation ───────────────────────────────────────────────────────────

    public boolean isInstalled(NaruSession session) {
        return getInstallationInfo(session).isInstalled();
    }

    public LlamaCppInstallationInfo getInstallationInfo(NaruSession session) {
        NEnv env = NEnv.of();
        NOsFamily os = env.osFamily();
        NArchFamily arch = env.archFamily();
        String dist = env.osDist() != null ? env.osDist().toString() : "unknown";
        String exePath = findExecutablePath(session);
        boolean installed = exePath != null;
        String version = installed ? resolveVersion(exePath) : null;
        NPath home = LlamaCppPaths.home(session);
        return new LlamaCppInstallationInfo(
                installed,
                exePath,
                version,
                os.id(),
                arch.id(),
                dist,
                home.toString(),
                LlamaCppPaths.binDir(session).toString(),
                LlamaCppPaths.modelsDir(session).toString(),
                LlamaCppPaths.backend(session)
        );
    }

    public String findExecutablePath(NaruSession session) {
        NPath managed = LlamaCppPaths.serverExecutable(session);
        if (managed.exists() && managed.isRegularFile()) {
            return managed.toString();
        }
        // system PATH, so a globally installed llama.cpp is honoured too
        String exeName = LlamaCppPaths.serverExecutableName();
        try {
            NExec which = NExec.ofSystem(NEnv.of().osFamily().isWindow() ? "where" : "which", exeName)
                    .failFast(false);
            String out = which.grabbedOut();
            if (which.exitCode() == 0 && !NBlankable.isBlank(out)) {
                String firstLine = out.split("\n")[0].trim();
                if (NPath.of(firstLine).exists()) {
                    return firstLine;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            NExec test = NExec.ofSystem(exeName, "--version").failFast(false);
            if (test.run().exitCode() == 0) {
                return exeName;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String resolveVersion(String executablePath) {
        try {
            NExec e = NExec.ofSystem(executablePath, "--version").failFast(false);
            String out = e.grabbedAll();
            for (String line : out.split("\n")) {
                String t = line.trim();
                if (t.toLowerCase(Locale.ROOT).startsWith("version:")) {
                    return t.substring("version:".length()).trim();
                }
            }
            if (!NBlankable.isBlank(out)) {
                return out.trim().split("\n")[0].trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public boolean install(NaruSession session, Consumer<NMsg> logger) {
        return LlamaCppInstaller.install(session, logger);
    }

    public boolean uninstall(NaruSession session, boolean purgeModels, Consumer<NMsg> logger) {
        return LlamaCppInstaller.uninstall(session, purgeModels, logger);
    }

    // ── Running state ──────────────────────────────────────────────────────────

    public boolean isRunning(NaruSession session) {
        return isRunningAt(getUrl(session));
    }

    private boolean isRunningAt(String baseUrl) {
        try {
            NHttpClient http = NHttpClient.of()
                    .connectTimeout(NDuration.ofSeconds(2))
                    .baseUri(baseUrl);
            NHttpResponse resp = http.GET("health").timeout(NDuration.ofSeconds(2)).run();
            if (resp.statusCode() != null && resp.statusCode().isOk()) {
                return true;
            }
        } catch (Exception ignored) {
        }
        // fall back to the OpenAI-compatible listing, in case /health is unavailable
        try {
            NHttpClient http = NHttpClient.of()
                    .connectTimeout(NDuration.ofSeconds(2))
                    .baseUri(baseUrl);
            NHttpResponse resp = http.GET("v1/models").timeout(NDuration.ofSeconds(2)).run();
            return resp.statusCode() != null && resp.statusCode().isOk();
        } catch (Exception ignored) {
            return false;
        }
    }

    public LlamaCppStatus getStatus(NaruSession session) {
        String baseUrl = getUrl(session);
        LlamaCppInstallationInfo installInfo = getInstallationInfo(session);

        boolean running = false;
        long responseTimeMs = -1;
        List<String> availableModels = new ArrayList<>();
        List<NaruModelPsResult> loadedModels = new ArrayList<>();

        NChronometer chrono = NChronometer.of();
        try {
            NHttpClient http = NHttpClient.of()
                    .connectTimeout(NDuration.ofSeconds(2))
                    .baseUri(baseUrl);
            NHttpResponse resp = http.GET("health").timeout(NDuration.ofSeconds(2)).run();
            if (resp.statusCode() != null && resp.statusCode().isOk()) {
                running = true;
                responseTimeMs = chrono.stop().durationMs();
            }
        } catch (Exception ignored) {
        }

        availableModels = modelIds(session);
        if (running) {
            loadedModels = listPs(session);
        }

        boolean startedByNaru = LlamaCppProcessManager.isStartedByNaru();
        long pid = LlamaCppProcessManager.getManagedPid();

        return new LlamaCppStatus(
                running,
                baseUrl,
                installInfo.getVersion(),
                startedByNaru,
                pid,
                responseTimeMs,
                availableModels,
                loadedModels,
                installInfo
        );
    }

    // ── Models ─────────────────────────────────────────────────────────────────

    public List<LlamaCppModelInfo> listModels(NaruSession session) {
        List<LlamaCppModelInfo> out = new ArrayList<>();
        NPath dir = LlamaCppPaths.modelsDir(session);
        if (!dir.exists() || !dir.isDirectory()) {
            return out;
        }
        for (NPath p : dir.list()) {
            if (p == null || !p.isRegularFile()) {
                continue;
            }
            String name = p.name();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
                continue;
            }
            Matcher m = SHARD.matcher(name);
            boolean sharded = m.matches();
            if (sharded && !"00001".equals(m.group(2))) {
                // only the first shard represents the model in a listing
                continue;
            }
            String id = name.substring(0, name.length() - ".gguf".length());
            long size = 0;
            try {
                size = p.contentLength();
            } catch (Exception ignored) {
            }
            out.add(new LlamaCppModelInfo(id, name, p, size, sharded));
        }
        out.sort((a, b) -> a.getId().compareToIgnoreCase(b.getId()));
        return out;
    }

    public List<String> modelIds(NaruSession session) {
        List<String> ids = new ArrayList<>();
        for (LlamaCppModelInfo m : listModels(session)) {
            ids.add(m.getId());
        }
        return ids;
    }

    public NPath resolveLocalModelPath(NaruSession session, String idOrName) {
        if (NBlankable.isBlank(idOrName)) {
            return null;
        }
        String s = idOrName.trim();
        // A URL is never a local model. NPath supports HTTP paths, so an
        // unguarded NPath.exists() would resolve a remote .gguf to a
        // "readable" path and make pullModel() believe it was already
        // provisioned -- downloading nothing.
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("hf.co/")) {
            return null;
        }
        NPath direct = NPath.of(s);
        if (direct.exists() && direct.isRegularFile()) {
            return direct;
        }
        NPath dir = LlamaCppPaths.modelsDir(session);
        NPath p = dir.resolve(s);
        if (p.exists() && p.isRegularFile()) {
            return p;
        }
        NPath withExt = dir.resolve(s + ".gguf");
        if (withExt.exists() && withExt.isRegularFile()) {
            return withExt;
        }
        String wanted = s.toLowerCase(Locale.ROOT);
        if (wanted.endsWith(".gguf")) {
            wanted = wanted.substring(0, wanted.length() - ".gguf".length());
        }
        for (LlamaCppModelInfo m : listModels(session)) {
            if (m.getId().toLowerCase(Locale.ROOT).equals(wanted)) {
                return m.getPath();
            }
        }
        return null;
    }

    public List<NaruModelPsResult> listPs(NaruSession session) {
        List<NaruModelPsResult> results = new ArrayList<>();
        String baseUrl = getUrl(session);
        try {
            NHttpClient http = NHttpClient.of()
                    .connectTimeout(NDuration.ofSeconds(5))
                    .baseUri(baseUrl);
            NHttpResponse resp = http.GET("v1/models").timeout(NDuration.ofSeconds(5)).run();
            if (resp.statusCode() != null && resp.statusCode().isOk()) {
                NElement root = elementReader().read(resp.contentAsString());
                if (root.isAnyObject()) {
                    NArrayElement data = root.asObject().get().getArray("data").orNull();
                    if (data != null) {
                        for (NElement el : data) {
                            el.asObject().ifPresent(o -> o.getStringValue("id").ifPresent(
                                    id -> results.add(new NaruModelPsResult(new NaruModelKey("llamacpp", id), 0L, null, 0L))));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return results;
    }

    /**
     * Provision a model: download every file of the plan into the models
     * directory. Existing files of the same size are skipped.
     */
    public LlamaCppModelInfo pullModel(String spec, NaruSession session, Consumer<NMsg> logger) {
        if (spec != null) {
            NPath local = resolveLocalModelPath(session, spec);
            if (local != null) {
                if (logger != null) {
                    logger.accept(NMsg.ofC("Model %s is already available at %s",
                            NMsg.ofStyledPrimary1(spec), NMsg.ofStyledPale(local.toString())));
                }
                return new LlamaCppModelInfo(stripGguf(local.name()), local.name(), local, sizeOf(local), false);
            }
        }
        LlamaCppDownloadPlan plan = LlamaCppModelResolver.resolve(spec);
        NPath dir = LlamaCppPaths.modelsDir(session);
        dir.mkdirs();
        if (logger != null) {
            logger.accept(NMsg.ofC("Provisioning %s (%s file(s))",
                    NMsg.ofStyledPrimary1(spec),
                    NMsg.ofStyledNumber(String.valueOf(plan.getFiles().size()))));
        }
        for (LlamaCppDownloadPlan.FileEntry entry : plan.getFiles()) {
            NPath target = dir.resolve(entry.getFileName());
            LlamaCppDownloader.download(entry.getUrl(), target, logger);
        }
        NPath main = dir.resolve(plan.getMainFileName());
        if (logger != null) {
            logger.accept(NMsg.ofC("Model %s ready at %s",
                    NMsg.ofStyledPrimary1(stripGguf(plan.getMainFileName())),
                    NMsg.ofStyledPale(main.toString())));
        }
        return new LlamaCppModelInfo(stripGguf(plan.getMainFileName()), plan.getMainFileName(), main, sizeOf(main), false);
    }

    public List<NPath> modelFiles(NaruSession session, String idOrName) {
        List<NPath> out = new ArrayList<>();
        if (NBlankable.isBlank(idOrName)) {
            return out;
        }
        String base = idOrName.trim();
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = stripGguf(base).toLowerCase(Locale.ROOT);
        NPath dir = LlamaCppPaths.modelsDir(session);
        if (!dir.exists() || !dir.isDirectory()) {
            return out;
        }
        for (NPath p : dir.list()) {
            if (p == null || !p.isRegularFile()) {
                continue;
            }
            String name = p.name();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
                continue;
            }
            Matcher m = SHARD.matcher(name);
            String seg = m.matches() ? m.group(1) : stripGguf(name);
            if (seg.toLowerCase(Locale.ROOT).equals(base)) {
                out.add(p);
            }
        }
        return out;
    }

    public boolean rmModel(String idOrName, NaruSession session, Consumer<NMsg> logger) {
        List<NPath> files = modelFiles(session, idOrName);
        if (files.isEmpty()) {
            throw new NIllegalArgumentException(NMsg.ofC("model not found: %s", idOrName));
        }
        for (NPath f : files) {
            f.delete();
            if (logger != null) {
                logger.accept(NMsg.ofC("Deleted %s", NMsg.ofStyledPale(f.toString())));
            }
        }
        return true;
    }

    // ── Start / stop ───────────────────────────────────────────────────────────

    public static class StartResult {
        private final boolean alreadyStarted;
        private final boolean alreadyInstalled;
        private final boolean started;
        private final boolean installed;
        private final String model;

        public StartResult(boolean alreadyStarted, boolean started, boolean installed, boolean alreadyInstalled, String model) {
            this.alreadyStarted = alreadyStarted;
            this.started = started;
            this.installed = installed;
            this.alreadyInstalled = alreadyInstalled;
            this.model = model;
        }

        public boolean alreadyStarted() {
            return alreadyStarted;
        }

        public boolean alreadyInstalled() {
            return alreadyInstalled;
        }

        public boolean started() {
            return started;
        }

        public boolean installed() {
            return installed;
        }

        public String model() {
            return model;
        }
    }

    public StartResult start(NaruSession session, String modelArg, Consumer<NMsg> logger) {
        if (isRunning(session)) {
            if (logger != null) {
                logger.accept(NMsg.ofC("llama.cpp server is already running at %s", NMsg.ofStyledPrimary1(getUrl(session))));
            }
            return new StartResult(true, true, false, false, modelArg);
        }

        LlamaCppInstallationInfo installInfo = getInstallationInfo(session);
        boolean alreadyInstalled = installInfo.isInstalled();
        if (!alreadyInstalled) {
            if (logger != null) {
                logger.accept(NMsg.ofC("llama.cpp is not installed. Attempting installation first...").asWarning());
            }
            if (!install(session, logger)) {
                if (logger != null) {
                    logger.accept(NMsg.ofC("Installation failed. Cannot start llama.cpp server.").asError());
                }
                return new StartResult(false, false, false, false, null);
            }
            installInfo = getInstallationInfo(session);
        }

        // Resolve the model to serve.
        String modelName = modelArg;
        if (NBlankable.isBlank(modelName)) {
            modelName = LlamaCppPaths.defaultModel(session);
        }
        NPath modelPath = resolveLocalModelPath(session, modelName);
        if (modelPath == null && !NBlankable.isBlank(modelName)) {
            // not local: it may name a repository or URL to provision first
            if (looksRemote(modelName)) {
                if (logger != null) {
                    logger.accept(NMsg.ofC("Model %s is not local; provisioning it now...", NMsg.ofStyledPrimary1(modelName)));
                }
                LlamaCppModelInfo info = pullModel(modelName, session, logger);
                modelPath = info.getPath();
                modelName = info.getId();
            }
        }
        if (modelPath == null) {
            List<LlamaCppModelInfo> local = listModels(session);
            if (!local.isEmpty()) {
                modelPath = local.get(0).getPath();
                modelName = local.get(0).getId();
                if (logger != null) {
                    logger.accept(NMsg.ofC("No model specified; using %s", NMsg.ofStyledPrimary1(modelName)));
                }
            }
        }
        if (modelPath == null) {
            if (logger != null) {
                logger.accept(NMsg.ofC("No model available. Pull one with '/llamacpp pull <repo>' or set llamacpp.model.").asError());
            }
            return new StartResult(false, false, false, alreadyInstalled, null);
        }

        NPath server = NBlankable.isBlank(installInfo.getExecutablePath())
                ? LlamaCppPaths.serverExecutable(session)
                : NPath.of(installInfo.getExecutablePath());
        NPath binDir = LlamaCppPaths.binDir(session);
        List<String> args = buildServerArgs(session, modelPath);

        if (logger != null) {
            logger.accept(NMsg.ofC("Starting llama.cpp server (%s) with model %s",
                    NMsg.ofStyledPrimary1(server.toString()),
                    NMsg.ofStyledPrimary2(modelName)));
        }
        try {
            boolean started = LlamaCppProcessManager.startProcess(server, binDir, args, session != null ? session.workingDir() : null);
            if (!started) {
                if (logger != null) {
                    logger.accept(NMsg.ofC("Failed to spawn llama-server process.").asError());
                }
                return new StartResult(false, false, true, alreadyInstalled, modelName);
            }
            if (logger != null) {
                logger.accept(NMsg.ofC("Waiting for llama.cpp server to become ready (loading model)..."));
            }
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < 120000) {
                if (isRunning(session)) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    long pid = LlamaCppProcessManager.getManagedPid();
                    if (logger != null) {
                        logger.accept(NMsg.ofC("llama.cpp server started at %s (PID %s, ready in %s ms)",
                                NMsg.ofStyledPrimary1(getUrl(session)),
                                NMsg.ofStyledPrimary2(pid > 0 ? String.valueOf(pid) : "unknown"),
                                NMsg.ofStyledNumber(String.valueOf(elapsed))));
                    }
                    return new StartResult(false, true, true, alreadyInstalled, modelName);
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (logger != null) {
                logger.accept(NMsg.ofC("Timeout waiting for llama.cpp server to respond.").asError());
            }
            return new StartResult(false, false, true, alreadyInstalled, modelName);
        } catch (Exception ex) {
            if (logger != null) {
                logger.accept(NMsg.ofC("Error starting llama.cpp: %s", ex.getMessage()).asError());
            }
            return new StartResult(false, false, true, alreadyInstalled, modelName);
        }
    }

    private List<String> buildServerArgs(NaruSession session, NPath modelPath) {
        List<String> args = new ArrayList<>();
        args.add("-m");
        args.add(modelPath.toString());
        args.add("--host");
        args.add(LlamaCppPaths.host(session));
        args.add("--port");
        args.add(String.valueOf(LlamaCppPaths.port(session)));

        String backend = LlamaCppPaths.backend(session);
        String ngl = LlamaCppPaths.config(session, "llamacpp.ngl");
        if (!NBlankable.isBlank(ngl)) {
            args.add("-ngl");
            args.add(ngl.trim());
        } else if (!"cpu".equals(backend)) {
            args.add("-ngl");
            args.add("999");
        }
        String ctx = LlamaCppPaths.config(session, "llamacpp.ctxSize");
        if (NBlankable.isBlank(ctx)) {
            ctx = LlamaCppPaths.config(session, "llamacpp.ctx");
        }
        if (!NBlankable.isBlank(ctx)) {
            args.add("--ctx-size");
            args.add(ctx.trim());
        }
        String extra = LlamaCppPaths.config(session, "llamacpp.args");
        if (!NBlankable.isBlank(extra)) {
            for (String tok : extra.trim().split("\\s+")) {
                if (!tok.isEmpty()) {
                    args.add(tok);
                }
            }
        }
        return args;
    }

    public boolean stop(NaruSession session, boolean forceAll, Consumer<NMsg> logger) {
        boolean startedByNaru = LlamaCppProcessManager.isStartedByNaru();
        if (!startedByNaru && !forceAll && !isRunning(session)) {
            if (logger != null) {
                logger.accept(NMsg.ofC("llama.cpp server is not running."));
            }
            return true;
        }
        if (startedByNaru) {
            if (logger != null) {
                logger.accept(NMsg.ofC("Stopping NARU-managed llama.cpp server (PID %s)...",
                        NMsg.ofStyledPrimary2(String.valueOf(LlamaCppProcessManager.getManagedPid()))));
            }
            LlamaCppProcessManager.stopIfStartedByNaru();
        } else if (forceAll) {
            if (logger != null) {
                logger.accept(NMsg.ofC("Stopping external llama.cpp server..."));
            }
            LlamaCppProcessManager.stopExternalProcess();
        } else {
            if (logger != null) {
                logger.accept(NMsg.ofC("llama.cpp server was not started by NARU. Use '--all' or '--force' to stop external instances.").asWarning());
            }
            return false;
        }
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < 5000) {
            if (!isRunning(session)) {
                if (logger != null) {
                    logger.accept(NMsg.ofC("llama.cpp server stopped successfully."));
                }
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return !isRunning(session);
    }

    public StartResult restart(NaruSession session, String modelArg, Consumer<NMsg> logger) {
        stop(session, true, logger);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
        }
        return start(session, modelArg, logger);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static boolean looksRemote(String spec) {
        String s = spec.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("http://") || s.startsWith("https://") || s.startsWith("hf.co/")
                || (s.contains("/") && !s.contains("\\") && !s.contains(":"));
    }

    private static String stripGguf(String name) {
        if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            return name.substring(0, name.length() - ".gguf".length());
        }
        return name;
    }

    private static long sizeOf(NPath p) {
        try {
            return p.contentLength();
        } catch (Exception e) {
            return 0;
        }
    }
}
