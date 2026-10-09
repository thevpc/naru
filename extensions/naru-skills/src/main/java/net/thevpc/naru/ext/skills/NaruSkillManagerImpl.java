package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.spawn.NaruToolTagExpression;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NListContainerElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NPairElement;
import net.thevpc.nuts.elem.NPrimitiveElement;
import net.thevpc.nuts.elem.NStringElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NNameFormat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Filesystem-backed skill resolution across the ordered root model (WP6).
 * <p>
 * Skills are read from a list of roots ordered by {@link NaruSkillRoot#precedence()}:
 * {@code <project>/.naru/local/skills} (strongest), the {@code .naru/skills} walk from
 * {@code projectDir} down to the task's {@code workingDir} (closest wins),
 * {@code ~/.naru/skills}, then the foreign project roots ({@code .claude}, {@code .agents},
 * {@code .opencode}) and the foreign user roots. The first copy of a name wins; every other
 * copy stays visible, marked shadowed, through {@link #entries(NaruTask)}.
 * <p>
 * NARU-native roots are always read. Foreign roots are opt-in: they are read only once
 * trusted, the decision persisted by {@link NaruSkillTrustStore}. An untrusted foreign root
 * still appears in {@link #roots(NaruTask)} so the user can see what is available to trust.
 * <p>
 * A skill lives in the open-standard folder form {@code <root>/<name>/SKILL.md}
 * (falling back to {@code skill.md}). Every
 * {@link NaruSkill} is an immutable snapshot carrying the content hash and body as read when
 * the snapshot was built; the base (projectDir) roots are snapshotted by {@link #reload()}
 * so request-time contribution is disk-free. Folder-scoped roots beyond the project
 * directory are read at request time, because they depend on the task's working directory.
 */
class NaruSkillManagerImpl implements NaruSkillManager {
    private final NaruSession session;
    private final NPath userHome;
    private final NaruSkillTrustStore trust;

    /** Base snapshot: copies keyed by the root that owns them (projectDir-root set). */
    private Map<NaruSkillRoot, List<SkillCopy>> baseCopies = new LinkedHashMap<>();
    private Map<String, List<SkillCopy>> baseByName = new LinkedHashMap<>();
    private Map<String, NaruSkill> baseWinners = new LinkedHashMap<>();
    private Map<String, SkillCopy> baseWinnerCopy = new LinkedHashMap<>();

    NaruSkillManagerImpl(NaruSession session) {
        this.session = session;
        this.userHome = resolveUserHome();
        this.trust = new NaruSkillTrustStore(session.projectDir(), userHome);
    }

    private static NPath resolveUserHome() {
        String override = System.getProperty("naru.skills.userHome");
        if (!NBlankable.isBlank(override)) {
            return NPath.of(override);
        }
        return NPath.ofUserHome();
    }

    // ── roots ──────────────────────────────────────────────────────────────

    private NaruSkillRoot root(NaruSkillRootKind kind, NPath path, String label, int precedence) {
        NaruSkillRoot r = new NaruSkillRoot(kind, path, label, precedence, false);
        return kind.foreign() && trust.isTrusted(r) ? r.asTrusted() : r;
    }

    /** The foreign families, project level then user level. */
    private static final String[][] FOREIGN = {
            {"claude", ".claude/skills"},
            {"agents", ".agents/skills"},
            {"opencode", ".opencode/skills"},
    };
    private static final String[][] FOREIGN_USER = {
            {"claude", ".claude/skills"},
            {"agents", ".agents/skills"},
            {"opencode", ".config/opencode/skills"},
    };

    /**
     * The ordered root set effective for a task at {@code workingDir}. Precedence is fixed
     * here so a merge can always sort deterministically; lower wins.
     */
    List<NaruSkillRoot> rootsFor(NPath workingDir) {
        List<NaruSkillRoot> out = new ArrayList<>();
        NPath project = session.projectDir();
        out.add(root(NaruSkillRootKind.PROJECT_PRIVATE, project.resolve(".naru/local/skills"), "naru", 10));

        // the .naru/skills walk: projectDir first (weakest) to workingDir last (strongest
        // within the walk). Closedness is expressed by the precedence: distance 0 is the
        // task's own directory.
        List<NPath> dirs = walkDirs(project, workingDir);
        for (int i = 0; i < dirs.size(); i++) {
            NPath dir = dirs.get(i);
            int distance = dirs.size() - 1 - i; // 0 for the workingDir entry
            out.add(root(NaruSkillRootKind.FOLDER_PUBLIC, dir.resolve(".naru/skills"),
                    "naru", 100 + distance));
        }

        out.add(root(NaruSkillRootKind.USER, userHome.resolve(".naru/skills"), "naru", 5000));

        int i = 0;
        for (String[] f : FOREIGN) {
            out.add(root(NaruSkillRootKind.FOREIGN_PROJECT, project.resolve(f[1]), f[0], 6000 + i++));
        }
        i = 0;
        for (String[] f : FOREIGN_USER) {
            out.add(root(NaruSkillRootKind.FOREIGN_USER, userHome.resolve(f[1]), f[0], 7000 + i++));
        }
        return out;
    }

    /**
     * The directories from {@code projectDir} down to {@code workingDir}, projectDir first.
     * When the working directory leaves the project, only the project directory is used
     * (the same fallback the model-context walk uses).
     */
    private static List<NPath> walkDirs(NPath project, NPath workingDir) {
        List<NPath> dirs = new ArrayList<>();
        if (project == null) {
            return dirs;
        }
        NPath wd = workingDir == null ? project : workingDir;
        if (!wd.startsWith(project)) {
            dirs.add(project);
            return dirs;
        }
        NPath p = wd;
        while (p != null && p.startsWith(project)) {
            dirs.add(p);
            if (p.equals(project)) {
                break;
            }
            p = p.parent();
        }
        // walkDirs expects projectDir first (weakest), workingDir last (strongest)
        Collections.reverse(dirs);
        return dirs;
    }

    @Override
    public List<NaruSkillRoot> roots(NaruTask task) {
        return Collections.unmodifiableList(rootsFor(task == null ? null : task.workingDir()));
    }

    @Override
    public boolean trust(NaruSkillRoot selected, boolean trusted) {
        if (selected == null || !selected.requiresTrust()) {
            return false;
        }
        NaruSkillRoot concrete = new NaruSkillRoot(selected.kind(), selected.path(),
                selected.label(), selected.precedence(), false);
        boolean changed = trust.setTrusted(concrete, trusted);
        if (changed) {
            reload();
        }
        return changed;
    }

    // ── discovery snapshot ─────────────────────────────────────────────────

    @Override
    public List<NaruSkill> available() {
        return baseWinners.values().stream()
                .sorted(Comparator.comparing(NaruSkill::getName))
                .collect(Collectors.toList());
    }

    @Override
    public List<NaruSkill> available(NaruTask task) {
        List<SkillCopy> copies = effectiveCopies(task == null ? null : task.workingDir());
        Map<String, List<SkillCopy>> byName = group(copies);
        List<NaruSkill> out = new ArrayList<>();
        for (Map.Entry<String, List<SkillCopy>> e : byName.entrySet()) {
            SkillCopy winner = winner(e.getValue());
            out.add(materialize(e.getKey(), winner, e.getValue().size() > 1));
        }
        out.sort(Comparator.comparing(NaruSkill::getName));
        return out;
    }

    @Override
    public NaruSkill findSkill(String name) {
        String canonical = canonicalName(name);
        return canonical == null ? null : baseWinners.get(canonical);
    }

    @Override
    public NaruSkill findSkill(NaruTask task, String name) {
        String canonical = canonicalName(name);
        if (canonical == null) {
            return null;
        }
        List<SkillCopy> copies = effectiveCopies(task == null ? null : task.workingDir());
        List<SkillCopy> named = group(copies).get(canonical);
        if (named == null || named.isEmpty()) {
            return null;
        }
        return materialize(canonical, winner(named), named.size() > 1);
    }

    @Override
    public List<NaruSkillEntry> entries(NaruTask task) {
        List<SkillCopy> copies = effectiveCopies(task == null ? null : task.workingDir());
        Map<String, List<SkillCopy>> byName = group(copies);
        List<NaruSkillEntry> out = new ArrayList<>();
        for (Map.Entry<String, List<SkillCopy>> e : byName.entrySet()) {
            SkillCopy winner = winner(e.getValue());
            for (SkillCopy c : e.getValue()) {
                NaruSkill skill = materialize(e.getKey(), c, e.getValue().size() > 1);
                out.add(new NaruSkillEntry(skill, c.root, c != winner));
            }
        }
        out.sort(Comparator.comparing((NaruSkillEntry x) -> x.skill().getName())
                .thenComparing(x -> x.root() == null ? Integer.MAX_VALUE : x.root().precedence()));
        return out;
    }

    @Override
    public NaruSkill reload(String name) {
        String canonical = canonicalName(name);
        if (canonical == null) {
            return null;
        }
        Map<String, List<SkillCopy>> byName = group(scanRoots(baseRoots()));
        List<SkillCopy> copies = byName.get(canonical);
        if (copies == null || copies.isEmpty()) {
            baseWinners.remove(canonical);
            baseWinnerCopy.remove(canonical);
            return null;
        }
        SkillCopy winner = winner(copies);
        NaruSkill skill = readSkill(canonical, winner, copies.size() > 1);
        baseWinners.put(canonical, skill);
        baseWinnerCopy.put(canonical, winner);
        return skill;
    }

    @Override
    public NaruSkill read(String name) {
        String canonical = canonicalName(name);
        if (canonical == null) {
            return null;
        }
        Map<String, List<SkillCopy>> byName = group(scanRoots(baseRoots()));
        List<SkillCopy> copies = byName.get(canonical);
        return copies == null || copies.isEmpty() ? null : readSkill(canonical, winner(copies), copies.size() > 1);
    }

    @Override
    public void reload() {
        List<SkillCopy> copies = scanRoots(baseRoots());
        baseCopies = new LinkedHashMap<>();
        for (SkillCopy c : copies) {
            baseCopies.computeIfAbsent(c.root, x -> new ArrayList<>()).add(c);
        }
        baseByName = group(copies);
        Map<String, NaruSkill> winners = new LinkedHashMap<>();
        Map<String, SkillCopy> winnerCopies = new LinkedHashMap<>();
        for (Map.Entry<String, List<SkillCopy>> e : baseByName.entrySet()) {
            SkillCopy winner = winner(e.getValue());
            NaruSkill skill = readSkill(e.getKey(), winner, e.getValue().size() > 1);
            if (skill != null) {
                winners.put(e.getKey(), skill);
                winnerCopies.put(e.getKey(), winner);
            }
        }
        this.baseWinners = winners;
        this.baseWinnerCopy = winnerCopies;
    }

    // ── root scanning ──────────────────────────────────────────────────────

    private List<NaruSkillRoot> baseRoots() {
        return rootsFor(session.projectDir());
    }

    /**
     * Copies for the roots effective at {@code workingDir}. Base roots are served from the
     * snapshot; folder roots beyond the project directory are read live.
     */
    private List<SkillCopy> effectiveCopies(NPath workingDir) {
        List<NaruSkillRoot> roots = rootsFor(workingDir);
        List<SkillCopy> out = new ArrayList<>();
        for (NaruSkillRoot r : roots) {
            if (r.requiresTrust() && !r.trusted()) {
                continue;
            }
            List<SkillCopy> cached = baseCopies.get(r);
            if (cached != null) {
                // Re-stamp the cached copies with the effective root: the same directory can
                // carry a different precedence depending on the task's working directory (the
                // project's public root is distance 0 from a task at the project, but distance
                // N from a task deeper in the tree). Without this, a snapshot copy and a live
                // folder copy would tie and the closest directory would not reliably win.
                for (SkillCopy c : cached) {
                    out.add(c.withRoot(r));
                }
            } else {
                out.addAll(scanRoot(r));
            }
        }
        return out;
    }

    private List<SkillCopy> scanRoots(List<NaruSkillRoot> roots) {
        List<SkillCopy> out = new ArrayList<>();
        for (NaruSkillRoot r : roots) {
            if (r.requiresTrust() && !r.trusted()) {
                continue;
            }
            out.addAll(scanRoot(r));
        }
        return out;
    }

    private List<SkillCopy> scanRoot(NaruSkillRoot root) {
        List<SkillCopy> out = new ArrayList<>();
        NPath dir = root.path();
        if (dir == null || !dir.isDirectory()) {
            return out;
        }
        NaruVisibility visibility = visibilityOf(root);
        for (NPath child : dir.stream().sorted(Comparator.comparing(NPath::name)).collect(Collectors.toList())) {
            if (child.isDirectory()) {
                String canonical = canonicalName(child.name());
                if (canonical == null) {
                    continue;
                }
                NPath md = resolveSkillMd(child);
                if (md != null) {
                    out.add(new SkillCopy(root, visibility, md));
                }
            }
        }
        return out;
    }

    private static NaruVisibility visibilityOf(NaruSkillRoot root) {
        return switch (root.kind()) {
            case PROJECT_PRIVATE, USER, FOREIGN_USER -> NaruVisibility.PRIVATE;
            case FOLDER_PUBLIC, FOREIGN_PROJECT -> NaruVisibility.PUBLIC;
        };
    }

    private static NPath resolveSkillMd(NPath dir) {
        NPath upper = dir.resolve("SKILL.md");
        if (upper.isRegularFile()) {
            return upper;
        }
        NPath lower = dir.resolve("skill.md");
        return lower.isRegularFile() ? lower : null;
    }

    private static Map<String, List<SkillCopy>> group(List<SkillCopy> copies) {
        Map<String, List<SkillCopy>> byName = new LinkedHashMap<>();
        for (SkillCopy c : copies) {
            String canonical = nameOf(c);
            if (canonical == null) {
                continue;
            }
            byName.computeIfAbsent(canonical, x -> new ArrayList<>()).add(c);
        }
        for (List<SkillCopy> list : byName.values()) {
            list.sort(COPY_ORDER);
        }
        return byName;
    }

    private static SkillCopy winner(List<SkillCopy> copies) {
        return copies.stream().min(COPY_ORDER).orElse(null);
    }

    private static final Comparator<SkillCopy> COPY_ORDER = Comparator
            .comparingInt((SkillCopy c) -> c.root == null ? Integer.MAX_VALUE : c.root.precedence())
            .thenComparing(c -> c.file == null ? "" : c.file.toString());

    private static String nameOf(SkillCopy c) {
        return canonicalName(c.file.parent().name());
    }

    // ── reading one skill ──────────────────────────────────────────────────

    /**
     * Reads the winner from the snapshot when it is the snapshot's winner (disk-free), and
     * from disk otherwise (a folder root, or a copy the snapshot never saw). The shadowed
     * flag is recomputed for the effective root set rather than trusting the snapshot's.
     */
    private NaruSkill materialize(String canonical, SkillCopy win, boolean shadowed) {
        if (win == null) {
            return null;
        }
        NaruSkill base = baseWinners.get(canonical);
        SkillCopy baseCopy = baseWinnerCopy.get(canonical);
        if (base != null && baseCopy != null && sameCopy(baseCopy, win)) {
            if (base.isShadowed() == shadowed) {
                return base;
            }
            return ((NaruSkillImpl) base).withShadowed(shadowed);
        }
        return readSkill(canonical, win, shadowed);
    }

    private static boolean sameCopy(SkillCopy a, SkillCopy b) {
        return a == b || (a != null && b != null
                && String.valueOf(a.file).equals(String.valueOf(b.file))
                && a.root.equals(b.root));
    }

    private NaruSkill readSkill(String canonical, SkillCopy win, boolean shadowed) {
        NPath rootPath = win.root.path();
        String originRoot = rootPath == null ? null : rootPath.toString();
        String baseDir = win.file.parent().toString();

        List<String> warnings = new ArrayList<>();
        String raw = readAll(win.file);
        String contentHash = "";
        String headerText = null;
        List<String> bodyLines = new ArrayList<>();
        Map<String, Object> frontMatter = new LinkedHashMap<>();
        if (raw == null) {
            warnings.add("cannot read '" + win.file + "'");
        } else {
            contentHash = sha256(raw);
            Header body = splitHeader(raw);
            headerText = body.header;
            bodyLines = body.bodyLines;
            if (body.unterminatedHeader) {
                warnings.add("skill '" + canonical + "' has an unterminated '---' front-matter block");
            }
            frontMatter = parseFrontMatter(canonical, win, headerText, warnings);
        }

        String description = textOf(frontMatter, "description");
        if (NBlankable.isBlank(description)) {
            warnings.add("skill '" + canonical + "' has no front-matter 'description' (the open standard requires one)");
            description = "";
        } else {
            description = description.trim();
        }

        NaruToolTagExpression requires = parseRequires(canonical, frontMatter, warnings);

        return new NaruSkillImpl(canonical, win.visibility, shadowed,
                win.file.toString(), originRoot, baseDir, description, contentHash,
                frontMatter, requires, warnings, bodyLines, win.root);
    }

    private static String readAll(NPath file) {
        try {
            return file.readString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static Header splitHeader(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Header(null, new ArrayList<>(), false);
        }
        List<String> lines = new ArrayList<>(List.of(raw.split("\n", -1)));
        if (lines.isEmpty() || !lines.get(0).trim().equals("---")) {
            return new Header(null, splitLines(raw), false);
        }
        int close = -1;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().equals("---")) {
                close = i;
                break;
            }
        }
        if (close <= 0) {
            return new Header(null, splitLines(raw), true);
        }
        StringBuilder header = new StringBuilder();
        for (int i = 1; i < close; i++) {
            if (i > 1) {
                header.append('\n');
            }
            header.append(lines.get(i));
        }
        StringBuilder body = new StringBuilder();
        for (int i = close + 1; i < lines.size(); i++) {
            body.append(lines.get(i));
            if (i < lines.size() - 1) {
                body.append('\n');
            }
        }
        return new Header(header.toString(), splitLines(body.toString()), false);
    }

    private static List<String> splitLines(String body) {
        if (body == null || body.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (String line : body.split("\r?\n")) {
            out.add(line);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static Map<String, Object> parseFrontMatter(String canonical, SkillCopy win,
                                                        String headerText, List<String> warnings) {
        if (NBlankable.isBlank(headerText)) {
            warnings.add("skill '" + canonical + "' has no YAML front-matter (the open standard requires name+description)");
            return new LinkedHashMap<>();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        try {
            NElement el = NElementReader.ofYaml().read(headerText);
            NObjectElement obj = el == null ? null : el.asObject().orNull();
            if (obj == null) {
                warnings.add("skill '" + canonical + "' front-matter is not a YAML mapping");
                return new LinkedHashMap<>();
            }
            for (NElement child : obj.children()) {
                if (child.isPair()) {
                    NPairElement pair = child.asPair().get();
                    map.put(keyString(pair.key()), convertValue(pair.value()));
                }
            }
        } catch (Exception e) {
            warnings.add("skill '" + canonical + "' has invalid YAML front-matter: " + e.getMessage());
            return new LinkedHashMap<>();
        }
        String fmName = textOf(map, "name");
        if (!NBlankable.isBlank(fmName)) {
            String fmCanonical = canonicalName(fmName);
            if (fmCanonical == null) {
                warnings.add("skill '" + canonical + "' front-matter name '" + fmName + "' is not a valid lower-kebab name");
            } else if (!fmCanonical.equals(canonical)) {
                warnings.add("skill '" + canonical + "' front-matter name '" + fmName + "' does not match the file/folder name");
            }
        }
        return map;
    }

    private static NaruToolTagExpression parseRequires(String canonical, Map<String, Object> frontMatter,
                                                       List<String> warnings) {
        String expr = textOf(frontMatter, "requires");
        if (NBlankable.isBlank(expr)) {
            return null;
        }
        try {
            return NaruToolTagExpression.parse(expr.trim());
        } catch (IllegalArgumentException e) {
            warnings.add("skill '" + canonical + "' has an invalid 'requires' expression '" + expr + "': " + e.getMessage());
            return null;
        }
    }

    private static String keyString(NElement key) {
        String s = key == null ? null : key.asStringValue().orNull();
        return s == null && key != null ? key.toString() : s;
    }

    private static Object convertValue(NElement v) {
        if (v == null) {
            return null;
        }
        NObjectElement o = v.asObject().orNull();
        if (o != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (NElement child : o.children()) {
                if (child.isPair()) {
                    NPairElement pair = child.asPair().get();
                    m.put(keyString(pair.key()), convertValue(pair.value()));
                }
            }
            return m;
        }
        NListContainerElement list = v.asListContainer().orNull();
        if (list != null) {
            List<Object> out = new ArrayList<>();
            for (NElement c : list.children()) {
                out.add(convertValue(c));
            }
            return out;
        }
        NPrimitiveElement p = v.asPrimitive().orNull();
        if (p != null && p.value() != null) {
            return String.valueOf(p.value());
        }
        NStringElement s = v.asString().orNull();
        if (s != null) {
            return s.rawValue();
        }
        String sv = v.asStringValue().orNull();
        return sv == null ? v.toString() : sv;
    }

    private static String textOf(Map<String, Object> frontMatter, String key) {
        Object v = frontMatter.get(key);
        return v instanceof String s ? s : null;
    }

    private static String firstParagraph(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line == null ? "" : line.trim();
            if (t.isEmpty()) {
                if (sb.length() > 0) {
                    break;
                }
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString();
    }

    private static String canonicalName(String name) {
        if (NBlankable.isBlank(name)) {
            return null;
        }
        String canonical = NNameFormat.LOWER_KEBAB_CASE.format(name.trim());
        return canonical.isEmpty() ? null : canonical;
    }

    private record Header(String header, List<String> bodyLines, boolean unterminatedHeader) {
    }

    private static final class SkillCopy {
        final NaruSkillRoot root;
        final NaruVisibility visibility;
        final NPath file;

        SkillCopy(NaruSkillRoot root, NaruVisibility visibility, NPath file) {
            this.root = root;
            this.visibility = visibility;
            this.file = file;
        }

        /** A copy of this entry under a different (effective) root, for per-task precedence. */
        SkillCopy withRoot(NaruSkillRoot newRoot) {
            return newRoot == root ? this
                    : new SkillCopy(newRoot, visibility, file);
        }
    }
}
