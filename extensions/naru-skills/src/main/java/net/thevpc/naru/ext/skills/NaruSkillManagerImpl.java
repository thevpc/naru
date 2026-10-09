package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.spawn.NaruToolTagExpression;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Filesystem-backed skill resolution, snapshot-based (v2).
 * <p>
 * A skill lives either in the legacy flat form {@code <root>/<name>.md} or the
 * open-standard folder form {@code <root>/<name>/SKILL.md} (falling back to
 * {@code skill.md}), where {@code <root>} is {@code <project>/.naru/skills} (public) or
 * {@code <project>/.naru/local/skills} (private). Within one root the folder form beats
 * the flat form; across roots private shadows public and there is no merge.
 * <p>
 * Every {@link NaruSkill} returned is an immutable snapshot holding the content hash and
 * body of the file as read when the snapshot was built. {@code available()} and
 * {@link #findSkill(String)} serve the cached snapshot and never touch the disk:
 * request-time contribution is therefore disk-free. {@link #reload()} deliberately
 * rebuilds the snapshot, and the doctor command compares snapshot hashes to the current
 * files so a silent edit is reported rather than silently applied.
 * <p>
 * Depends only on {@link NaruSession#projectDir()}, so this extension needs nothing from
 * {@code naru-impl}.
 */
class NaruSkillManagerImpl implements NaruSkillManager {
    private final NaruSession session;
    private Map<String, NaruSkill> cache = new HashMap<>();

    NaruSkillManagerImpl(NaruSession session) {
        this.session = session;
    }

    // ── discovery snapshot ─────────────────────────────────────────────────

    @Override
    public List<NaruSkill> available() {
        return cache.values().stream()
                .sorted(Comparator.comparing(NaruSkill::getName))
                .collect(Collectors.toList());
    }

    @Override
    public NaruSkill findSkill(String name) {
        String canonical = canonicalName(name);
        return canonical == null ? null : cache.get(canonical);
    }

    @Override
    public NaruSkill reload(String name) {
        String canonical = canonicalName(name);
        if (canonical == null) {
            return null;
        }
        Map<String, SkillEntry> found = new HashMap<>();
        collect(rootDir(NaruVisibility.PUBLIC), NaruVisibility.PUBLIC, canonical, found);
        collect(rootDir(NaruVisibility.PRIVATE), NaruVisibility.PRIVATE, canonical, found);
        SkillEntry entry = found.get(canonical);
        NaruSkill skill = entry == null ? null : readSkill(canonical, entry);
        if (skill == null) {
            cache.remove(canonical);
            return null;
        }
        cache.put(canonical, skill);
        return skill;
    }

    @Override
    public NaruSkill read(String name) {
        String canonical = canonicalName(name);
        if (canonical == null) {
            return null;
        }
        Map<String, SkillEntry> found = new HashMap<>();
        collect(rootDir(NaruVisibility.PUBLIC), NaruVisibility.PUBLIC, canonical, found);
        collect(rootDir(NaruVisibility.PRIVATE), NaruVisibility.PRIVATE, canonical, found);
        SkillEntry entry = found.get(canonical);
        return entry == null ? null : readSkill(canonical, entry);
    }

    @Override
    public void reload() {
        Map<String, SkillEntry> found = new HashMap<>();
        collect(rootDir(NaruVisibility.PUBLIC), NaruVisibility.PUBLIC, null, found);
        collect(rootDir(NaruVisibility.PRIVATE), NaruVisibility.PRIVATE, null, found);
        Map<String, NaruSkill> next = new HashMap<>();
        for (Map.Entry<String, SkillEntry> e : found.entrySet()) {
            NaruSkill skill = readSkill(e.getKey(), e.getValue());
            if (skill != null) {
                next.put(e.getKey(), skill);
            }
        }
        this.cache = next;
    }

    private NPath rootDir(NaruVisibility visibility) {
        if (visibility == NaruVisibility.PUBLIC) {
            return session.projectDir().resolve(".naru/skills/");
        }
        return session.projectDir().resolve(".naru/local/skills/");
    }

    /**
     * Scans one skill root, filling {@code found} with the entries whose name matches
     * {@code onlyName} (or all when null). Within a root the folder form beats the flat
     * form when both define the same name; across roots the private copy shadows the public
     * one (which {@link #readSkill} enforces, not this scan).
     */
    private void collect(NPath root, NaruVisibility visibility, String onlyName, Map<String, SkillEntry> found) {
        if (!root.isDirectory()) {
            return;
        }
        for (NPath child : root.stream().sorted(Comparator.comparing(NPath::name)).collect(Collectors.toList())) {
            if (child.isRegularFile() && child.name().endsWith(".md")) {
                String canonical = canonicalName(child.name().substring(0, child.name().length() - 3));
                if (canonical == null || (onlyName != null && !onlyName.equals(canonical))) {
                    continue;
                }
                SkillEntry e = found.computeIfAbsent(canonical, x -> new SkillEntry());
                // two files can canonicalize to the same name (e.g. "My Skill.md" and
                // "my-skill.md"); keep the first in sorted order and stay deterministic
                if (visibility == NaruVisibility.PUBLIC) {
                    if (e.publicFlat == null) {
                        e.publicFlat = new SkillCopy(visibility, NaruSkillLayout.FLAT, child);
                    }
                } else if (e.privateFlat == null) {
                    e.privateFlat = new SkillCopy(visibility, NaruSkillLayout.FLAT, child);
                }
            } else if (child.isDirectory()) {
                String canonical = canonicalName(child.name());
                if (canonical == null || (onlyName != null && !onlyName.equals(canonical))) {
                    continue;
                }
                NPath md = resolveSkillMd(child);
                if (md != null) {
                    SkillEntry e = found.computeIfAbsent(canonical, x -> new SkillEntry());
                    SkillCopy copy = new SkillCopy(visibility, NaruSkillLayout.FOLDER, md);
                    if (visibility == NaruVisibility.PUBLIC) {
                        e.publicFolder = copy;
                    } else {
                        e.privateFolder = copy;
                    }
                }
            }
        }
    }

    private static NPath resolveSkillMd(NPath dir) {
        NPath upper = dir.resolve("SKILL.md");
        if (upper.isRegularFile()) {
            return upper;
        }
        NPath lower = dir.resolve("skill.md");
        return lower.isRegularFile() ? lower : null;
    }

    // ── reading one skill ──────────────────────────────────────────────────

    private NaruSkill readSkill(String canonical, SkillEntry entry) {
        SkillCopy win = entry.privateCopy() != null ? entry.privateCopy() : entry.publicCopy();
        if (win == null) {
            return null;
        }
        boolean shadowed = entry.publicCopy() != null && entry.privateCopy() != null;
        NPath root = rootDir(win.visibility);
        String originRoot = root.toString();
        String baseDir = win.layout == NaruSkillLayout.FOLDER
                ? win.file.parent().toString()
                : root.toString();

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
            if (win.layout == NaruSkillLayout.FLAT) {
                description = firstParagraph(bodyLines);
            } else {
                warnings.add("skill '" + canonical + "' has no front-matter 'description' (the open standard requires one)");
                description = "";
            }
        } else {
            description = description.trim();
        }

        NaruToolTagExpression requires = parseRequires(canonical, frontMatter, warnings);

        return new NaruSkillImpl(canonical, win.visibility, shadowed, win.layout,
                win.file.toString(), originRoot, baseDir, description, contentHash,
                frontMatter, requires, warnings, bodyLines);
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

    /**
     * Splits raw file text into the optional {@code ---}-delimited front-matter header and
     * the body. A first line that is not {@code ---} means no header (legacy flat files);
     * a header that never closes is reported by the caller as a warning and the whole file
     * is treated as body.
     */
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

    /**
     * Lenient YAML front-matter parsing: never rejects a skill. Malformed YAML, a
     * non-mapping header, a {@code name} that disagrees with the file/folder, and a
     * missing {@code description} on a folder skill all produce warnings instead.
     */
    private static Map<String, Object> parseFrontMatter(String canonical, SkillCopy win,
                                                        String headerText, List<String> warnings) {
        if (NBlankable.isBlank(headerText)) {
            if (win.layout == NaruSkillLayout.FOLDER) {
                warnings.add("skill '" + canonical + "' has no YAML front-matter (the open standard requires name+description)");
            }
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

    private static class SkillCopy {
        final NaruVisibility visibility;
        final NaruSkillLayout layout;
        final NPath file;

        SkillCopy(NaruVisibility visibility, NaruSkillLayout layout, NPath file) {
            this.visibility = visibility;
            this.layout = layout;
            this.file = file;
        }
    }

    private static class SkillEntry {
        SkillCopy publicFlat;
        SkillCopy privateFlat;
        SkillCopy publicFolder;
        SkillCopy privateFolder;

        SkillCopy publicCopy() {
            return publicFolder != null ? publicFolder : publicFlat;
        }

        SkillCopy privateCopy() {
            return privateFolder != null ? privateFolder : privateFlat;
        }
    }
}