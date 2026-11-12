package net.thevpc.naru.ext.assess;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import net.thevpc.nuts.elem.NElementReader;

/** Persists runs as one JSON file each under {@code ~/.naru/assess/runs} (override: {@code -Dnaru.assess.dir}). */
public final class AssessStore {
    private final Path runsDir;

    public AssessStore() {
        this(root().resolve("runs"));
    }

    public AssessStore(Path runsDir) {
        this.runsDir = runsDir;
    }

    public static Path root() {
        String o = System.getProperty("naru.assess.dir");
        return o != null && !o.isBlank() ? Path.of(o) : Path.of(System.getProperty("user.home"), ".naru", "assess");
    }

    public Path runsDir() {
        return runsDir;
    }

    public Path save(AssessRun run) {
        try {
            Files.createDirectories(runsDir);
            Path f = runsDir.resolve(run.id + ".json");
            Files.writeString(f, AssessJson.write(run.toMap()), StandardCharsets.UTF_8);
            return f;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs, newest first. Unreadable files are skipped. */
    public List<AssessRun> list() {
        if (!Files.isDirectory(runsDir)) {
            return List.of();
        }
        List<AssessRun> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(runsDir)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".json"))::iterator) {
                try {
                    Map<?, ?> m = NElementReader.ofJson().read(Files.readString(p), Map.class);
                    out.add(AssessRun.ofMap(m));
                } catch (RuntimeException | IOException e) {
                    // a damaged file must not hide the others
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparing((AssessRun r) -> r.id == null ? "" : r.id).reversed());
        return out;
    }

    /** {@code last}/blank for the newest, otherwise an id or an unambiguous id prefix. */
    public Optional<AssessRun> find(String idOrLast) {
        List<AssessRun> all = list();
        if (idOrLast == null || idOrLast.isBlank() || idOrLast.equals("last")) {
            return all.stream().findFirst();
        }
        List<AssessRun> hits = all.stream().filter(r -> r.id != null && r.id.startsWith(idOrLast)).toList();
        return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();
    }
}
