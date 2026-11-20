package net.thevpc.naru.ext.assess;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * What a check can look at: the work directory after the model finished, the fingerprint of
 * its starting state, and (lazily, once each) the build results.
 */
public final class AssessContext {
    private final AssessAsset asset;
    private final AssessTask task;
    private final Path dir;
    private final Map<String, String> baseline;
    private AssessProcess.Result compile;
    private AssessProcess.Result test;
    private AssessBuild.HiddenOutcome hidden;

    public AssessContext(AssessAsset asset, AssessTask task, Path dir, Map<String, String> baseline) {
        this.asset = asset;
        this.task = task;
        this.dir = dir;
        this.baseline = baseline;
    }

    public Path dir() {
        return dir;
    }

    public AssessTask task() {
        return task;
    }

    public synchronized AssessProcess.Result compile() {
        if (compile == null) {
            compile = asset.build().compile(dir);
        }
        return compile;
    }

    public synchronized AssessProcess.Result test() {
        if (test == null) {
            test = asset.build().test(dir);
        }
        return test;
    }

    public synchronized AssessBuild.HiddenOutcome hidden() {
        if (hidden == null) {
            hidden = asset.build().hiddenTests(task, dir);
        }
        return hidden;
    }

    /** Paths (relative, '/'-separated) whose content differs from the starting state, or that vanished. */
    public TreeSet<String> modified() {
        Map<String, String> now = fingerprint(dir);
        TreeSet<String> out = new TreeSet<>();
        baseline.forEach((p, h) -> {
            if (!h.equals(now.get(p))) {
                out.add(p);
            }
        });
        return out;
    }

    public TreeSet<String> added() {
        Map<String, String> now = fingerprint(dir);
        TreeSet<String> out = new TreeSet<>(now.keySet());
        out.removeAll(baseline.keySet());
        return out;
    }

    public Map<String, String> baseline() {
        return baseline;
    }

    /** SHA-256 per file under {@code dir}, ignoring build output. */
    public static Map<String, String> fingerprint(Path dir) {
        Map<String, String> out = new HashMap<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("target/") || rel.startsWith(".git/")) {
                    continue;
                }
                out.put(rel, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }
}
