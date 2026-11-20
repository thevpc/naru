package net.thevpc.naru.ext.assess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs an external command with a hard timeout and captures its combined output. */
public final class AssessProcess {
    private AssessProcess() {
    }

    public record Result(int exitCode, boolean timedOut, String output) {
        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }

        /**
         * The lines that explain a failed Maven build: errors minus the boilerplate it appends
         * ("re-run with -e", help links, dump files). Falls back to the tail of the output.
         */
        public String cause(int max) {
            List<String> keep = new java.util.ArrayList<>();
            for (String l : output.split("\\R")) {
                String t = l.strip();
                if (t.startsWith("[ERROR]") && !t.equals("[ERROR]") && !t.contains("[Help 1]") && !t.contains("dump files")
                        && !t.contains("-e switch") && !t.contains("-X switch") && !t.contains("full stack trace")
                        && !t.contains("For more information") && !t.contains("Please refer to")
                        && !t.contains("Failed to execute goal")) {
                    keep.add(t.substring(7).strip());
                }
            }
            if (keep.isEmpty()) {
                return tail(max).replaceAll("\\s+", " ");
            }
            return String.join(" | ", keep.subList(0, Math.min(max, keep.size())));
        }

        /** The last {@code max} lines of the output, which is where build tools put the cause. */
        public String tail(int max) {
            String[] lines = output.split("\\R");
            int from = Math.max(0, lines.length - max);
            return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
        }
    }

    public static Result run(Path dir, long timeoutSeconds, List<String> command) {
        try {
            Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
            // drain on a thread: a chatty build must not block on a full pipe
            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (var in = p.getInputStream()) {
                    out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // process killed
                }
            }, "assess-proc-reader");
            reader.setDaemon(true);
            reader.start();
            boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
            reader.join(5000);
            return new Result(done ? p.exitValue() : -1, !done, out.toString());
        } catch (IOException e) {
            return new Result(-1, false, "cannot run " + command + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, false, "interrupted");
        }
    }
}
