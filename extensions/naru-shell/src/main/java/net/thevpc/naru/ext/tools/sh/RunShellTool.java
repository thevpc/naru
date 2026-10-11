package net.thevpc.naru.ext.tools.sh;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class RunShellTool extends DefaultNaruTool {

    public static final int MAX_OUTPUT_BYTES = 51_200;
    public static final int MAX_OUTPUT_LINES = 2_000;

    public RunShellTool() {
        super("run_shell", new String[]{NaruToolTags.NETWORK, NaruToolTags.EXECUTE, NaruToolTags.WRITE});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Execute a shell command and return its output (stdout + stderr).");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("command", "Shell command to execute", true).build(),
                NaruToolParameter.string("working_dir", "Directory to run the command in (defaults to project dir)", false).build(),
                NaruToolParameter.integer("timeout_seconds", "Max seconds to wait (default: 60)", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String command = context.stringArg("command").orNull();
        String workDir = context.stringArg("working_dir").orNull();
        if (NBlankable.isBlank(workDir)) {
            workDir = context.stringArg("workdir").orNull();
        }
        int timeoutSec = context.numberArg("timeout_seconds")
                .map(Number::intValue)
                .orElseGet(() -> context.numberArg("timeout").map(n -> Math.max(1, n.intValue() / 1000)).orElse(60));

        if (NBlankable.isBlank(command)) return "ERROR: 'command' is required.";

        NPath cwdPath = !NBlankable.isBlank(workDir) ? context.task().resolve(workDir) : context.task().projectDir();
        File cwd = cwdPath != null ? new File(cwdPath.toString()) : new File(".");

        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
            pb.directory(cwd);
            pb.redirectErrorStream(true); // merge stdout + stderr
            pb.environment().putAll(System.getenv());

            Process proc = pb.start();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Thread readerThread = new Thread(() -> {
                try (InputStream is = proc.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        baos.write(buf, 0, n);
                    }
                } catch (Exception ignored) {
                }
            });
            readerThread.start();

            boolean finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                readerThread.join(1000);
                long timeoutMs = (long) timeoutSec * 1000;
                return String.format("(no output)\n\n<shell_metadata>\nshell tool terminated command after exceeding timeout %d ms. If this command is expected to take longer and is not waiting for interactive input, retry with a larger timeout value in milliseconds.\n</shell_metadata>", timeoutMs);
            }

            readerThread.join(2000);
            byte[] fullBytes = baos.toByteArray();
            String fullOutput = new String(fullBytes, StandardCharsets.UTF_8);

            // Check if truncation is needed
            String[] lines = fullOutput.split("\n", -1);
            boolean bytesOver = fullBytes.length > MAX_OUTPUT_BYTES;
            boolean linesOver = lines.length > MAX_OUTPUT_LINES;

            if (bytesOver || linesOver) {
                // Spill full output to a file
                Path spillDir = Paths.get("/tmp/naru-tool-output");
                Files.createDirectories(spillDir);
                Path spillFile = spillDir.resolve("tool_" + UUID.randomUUID().toString().replace("-", ""));
                Files.write(spillFile, fullBytes);

                // Keep tail of output
                String tailOutput;
                if (bytesOver) {
                    int keepBytes = MAX_OUTPUT_BYTES;
                    tailOutput = new String(fullBytes, fullBytes.length - keepBytes, keepBytes, StandardCharsets.UTF_8);
                } else {
                    int startIndex = lines.length - MAX_OUTPUT_LINES;
                    StringBuilder sb = new StringBuilder();
                    for (int i = startIndex; i < lines.length; i++) {
                        sb.append(lines[i]);
                        if (i < lines.length - 1) sb.append("\n");
                    }
                    tailOutput = sb.toString();
                }

                return String.format("...output truncated...\n\nFull output saved to: %s\n\n%s", spillFile, tailOutput);
            }

            return fullOutput;

        } catch (Exception e) {
            return "ERROR running command: " + e.getMessage();
        }
    }
}
