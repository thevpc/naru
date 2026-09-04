package net.thevpc.naru.ext.tools.ollama;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelPsResult;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.elem.NArrayElement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NObjectElement;
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

import java.util.*;
import java.util.function.Consumer;

public class OllamaService {

    private static final OllamaService INSTANCE = new OllamaService();

    public static OllamaService of() {
        return INSTANCE;
    }

    private NElementReader elementReader() {
        return NElementReader.ofJson();
    }

    public String getOllamaUrl(NaruSession session) {
        if (session != null && session.agent() != null && session.agent().env() != null) {
            String url = session.agent().env().get("ollama.url")
                    .flatMap(x -> x.asStringValue())
                    .orElse("http://localhost:11434");
            return url.replaceAll("/$", "");
        }
        return "http://localhost:11434";
    }

    public boolean isInstalled() {
        return getInstallationInfo().isInstalled();
    }

    public OllamaInstallationInfo getInstallationInfo() {
        NEnv env = NEnv.of();
        NOsFamily os = env.osFamily();
        NArchFamily arch = env.archFamily();
        String dist = env.osDist() != null ? env.osDist().toString() : "unknown";

        String exePath = findExecutablePath();
        boolean installed = exePath != null;
        String version = null;

        if (installed) {
            version = resolveVersion(exePath);
        }

        return new OllamaInstallationInfo(
                installed,
                exePath,
                version,
                os.id(),
                arch.id(),
                dist
        );
    }

    public String findExecutablePath() {
        // 1. Try 'which ollama' or 'where ollama'
        try {
            NExec which = NExec.ofSystem(NEnv.of().osFamily().isWindow() ? "where" : "which", "ollama")
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

        // 2. Check known standard paths
        List<NPath> standardPaths = new ArrayList<>();
        standardPaths.add(NPath.of("/usr/local/bin/ollama"));
        standardPaths.add(NPath.of("/usr/bin/ollama"));
        standardPaths.add(NPath.ofUserHome().resolve(".local/bin/ollama"));
        standardPaths.add(NPath.ofUserHome().resolve(".ollama/bin/ollama"));
        standardPaths.add(NPath.of("/opt/homebrew/bin/ollama"));
        standardPaths.add(NPath.of("/Applications/Ollama.app/Contents/Resources/ollama"));

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            standardPaths.add(NPath.of(localAppData).resolve("Programs/Ollama/ollama.exe"));
        }
        String progFiles = System.getenv("ProgramFiles");
        if (progFiles != null) {
            standardPaths.add(NPath.of(progFiles).resolve("Ollama/ollama.exe"));
        }

        for (NPath p : standardPaths) {
            if (p.exists() && (p.isRegularFile() || !p.isDirectory())) {
                return p.toString();
            }
        }

        // 3. Test if running 'ollama --version' works in PATH
        try {
            NExec test = NExec.ofSystem("ollama", "--version").failFast(false);
            if (test.run().exitCode() == 0) {
                return "ollama";
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private String resolveVersion(String executablePath) {
        try {
            String exe = NBlankable.isBlank(executablePath) ? "ollama" : executablePath;
            NExec e = NExec.ofSystem(exe, "--version").failFast(false);
            String out = e.grabbedAll();
            for (String line : out.split("\n")) {
                line = line.trim();
                if (line.toLowerCase().contains("version")) {
                    String[] parts = line.split("\\s+");
                    return parts[parts.length - 1];
                }
            }
            if (!NBlankable.isBlank(out)) {
                return out.trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public boolean isRunning(NaruSession session) {
        String baseUrl = getOllamaUrl(session);
        try {
            NHttpClient http = NHttpClient.of()
                    .connectTimeout(NDuration.ofSeconds(2))
                    .baseUri(baseUrl);
            NHttpRequest req = http.GET("").timeout(NDuration.ofSeconds(2));
            NHttpResponse resp = req.run();
            return (resp.statusCode() != null && resp.statusCode().isOk()) || resp.contentAsString().contains("Ollama is running");
        } catch (Exception e) {
            return false;
        }
    }

    public OllamaStatus getStatus(NaruSession session) {
        String baseUrl = getOllamaUrl(session);
        OllamaInstallationInfo installInfo = getInstallationInfo();

        boolean running = false;
        String serverVersion = null;
        long responseTimeMs = -1;
        List<String> availableModels = new ArrayList<>();
        List<NaruModelPsResult> loadedModels = new ArrayList<>();

        NChronometer chrono = NChronometer.of();
        try {
            NHttpClient http = NHttpClient.of()
                    .connectTimeout(NDuration.ofSeconds(2))
                    .baseUri(baseUrl);
            NHttpRequest req = http.GET("api/version").timeout(NDuration.ofSeconds(2));
            NHttpResponse resp = req.run();
            if (resp.statusCode() != null && resp.statusCode().isOk()) {
                running = true;
                responseTimeMs = chrono.stop().durationMs();
                try {
                    NElement json = elementReader().read(resp.contentAsString());
                    if (json.isAnyObject()) {
                        serverVersion = json.asObject().get().getStringValue("version").orElse(null);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            // Check root endpoint as fallback
            try {
                chrono = NChronometer.of();
                NHttpClient http = NHttpClient.of()
                        .connectTimeout(NDuration.ofSeconds(2))
                        .baseUri(baseUrl);
                NHttpRequest req = http.GET("").timeout(NDuration.ofSeconds(2));
                NHttpResponse resp = req.run();
                if ((resp.statusCode() != null && resp.statusCode().isOk()) || resp.contentAsString().contains("Ollama is running")) {
                    running = true;
                    responseTimeMs = chrono.stop().durationMs();
                }
            } catch (Exception ignored) {
            }
        }

        if (running) {
            availableModels = listModels(session);
            loadedModels = listPs(session);
        }

        boolean startedByNaru = OllamaProcessManager.isStartedByNaru();
        long pid = OllamaProcessManager.getManagedPid();

        return new OllamaStatus(
                running,
                baseUrl,
                serverVersion,
                startedByNaru,
                pid,
                responseTimeMs,
                availableModels,
                loadedModels,
                installInfo
        );
    }

    public List<String> listModels(NaruSession session) {
        String baseUrl = getOllamaUrl(session);
        List<String> models = new ArrayList<>();
        try {
            NHttpClient http = NHttpClient.of()
                    .connectTimeout(NDuration.ofSeconds(5))
                    .baseUri(baseUrl);
            NHttpRequest req = http.GET("api/tags").timeout(NDuration.ofSeconds(5));
            NHttpResponse resp = req.run();
            if (resp.statusCode() != null && resp.statusCode().isOk()) {
                NElement root = elementReader().read(resp.contentAsString());
                if (root.isAnyObject()) {
                    NArrayElement arr = root.asObject().get().getArray("models").orNull();
                    if (arr != null) {
                        for (NElement el : arr) {
                            el.asObject().ifPresent(obj -> {
                                obj.getStringValue("name").ifPresent(models::add);
                            });
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return models;
    }

    public List<NaruModelPsResult> listPs(NaruSession session) {
        String baseUrl = getOllamaUrl(session);
        List<NaruModelPsResult> results = new ArrayList<>();
        try {
            NHttpClient http = NHttpClient.of()
                    .connectTimeout(NDuration.ofSeconds(5))
                    .baseUri(baseUrl);
            NHttpRequest req = http.GET("api/ps").timeout(NDuration.ofSeconds(5));
            NHttpResponse resp = req.run();
            if (resp.statusCode() != null && resp.statusCode().isOk()) {
                NElement json = resp.contentAsJson();
                if (json.isAnyObject()) {
                    for (NElement m : json.asObject().get().getArray("models").orElse(NArrayElement.ofEmpty()).children()) {
                        if (m.isAnyObject()) {
                            NObjectElement model = m.asObject().get();
                            results.add(new NaruModelPsResult(
                                    new NaruModelKey("ollama", model.getStringValue("name").orElse("")),
                                    model.getLongValue("size").orElse(0L),
                                    model.getInstantValue("expires_at").orNull(),
                                    model.getLongValue("size_vram").orElse(0L)
                            ));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return results;
    }

    public boolean start(NaruSession session, Consumer<NMsg> logger) {
        if (isRunning(session)) {
            if (logger != null) {
                logger.accept(NMsg.ofC("Ollama is already running at %s", NMsg.ofStyledPrimary1(getOllamaUrl(session))));
            }
            return true;
        }

        OllamaInstallationInfo installInfo = getInstallationInfo();
        if (!installInfo.isInstalled()) {
            if (logger != null) {
                logger.accept(NMsg.ofC("Ollama is not installed. Attempting installation first...").asWarning());
            }
            boolean installed = install(logger);
            if (!installed) {
                if (logger != null) {
                    logger.accept(NMsg.ofC("Installation failed. Cannot start Ollama.").asError());
                }
                return false;
            }
            installInfo = getInstallationInfo();
        }

        String exe = installInfo.getExecutablePath();
        String url = getOllamaUrl(session);
        NPath workingDir = session != null ? session.workingDir() : null;

        if (logger != null) {
            logger.accept(NMsg.ofC("Starting Ollama server (%s serve)...", NMsg.ofStyledPrimary1(exe != null ? exe : "ollama")));
        }

        try {
            boolean started = OllamaProcessManager.startProcess(exe, url, workingDir);
            if (!started) {
                if (logger != null) {
                    logger.accept(NMsg.ofC("Failed to spawn Ollama process.").asError());
                }
                return false;
            }

            // Poll until server responds (up to 15 seconds)
            if (logger != null) {
                logger.accept(NMsg.ofC("Waiting for Ollama to become ready..."));
            }

            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < 15000) {
                if (isRunning(session)) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    long pid = OllamaProcessManager.getManagedPid();
                    if (logger != null) {
                        logger.accept(NMsg.ofC("Ollama started successfully at %s (PID %s, ready in %s ms)",
                                NMsg.ofStyledPrimary1(url),
                                NMsg.ofStyledPrimary2(pid > 0 ? String.valueOf(pid) : "unknown"),
                                NMsg.ofStyledNumber(String.valueOf(elapsed))
                        ));
                    }
                    return true;
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (logger != null) {
                logger.accept(NMsg.ofC("Timeout waiting for Ollama server to respond.").asError());
            }
            return false;
        } catch (Exception ex) {
            if (logger != null) {
                logger.accept(NMsg.ofC("Error starting Ollama: %s", ex.getMessage()).asError());
            }
            return false;
        }
    }

    public boolean stop(NaruSession session, boolean forceAll, Consumer<NMsg> logger) {
        boolean startedByNaru = OllamaProcessManager.isStartedByNaru();

        if (!startedByNaru && !forceAll && !isRunning(session)) {
            if (logger != null) {
                logger.accept(NMsg.ofC("Ollama is not running."));
            }
            return true;
        }

        if (logger != null) {
            if (startedByNaru) {
                logger.accept(NMsg.ofC("Stopping NARU-managed Ollama server (PID %s)...",
                        NMsg.ofStyledPrimary2(String.valueOf(OllamaProcessManager.getManagedPid()))
                ));
            } else {
                logger.accept(NMsg.ofC("Stopping Ollama server..."));
            }
        }

        if (startedByNaru) {
            OllamaProcessManager.stopIfStartedByNaru();
        } else if (forceAll) {
            OllamaProcessManager.stopExternalProcess();
        } else {
            if (logger != null) {
                logger.accept(NMsg.ofC("Ollama was not started by NARU. Use '--all' or '--force' to stop system instances.").asWarning());
            }
            return false;
        }

        // Verify stopped
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < 5000) {
            if (!isRunning(session)) {
                if (logger != null) {
                    logger.accept(NMsg.ofC("Ollama server stopped successfully."));
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

        if (logger != null) {
            logger.accept(NMsg.ofC("Ollama process termination attempted."));
        }
        return !isRunning(session);
    }

    public boolean restart(NaruSession session, Consumer<NMsg> logger) {
        stop(session, true, logger);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
        }
        return start(session, logger);
    }

    public boolean install(Consumer<NMsg> logger) {
        return OllamaInstaller.install(logger);
    }

    public boolean uninstall(boolean purgeModels, Consumer<NMsg> logger) {
        return OllamaInstaller.uninstall(purgeModels, logger);
    }

    public void pullModel(String model, NaruSession session, Consumer<NMsg> logger) {
        String baseUrl = getOllamaUrl(session);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);

        if (logger != null) {
            logger.accept(NMsg.ofC("Pulling model %s from Ollama registry...", NMsg.ofStyledPrimary1(model)));
        }

        NHttpClient http = NHttpClient.of()
                .connectTimeout(NDuration.ofSeconds(30))
                .baseUri(baseUrl);
        NHttpRequest request = http.POST("api/pull")
                .timeout(NDuration.ofMinutes(30))
                .jsonRequestBody(body);
        request.run().ifErrorThrow();

        if (logger != null) {
            logger.accept(NMsg.ofC("Model %s pulled successfully.", NMsg.ofStyledPrimary1(model)));
        }
    }

    public void deleteModel(String model, NaruSession session, Consumer<NMsg> logger) {
        String baseUrl = getOllamaUrl(session);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);

        if (logger != null) {
            logger.accept(NMsg.ofC("Deleting model %s...", NMsg.ofStyledPrimary1(model)));
        }

        NHttpClient http = NHttpClient.of()
                .connectTimeout(NDuration.ofSeconds(10))
                .baseUri(baseUrl);
        NHttpRequest request = http.POST("api/delete")
                .timeout(NDuration.ofSeconds(30))
                .jsonRequestBody(body);
        request.run().ifErrorThrow();

        if (logger != null) {
            logger.accept(NMsg.ofC("Model %s deleted successfully.", NMsg.ofStyledPrimary1(model)));
        }
    }
}
