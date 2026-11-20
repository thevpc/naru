package net.thevpc.naru.ext.tools.llamacpp;

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
 * Owns the {@code llama-server} process NARU spawns.
 *
 * <p>llama.cpp loads exactly one model at launch, so the model file is part of
 * the command line rather than of the request body. The process is started with
 * its working directory set to the bundle's {@code bin} folder and that folder
 * prepended to the platform library search path, so the shared libraries shipped
 * next to the executable are found even when {@code $ORIGIN} is not honoured.
 * A JVM shutdown hook stops it when NARU exits.
 */
public class LlamaCppProcessManager {

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
                Runtime.getRuntime().addShutdownHook(new Thread(LlamaCppProcessManager::stopIfStartedByNaru,
                        "naru-llamacpp-shutdown"));
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
     * Spawn {@code llama-server} with the given arguments.
     *
     * @param server     the executable to run
     * @param binDir     the bundle directory (working dir and library path)
     * @param args       arguments after the executable
     * @param workingDir optional working directory override
     * @return true when the process was started
     */
    public static boolean startProcess(NPath server, NPath binDir, List<String> args, NPath workingDir) throws IOException {
        synchronized (lock) {
            ensureShutdownHook();
            if (managedProcess != null && managedProcess.isAlive()) {
                return true;
            }
            List<String> command = new ArrayList<>();
            command.add(server.toString());
            command.addAll(args);

            ProcessBuilder pb = new ProcessBuilder(command);
            NPath cwd = workingDir != null && workingDir.isDirectory() ? workingDir : binDir;
            if (cwd != null && cwd.isDirectory()) {
                pb.directory(new File(cwd.toString()));
            }

            Map<String, String> env = pb.environment();
            if (binDir != null) {
                String bin = binDir.toString();
                NOsFamily os = NEnv.of().osFamily();
                if (os.isWindow()) {
                    String path = env.get("PATH");
                    env.put("PATH", NBlankable.isBlank(path) ? bin : bin + File.pathSeparator + path);
                } else if (os.isMacOs()) {
                    String dyld = env.get("DYLD_LIBRARY_PATH");
                    String ld = env.get("LD_LIBRARY_PATH");
                    env.put("DYLD_LIBRARY_PATH", NBlankable.isBlank(dyld) ? bin : bin + File.pathSeparator + dyld);
                    env.put("LD_LIBRARY_PATH", NBlankable.isBlank(ld) ? bin : bin + File.pathSeparator + ld);
                } else {
                    String ld = env.get("LD_LIBRARY_PATH");
                    env.put("LD_LIBRARY_PATH", NBlankable.isBlank(ld) ? bin : bin + File.pathSeparator + ld);
                }
            }

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

    public static boolean stopIfStartedByNaru() {
        synchronized (lock) {
            if (managedProcess == null) {
                return false;
            }
            try {
                if (managedProcess.isAlive()) {
                    managedProcess.destroy();
                    try {
                        if (!managedProcess.waitFor(5, TimeUnit.SECONDS)) {
                            managedProcess.destroyForcibly();
                            managedProcess.waitFor(5, TimeUnit.SECONDS);
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
     * Best-effort stop of any {@code llama-server} running on the machine, not
     * only the NARU-managed one.
     */
    public static boolean stopExternalProcess() {
        stopIfStartedByNaru();
        NOsFamily osFamily = NEnv.of().osFamily();
        try {
            if (osFamily.isWindow()) {
                NExec.ofSystem("taskkill", "/IM", "llama-server.exe", "/F").failFast(false).run();
                return true;
            }
            NExec.ofSystem("pkill", "-f", "llama-server").failFast(false).run();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
