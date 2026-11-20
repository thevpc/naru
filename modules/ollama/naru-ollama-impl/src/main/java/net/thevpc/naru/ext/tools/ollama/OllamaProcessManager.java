package net.thevpc.naru.ext.tools.ollama;

import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.platform.NEnv;
import net.thevpc.nuts.platform.NOsFamily;
import net.thevpc.nuts.util.NBlankable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the Ollama process spawned by NARU and ensures it is cleanly stopped when NARU exits.
 */
public class OllamaProcessManager {

    private static final Object lock = new Object();
    private static Process managedProcess;
    private static long managedPid = -1;
    private static final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);

    static {
        ensureShutdownHook();
    }

    private static void ensureShutdownHook() {
        if (shutdownHookRegistered.compareAndSet(false, true)) {
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    stopIfStartedByNaru();
                }, "naru-ollama-shutdown"));
            } catch (IllegalStateException ignored) {
                // JVM already shutting down
            }
        }
    }

    public static boolean isStartedByNaru() {
        synchronized (lock) {
            return managedProcess != null && managedProcess.isAlive();
        }
    }

    public static long getManagedPid() {
        synchronized (lock) {
            if (managedProcess != null && managedProcess.isAlive()) {
                return managedPid;
            }
            return -1;
        }
    }

    /**
     * Spawns an Ollama server process with "ollama serve".
     *
     * @param executablePath path to ollama binary (or "ollama")
     * @param baseUrl        base URL or host (e.g. "http://localhost:11434")
     * @param workingDir     directory to run in
     * @return true if process started
     * @throws IOException on launch failure
     */
    public static boolean startProcess(String executablePath, String baseUrl, NPath workingDir) throws IOException {
        synchronized (lock) {
            ensureShutdownHook();
            if (managedProcess != null && managedProcess.isAlive()) {
                return true; // already running by naru
            }

            String exe = NBlankable.isBlank(executablePath) ? "ollama" : executablePath;
            List<String> command = new ArrayList<>();
            command.add(exe);
            command.add("serve");

            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null && workingDir.isDirectory()) {
                pb.directory(new File(workingDir.toString()));
            }

            Map<String, String> env = pb.environment();
            if (!NBlankable.isBlank(baseUrl) && !baseUrl.contains("localhost:11434") && !baseUrl.contains("127.0.0.1:11434")) {
                env.put("OLLAMA_HOST", baseUrl.replaceAll("^https?://", ""));
            }

            // Redirect I/O to discard or pipe to avoid blocking buffers
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);

            Process proc = pb.start();
            managedProcess = proc;
            try {
                managedPid = proc.pid();
            } catch (Throwable ignored) {
                managedPid = -1;
            }

            return true;
        }
    }

    /**
     * Stops the Ollama process if it was started by NARU.
     *
     * @return true if a managed process was stopped, false if no managed process was running
     */
    public static boolean stopIfStartedByNaru() {
        synchronized (lock) {
            if (managedProcess == null) {
                return false;
            }
            try {
                if (managedProcess.isAlive()) {
                    managedProcess.destroy();
                    try {
                        if (!managedProcess.waitFor(3, TimeUnit.SECONDS)) {
                            managedProcess.destroyForcibly();
                            managedProcess.waitFor(2, TimeUnit.SECONDS);
                        }
                    } catch (InterruptedException e) {
                        managedProcess.destroyForcibly();
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                managedProcess = null;
                managedPid = -1;
            }
            return true;
        }
    }

    /**
     * Attempts to stop an externally running Ollama service on the local machine.
     *
     * @return true if stop command was attempted
     */
    public static boolean stopExternalProcess() {
        stopIfStartedByNaru();

        NOsFamily osFamily = NEnv.of().osFamily();
        try {
            if (osFamily.isWindow()) {
                NExec.ofSystem("taskkill", "/IM", "ollama.exe", "/F").failFast(false).run();
                NExec.ofSystem("taskkill", "/IM", "ollama app.exe", "/F").failFast(false).run();
                return true;
            } else if (osFamily.isMacOs()) {
                NExec.ofSystem("pkill", "-f", "ollama").failFast(false).run();
                NExec.ofSystem("osascript", "-e", "quit app \"Ollama\"").failFast(false).run();
                return true;
            } else {
                // Linux / Unix
                NExec.ofSystem("systemctl", "stop", "ollama").failFast(false).run();
                NExec.ofSystem("pkill", "-f", "ollama serve").failFast(false).run();
                NExec.ofSystem("pkill", "-x", "ollama").failFast(false).run();
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }
}
