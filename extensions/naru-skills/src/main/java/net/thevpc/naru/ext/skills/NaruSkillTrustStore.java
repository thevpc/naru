package net.thevpc.naru.ext.skills;

import net.thevpc.nuts.elem.NArrayElement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NPairElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists the one-time trust decisions for foreign skill roots (WP6).
 * <p>
 * A foreign root is read only after it was trusted, and the persisted value is now a
 * {@link NaruSkillTrustLevel} rather than a boolean: reading costs nothing, but a root
 * trusted at {@code read} may not have its skills' write or exec {@code allowed-tools}
 * honoured. Trust is scoped like the root itself: a project-level foreign root
 * ({@code <project>/.claude/skills}) is persisted under the project at
 * {@code .naru/local/skills-trust.tson}, and a user-level one
 * ({@code ~/.claude/skills}) under the user home at {@code ~/.naru/skills-trust.tson}.
 * The key is the root's label plus its path relative to the scope base, so a project that
 * is moved (or cloned to another machine) keeps the trust it declared.
 * <p>
 * Nothing here reads skill content: the store only answers "may this root be read, and how
 * far".
 */
final class NaruSkillTrustStore {
    private static final String FILE = "skills-trust.tson";

    private final NPath projectDir;
    private final NPath userHome;
    private final NPath projectFile;
    private final NPath userFile;
    private final Map<String, NaruSkillTrustLevel> projectTrusted = new LinkedHashMap<>();
    private final Map<String, NaruSkillTrustLevel> userTrusted = new LinkedHashMap<>();

    NaruSkillTrustStore(NPath projectDir, NPath userHome) {
        this.projectDir = projectDir == null ? null : projectDir.normalize();
        this.userHome = userHome == null ? null : userHome.normalize();
        this.projectFile = this.projectDir == null ? null : this.projectDir.resolve(".naru/local/" + FILE);
        this.userFile = this.userHome == null ? null : this.userHome.resolve(".naru/" + FILE);
        load();
    }

    boolean isTrusted(NaruSkillRoot root) {
        return trustLevel(root).atLeast(NaruSkillTrustLevel.READ);
    }

    /** The granted level of a foreign root; native roots are {@code READ} by contract. */
    NaruSkillTrustLevel trustLevel(NaruSkillRoot root) {
        if (root == null || !root.requiresTrust()) {
            return NaruSkillTrustLevel.READ;
        }
        NaruSkillTrustLevel level = scope(root).get(key(root));
        return level == null ? NaruSkillTrustLevel.NONE : level;
    }

    /** Records a trust decision at a level. Returns true when the persisted state changed. */
    boolean setTrust(NaruSkillRoot root, NaruSkillTrustLevel level) {
        if (root == null || !root.requiresTrust()) {
            return false;
        }
        NaruSkillTrustLevel normalized = level == null ? NaruSkillTrustLevel.NONE : level;
        String key = key(root);
        Map<String, NaruSkillTrustLevel> scope = scope(root);
        NaruSkillTrustLevel previous = scope.get(key);
        boolean changed;
        if (normalized == NaruSkillTrustLevel.NONE) {
            changed = scope.remove(key) != null;
        } else {
            changed = !normalized.equals(previous);
            scope.put(key, normalized);
        }
        if (changed) {
            save(root.kind().userLevel());
        }
        return changed;
    }

    /** Backwards-compatible boolean form: {@code true} means {@code read}. */
    boolean setTrusted(NaruSkillRoot root, boolean trusted) {
        return setTrust(root, trusted ? NaruSkillTrustLevel.READ : NaruSkillTrustLevel.NONE);
    }

    // ── keying and scope ────────────────────────────────────────────────────

    private Map<String, NaruSkillTrustLevel> scope(NaruSkillRoot root) {
        return root.kind().userLevel() ? userTrusted : projectTrusted;
    }

    private String key(NaruSkillRoot root) {
        NPath base = root.kind().userLevel() ? userHome : projectDir;
        String rel = null;
        if (base != null && root.path() != null && root.path().startsWith(base)) {
            NOptional<String> r = base.relativize(root.path());
            rel = r.orNull();
        }
        if (NBlankable.isBlank(rel)) {
            rel = String.valueOf(root.path());
        }
        return root.label() + "|" + rel;
    }

    // ── persistence ─────────────────────────────────────────────────────────

    private void load() {
        read(projectFile, projectTrusted);
        read(userFile, userTrusted);
    }

    private static void read(NPath file, Map<String, NaruSkillTrustLevel> into) {
        if (file == null || !file.isRegularFile()) {
            return;
        }
        try {
            String text = file.readString(StandardCharsets.UTF_8);
            if (NBlankable.isBlank(text)) {
                return;
            }
            NElement el = NElementReader.ofTson().read(text);
            NObjectElement obj = el == null ? null : el.asObject().orNull();
            if (obj == null) {
                return;
            }
            NObjectElement trustObj = obj.getObject("trust").orNull();
            if (trustObj != null) {
                for (NPairElement pair : trustObj.namedPairs()) {
                    String k = pair.key().asStringValue().orNull();
                    String v = pair.value() == null ? null : pair.value().asStringValue().orNull();
                    NaruSkillTrustLevel level = parseLevel(v);
                    if (!NBlankable.isBlank(k) && level != NaruSkillTrustLevel.NONE) {
                        into.put(k, level);
                    }
                }
                return;
            }
            // pre-level format: a plain array of trusted keys, each worth "read"
            NArrayElement arr = obj.getArray("trusted").orNull();
            if (arr != null) {
                for (NElement e : arr.children()) {
                    String s = e.asStringValue().orNull();
                    if (!NBlankable.isBlank(s)) {
                        into.put(s, NaruSkillTrustLevel.READ);
                    }
                }
            }
        } catch (Exception e) {
            // a corrupt trust file is treated as "nothing trusted" rather than failing the
            // session: the worst case is one extra prompt, never a crash
        }
    }

    private static NaruSkillTrustLevel parseLevel(String value) {
        if (value == null) {
            return NaruSkillTrustLevel.NONE;
        }
        try {
            return NaruSkillTrustLevel.valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            return NaruSkillTrustLevel.NONE;
        }
    }

    private void save(boolean userLevel) {
        NPath file = userLevel ? userFile : projectFile;
        Map<String, NaruSkillTrustLevel> values = userLevel ? userTrusted : projectTrusted;
        if (file == null) {
            return;
        }
        try {
            if (values.isEmpty()) {
                if (file.exists()) {
                    file.delete();
                }
                return;
            }
            file.mkParentDirs();
            NObjectElementBuilder trustBuilder = NElement.ofObjectBuilder();
            for (Map.Entry<String, NaruSkillTrustLevel> e : values.entrySet()) {
                trustBuilder.set(e.getKey(), e.getValue().name().toLowerCase());
            }
            NElement payload = NElement.ofObjectBuilder()
                    .set("trust", trustBuilder.build())
                    .build();
            NElementWriter.ofTson().write(payload, file);
        } catch (Exception e) {
            // persistence is best effort; an unwritable trust file must not break a load
        }
    }
}